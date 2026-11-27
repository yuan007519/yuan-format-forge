package com.fmtlab.toolbox;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 HLS（m3u8）预处理，纯离线。
 *
 * 做法：把索引和它引用的分片一起复制进 cache/convert/hls/，并且保留 m3u8 里写的
 * 相对路径。之后 FFmpeg 拿到的就是本地 index.m3u8，按相对路径找分片时自然落在
 * 同目录，全程不碰网络。
 *
 * 离线判断死了三种情况：分片是 http(s) 链接、AES-128 密钥在网络上、同目录分片
 * 读不到（系统只授权了单个文件，这时抛 NeedFolder 让用户再授权一次所在文件夹）。
 */
public final class Hls {

    /** 同目录分片读不到：需要用户额外授权一次 m3u8 所在的文件夹 */
    public static class NeedFolder extends Exception {
        public NeedFolder(String msg) {
            super(msg);
        }
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private Hls() {}

    public static boolean isHls(String name) {
        String e = Ui.ext(name);
        return "m3u8".equals(e) || "m3u".equals(e);
    }

    /** 缓存里的 HLS 工作目录 */
    public static File dir(Context c) {
        return new File(new File(c.getCacheDir(), "convert"), "hls");
    }

    public static void clean(Context c) {
        deleteTree(dir(c));
    }

