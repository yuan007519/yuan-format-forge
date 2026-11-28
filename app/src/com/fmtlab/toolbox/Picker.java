package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.InputStream;

/**
 * 统一的文件选择器 + 文件类型探测。
 *
 * 为什么不用 setType("video/*")：系统只按 provider 报上来的 MIME 过滤，而 MIME 是各
 * provider 用自己的扩展名表猜出来的。rmvb / m2ts / vob / f4v 这类扩展名在很多设备上
 * 猜不出来，一律报成 application/octet-stream，于是被 video/* 直接过滤掉，列表里就
 * "少了几个文件"。这里改成不做 MIME 过滤（* / *），选中之后再按扩展名、文件头、轨道
 * 三种手段判断真实类型，判断不出来也照常允许尝试，并给出明确提示。
 */
public final class Picker {

    public static final int OTHER = 0;
    public static final int VIDEO = 1;
    public static final int AUDIO = 2;
    public static final int IMAGE = 3;
    public static final int PDF = 4;
    public static final int TEXT = 5;

    private static final String[] VIDEO_EXT = {
            "mp4", "m4v", "mkv", "mov", "qt", "avi", "flv", "wmv", "asf", "ts", "m2ts", "mts",
            "mpg", "mpeg", "mpe", "vob", "rmvb", "rm", "3gp", "3g2", "webm", "f4v", "ogv",
            "divx", "mxf", "dv", "wtv", "tp", "trp", "m1v", "m2v", "ogm", "h264", "h265",
            "hevc", "vdat", "vob"
    };
    private static final String[] AUDIO_EXT = {
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "amr", "ape",
            "mid", "midi", "aiff", "aif", "ac3", "eac3", "dts", "mka", "ra", "au", "mp2",
            "mpc", "tta", "wv", "spx", "caf", "silk", "slk", "m4b"
    };
    private static final String[] IMAGE_EXT = {
            "png", "jpg", "jpeg", "jpe", "webp", "bmp", "gif", "heic", "heif", "tif", "tiff",
            "ico", "avif", "jp2", "dng", "raw", "jfif"
    };

    private Picker() {}

