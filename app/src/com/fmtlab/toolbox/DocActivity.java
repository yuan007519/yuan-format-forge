package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** 文档格式矩阵：Word / Excel / PPT / ODF / EPUB / 网页 / Markdown / CSV / JSON / 纯文本互转 */
public class DocActivity extends Activity {

    private static final int REQ_PICK = 51;
    private static final int REQ_SAVE = 52;
    /** 本页面任务在进程级状态仓库里的 key */
    private static final String KEY = "doc";

    private TextView tvDocInfo, tvOutHint, tvDocStatus, tvPreview;
    private ScrollView svPreview;
    private LinearLayout outChips, docActionRow, docResultHost;
    private LinearLayout body, cardPick, cardFormat, cardRun, cardResult;
    private ProgressBar pbDoc;
    private Button btnDocConvert;
    private QueueUi queue;

    private Ui.Chips chips;
    private String[] outLabels = new String[0];

    /** 最后一个成功结果：复制 / 另存 / 分享 / 预览沿用原来的语义 */
    private String resultText = "";
    private File resultFile;
    private String resultName = "";
    private String previewText = "";

    private ExecutorService exec;
    /** 已经写过历史记录的输出文件，旋转恢复时不再重复写 */
    private final Set<File> loggedHistory = new HashSet<File>();

