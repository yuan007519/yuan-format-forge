package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fmtlab.toolbox.core.DocMatrix;
import com.fmtlab.toolbox.core.PdfExport;
import com.fmtlab.toolbox.core.PptxReader;
import com.fmtlab.toolbox.core.TextCodec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 文档格式矩阵：Word / Excel / PPT / ODF / EPUB / 网页 / Markdown / CSV / JSON / 纯文本互转 */
public class DocActivity extends Activity {

    private static final int REQ_PICK = 51;
    private static final int REQ_SAVE = 52;

    private TextView tvDocInfo, tvOutHint, tvDocStatus, tvPreview;
    private ScrollView svPreview;
    private LinearLayout outChips, docActionRow, docResultHost;
    private ProgressBar pbDoc;
    private Button btnDocConvert;

    private Uri docUri;
    private String docName = "";
    private String[] outLabels = new String[0];
    private Ui.Chips chips;
    private String resultText = "";
    private File resultFile;
    private String resultName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("文档转换");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("Word / Excel / PPT / ODF / EPUB / 网页 / MD / CSV / JSON · 可出 DOCX 与 PDF");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvDocInfo = (TextView) findViewById(R.id.tvDocInfo);
        tvOutHint = (TextView) findViewById(R.id.tvOutHint);
        tvDocStatus = (TextView) findViewById(R.id.tvDocStatus);
        tvPreview = (TextView) findViewById(R.id.tvPreview);
        svPreview = (ScrollView) findViewById(R.id.svPreview);
        outChips = (LinearLayout) findViewById(R.id.outChips);
        docActionRow = (LinearLayout) findViewById(R.id.docActionRow);
        docResultHost = (LinearLayout) findViewById(R.id.docResultHost);
        pbDoc = (ProgressBar) findViewById(R.id.pbDoc);
        btnDocConvert = (Button) findViewById(R.id.btnDocConvert);

