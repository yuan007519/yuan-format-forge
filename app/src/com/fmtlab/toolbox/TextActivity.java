package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fmtlab.toolbox.core.CommonChars;
import com.fmtlab.toolbox.core.EncodingDetector;
import com.fmtlab.toolbox.core.MojibakeFixer;
import com.fmtlab.toolbox.core.TextCodec;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 文本乱码修复 + 编码转换 */
public class TextActivity extends Activity {

    private static final int REQ_PICK_TXT = 21;
    private static final int REQ_SAVE_TXT = 22;
    private static final String[] ENC_LABELS = {"UTF-8", "GBK", "GB18030", "Big5", "UTF-16LE", "ISO-8859-1"};
    private static final String[] ENC_VALUES = {
            TextCodec.UTF8, TextCodec.GBK, TextCodec.GB18030, TextCodec.BIG5, TextCodec.UTF16LE, TextCodec.LATIN1
    };

    private EditText etInput;
    private TextView tvDetect, tvMeta, tvResult, tvWarning, tvOptionTitle;
    private LinearLayout optionHost;
    private CheckBox cbPerLine;
    private Button btnFix, btnCopy, btnSaveText, btnShareText;
    private Ui.Chips encChips;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;
    private String resultText = "";
    private String openFileInfo = null;
    private byte[] pendingBytes;
    private String pendingName;