    public static String readText(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取 m3u8");
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** 分片条数，用于进度提示 */
    public static int refCount(String text) {
        try {
            return localRefs(text).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 索引里 EXTINF 累加出来的总时长（秒），替代 MediaMetadataRetriever 探不出来的时长 */
    public static long totalSeconds(String text) {
        double total = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("#EXTINF:")) continue;
            String num = line.substring(8);
            int comma = num.indexOf(',');
            if (comma > 0) num = num.substring(0, comma);
            try { total += Double.parseDouble(num.trim()); } catch (Exception ignored) {}
        }
        return (long) total;
    }

    /** m3u8 里所有需要落到本地文件的引用（分片、EXT-X-MAP、本地密钥） */
    private static List<String> localRefs(String text) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                while (m.find()) {
                    checkOffline(m.group(1));
                    out.add(m.group(1));
                }
                if (line.startsWith("#EXT-X-KEY") && line.toUpperCase().contains("METHOD=AES-128")) {
                    if (line.indexOf("URI=\"") < 0) {
                        throw new Exception("这个 m3u8 已加密（AES-128）但没写密钥位置，离线无法解密");
                    }
                }
                continue;
            }
            checkOffline(line);
            out.add(line);
        }
        if (out.isEmpty()) throw new Exception("这个 m3u8 里没有可读取的分片");
        return out;
    }

    private static void checkOffline(String v) throws Exception {
        String low = v.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("//")) {
            throw new Exception("这个 m3u8 的分片或密钥在网络上，离线读不到。\n"
                    + "在线 m3u8（http 链接）需要联网和网络权限，本应用刻意不申请。");
        }
        if (low.startsWith("data:")) throw new Exception("m3u8 里有内嵌数据，暂不支持");
        if (v.indexOf('?') >= 0 || v.indexOf('#') >= 0) {
            throw new Exception("m3u8 里的分片地址带参数（" + v + "），本地文件不支持");
        }
        if (v.startsWith("/")) throw new Exception("m3u8 用的是绝对路径（" + v + "），暂不支持");
    }

    private static String normalize(String rel) {
        String s = rel.replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    /**
     * 把索引 + 分片落到 cache/convert/hls/，返回本地 index.m3u8。
     *
     * @param treeUri 可为 null。为 null 时按「单文件授权 + 同目录兄弟文档」尝试；
     *                用户已授权所在文件夹时传进来的 tree URI，走 DocumentsContract 遍历。
     */
    public static File stage(Context c, Uri docUri, Uri treeUri, String text) throws Exception {
        File d = dir(c);
        deleteTree(d);
        if (!d.exists() && !d.mkdirs()) throw new Exception("无法创建缓存目录");

        for (String ref : localRefs(text)) {
            String rel = normalize(ref);
            copyOne(c, docUri, treeUri, rel, new File(d, rel));
        }

        File index = new File(d, "index.m3u8");
        OutputStream o = new FileOutputStream(index);
        try {
            o.write(text.getBytes("UTF-8"));
            o.flush();
        } finally {
            try { o.close(); } catch (Exception ignored) {}
        }
        return index;
    }

    /**
     * 多选入口：把用户一起选中的 m3u8 和分片落到缓存，再写一份本地 index.m3u8。
     * 不去读同目录兄弟文件，所以不依赖额外的目录授权。
     */
    public static File stageSelected(Context c, java.util.List<Uri> uris, File dir) throws Exception {
        if (uris == null || uris.isEmpty()) throw new Exception("没有选中任何文件");
        Uri indexUri = null;
        for (Uri u : uris) {
            String n = Ui.displayName(c, u);
            if (isHls(n)) {
                indexUri = u;
                break;
            }
        }
        if (indexUri == null) throw new Exception("选中的文件里没有 m3u8 索引");

        String text = readText(c, indexUri);
        deleteTree(dir);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");

        java.util.List<String> missing = new ArrayList<String>();
        int copied = 0;
        for (String ref : localRefs(text)) {
            String rel = normalize(ref);
            Uri found = findSelected(c, uris, rel);
            if (found == null) {
                missing.add(rel);
                continue;
            }
            copyUriTo(c, found, new File(dir, rel));
            copied++;
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < missing.size() && i < 3; i++) {
                if (i > 0) sb.append("、");
                sb.append(missing.get(i));
            }
            if (missing.size() > 3) sb.append(" 等 " + missing.size() + " 个");
            throw new Exception("少选了分片：" + sb + "。请把 m3u8 和同目录的分片一起选中。");
        }

        File index = new File(dir, "index.m3u8");
        OutputStream o = new FileOutputStream(index);
        try {
            o.write(text.getBytes("UTF-8"));
            o.flush();
        } finally {
            try { o.close(); } catch (Exception ignored) {}
        }
        return index;
    }

    private static Uri findSelected(Context c, java.util.List<Uri> uris, String rel) {
        String base = rel;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        for (Uri u : uris) {
            String n = Ui.displayName(c, u);
            if (n == null) continue;
            if (n.equals(rel) || n.equals(base)) return u;
        }
        return null;
    }

    private static void copyUriTo(Context c, Uri src, File dst) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(src);
            if (in == null) throw new Exception("读不到 " + dst.getName());
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static void copyOne(Context c, Uri docUri, Uri treeUri, String rel, File dst) throws Exception {        Uri src = treeUri != null
                ? DocumentsContract.buildDocumentUriUsingTree(treeUri, findDocId(c, treeUri, rel))
                : sibling(docUri, rel);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(src);
            if (in == null) throw new java.io.FileNotFoundException(rel);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (SecurityException se) {
            throw new NeedFolder("系统只授权了 m3u8 这一个文件，读不到同目录分片（" + rel + "）");
        } catch (java.io.FileNotFoundException fe) {
            if (treeUri != null) {
                throw new Exception("在所选文件夹里没找到 " + rel + "，确认分片和 m3u8 在同一目录");
            }
            throw new NeedFolder("没找到同目录分片 " + rel + "，需要授权它所在的文件夹");
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /** 单文件授权：把 docId 的末段换成相对路径，拼出兄弟文档的 URI */
    private static Uri sibling(Uri docUri, String rel) throws Exception {
        String docId = docUri.getLastPathSegment();
        if (docId == null || docId.indexOf('/') < 0) {
            throw new NeedFolder("这个入口读不到同目录文件，请重新授权所在文件夹");
        }
        String dir = docId.substring(0, docId.lastIndexOf('/'));
        String newId = dir + "/" + rel;
        return new Uri.Builder()
                .scheme(docUri.getScheme())
                .authority(docUri.getAuthority())
                .appendPath("document")
                .appendPath(newId)
                .build();
    }

    /** 已授权文件夹：按相对路径逐级找子文档的 documentId */
    private static String findDocId(Context c, Uri treeUri, String rel) throws Exception {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        for (String part : rel.split("/")) {
            if (part.length() == 0 || ".".equals(part)) continue;
            String child = childDocId(c, treeUri, id, part);
            if (child == null) throw new java.io.FileNotFoundException(rel);
            id = child;
        }
        return id;
    }

    private static String childDocId(Context c, Uri treeUri, String parentId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            }, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String display = cur.getString(1);
                if (name.equals(display)) return cur.getString(0);
            }
            return null;
        } catch (SecurityException se) {
            return null;
        } finally {
            try { if (cur != null) cur.close(); } catch (Exception ignored) {}
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