    /** 不做 MIME 过滤的选择器：设备上任何文件都能列出来 */
    public static Intent open(boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // 允许把 uri 申请成持久权限，转码队列才能在关掉页面后恢复（1.6.7）
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    /**
     * 取选中的第一个文件。
     *
     * 为什么不能只读 data.getData()：部分 ROM（实测 vivo）的系统文件选择器即使单选，
     * 结果也只放在 ClipData 里，getData() 是 null，页面就"选了没反应"。这里两种都认。
     */
    public static Uri firstUri(Intent data) {
        if (data == null) return null;
        Uri u = data.getData();
        if (u != null) return u;
        android.content.ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).getUri();
        }
        return null;
    }

    /** 授权整个文件夹（m3u8 要用它来读同目录分片） */
    public static boolean launchTree(Activity a, int requestCode) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), requestCode);
            return true;
        } catch (Exception e) {
            Ui.toast(a, "无法打开文件夹选择器");
            return false;
        }
    }

    /** 单文件也可以用多选入口，这里把选中的全部 uri 取出来 */
    public static java.util.List<Uri> allUris(Intent data) {
        java.util.List<Uri> out = new java.util.ArrayList<Uri>();
        if (data == null) return out;
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
            if (!out.isEmpty()) return out;
        }
        Uri u = data.getData();
        if (u != null) out.add(u);
        return out;
    }

    public static boolean launch(Activity a, int requestCode, boolean multiple) {
        try {
            a.startActivityForResult(open(multiple), requestCode);
            return true;
        } catch (Exception e) {
            Ui.toast(a, "无法打开文件选择器");
            return false;
        }
    }

    public static String label(int kind) {
        switch (kind) {
            case VIDEO: return "视频";
            case AUDIO: return "音频";
            case IMAGE: return "图片";
            case PDF: return "PDF";
            case TEXT: return "文本";
            default: return "未知类型文件";
        }
    }

    /** 判断文件的真实类型：扩展名 → 文件头 → 音视频轨道 → 系统 MIME */
    public static int kindOf(Context c, Uri uri, String name) {
        String e = Ui.ext(name);
        if (match(VIDEO_EXT, e)) return VIDEO;
        if (match(AUDIO_EXT, e)) return AUDIO;
        if (match(IMAGE_EXT, e)) return IMAGE;
        if ("pdf".equals(e)) return PDF;
        if (Ui.isTextFile(name)) return TEXT;

        int magic = sniff(c, uri);
        if (magic != OTHER) return magic;

        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, uri, null);
            boolean hasVideo = false, hasAudio = false;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) hasVideo = true;
                else if (mime.startsWith("audio/")) hasAudio = true;
            }
            if (hasVideo) return VIDEO;
            if (hasAudio) return AUDIO;
        } catch (Throwable ignored) {
            // RMVB / VOB / 老 AVI 系统建不出解复用器，这里失败很正常，继续往下猜
        } finally {
            try { ex.release(); } catch (Throwable ignored) {}
        }

        String mime = null;
        try { mime = c.getContentResolver().getType(uri); } catch (Throwable ignored) {}
        if (mime != null) {
            if (mime.startsWith("video/")) return VIDEO;
            if (mime.startsWith("audio/")) return AUDIO;
            if (mime.startsWith("image/")) return IMAGE;
        }
        return OTHER;
    }

    /** 读文件头猜类型；猜不出来返回 OTHER */
    private static int sniff(Context c, Uri uri) {
        byte[] head = new byte[32];
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return OTHER;
            int n = 0;
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r <= 0) break;
                n += r;
            }
            if (n < 4) return OTHER;

            if (head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return PDF;
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return IMAGE;
            if (head[0] == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return IMAGE;
            if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return IMAGE;
            if (head[0] == 'B' && head[1] == 'M') return IMAGE;
            if (head[0] == 'I' && head[1] == 'I' && head[2] == 0x2A && head[3] == 0) return IMAGE;
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                if (n >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return IMAGE;
                if (n >= 12 && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') return AUDIO;
                if (n >= 12 && head[8] == 'A' && head[9] == 'V' && head[10] == 'I' && head[11] == ' ') return VIDEO;
            }
            if (head[0] == 'f' && head[1] == 't' && head[2] == 'y' && head[3] == 'p') return VIDEO;
            if (head[0] == 0x1A && head[1] == 0x45 && head[2] == 0xDF && head[3] == 0xA3) return VIDEO;
            if (head[0] == 'F' && head[1] == 'L' && head[2] == 'V') return VIDEO;
            if (head[0] == 0 && head[1] == 0 && (head[2] & 0xFF) == 1 && (head[3] & 0xFF) == 0xBA) return VIDEO;
            if (head[0] == 0x30 && head[1] == 0x26 && head[2] == 0xB7 && head[3] == 0x1D) return VIDEO;
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return AUDIO;
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return AUDIO;
            if (head[0] == 0x1A && head[1] == 0x45 && head[2] == 0xDF && head[3] == 0xA3) return VIDEO;
            if (n >= 16 && head[0] == '.' && head[1] == 'R' && head[2] == 'M' && head[3] == 'F') return VIDEO;
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return AUDIO;
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFB) return AUDIO;
        } catch (Throwable ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
        return OTHER;
    }

    /** 选中后的提示语：类型对得上就返回 null */
    public static String mismatchNote(Context c, Uri uri, String name, int expect) {
        int kind = kindOf(c, uri, name);
        if (kind == expect) return null;
        String ext = Ui.ext(name);
        String tail = ext.length() == 0 ? "" : "（扩展名 ." + ext + "）";
        return "这个文件看着是" + label(kind) + tail + "，不是" + label(expect)
                + "。仍可点“开始”，处理失败会给出具体原因。";
    }

    private static boolean match(String[] list, String e) {
        if (e == null || e.length() == 0) return false;
        for (String s : list) if (s.equals(e)) return true;
        return false;
    }
}
