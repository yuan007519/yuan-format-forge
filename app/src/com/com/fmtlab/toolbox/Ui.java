package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 界面与文件小工具。 */
public final class Ui {

    private Ui() {}

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int dpi(Context c, int v) {
        return Math.round(dp(c, v));
    }

    public static void toast(Context c, String msg) {
        if (c == null || msg == null) return;
        Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    public static GradientDrawable roundRect(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dpi(c, (int) strokeDp)), strokeColor);
        return d;
    }

    public static void copy(Activity a, String text) {
        if (TextUtils.isEmpty(text)) {
            toast(a, "没有可复制的内容");
            return;
        }
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("格式工坊", text));
            toast(a, "已复制到剪贴板");
        }
    }

    public static String paste(Activity a) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return "";
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence t = clip.getItemAt(0).coerceToText(a);
        return t == null ? "" : t.toString();
    }

    public static byte[] readBytes(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("无法读取该文件");
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(java.util.Locale.US);
    }

    public static String baseName(String name) {
        if (name == null) return "file";
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    public static String displayName(Context c, Uri uri) {
        String name = null;
        try {
            ContentResolver cr = c.getContentResolver();
            android.database.Cursor cur = cr.query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cur.moveToFirst()) name = cur.getString(idx);
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name;
    }

    public static String mimeFor(String name) {
        String e = ext(name);
        switch (e) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "gif": return "image/gif";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "md": return "text/markdown";
            case "html":
            case "htm": return "text/html";
            case "json": return "application/json";
            case "xml": return "text/xml";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "mp4": return "video/mp4";
            default: return "application/octet-stream";
        }
    }

    /** 输出目录：应用专属目录，不需要存储权限 */
    public static File outDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File dir = new File(base, "output");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File writeTemp(Context c, String name, byte[] data) throws IOException {
        File f = new File(outDir(c), name);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
        }
        return f;
    }

    public static void shareFiles(Activity a, List<File> files, String subject) {
        if (files == null || files.isEmpty()) {
            toast(a, "还没有生成文件");
            return;
        }
        Intent intent;
        if (files.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeFor(files.get(0).getName()));
            intent.putExtra(Intent.EXTRA_STREAM, ExportProvider.uriFor(a, files.get(0)));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("*/*");
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            for (File f : files) uris.add(ExportProvider.uriFor(a, f));
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        try {
            a.startActivity(Intent.createChooser(intent, "分享文件"));
        } catch (Exception e) {
            toast(a, "没有可用的分享应用");
        }
    }

    public static Intent createDocIntent(String name, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime == null ? "application/octet-stream" : mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        return i;
    }

    public static void writeToUri(Context c, Uri uri, byte[] data) throws IOException {
        OutputStream out = c.getContentResolver().openOutputStream(uri);
        if (out == null) throw new IOException("无法写入该位置");
        try {
            out.write(data);
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    public static void copyToUri(Context c, Uri uri, File src) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = c.getContentResolver().openOutputStream(uri);
        if (out == null) throw new IOException("无法写入该位置");
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    /** 用 DocumentsContract 在用户选中的目录里建文件并写入 */
    public static boolean copyIntoTrees(Context c, Uri treeUri, List<File> files, String mimeFallback) {
        try {
            String parentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
            for (File f : files) {
                String name = f.getName();
                Uri doc = android.provider.DocumentsContract.createDocument(
                        c.getContentResolver(), parent, mimeFor(name), name);
                if (doc == null) return false;
                copyToUri(c, doc, f);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static TextView chip(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dpi(a, 14), dpi(a, 8), dpi(a, 14), dpi(a, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dpi(a, 8));
        tv.setLayoutParams(lp);
        tv.setBackgroundResource(R.drawable.bg_chip_off);
        tv.setTextColor(a.getResources().getColorStateList(R.color.text_primary));
        return tv;
    }

    public static void styleChip(Activity a, TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        tv.setTextColor(a.getResources().getColor(on ? R.color.chip_on_text : R.color.text_primary));
        tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(c, heightDp)));
        return v;
    }

    public static boolean isTextFile(String name) {
        String e = ext(name);
        return e.equals("txt") || e.equals("log") || e.equals("csv") || e.equals("md")
                || e.equals("html") || e.equals("htm") || e.equals("json") || e.equals("xml")
                || e.equals("srt") || e.equals("ini") || e.equals("conf") || e.equals("yaml")
                || e.equals("yml") || e.equals("ass") || e.equals("vtt") || e.equals("bat")
                || e.equals("sh") || e.equals("css") || e.equals("js") || e.equals("java")
                || e.equals("py") || e.equals("c") || e.equals("cpp") || e.equals("h");
    }

    public static boolean isImage(String name) {
        String e = ext(name);
        return e.equals("png") || e.equals("jpg") || e.equals("jpeg") || e.equals("webp")
                || e.equals("bmp") || e.equals("gif") || e.equals("heic") || e.equals("heif");
    }

    public static String apiLevelNote() {
        return "Android " + Build.VERSION.RELEASE;
    }

    /** 结果行：文件名 + 说明 + 分享按钮 */
    public static LinearLayout resultRow(Activity a, File f, String meta, View.OnClickListener onShare) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_result);
        row.setPadding(dpi(a, 12), dpi(a, 10), dpi(a, 8), dpi(a, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpi(a, 8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(a);
        name.setText(f.getName());
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        name.setTextColor(a.getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        TextView sub = new TextView(a);
        sub.setText(meta);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setTextColor(a.getColor(R.color.text_secondary));

        col.addView(name);
        col.addView(sub);
        row.addView(col);

        if (onShare != null) {
            ImageView share = new ImageView(a);
            share.setImageResource(R.drawable.ic_share);
            share.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.brand)));
            share.setPadding(dpi(a, 8), dpi(a, 8), dpi(a, 8), dpi(a, 8));
            share.setLayoutParams(new LinearLayout.LayoutParams(dpi(a, 38), dpi(a, 38)));
            share.setBackgroundResource(R.drawable.bg_card_ripple);
            share.setOnClickListener(onShare);
            row.addView(share);
        }
        return row;
    }

    /** 一排可选标签（chips）的轻量管理 */
    public static class Chips {
        private final Activity act;
        private final LinearLayout host;
        private final String[] labels;
        private final java.util.List<TextView> views = new java.util.ArrayList<>();
        private int selected;
        private Runnable onChange;

        public Chips(Activity act, LinearLayout host, String[] labels, int selected) {
            this.act = act;
            this.host = host;
            this.labels = labels;
            this.selected = Math.max(0, Math.min(selected, labels.length - 1));
            ensureScrollable(host);
            build();
        }

        /**
         * 标签一多，横向 LinearLayout 会把最后一个挤成很窄的一条（文字竖排）。
         * 这里把承载容器套进横向滚动容器，让每个标签保持 wrap_content 的自然宽度。
         */
        private void ensureScrollable(LinearLayout box) {
            ViewParent p = box.getParent();
            if (p instanceof HorizontalScrollView) {
                // 已经在滚动容器里，保持 XML 里的 LayoutParams 原样返回。
                // 这里绝不能 setLayoutParams(new ViewGroup.LayoutParams(...))：
                // HorizontalScrollView 是 FrameLayout，子 View 的 params 必须是
                // FrameLayout.LayoutParams，换成基类会在 onLayout 里 ClassCastException 闪退。
                return;
            }
            if (!(p instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) p;
            int index = parent.indexOfChild(box);
            if (index < 0) return;
            ViewGroup.LayoutParams lp = box.getLayoutParams();
            HorizontalScrollView hsv = new HorizontalScrollView(act);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hsv.setFillViewport(false);
            hsv.setClipToPadding(false);
            parent.removeViewAt(index);
            hsv.setLayoutParams(lp);
            hsv.addView(box, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            parent.addView(hsv, index);
        }

        private void build() {
            host.removeAllViews();
            views.clear();
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView tv = Ui.chip(act, labels[i]);
                Ui.styleChip(act, tv, i == selected);
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setSelected(index);
                        if (onChange != null) onChange.run();
                    }
                });
                host.addView(tv);
                views.add(tv);
            }
        }

        public Chips onChange(Runnable r) {
            this.onChange = r;
            return this;
        }

        public void setSelected(int index) {
            if (labels.length == 0) return;
            selected = Math.max(0, Math.min(index, labels.length - 1));
            for (int i = 0; i < views.size(); i++) Ui.styleChip(act, views.get(i), i == selected);
        }

        public int selected() {
            return selected;
        }

        public String label() {
            return labels.length == 0 ? "" : labels[selected];
        }

        public String labelAt(int i) {
            return (i >= 0 && i < labels.length) ? labels[i] : "";
        }

        public int count() {
            return labels.length;
        }

        public void setVisible(boolean visible) {
            if (host != null) host.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
