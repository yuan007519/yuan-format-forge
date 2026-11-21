package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fmtlab.toolbox.core.PdfExport;
import com.fmtlab.toolbox.core.PptxReader;
import com.fmtlab.toolbox.core.PptxWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF 转图片 / 图片合成 PDF / PDF 转 PPTX / PPTX 转 PDF */
public class PdfActivity extends Activity {

    private static final int REQ_PICK_PDF = 41;
    private static final int REQ_PICK_IMG = 42;
    private static final int REQ_TREE = 43;
    private static final int REQ_PICK_PPTX = 44;

    private static final String[] MODE_LABELS = {"PDF 转图片", "图片 转 PDF", "PDF 转 PPTX", "PPTX 转 PDF", "PDF 转文本"};
    private static final String[] FMT_LABELS = {"PNG", "JPG"};
    private static final String[] SCALE_LABELS = {"标准", "高清", "超清"};
    private static final String[] PAPER_LABELS = {"跟随图片", "A4 竖版", "A4 横版"};

    private TextView tvPdfInfo, tvImgInfo, tvPdfStatus, tvPptxInfo, tvPptxNote;
    private EditText etPageRange;
    private LinearLayout pdfResultHost, cardPdfToImg, cardImgToPdf, cardPptxToPdf, pdfImgOptions, pdfTextOptions;
    private ProgressBar pbPdf;
    private Button btnPdfConvert, btnPdfSaveAll;
    private Ui.Chips modeChips, fmtChips, dpiChips, paperChips, pdfTextChips;
    private static final String[] PDF_TEXT_OUT = {"TXT", "Markdown", "HTML", "DOCX"};

    private Uri pdfUri;
    private String pdfName = "";
    private Uri pptxUri;
    private String pptxName = "";
    private final List<Uri> pickImages = new ArrayList<>();
    private final List<File> results = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("PDF 工具");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("转图片 · 图片合 PDF · PDF ⇄ PPTX");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvPdfInfo = (TextView) findViewById(R.id.tvPdfInfo);
        tvImgInfo = (TextView) findViewById(R.id.tvImgInfo);
        tvPdfStatus = (TextView) findViewById(R.id.tvPdfStatus);
        tvPptxInfo = (TextView) findViewById(R.id.tvPptxInfo);
        tvPptxNote = (TextView) findViewById(R.id.tvPptxNote);
        etPageRange = (EditText) findViewById(R.id.etPageRange);
        pdfResultHost = (LinearLayout) findViewById(R.id.pdfResultHost);
        cardPdfToImg = (LinearLayout) findViewById(R.id.cardPdfToImg);
        cardImgToPdf = (LinearLayout) findViewById(R.id.cardImgToPdf);
        cardPptxToPdf = (LinearLayout) findViewById(R.id.cardPptxToPdf);
        pdfImgOptions = (LinearLayout) findViewById(R.id.pdfImgOptions);
        pdfTextOptions = (LinearLayout) findViewById(R.id.pdfTextOptions);
        pbPdf = (ProgressBar) findViewById(R.id.pbPdf);
        btnPdfConvert = (Button) findViewById(R.id.btnPdfConvert);
        btnPdfSaveAll = (Button) findViewById(R.id.btnPdfSaveAll);

        modeChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.modeChips), MODE_LABELS, 0)
                .onChange(new Runnable() {
                    @Override
                    public void run() {
                        syncMode();
                    }
                });
        fmtChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.pdfFmtChips), FMT_LABELS, 0);
        dpiChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.dpiChips), SCALE_LABELS, 0);
        paperChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.paperChips), PAPER_LABELS, 1);
        pdfTextChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.pdfTextOutChips), PDF_TEXT_OUT, 0);
        syncMode();

        findViewById(R.id.btnPickPdf).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(PdfActivity.this, REQ_PICK_PDF, false);
            }
        });

        findViewById(R.id.btnPickImagesPdf).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(PdfActivity.this, REQ_PICK_IMG, true);
            }
        });

        findViewById(R.id.btnPickPptx).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(PdfActivity.this, REQ_PICK_PPTX, false);
            }
        });

        btnPdfConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (modeChips.selected()) {
                    case 0: exportPdf(); break;
                    case 1: imagesToPdf(); break;
                    case 2: pdfToPptx(); break;
                    case 3: pptxToPdf(); break;
                    default: pdfToText(); break;
                }
            }
        });

        btnPdfSaveAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (results.isEmpty()) {
                    Ui.toast(PdfActivity.this, "还没有可保存的文件");
                    return;
                }
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
                } catch (Exception e) {
                    Ui.toast(PdfActivity.this, "无法打开文件夹选择器");
                }
            }
        });
    }

    private void syncMode() {
        int mode = modeChips.selected();
        boolean textMode = mode == 4;
        cardPdfToImg.setVisibility(mode == 0 || mode == 2 || textMode ? View.VISIBLE : View.GONE);
        cardImgToPdf.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        cardPptxToPdf.setVisibility(mode == 3 ? View.VISIBLE : View.GONE);
        pdfImgOptions.setVisibility(mode == 0 || mode == 2 ? View.VISIBLE : View.GONE);
        pdfTextOptions.setVisibility(textMode ? View.VISIBLE : View.GONE);
        tvPptxNote.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        switch (mode) {
            case 0: btnPdfConvert.setText("开始导出图片"); break;
            case 1: btnPdfConvert.setText("开始生成 PDF"); break;
            case 2: btnPdfConvert.setText("开始生成 PPTX"); break;
            case 3: btnPdfConvert.setText("开始生成 PDF"); break;
            default: btnPdfConvert.setText("开始抽取文字"); break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_PDF && data.getData() != null) {
            pdfUri = data.getData();
            pdfName = Ui.displayName(this, pdfUri);
            int pages = pageCount(pdfUri);
            StringBuilder sb = new StringBuilder();
            sb.append("已选择：").append(pdfName).append('\n').append(Ui.size(sizeOf(pdfUri)));
            if (pages > 0) sb.append(" · 共 ").append(pages).append(" 页");
            else sb.append(" · 系统打不开这个 PDF（可能加密或已损坏）");
            String note = Picker.mismatchNote(this, pdfUri, pdfName, Picker.PDF);
            if (note != null) sb.append('\n').append(note);
            tvPdfInfo.setText(sb.toString());
        } else if (requestCode == REQ_PICK_PPTX && data.getData() != null) {
            pptxUri = data.getData();
            pptxName = Ui.displayName(this, pptxUri);
            StringBuilder sb = new StringBuilder();
            sb.append("已选择：").append(pptxName).append('\n').append(Ui.size(sizeOf(pptxUri)));
            if (!"pptx".equals(Ui.ext(pptxName))) {
                sb.append('\n').append("扩展名不是 .pptx，仍会按 PPTX 结构尝试解析");
            }
            tvPptxInfo.setText(sb.toString());
        } else if (requestCode == REQ_PICK_IMG) {
            pickImages.clear();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    pickImages.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                pickImages.add(data.getData());
            }
            StringBuilder sb = new StringBuilder("已选择 " + pickImages.size() + " 个文件：");
            for (int i = 0; i < Math.min(3, pickImages.size()); i++) {
                if (i > 0) sb.append("、");
                sb.append(Ui.displayName(this, pickImages.get(i)));
            }
            if (pickImages.size() > 3) sb.append(" 等");
            tvImgInfo.setText(sb.toString());
        } else if (requestCode == REQ_TREE && data.getData() != null) {
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            boolean ok = Ui.copyIntoTrees(this, tree, results, "application/octet-stream");
            Ui.toast(this, ok ? "已全部保存到所选文件夹" : "保存失败，换个文件夹试试");
        }
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

    private int pageCount(Uri uri) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer r = null;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return 0;
            r = new PdfRenderer(pfd);
            return r.getPageCount();
        } catch (Exception e) {
            return 0;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        }
    }

    private Set<Integer> parsePages(String spec) {
        Set<Integer> set = new HashSet<>();
        if (spec == null || spec.trim().isEmpty()) return set;
        for (String part : spec.split("[,，]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] ab = p.split("-");
                try {
                    int a = Integer.parseInt(ab[0].trim());
                    int b = Integer.parseInt(ab[1].trim());
                    if (a > b) { int t = a; a = b; b = t; }
                    for (int i = a; i <= b; i++) set.add(i);
                } catch (Exception ignored) {}
            } else {
                try { set.add(Integer.parseInt(p)); } catch (Exception ignored) {}
            }
        }
        return set;
    }

    private float renderScale() {
        return dpiChips.selected() == 0 ? 2f : (dpiChips.selected() == 1 ? 3f : 4f);
    }

    private void exportPdf() {
        if (pdfUri == null) {
            Ui.toast(this, "请先选择 PDF 文件");
            return;
        }
        final int fmt = fmtChips.selected();
        final float scale = renderScale();
        final Set<Integer> want = parsePages(etPageRange.getText().toString());

        results.clear();
        pdfResultHost.removeAllViews();
        btnPdfSaveAll.setVisibility(View.GONE);
        pbPdf.setVisibility(View.VISIBLE);
        tvPdfStatus.setText("准备导出…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                ParcelFileDescriptor pfd = null;
                PdfRenderer renderer = null;
                int ok = 0;
                try {
                    pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
                    if (pfd == null) throw new Exception("无法打开 PDF");
                    renderer = new PdfRenderer(pfd);
                    int total = renderer.getPageCount();
                    final int pageTotal = total;
                    for (int i = 0; i < total; i++) {
                        final int pageNo = i + 1;
                        if (!want.isEmpty() && !want.contains(pageNo)) continue;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvPdfStatus.setText("正在渲染第 " + pageNo + "/" + pageTotal + " 页…");
                            }
                        });
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            int w = Math.max(1, (int) (page.getWidth() * scale));
                            int h = Math.max(1, (int) (page.getHeight() * scale));
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            String ext = FMT_LABELS[fmt].toLowerCase();
                            File out = new File(Ui.outDir(PdfActivity.this),
                                    uniqueName(Ui.baseName(pdfName) + "_第" + pageNo + "页." + ext));
                            FileOutputStream fos = new FileOutputStream(out);
                            try {
                                bmp.compress(fmt == 0 ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, fos);
                                fos.flush();
                            } finally {
                                fos.close();
                            }
                            bmp.recycle();
                            results.add(out);
                            ok++;
                            final File fout = out;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    addRow(fout, "PDF 第 " + pageNo + " 页");
                                }
                            });
                        } finally {
                            page.close();
                        }
                    }
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvPdfStatus.setText("导出失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        }
                    });
                } finally {
                    try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
                    try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
                }
                final int done = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pbPdf.setVisibility(View.GONE);
                        if (done > 0) {
                            tvPdfStatus.setText("完成：共导出 " + done + " 页图片");
                            btnPdfSaveAll.setVisibility(View.VISIBLE);
                        } else if (tvPdfStatus.getText().toString().startsWith("正在")) {
                            tvPdfStatus.setText("没有可导出的页面，请检查页码范围");
                        }
                    }
                });
            }
        });
    }

    /** PDF → PPTX：每页渲染成 JPEG，写成一个图片版 PPTX */
    private void pdfToPptx() {
        if (pdfUri == null) {
            Ui.toast(this, "请先选择 PDF 文件");
            return;
        }
        final float scale = renderScale();
        final Set<Integer> want = parsePages(etPageRange.getText().toString());

        results.clear();
        pdfResultHost.removeAllViews();
        btnPdfSaveAll.setVisibility(View.GONE);
        pbPdf.setVisibility(View.VISIBLE);
        tvPdfStatus.setText("准备转 PPTX…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                ParcelFileDescriptor pfd = null;
                PdfRenderer renderer = null;
                try {
                    pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
                    if (pfd == null) throw new Exception("无法打开 PDF");
                    renderer = new PdfRenderer(pfd);
                    int total = renderer.getPageCount();
                    if (total <= 0) throw new Exception("这个 PDF 没有页面");

                    List<byte[]> jpegs = new ArrayList<>();
                    int[] wpt = new int[total];
                    int[] hpt = new int[total];
                    int idx = 0;
                    for (int i = 0; i < total; i++) {
                        final int pageNo = i + 1;
                        wpt[i] = 0;
                        hpt[i] = 0;
                        if (!want.isEmpty() && !want.contains(pageNo)) continue;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvPdfStatus.setText("正在渲染第 " + pageNo + "/" + total + " 页…");
                            }
                        });
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            int w = Math.max(1, (int) (page.getWidth() * scale));
                            int h = Math.max(1, (int) (page.getHeight() * scale));
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo);
                            bmp.recycle();
                            jpegs.add(bo.toByteArray());
                            wpt[idx] = page.getWidth();
                            hpt[idx] = page.getHeight();
                            idx++;
                        } finally {
                            page.close();
                        }
                    }
                    if (jpegs.isEmpty()) throw new Exception("没有可转换的页面，请检查页码范围");

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvPdfStatus.setText("正在打包 PPTX（共 " + jpegs.size() + " 页）…");
                        }
                    });

                    File out = new File(Ui.outDir(PdfActivity.this),
                            uniqueName(Ui.baseName(pdfName) + ".pptx"));
                    PptxWriter.fromImages(out, jpegs, wpt, hpt, Ui.baseName(pdfName));
                    results.add(out);
                    final File fout = out;

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            btnPdfSaveAll.setVisibility(View.VISIBLE);
                            tvPdfStatus.setText("完成：" + fout.getName() + " · " + Ui.size(fout.length()));
                            addRow(fout, "图片版 PPTX · " + jpegs.size() + " 张幻灯片");
                        }
                    });
                } catch (final Throwable e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            tvPdfStatus.setText("转 PPTX 失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        }
                    });
                } finally {
                    try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
                    try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /** PPTX → PDF：有整页图片就还原图片，纯文字就重新排版 */
    private void pptxToPdf() {
        if (pptxUri == null) {
            Ui.toast(this, "请先选择 PPTX 文件");
            return;
        }
        results.clear();
        pdfResultHost.removeAllViews();
        btnPdfSaveAll.setVisibility(View.GONE);
        pbPdf.setVisibility(View.VISIBLE);
        tvPdfStatus.setText("正在读取 PPTX…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = Ui.readBytes(PdfActivity.this, pptxUri);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvPdfStatus.setText("正在解析幻灯片结构…");
                        }
                    });
                    PptxReader.Deck deck = PptxReader.read(data);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvPdfStatus.setText("正在排版成 PDF（共 " + deck.slides.size() + " 页幻灯片）…");
                        }
                    });
                    File out = new File(Ui.outDir(PdfActivity.this),
                            uniqueName(Ui.baseName(pptxName) + ".pdf"));
                    int pages = PdfExport.slidesToPdf(out, deck);
                    results.add(out);
                    final File fout = out;
                    final int pageCount = pages;
                    final int slideCount = deck.slides.size();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            btnPdfSaveAll.setVisibility(View.VISIBLE);
                            tvPdfStatus.setText("完成：" + fout.getName() + " · " + Ui.size(fout.length()));
                            addRow(fout, slideCount + " 页幻灯片 → " + pageCount + " 页 PDF");
                        }
                    });
                } catch (final Throwable e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            tvPdfStatus.setText("转 PDF 失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        }
                    });
                }
            }
        });
    }

    /** PDF → TXT / Markdown / HTML / DOCX：抽文字层，不还原版式 */
    private void pdfToText() {
        if (pdfUri == null) {
            Ui.toast(this, "请先选择 PDF 文件");
            return;
        }
        final String label = pdfTextChips.label();
        final String outExt;
        if ("Markdown".equals(label)) outExt = "md";
        else if ("HTML".equals(label)) outExt = "html";
        else if ("DOCX".equals(label)) outExt = "docx";
        else outExt = "txt";

        results.clear();
        pdfResultHost.removeAllViews();
        btnPdfSaveAll.setVisibility(View.GONE);
        pbPdf.setVisibility(View.VISIBLE);
        tvPdfStatus.setText("正在抽取文字…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = Ui.readBytes(PdfActivity.this, pdfUri);
                    final String text = com.fmtlab.toolbox.core.PdfText.extract(data);
                    File out = new File(Ui.outDir(PdfActivity.this),
                            uniqueName(Ui.baseName(pdfName) + "_文本." + outExt));
                    final String note = com.fmtlab.toolbox.core.DocMatrix.write(
                            text.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                            "txt", pdfName, label, out);
                    if (!out.exists() || out.length() == 0) throw new Exception("写出失败");
                    results.add(out);
                    final File fout = out;
                    final int chars = text.length();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            btnPdfSaveAll.setVisibility(View.VISIBLE);
                            tvPdfStatus.setText("完成：" + label + " · " + chars + " 字符 · " + Ui.size(fout.length())
                                    + "（" + note + "）");
                            addRow(fout, label + " · 抽取 " + chars + " 字符");
                        }
                    });
                } catch (final Throwable e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbPdf.setVisibility(View.GONE);
                            tvPdfStatus.setText("抽取失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        }
                    });
                }
            }
        });
    }

    private void imagesToPdf() {
        if (pickImages.isEmpty()) {
            Ui.toast(this, "请先选择图片");
            return;
        }
        final int paper = paperChips.selected();
        results.clear();
        pdfResultHost.removeAllViews();
        btnPdfSaveAll.setVisibility(View.GONE);
        pbPdf.setVisibility(View.VISIBLE);
        tvPdfStatus.setText("准备合成…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                PdfDocument doc = new PdfDocument();
                int ok = 0;
                try {
                    for (int i = 0; i < pickImages.size(); i++) {
                        final int idx = i + 1;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvPdfStatus.setText("正在处理第 " + idx + "/" + pickImages.size() + " 张…");
                            }
                        });
                        Bitmap bmp = decode(pickImages.get(i));
                        if (bmp == null) continue;
                        try {
                            int pw, ph;
                            if (paper == 0) {
                                pw = (int) (bmp.getWidth() * 0.75);
                                ph = (int) (bmp.getHeight() * 0.75);
                            } else if (paper == 1) {
                                pw = 595; ph = 842;
                            } else {
                                pw = 842; ph = 595;
                            }
                            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, idx).create();
                            PdfDocument.Page page = doc.startPage(info);
                            android.graphics.Canvas canvas = page.getCanvas();
                            canvas.drawColor(Color.WHITE);
                            Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
                            Rect dst;
                            if (paper == 0) {
                                dst = new Rect(0, 0, pw, ph);
                            } else {
                                int margin = 24;
                                int availW = pw - margin * 2;
                                int availH = ph - margin * 2;
                                double s = Math.min((double) availW / bmp.getWidth(), (double) availH / bmp.getHeight());
                                int dw = (int) (bmp.getWidth() * s);
                                int dh = (int) (bmp.getHeight() * s);
                                int left = (pw - dw) / 2;
                                int top = (ph - dh) / 2;
                                dst = new Rect(left, top, left + dw, top + dh);
                            }
                            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                            canvas.drawBitmap(bmp, src, dst, paint);
                            doc.finishPage(page);
                            ok++;
                        } finally {
                            bmp.recycle();
                        }
                    }
                    String name = uniqueName("合并文档_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                            java.util.Locale.US).format(new java.util.Date()) + ".pdf");
                    File out = new File(Ui.outDir(PdfActivity.this), name);
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        doc.writeTo(fos);
                        fos.flush();
                    } finally {
                        fos.close();
                    }
                    results.add(out);
                    final File fout = out;
                    final int pages = ok;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            addRow(fout, pages + " 页 · " + Ui.size(fout.length()));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvPdfStatus.setText("合成失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        }
                    });
                } finally {
                    doc.close();
                }
                final int done = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pbPdf.setVisibility(View.GONE);
                        if (done > 0) {
                            tvPdfStatus.setText("完成：已生成 " + done + " 页的 PDF");
                            btnPdfSaveAll.setVisibility(View.VISIBLE);
                        } else {
                            tvPdfStatus.setText("合成失败，请检查图片格式");
                        }
                    }
                });
            }
        });
    }

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) > 2400) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            InputStream in2 = getContentResolver().openInputStream(uri);
            try {
                return BitmapFactory.decodeStream(in2, null, o2);
            } finally {
                in2.close();
            }
        } catch (Exception e) {
            return null;
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

    private void addRow(final File f, String meta) {
        pdfResultHost.addView(Ui.resultRow(this, f, meta + " · " + Ui.size(f.length()),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<File> one = new ArrayList<>();
                        one.add(f);
                        Ui.shareFiles(PdfActivity.this, one, "PDF 工具输出");
                    }
                }));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