    /** 任务状态变化时回到主线程重画界面 */
    private final TaskHold.Listener taskListener = new TaskHold.Listener() {
        @Override
        public void onTaskChanged(String key) {
            syncUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc);
        // 共享进程级单线程池，不随界面销毁而关闭
        exec = TaskHold.exec();

        ((TextView) findViewById(R.id.tvTitle)).setText("文档转换");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("Word、Excel、PPT、PDF、EPUB、MD 互转");
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
        body = (LinearLayout) findViewById(R.id.docBody);
        cardPick = (LinearLayout) findViewById(R.id.cardPickDoc);
        cardFormat = (LinearLayout) findViewById(R.id.cardDocFormat);
        cardRun = (LinearLayout) findViewById(R.id.cardDocRun);
        cardResult = (LinearLayout) findViewById(R.id.cardDocResult);

        // 队列面板挂到结果卡最上面；双栏时再整体搬到右栏
        queue = new QueueUi(this, cardResult);

        findViewById(R.id.btnPickDoc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 多选：一次可选多个文档，同一输出格式下按队列顺序逐个转换
                Picker.launch(DocActivity.this, REQ_PICK, true);
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

        // 旋转 / 回收后重进：先取回进程级任务状态，已存在的结果不再重复写历史
        TaskHold.State saved = TaskHold.peek(KEY);
        if (saved != null) loggedHistory.addAll(saved.results);
        restoreChips(saved);
        syncUi();

        // 平板双栏：手机（最小宽度 < 600dp）twoPane 直接返回 false，单列一个 view 都不动
        View[] leftViews = {cardPick, cardFormat, cardRun};
        View[] rightViews = {queue.view(), cardResult};
        Ui.twoPane(this, body, leftViews, rightViews);
        Ui.centerTabletContent(this);

        TaskHold.attach(KEY, taskListener);
    }

    /** 从进程级状态恢复输出格式标签；状态为空时保持空标签 */
    private void restoreChips(TaskHold.State s) {
        outChips.removeAllViews();
        if (s == null || s.jobs.isEmpty()) {
            chips = null;
            outLabels = new String[0];
            return;
        }
        String firstName = s.jobs.get(0).name;
        outLabels = labelsForName(firstName);
        if (outLabels.length == 0) {
            chips = null;
            return;
        }
        int sel = 0;
        for (int i = 0; i < outLabels.length; i++) {
            if (outLabels[i].equals(s.mode)) sel = i;
        }
        chips = new Ui.Chips(this, outChips, outLabels, sel);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK) {
            // 优先读 ClipData 拿全部选中的 uri（按选择顺序），没有再退回单文件 getData()
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;

            List<String> names = new ArrayList<>();
            for (Uri u : uris) names.add(Ui.displayName(this, u));

            // 开一批新任务：干净的状态对象
            TaskHold.State s = TaskHold.begin(KEY, "");
            for (int i = 0; i < uris.size(); i++) {
                s.jobs.add(new TaskHold.Job(names.get(i), uris.get(i)));
            }

            StringBuilder sb = new StringBuilder("已选 ").append(uris.size()).append(" 个文档：");
            for (int i = 0; i < Math.min(3, names.size()); i++) {
                if (i > 0) sb.append("、");
                sb.append(names.get(i));
            }
            if (names.size() > 3) sb.append(" 等");
            s.info = sb.toString();
            s.status = "等待开始转换";

            // 本地结果与历史标记清空
            resultText = "";
            resultFile = null;
            resultName = "";
            previewText = "";
            loggedHistory.clear();

            // 输出格式按第一个文件的类型推断；不支持的文件在队列里会逐条标失败
            String ext0 = Ui.ext(names.get(0));
            outLabels = labelsForName(names.get(0));
            if (DocMatrix.isLegacyBinary(ext0)) {
                chips = null;
                tvOutHint.setText("." + ext0 + " 这种老格式解不开，先在原软件里另存成 docx / xlsx / pptx。");
            } else if (outLabels.length == 0) {
                chips = null;
                tvOutHint.setText("暂不支持 ." + ext0 + "，能读 docx、xlsx、pptx、epub、md、csv。");
            } else {
                chips = new Ui.Chips(this, outChips, outLabels, 0);
                tvOutHint.setText(DocMatrix.describe(ext0) + "\n可转换为：" + TextUtils.join(" / ", outLabels));
            }
            syncUi();
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

    private String[] labelsForName(String name) {
        String ext = Ui.ext(name);
        if (DocMatrix.isLegacyBinary(ext)) return new String[0];
        return outputsFor(ext);
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

    /** 按扩展名判断是否二进制输出（恢复界面时没有标签可用） */
    private static boolean isBinaryExt(String ext) {
        return "docx".equals(ext) || "pdf".equals(ext) || "epub".equals(ext) || "odt".equals(ext)
                || "xlsx".equals(ext) || "ods".equals(ext) || "png".equals(ext)
                || "jpg".equals(ext) || "jpeg".equals(ext);
    }

    private void convert() {
        TaskHold.State s = TaskHold.peek(KEY);
        if (s == null || s.jobs.isEmpty()) {
            Ui.toast(this, "请先选择文档");
            return;
        }
        if (chips == null) {
            Ui.toast(this, "暂不支持该格式");
            return;
        }
        final String out = chips.label();

        // 重置这批任务的状态，全部写进进程级仓库
        s.mode = out;
        s.cursor = -1;
        s.busy = true;
        s.ok = 0;
        s.fail = 0;
        s.finished = false;
        s.results.clear();
        for (TaskHold.Job j : s.jobs) {
            j.state = TaskHold.WAIT;
            j.note = "";
            j.outputs.clear();
        }
        resultText = "";
        resultFile = null;
        resultName = "";
        previewText = "";
        loggedHistory.clear();
        syncUi();

        // 串行跑在共享单线程池里，不占 UI 线程；单个文件失败不影响后面的文件
        exec.execute(new Runnable() {
            @Override
            public void run() {
                TaskHold.State st = TaskHold.of(KEY);
                for (int i = 0; i < st.jobs.size(); i++) {
                    final TaskHold.Job job = st.jobs.get(i);
                    st.cursor = i;
                    job.state = TaskHold.RUN;
                    st.status = "正在第 " + (i + 1) + "/" + st.jobs.size() + " 个：" + job.name;
                    TaskHold.notifyChanged(KEY);
                    try {
                        convertOne(job, st, out);
                        job.state = TaskHold.OK;
                        st.ok++;
                    } catch (Throwable t) {
                        job.state = TaskHold.FAIL;
                        st.fail++;
                        job.note = reasonOf(t);
                    }
                    TaskHold.notifyChanged(KEY);
                }
                st.busy = false;
                st.finished = true;
                st.status = "共 " + st.jobs.size() + " 个：成功 " + st.ok + "、失败 " + st.fail;
                TaskHold.notifyChanged(KEY);
            }
        });
    }

    /** 转换队列里的一个文件；抛异常即记失败，由外层循环继续下一个 */
    private void convertOne(TaskHold.Job job, TaskHold.State s, String out) throws Exception {
        String ext = Ui.ext(job.name);
        if (isSlideImage(out)) {
            exportSlides(job, s, out);
            return;
        }
        final byte[] bytes = Ui.readBytes(this, job.uri);
        final String outExt = extOf(out);
        final File outFile = new File(Ui.outDir(this),
                uniqueName(Ui.baseName(job.name) + "_转换." + outExt));
        final String note = DocMatrix.write(bytes, ext, job.name, out, outFile);
        if (!outFile.exists() || outFile.length() == 0) throw new Exception("写出失败");
        final boolean binary = isBinaryOut(out);
        final String textContent = binary ? "" : readText(outFile);
        job.outputs.add(outFile);
        s.results.add(outFile);
        job.note = out + " · " + Ui.size(outFile.length());

        // 记录最后一个成功结果，供预览、复制、另存、分享使用
        resultFile = binary ? outFile : null;
        resultText = textContent;
        resultName = outFile.getName();
        previewText = binary
                ? "已生成 " + out + " 文件：" + outFile.getName() + "\n" + Ui.size(outFile.length())
                        + "\n\n" + note + "\n\n（这是二进制文件，预览不可读，点「另存为」或「分享」导出）"
                : textContent;
    }

    /** PPTX 逐页导图：先用现有排版引擎出 A4 PDF，再逐页渲染成 PNG / JPG；一项产出多张图，算成功一次 */
    private void exportSlides(TaskHold.Job job, TaskHold.State s, String label) throws Exception {
        final String outExt = extOf(label);
        final Bitmap.CompressFormat cf = "png".equals(outExt)
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        byte[] bytes = Ui.readBytes(this, job.uri);
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
                // 进度写进状态，由主线程监听刷新；不逐页 post 到界面
                s.status = "正在导出第 " + (i + 1) + "/" + total + " 张…";
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    int w = Math.max(1, (int) (page.getWidth() * 2));
                    int h = Math.max(1, (int) (page.getHeight() * 2));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File out = new File(Ui.outDir(this), uniqueName(
                            Ui.baseName(job.name) + "_第" + (i + 1) + "页." + outExt));
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    try {
                        bmp.compress(cf, 92, fos);
                        fos.flush();
                    } finally {
                        fos.close();
                    }
                    bmp.recycle();
                    ok++;
                    job.outputs.add(out);
                    s.results.add(out);
                } finally {
                    page.close();
                }
            }
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            if (tmpPdf.exists()) tmpPdf.delete();
        }
        if (ok == 0) throw new Exception("没有导出任何图片");
        job.note = label + " · 导出 " + ok + " 张图片";
        // 导图项有多个输出文件，没有单一可复制的结果；预览给一句说明，操作行沿用之前的普通结果
        File last = job.outputs.get(job.outputs.size() - 1);
        previewText = "已导出 " + ok + " 张 " + label + " 图片：\n" + last.getName()
                + " 等，全部保存在应用目录，可逐条分享或另存。";
        // 之前没有普通结果时，操作行先挂到最后一张图上，便于分享 / 另存
        if (resultName.isEmpty()) {
            resultFile = last;
            resultText = "";
            resultName = last.getName();
        }
    }