    private final Runnable detectRunner = new Runnable() {
        @Override
        public void run() {
            detectNow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text);
        exec = Executors.newSingleThreadExecutor();

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((TextView) findViewById(R.id.tvTitle)).setText("文本乱码修复");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("自动识别编码 · 一键还原被读错的文字");

        etInput = (EditText) findViewById(R.id.etInput);
        tvDetect = (TextView) findViewById(R.id.tvDetect);
        tvMeta = (TextView) findViewById(R.id.tvMeta);
        tvResult = (TextView) findViewById(R.id.tvResult);
        tvWarning = (TextView) findViewById(R.id.tvWarning);
        tvOptionTitle = (TextView) findViewById(R.id.tvOptionTitle);
        optionHost = (LinearLayout) findViewById(R.id.optionHost);
        cbPerLine = (CheckBox) findViewById(R.id.cbPerLine);
        btnFix = (Button) findViewById(R.id.btnFix);
        btnCopy = (Button) findViewById(R.id.btnCopy);
        btnSaveText = (Button) findViewById(R.id.btnSaveText);
        btnShareText = (Button) findViewById(R.id.btnShareText);

        encChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.encChips), ENC_LABELS, 0);

        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(detectRunner);
                handler.postDelayed(detectRunner, 320);
            }
        });

        findViewById(R.id.btnPaste).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = Ui.paste(TextActivity.this);
                if (t == null || t.isEmpty()) {
                    Ui.toast(TextActivity.this, "剪贴板是空的");
                    return;
                }
                openFileInfo = null;
                etInput.setText(t);
            }
        });

        findViewById(R.id.btnOpenTxt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTextFile();
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileInfo = null;
                etInput.setText("");
                resultText = "";
                tvResult.setText("（修复后的文本会显示在这里）");
                tvMeta.setText("修复前 — → 修复后 —");
                tvWarning.setVisibility(View.GONE);
                optionHost.removeAllViews();
                tvOptionTitle.setVisibility(View.GONE);
            }
        });

        btnFix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doFix();
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.copy(TextActivity.this, resultText);
            }
        });

        btnSaveText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult();
            }
        });

        btnShareText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareResult();
            }
        });
    }

    // ------------------------------------------------------------ 识别

    private void detectNow() {
        final String text = etInput.getText().toString();
        if (text.trim().isEmpty()) {
            tvDetect.setText("识别结果：等待输入…");
            return;
        }
        if (openFileInfo != null) {
            tvDetect.setText(openFileInfo);
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final double s = CommonChars.sanity(text);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (etInput.getText().toString().equals(text) && openFileInfo == null) {
                            String verdict = s >= 0.85 ? "正常" : (s >= 0.62 ? "可能含少量乱码" : "疑似乱码");
                            tvDetect.setText("文本状态：" + verdict + "（正常度 " + (int) Math.round(s * 100)
                                    + "%）" + (s < 0.72 ? "，建议点下面的按钮修复" : ""));
                        }
                    }
                });
            }
        });
    }

    private void pickTextFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        String[] mimes = {"text/*", "application/json", "application/xml", "application/octet-stream"};
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        try {
            startActivityForResult(i, REQ_PICK_TXT);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件选择器");
        }
    }

    private void loadTextFile(final Uri uri) {
        final String name = Ui.displayName(this, uri);
        if (!Ui.isTextFile(name)) {
            Ui.toast(this, "这不是文本文件：" + name);
            return;
        }
        tvDetect.setText("正在读取 " + name + " …");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] bytes = Ui.readBytes(TextActivity.this, uri);
                    if (bytes.length > 40 * 1024 * 1024) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(TextActivity.this, "文件超过 40MB，暂不支持");
                            }
                        });
                        return;
                    }
                    final EncodingDetector.Result r = EncodingDetector.detect(bytes);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            openFileInfo = "文件编码：" + EncodingDetector.describe(r)
                                    + "｜原始大小 " + Ui.size(bytes.length);
                            tvDetect.setText(openFileInfo);
                            etInput.setText(r.text);
                            resultText = "";
                            tvResult.setText("（修复后的文本会显示在这里）");
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(TextActivity.this, "读取失败：" + e.getMessage());
                            tvDetect.setText("识别结果：读取失败");
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------ 修复

    private void doFix() {
        final String src = etInput.getText().toString();
        if (src.trim().isEmpty()) {
            Ui.toast(this, "请先粘贴文本或打开文本文件");
            return;
        }
        final boolean perLine = cbPerLine.isChecked();
        btnFix.setEnabled(false);
        btnFix.setText("正在分析…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final MojibakeFixer.Result r = MojibakeFixer.fix(src);
                    String target;
                    if (perLine && src.length() <= 80000) {
                        target = MojibakeFixer.fixMixed(src);
                    } else {
                        target = r.text;
                    }
                    final String finalTarget = target;
                    final double before = CommonChars.sanity(src);
                    final double after = CommonChars.sanity(target);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyResult(finalTarget, r, before, after);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(TextActivity.this, "修复失败：" + e.getMessage());
                        }
                    });
                } finally {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            btnFix.setEnabled(true);
                            btnFix.setText("一键修复乱码");
                        }
                    });
                }
            }
        });
    }

    private void applyResult(String target, MojibakeFixer.Result r, double before, double after) {
        resultText = target;
        tvResult.setText(target.isEmpty() ? "（空）" : target);
        String path = r.applied;
        if (path == null || path.equals("无需修复")) path = "未发现需要还原的编码错位";
        tvMeta.setText("修复前 " + (int) Math.round(before * 100) + "% → 修复后 "
                + (int) Math.round(after * 100) + "%｜" + path);

        String warn = r.warning;
        if (after < 0.55) {
            warn = "结果仍不像正常文本，可能是数据已损坏，或需要选择下面其它候选结果。";
        }
        if (warn == null) {
            tvWarning.setVisibility(View.GONE);
        } else {
            tvWarning.setText("⚠ " + warn);
            tvWarning.setVisibility(View.VISIBLE);
        }
        renderOptions(r.options, target);
    }

    @SuppressWarnings("unchecked")
    private void renderOptions(List<MojibakeFixer.Option> options, String current) {
        optionHost.removeAllViews();
        if (options == null) {
            tvOptionTitle.setVisibility(View.GONE);
            return;
        }
        int shown = 0;
        for (final MojibakeFixer.Option o : options) {
            if (o.text.equals(current)) continue;
            if (o.score < 0.5) continue;
            if (shown >= 4) break;
            TextView tv = new TextView(this);
            tv.setText(o.label + " · 正常度 " + (int) Math.round(o.score * 100) + "%\n" + preview(o.text));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tv.setTextColor(getColor(R.color.text_secondary));
            tv.setLineSpacing(Ui.dp(this, 2f), 1f);
            tv.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 12), Ui.dpi(this, 10));
            tv.setBackgroundResource(R.drawable.bg_result);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dpi(this, 8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resultText = o.text;
                    tvResult.setText(o.text);
                    tvMeta.setText("已手动采用：" + o.label + "｜正常度 "
                            + (int) Math.round(o.score * 100) + "%");
                    Ui.toast(TextActivity.this, "已切换到该结果");
                }
            });
            optionHost.addView(tv);
            shown++;
        }
        tvOptionTitle.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private String preview(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (one.length() > 60) one = one.substring(0, 60) + "…";
        return one;
    }

    // ------------------------------------------------------------ 保存 / 分享

    private void saveResult() {
        if (resultText.isEmpty()) {
            Ui.toast(this, "还没有修复结果");
            return;
        }
        String encName = ENC_VALUES[encChips.selected()];
        byte[] data = TextCodec.encodeStrict(resultText, encName);
        if (data == null) {
            Ui.toast(this, "该编码无法表示部分字符，建议改成 UTF-8");
            return;
        }
        byte[] withBom = data;
        if (TextCodec.UTF16LE.equals(encName) || TextCodec.UTF16BE.equals(encName)) {
            withBom = new byte[data.length + 2];
            if (TextCodec.UTF16LE.equals(encName)) {
                withBom[0] = (byte) 0xFF;
                withBom[1] = (byte) 0xFE;
            } else {
                withBom[0] = (byte) 0xFE;
                withBom[1] = (byte) 0xFF;
            }
            System.arraycopy(data, 0, withBom, 2, data.length);
        }
        pendingBytes = withBom;
        pendingName = "修复结果_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "_" + ENC_LABELS[encChips.selected()].replace("-", "") + ".txt";
        try {
            startActivityForResult(Ui.createDocIntent(pendingName, "text/plain"), REQ_SAVE_TXT);
        } catch (Exception e) {
            Ui.toast(this, "无法打开保存对话框");
        }
    }

    private void shareResult() {
        if (resultText.isEmpty()) {
            Ui.toast(this, "还没有修复结果");
            return;
        }
        String encName = ENC_VALUES[encChips.selected()];
        byte[] data = TextCodec.encodeStrict(resultText, encName);
        if (data == null) data = TextCodec.encodeStrict(resultText, TextCodec.UTF8);
        if (data == null) {
            Ui.toast(this, "内容无法编码为文件");
            return;
        }
        try {
            File f = Ui.writeTemp(this, "修复结果.txt", data);
            List<File> list = new ArrayList<>();
            list.add(f);
            Ui.shareFiles(this, list, "文本乱码修复结果");
        } catch (Exception e) {
            Ui.toast(this, "生成文件失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_TXT && uri != null) {
            loadTextFile(uri);
        } else if (requestCode == REQ_SAVE_TXT && uri != null && pendingBytes != null) {
            try {
                Ui.writeToUri(this, uri, pendingBytes);
                Ui.toast(this, "已保存：" + pendingName);
            } catch (Exception e) {
                Ui.toast(this, "保存失败：" + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