        findViewById(R.id.btnPickDoc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    Ui.toast(DocActivity.this, "无法打开文件选择器");
                }
            }
        });

        btnDocConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });

        findViewById(R.id.btnDocCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultText.isEmpty()) {
                    Ui.toast(DocActivity.this, resultFile != null ? "二进制文件无法复制成文字，请用「保存」或「分享」" : "还没有转换结果");
                    return;
                }
                Ui.copy(DocActivity.this, resultText);
            }
        });

        findViewById(R.id.btnDocSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultFile == null && resultText.isEmpty()) {
                    Ui.toast(DocActivity.this, "还没有转换结果");
                    return;
                }
                try {
                    startActivityForResult(Ui.createDocIntent(resultName, Ui.mimeFor(resultName)), REQ_SAVE);
                } catch (Exception e) {
                    Ui.toast(DocActivity.this, "无法打开保存对话框");
                }
            }
        });

        findViewById(R.id.btnDocShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultFile != null) {
                    List<File> list = new ArrayList<>();
                    list.add(resultFile);
                    Ui.shareFiles(DocActivity.this, list, "文档转换结果");
                    return;
                }
                if (resultText.isEmpty()) {
                    Ui.toast(DocActivity.this, "还没有转换结果");
                    return;
                }
                try {
                    File f = Ui.writeTemp(DocActivity.this, resultName,
                            TextCodec.encodeStrict(resultText, TextCodec.UTF8));
                    List<File> list = new ArrayList<>();
                    list.add(f);
                    Ui.shareFiles(DocActivity.this, list, "文档转换结果");
                } catch (Exception e) {
                    Ui.toast(DocActivity.this, "生成文件失败：" + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK) {
            docUri = data.getData();
            docName = Ui.displayName(this, docUri);
            String ext = Ui.ext(docName);
            resultText = "";
            resultFile = null;
            docResultHost.removeAllViews();
            docResultHost.setVisibility(View.GONE);
            tvPreview.setText("");
            svPreview.setVisibility(View.GONE);
            tvDocInfo.setText("已选择：" + docName + "（." + ext + "）");
            if (DocMatrix.isLegacyBinary(ext)) {
                outLabels = new String[0];
                tvOutHint.setText("老式二进制格式 ." + ext + "（doc / wps / xls / et / ppt / dps / mobi / ofd）"
                        + "的私有编码解不开，请先在原软件里另存为 docx / xlsx / pptx，或直接转 PDF。");
            } else {
                outLabels = outputsFor(ext);
                if (outLabels.length == 0) {
                    tvOutHint.setText("暂不支持 ." + ext + "：可读 docx / xlsx / pptx / odt / ods / odp / epub / html / md / csv / tsv / json / txt / xml / yaml。");
                } else {
                    tvOutHint.setText(DocMatrix.describe(ext) + "\n可转换为：" + TextUtils.join(" / ", outLabels));
                }
            }
            outChips.removeAllViews();
            chips = outLabels.length == 0 ? null : new Ui.Chips(this, outChips, outLabels, 0);
        } else if (requestCode == REQ_SAVE) {
            try {
                if (resultFile != null) {
                    Ui.copyToUri(this, data.getData(), resultFile);
                } else {
                    Ui.writeToUri(this, data.getData(),
                            TextCodec.encodeStrict(resultText, TextCodec.UTF8));
                }
                Ui.toast(this, "已保存：" + resultName);
            } catch (Exception e) {
                Ui.toast(this, "保存失败：" + e.getMessage());
            }
        }
    }

    private String[] outputsFor(String ext) {
        if (!DocMatrix.readable(ext)) return new String[0];
        String[] base = DocMatrix.isTable(ext) ? DocMatrix.TABLE_OUT : DocMatrix.TEXT_OUT;
        if (!"pptx".equals(ext)) return base;
        List<String> all = new ArrayList<>();
        for (String s : base) all.add(s);
        all.add("PNG 图片");
        all.add("JPG 图片");
        return all.toArray(new String[0]);
    }

    private static String extOf(String label) {
        if ("Markdown".equals(label) || "Markdown 表格".equals(label)) return "md";
        if ("HTML".equals(label) || "HTML 表格".equals(label)) return "html";
        if ("TXT".equals(label)) return "txt";
        if ("CSV".equals(label)) return "csv";
        if ("TSV".equals(label)) return "tsv";
        if ("JSON".equals(label)) return "json";
        if ("DOCX".equals(label)) return "docx";
        if ("ODT".equals(label)) return "odt";
        if ("RTF".equals(label)) return "rtf";
        if ("XLSX".equals(label)) return "xlsx";
        if ("ODS".equals(label)) return "ods";
        if ("PNG 图片".equals(label)) return "png";
        if ("JPG 图片".equals(label)) return "jpg";
        if ("PDF".equals(label)) return "pdf";
        if ("EPUB".equals(label)) return "epub";
        return "txt";
    }

    private static boolean isBinaryOut(String label) {
        return "DOCX".equals(label) || "PDF".equals(label) || "EPUB".equals(label)
                || "ODT".equals(label) || "XLSX".equals(label) || "ODS".equals(label);
    }

    private static boolean isSlideImage(String label) {
        return "PNG 图片".equals(label) || "JPG 图片".equals(label);
    }

    private void convert() {
        if (docUri == null) {
            Ui.toast(this, "请先选择文档");
            return;
        }
        if (chips == null) {
            Ui.toast(this, "暂不支持该格式");
            return;
        }
        final String ext = Ui.ext(docName);
        final String out = chips.label();
        pbDoc.setVisibility(View.VISIBLE);
        btnDocConvert.setEnabled(false);
        svPreview.setVisibility(View.GONE);
        docActionRow.setVisibility(View.GONE);
        docResultHost.removeAllViews();
        docResultHost.setVisibility(View.GONE);
        tvDocStatus.setText("正在读取并转换…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isSlideImage(out)) {
                        exportSlides(out);
                        return;
                    }
                    final byte[] bytes = Ui.readBytes(DocActivity.this, docUri);
                    final String outExt = extOf(out);
                    final File outFile = new File(Ui.outDir(DocActivity.this),
                            uniqueName(Ui.baseName(docName) + "_转换." + outExt));
                    final String note = DocMatrix.write(bytes, ext, docName, out, outFile);
                    if (!outFile.exists() || outFile.length() == 0) throw new Exception("写出失败");
                    final boolean binary = isBinaryOut(out);
                    final String textContent = binary ? "" : readText(outFile);
                    final String previewText = binary
                            ? "已生成 " + out + " 文件：" + outFile.getName() + "\n" + Ui.size(outFile.length())
                                    + "\n\n" + note + "\n\n（DOCX / PDF / EPUB 是二进制文件，点「保存到手机」或「分享」导出）"
                            : textContent;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            resultFile = binary ? outFile : null;
                            resultText = textContent;
                            resultName = outFile.getName();
                            pbDoc.setVisibility(View.GONE);
                            btnDocConvert.setEnabled(true);
                            svPreview.setVisibility(View.VISIBLE);
                            docActionRow.setVisibility(View.VISIBLE);
                            String shown = previewText.length() > 3500
                                    ? previewText.substring(0, 3500) + "\n\n…（预览截断，完整内容请另存或分享）"
                                    : previewText;
                            tvPreview.setText(shown.isEmpty() ? "（空）" : shown);
                            tvDocStatus.setText("转换完成：" + out + " · " + note
                                    + " · " + Ui.size(outFile.length()) + "（已存到应用目录）");
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbDoc.setVisibility(View.GONE);
                            btnDocConvert.setEnabled(true);
                            tvDocStatus.setText("转换失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                        }
                    });
                }
            }
        });
    }

    /** PPTX 逐页导图：先用现有排版引擎出 A4 PDF，再逐页渲染成 PNG / JPG */
    private void exportSlides(String label) throws Exception {
        final String outExt = extOf(label);
        final Bitmap.CompressFormat cf = "png".equals(outExt)
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        byte[] bytes = Ui.readBytes(DocActivity.this, docUri);
        PptxReader.Deck deck = PptxReader.read(bytes);
        if (deck.slides.isEmpty()) throw new Exception("这个 PPTX 里没有可导出的幻灯片");

        final File tmpPdf = new File(getCacheDir(), "slides_" + System.currentTimeMillis() + ".pdf");
        PdfExport.slidesToPdf(tmpPdf, deck);

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        int ok = 0;
        try {
            pfd = ParcelFileDescriptor.open(tmpPdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int total = renderer.getPageCount();
            for (int i = 0; i < total; i++) {
                final int pageNo = i + 1;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvDocStatus.setText("正在导出第 " + pageNo + "/" + total + " 张…");
                    }
                });
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    int w = Math.max(1, (int) (page.getWidth() * 2));
                    int h = Math.max(1, (int) (page.getHeight() * 2));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File out = new File(Ui.outDir(this), uniqueName(
                            Ui.baseName(docName) + "_第" + pageNo + "页." + outExt));
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    try {
                        bmp.compress(cf, 92, fos);
                        fos.flush();
                    } finally {
                        fos.close();
                    }
                    bmp.recycle();
                    ok++;
                    final File fout = out;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            addResultRow(fout);
                        }
                    });
                } finally {
                    page.close();
                }
            }
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            if (tmpPdf.exists()) tmpPdf.delete();
        }
        final int done = ok;
        handler.post(new Runnable() {
            @Override
            public void run() {
                pbDoc.setVisibility(View.GONE);
                btnDocConvert.setEnabled(true);
                docResultHost.setVisibility(View.VISIBLE);
                if (done > 0) {
                    tvDocStatus.setText("已导出 " + done + " 张 " + label + "（保存在应用目录，可分享或另存）");
                } else {
                    tvDocStatus.setText("没有导出任何图片");
                }
            }
        });
    }

    private void addResultRow(final File f) {
        docResultHost.setVisibility(View.VISIBLE);
        docResultHost.addView(Ui.resultRow(this, f, Ui.size(f.length()), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<File> one = new ArrayList<>();
                one.add(f);
                Ui.shareFiles(DocActivity.this, one, "文档转换结果");
            }
        }));
    }

    private String readText(File f) {
        if (f == null) return "";
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] data = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024L)];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(data, 0, off, java.nio.charset.Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "已生成：" + f.getName();
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private String uniqueName(String name) {
        File dir = Ui.outDir(this);
        if (!new File(dir, name).exists()) return name;
        String base = Ui.baseName(name);
        String ext = Ui.ext(name);
        for (int i = 2; i < 1000; i++) {
            String n = base + "(" + i + ")." + ext;
            if (!new File(dir, n).exists()) return n;
        }
        return base + "_" + System.currentTimeMillis() + "." + ext;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
