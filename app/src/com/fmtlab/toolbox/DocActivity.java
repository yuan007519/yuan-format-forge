package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fmtlab.toolbox.core.DocConverter;
import com.fmtlab.toolbox.core.EncodingDetector;
import com.fmtlab.toolbox.core.TextCodec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Office / 网页 / Markdown 文档互转 */
public class DocActivity extends Activity {

    private static final int REQ_PICK = 51;
    private static final int REQ_SAVE = 52;

    private TextView tvDocInfo, tvOutHint, tvDocStatus, tvPreview;
    private ScrollView svPreview;
    private LinearLayout outChips, docActionRow;
    private ProgressBar pbDoc;
    private Button btnDocConvert;

    private Uri docUri;
    private String docName = "";
    private String[] outLabels = new String[0];
    private Ui.Chips chips;
    private String resultText = "";
    private String resultExt = "txt";
    private String resultName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("文档转换");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("DOCX / XLSX / PPTX / HTML / MD / TXT");
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
                Ui.copy(DocActivity.this, resultText);
            }
        });

        findViewById(R.id.btnDocSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultText.isEmpty()) {
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
            tvDocInfo.setText("已选择：" + docName + "（." + ext + "）");
            outLabels = outputsFor(ext);
            if (outLabels.length == 0) {
                tvOutHint.setText("暂不支持该格式：." + ext + "。支持 docx / xlsx / pptx / html / md / txt / csv。");
                outChips.removeAllViews();
                chips = null;
            } else {
                chips = new Ui.Chips(this, outChips, outLabels, 0);
                tvOutHint.setText("可转换为：" + TextUtils.join(" / ", outLabels));
            }
        } else if (requestCode == REQ_SAVE) {
            try {
                Ui.writeToUri(this, data.getData(),
                        TextCodec.encodeStrict(resultText, TextCodec.UTF8));
                Ui.toast(this, "已保存：" + resultName);
            } catch (Exception e) {
                Ui.toast(this, "保存失败：" + e.getMessage());
            }
        }
    }

    private String[] outputsFor(String ext) {
        if ("docx".equals(ext)) return new String[]{"Markdown", "HTML", "纯文本"};
        if ("xlsx".equals(ext)) return new String[]{"CSV"};
        if ("pptx".equals(ext)) return new String[]{"纯文本"};
        if ("html".equals(ext) || "htm".equals(ext)) return new String[]{"纯文本"};
        if ("md".equals(ext)) return new String[]{"HTML"};
        if ("txt".equals(ext)) return new String[]{"HTML"};
        if ("csv".equals(ext)) return new String[]{"HTML 表格"};
        return new String[0];
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
        tvDocStatus.setText("正在转换…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] bytes = Ui.readBytes(DocActivity.this, docUri);
                    String text;
                    String outExt;
                    if ("docx".equals(ext)) {
                        if ("Markdown".equals(out)) {
                            text = DocConverter.docxToMarkdown(bytes);
                            outExt = "md";
                        } else if ("HTML".equals(out)) {
                            text = DocConverter.docxToHtml(bytes);
                            outExt = "html";
                        } else {
                            text = DocConverter.docxToText(bytes);
                            outExt = "txt";
                        }
                    } else if ("xlsx".equals(ext)) {
                        text = DocConverter.xlsxToCsv(bytes);
                        outExt = "csv";
                    } else if ("pptx".equals(ext)) {
                        text = DocConverter.pptxToText(bytes);
                        outExt = "txt";
                    } else if ("html".equals(ext) || "htm".equals(ext)) {
                        EncodingDetector.Result r = EncodingDetector.detect(bytes);
                        text = DocConverter.htmlToText(r.text);
                        outExt = "txt";
                    } else if ("md".equals(ext)) {
                        EncodingDetector.Result r = EncodingDetector.detect(bytes);
                        text = DocConverter.markdownToHtml(r.text, Ui.baseName(docName));
                        outExt = "html";
                    } else if ("txt".equals(ext)) {
                        EncodingDetector.Result r = EncodingDetector.detect(bytes);
                        text = DocConverter.textToHtml(r.text, Ui.baseName(docName));
                        outExt = "html";
                    } else if ("csv".equals(ext)) {
                        EncodingDetector.Result r = EncodingDetector.detect(bytes);
                        text = csvToHtmlTable(r.text, Ui.baseName(docName));
                        outExt = "html";
                    } else {
                        throw new Exception("暂不支持该格式");
                    }
                    final String ft = text;
                    final String fe = outExt;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            resultText = ft;
                            resultExt = fe;
                            resultName = Ui.baseName(docName) + "_转换." + fe;
                            pbDoc.setVisibility(View.GONE);
                            btnDocConvert.setEnabled(true);
                            svPreview.setVisibility(View.VISIBLE);
                            docActionRow.setVisibility(View.VISIBLE);
                            String shown = ft.length() > 3500 ? ft.substring(0, 3500) + "\n\n…（预览截断，完整内容请另存或分享）" : ft;
                            tvPreview.setText(shown.isEmpty() ? "（空）" : shown);
                            tvDocStatus.setText("转换完成：" + preLabel(out) + " · " + ft.length() + " 字符");
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

    private String preLabel(String out) {
        return out == null ? "" : out;
    }

    private String csvToHtmlTable(String csv, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<title>").append(title).append("</title><style>")
                .append("body{font-family:-apple-system,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;padding:20px;background:#fff;color:#1f2937;}")
                .append("table{border-collapse:collapse;width:100%;font-size:14px;}")
                .append("td,th{border:1px solid #e2e8f0;padding:8px 10px;text-align:left;}")
                .append("tr:nth-child(even){background:#f8fafc;}")
                .append("</style></head><body><table>");
        String[] lines = csv.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            sb.append("<tr>");
            for (String cell : splitCsvLine(lines[i])) {
                sb.append("<td>").append(escapeHtml(cell)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    q = !q;
                }
            } else if (c == ',' && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
