package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fmtlab.toolbox.core.Bmp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 图片格式转换：PNG / JPG / WEBP / BMP，可调画质与尺寸，支持批量 */
public class ImageActivity extends Activity {

    private static final int REQ_PICK = 31;
    private static final int REQ_TREE = 32;

    private static final String[] FMT_LABELS = {"PNG", "JPG", "WEBP", "BMP"};
    private static final String[] FMT_EXTS = {"png", "jpg", "webp", "bmp"};
    private static final String[] SCALE_LABELS = {"原尺寸", "≤1920", "≤1280", "≤800", "50%"};

    private TextView tvFileInfo, tvStatus, tvQualityLabel;
    private SeekBar sbQuality;
    private ProgressBar pb;
    private LinearLayout resultHost;
    private Button btnConvert, btnSaveAll;
    private Ui.Chips fmtChips, scaleChips;

    private final List<Uri> picked = new ArrayList<>();
    private final List<String> pickedNames = new ArrayList<>();
    private final List<File> results = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("图片格式转换");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("批量互转 · 画质可调 · 自动纠正拍照方向");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvFileInfo = (TextView) findViewById(R.id.tvFileInfo);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvQualityLabel = (TextView) findViewById(R.id.tvQualityLabel);
        sbQuality = (SeekBar) findViewById(R.id.sbQuality);
        pb = (ProgressBar) findViewById(R.id.pb);
        resultHost = (LinearLayout) findViewById(R.id.resultHost);
        btnConvert = (Button) findViewById(R.id.btnConvert);
        btnSaveAll = (Button) findViewById(R.id.btnSaveAll);

        fmtChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.fmtChips), FMT_LABELS, 0);
        scaleChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.scaleChips), SCALE_LABELS, 0);

        sbQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvQualityLabel.setText("画质 " + progress + "%（JPG / WEBP 有效）");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.btnPickImages).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(ImageActivity.this, REQ_PICK, true);
            }
        });

        btnConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });

        btnSaveAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (results.isEmpty()) {
                    Ui.toast(ImageActivity.this, "还没有可保存的文件");
                    return;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(i, REQ_TREE);
                } catch (Exception e) {
                    Ui.toast(ImageActivity.this, "无法打开文件夹选择器");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            picked.clear();
            pickedNames.clear();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) addPicked(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addPicked(data.getData());
            }
            updateFileInfo();
        } else if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            boolean ok = Ui.copyIntoTrees(this, tree, results, "application/octet-stream");
            Ui.toast(this, ok ? "已全部保存到所选文件夹" : "保存失败，换个文件夹试试");
        }
    }

    private void addPicked(Uri uri) {
        if (uri == null) return;
        String name = Ui.displayName(this, uri);
        picked.add(uri);
        pickedNames.add(name);
    }

    private void updateFileInfo() {
        if (picked.isEmpty()) {
            tvFileInfo.setText("还没有选择图片");
            return;
        }
        StringBuilder sb = new StringBuilder("已选择 " + picked.size() + " 张：");
        long total = 0;
        int shown = 0;
        for (int i = 0; i < pickedNames.size(); i++) {
            total += sizeOf(picked.get(i));
            if (shown < 3) {
                if (shown > 0) sb.append("、");
                sb.append(pickedNames.get(i));
                shown++;
            }
        }
        if (pickedNames.size() > 3) sb.append(" 等");
        sb.append("\n共 ").append(Ui.size(total));
        tvFileInfo.setText(sb.toString());
    }

    private long sizeOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (idx >= 0 && c.moveToFirst()) return c.getLong(idx);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int targetMax() {
        int sel = scaleChips.selected();
        switch (sel) {
            case 1: return 1920;
            case 2: return 1280;
            case 3: return 800;
            default: return 0;
        }
    }

    private void convert() {
        if (picked.isEmpty()) {
            Ui.toast(this, "请先选择图片");
            return;
        }
        final int fmt = fmtChips.selected();
        final int quality = Math.max(1, sbQuality.getProgress());
        final int maxEdge = targetMax();
        final boolean half = scaleChips.selected() == 4;

        results.clear();
        resultHost.removeAllViews();
        btnSaveAll.setVisibility(View.GONE);
        pb.setVisibility(View.VISIBLE);
        tvStatus.setText("准备转换…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (int i = 0; i < picked.size(); i++) {
                    final Uri uri = picked.get(i);
                    final String name = pickedNames.get(i);
                    final int index = i + 1;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("正在转换 " + index + "/" + picked.size() + "：" + name);
                        }
                    });
                    try {
                        File out = convertOne(uri, name, fmt, quality, maxEdge, half);
                        if (out != null) {
                            results.add(out);
                            ok++;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    addResultRow(out);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        final String msg = name + "：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvStatus.setText("部分文件失败：" + msg);
                            }
                        });
                    }
                }
                final int done = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pb.setVisibility(View.GONE);
                        if (done == 0) {
                            tvStatus.setText("转换失败，可能是格式不受支持或图片过大");
                        } else {
                            tvStatus.setText("完成：" + done + "/" + picked.size() + " 张已转换为 "
                                    + FMT_LABELS[fmt] + "（保存在应用专属目录，可分享或另存）");
                            btnSaveAll.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private File convertOne(Uri uri, String name, int fmt, int quality, int maxEdge, boolean half) throws Exception {
        Bitmap src = loadBitmap(uri, half ? 0 : maxEdge);
        if (src == null) throw new Exception("无法解码这张图片");
        Bitmap work = src;
        if (half) {
            work = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 2), Math.max(1, src.getHeight() / 2), true);
        } else if (maxEdge > 0) {
            int w = src.getWidth(), h = src.getHeight();
            if (Math.max(w, h) > maxEdge) {
                double s = (double) maxEdge / Math.max(w, h);
                work = Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
            }
        }

        String outName = uniqueName(Ui.baseName(name) + "_" + FMT_LABELS[fmt].toLowerCase() + "." + FMT_EXTS[fmt]);
        File out = new File(Ui.outDir(this), outName);

        if (fmt == 3) {
            int w = work.getWidth(), h = work.getHeight();
            int[] px = new int[w * h];
            work.getPixels(px, 0, w, 0, 0, w, h);
            byte[] data = Bmp.encode(px, w, h);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
        } else {
            Bitmap toWrite = work;
            if (fmt == 1 && work.hasAlpha()) {
                toWrite = Bitmap.createBitmap(work.getWidth(), work.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(toWrite);
                c.drawColor(Color.WHITE);
                c.drawBitmap(work, 0, 0, null);
            }
            Bitmap.CompressFormat cf = Bitmap.CompressFormat.PNG;
            if (fmt == 1) cf = Bitmap.CompressFormat.JPEG;
            else if (fmt == 2) {
                if (Build.VERSION.SDK_INT >= 30) cf = Bitmap.CompressFormat.WEBP_LOSSY;
                else cf = Bitmap.CompressFormat.WEBP;
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                toWrite.compress(cf, fmt == 0 ? 100 : quality, fos);
                fos.flush();
            } finally {
                fos.close();
            }
            if (toWrite != work && !toWrite.isRecycled()) toWrite.recycle();
        }
        if (work != src && !work.isRecycled()) work.recycle();
        if (!src.isRecycled()) src.recycle();

        if (!out.exists() || out.length() == 0) throw new Exception("写出失败");
        return out;
    }

    private String uniqueName(String name) {
        File dir = Ui.outDir(this);
        File f = new File(dir, name);
        if (!f.exists()) return name;
        String base = Ui.baseName(name);
        String ext = Ui.ext(name);
        for (int i = 2; i < 1000; i++) {
            String n = base + "(" + i + ")." + ext;
            if (!new File(dir, n).exists()) return n;
        }
        return base + "_" + System.currentTimeMillis() + "." + ext;
    }

    /** 解码 + 按 EXIF 纠正方向；maxEdge 用于提前降采样省内存 */
    private Bitmap loadBitmap(Uri uri, int maxEdge) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in1 = getContentResolver().openInputStream(uri);
        if (in1 == null) throw new Exception("无法读取文件");
        try {
            BitmapFactory.decodeStream(in1, null, bounds);
        } finally {
            in1.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("不是有效的图片");

        int sample = 1;
        if (maxEdge > 0) {
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) > maxEdge) sample *= 2;
        } else {
            while ((long) bounds.outWidth * bounds.outHeight / ((long) sample * sample) > 16L * 1024 * 1024) sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in2 = getContentResolver().openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in2, null, opts);
        } finally {
            in2.close();
        }
        if (bmp == null) throw new Exception("解码失败");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            InputStream in3 = getContentResolver().openInputStream(uri);
            try {
                ExifInterface exif = new ExifInterface(in3);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } finally {
                in3.close();
            }
        } catch (Exception ignored) {}

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            default: break;
        }
        if (!m.isIdentity()) {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    private void addResultRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_result);
        row.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(this, 8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(colLp);
        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        name.setTextColor(getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView meta = new TextView(this);
        meta.setText(Ui.size(f.length()) + " · " + imageDims(f));
        meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        meta.setTextColor(getColor(R.color.text_secondary));
        col.addView(name);
        col.addView(meta);
        row.addView(col);

        ImageView share = new ImageView(this);
        share.setImageResource(R.drawable.ic_share);
        share.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.brand)));
        share.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 8), Ui.dpi(this, 8), Ui.dpi(this, 8));
        share.setLayoutParams(new LinearLayout.LayoutParams(Ui.dpi(this, 38), Ui.dpi(this, 38)));
        share.setBackgroundResource(R.drawable.bg_card_ripple);
        final File file = f;
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<File> one = new ArrayList<>();
                one.add(file);
                Ui.shareFiles(ImageActivity.this, one, "转换后的图片");
            }
        });
        row.addView(share);

        resultHost.addView(row);
    }

    private String imageDims(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return "—";
        return o.outWidth + "×" + o.outHeight;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