    /** 失败原因：异常 message 截断到 40 字，拿不到就给固定提示 */
    private static String reasonOf(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) return "这个文件解析失败";
        m = m.trim();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    /** 按当前状态重画整个界面：队列、状态栏、结果行、预览、操作行、按钮与进度条 */
    private void syncUi() {
        TaskHold.State s = TaskHold.peek(KEY);
        queue.render(s);

        boolean busy = s != null && s.busy;
        pbDoc.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnDocConvert.setEnabled(!busy);

        tvDocStatus.setText(s == null || TextUtils.isEmpty(s.status)
                ? "转换结果会显示在这里" : s.status);
        if (s != null && !TextUtils.isEmpty(s.info)) tvDocInfo.setText(s.info);

        renderResultHost(s);
        syncPreview(s);

        // 结果卡：正在跑、队列非空、或已有结果时才显示；否则整卡隐藏，不留空卡
        boolean queueVisible = s != null && !s.jobs.isEmpty();
        boolean hasResult = !resultName.isEmpty() || (s != null && !s.results.isEmpty());
        cardResult.setVisibility((busy || queueVisible || hasResult) ? View.VISIBLE : View.GONE);
    }

    /** 重画结果行；已写过历史的文件用静默行，避免旋转恢复时重复记历史 */
    private void renderResultHost(TaskHold.State s) {
        docResultHost.removeAllViews();
        if (s == null || s.results.isEmpty()) {
            docResultHost.setVisibility(View.GONE);
            return;
        }
        docResultHost.setVisibility(View.VISIBLE);
        for (final File f : s.results) {
            String meta = Ui.size(f.length());
            View.OnClickListener share = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<File> one = new ArrayList<>();
                    one.add(f);
                    Ui.shareFiles(DocActivity.this, one, "文档转换结果");
                }
            };
            if (loggedHistory.contains(f)) {
                docResultHost.addView(Ui.resultRowQuiet(this, f, meta, share));
            } else {
                docResultHost.addView(Ui.resultRow(this, f, meta, share));
                loggedHistory.add(f);
            }
        }
    }

    /** 预览与操作行：指向最后一个成功结果；旋转恢复时从结果文件还原 */
    private void syncPreview(TaskHold.State s) {
        if (resultName == null) resultName = "";
        if (resultName.isEmpty()) restoreLastResult(s);

        if (resultName.isEmpty()) {
            svPreview.setVisibility(View.GONE);
            docActionRow.setVisibility(View.GONE);
            return;
        }
        docActionRow.setVisibility(View.VISIBLE);
        if (previewText.isEmpty()) {
            svPreview.setVisibility(View.GONE);
            return;
        }
        svPreview.setVisibility(View.VISIBLE);
        String shown = previewText.length() > 3500
                ? previewText.substring(0, 3500) + "\n\n…（预览截断，完整内容请另存或分享）"
                : previewText;
        tvPreview.setText(shown.isEmpty() ? "（空）" : shown);
    }

    /** 恢复最后一个结果文件：二进制给说明，文本重新读出来预览 */
    private void restoreLastResult(TaskHold.State s) {
        if (s == null || s.results.isEmpty()) return;
        File last = s.results.get(s.results.size() - 1);
        resultName = last.getName();
        if (isBinaryExt(Ui.ext(resultName))) {
            resultFile = last;
            resultText = "";
            previewText = "已生成 " + resultName + "\n" + Ui.size(last.length())
                    + "\n\n（这是二进制文件，预览不可读，点「另存为」或「分享」导出）";
        } else {
            resultFile = null;
            resultText = readText(last);
            previewText = resultText;
        }
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
        // 共享线程池不 shutdown，任务状态留在 TaskHold 里供旋转后恢复
        TaskHold.detach(KEY);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 1.6.1：旋转不再重建页面，这里只把平板居中列的宽度按新屏幕重算一次
        Ui.refreshTabletLayout(this);
    }
}
