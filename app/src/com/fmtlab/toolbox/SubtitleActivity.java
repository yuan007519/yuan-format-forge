package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fmtlab.toolbox.core.EncodingDetector;
import com.fmtlab.toolbox.core.Subtitle;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** 字幕转换：srt / vtt / ass / ssa 互转，另可导出纯文本 */
public class SubtitleActivity extends Activity {

    private static final int REQ_PICK = 71;
    private static final String[] OUT_LABELS = {"SRT", "VTT", "ASS", "SSA", "TXT"};
    private static final String[] OUT_EXT = {"srt", "vtt", "ass", "ssa", "txt"};

    private final Handler handler = new Handler();
    private final java.util.concurrent.ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<File> results = new ArrayList<File>();

    private TextView tvSubInfo, tvSubStatus, tvSubPreview;
    private ProgressBar pbSub;
    private LinearLayout subResultHost;
    private Ui.Chips outChips;

    private Uri srcUri;
    private String srcName = "";
    private String srcKind = "";
    private List<Subtitle.Cue> cues = new ArrayList<Subtitle.Cue>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subtitle);
        Ui.centerTabletContent(this);

        ((TextView) findViewById(R.id.tvTitle)).setText("字幕转换");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("SRT · VTT · ASS · SSA · TXT");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvSubInfo = (TextView) findViewById(R.id.tvSubInfo);
        tvSubStatus = (TextView) findViewById(R.id.tvSubStatus);
        tvSubPreview = (TextView) findViewById(R.id.tvSubPreview);
        pbSub = (ProgressBar) findViewById(R.id.pbSub);
        subResultHost = (LinearLayout) findViewById(R.id.subResultHost);
        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.subOutChips), OUT_LABELS, 0);

        findViewById(R.id.btnPickSub).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(SubtitleActivity.this, REQ_PICK, false);
            }
        });
        findViewById(R.id.btnSubConvert).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || Picker.firstUri(data) == null) return;
        srcUri = Picker.firstUri(data);
        srcName = Ui.displayName(this, srcUri);
        String ext = Ui.ext(srcName);
        srcKind = Subtitle.kind(ext);
        if (srcKind.isEmpty()) srcKind = "srt";
        tvSubInfo.setText("已选择：" + srcName + "（按 " + srcKind.toUpperCase() + " 解析）");
        load();
    }

    private void load() {
        pbSub.setVisibility(View.VISIBLE);
        tvSubStatus.setText("正在读取字幕…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] bytes = Ui.readBytes(SubtitleActivity.this, srcUri);
                    String text = EncodingDetector.detect(bytes).text;
                    final List<Subtitle.Cue> parsed = Subtitle.parse(text, srcKind);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbSub.setVisibility(View.GONE);
                            cues = parsed;
                            if (parsed.isEmpty()) {
                                tvSubStatus.setText("没有解析出任何字幕条目，请确认文件格式");
                                tvSubPreview.setText("（未解析到内容）");
                                return;
                            }
                            long total = parsed.get(parsed.size() - 1).endMs;
                            tvSubStatus.setText("已解析 " + parsed.size() + " 条字幕，总时长 "
                                    + fmtTime(total) + "，可导出为 " + OUT_LABELS.length + " 种格式");
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < Math.min(parsed.size(), 12); i++) {
                                Subtitle.Cue c = parsed.get(i);
                                sb.append(fmtTime(c.startMs)).append(" → ").append(fmtTime(c.endMs))
                                        .append("\n").append(c.text).append("\n\n");
                            }
                            if (parsed.size() > 12) sb.append("… 还有 ").append(parsed.size() - 12).append(" 条");
                            tvSubPreview.setText(sb.toString());
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbSub.setVisibility(View.GONE);
                            tvSubStatus.setText("读取失败：" + msg(e));
                        }
                    });
                }
            }
        });
    }

    private void convert() {
        if (cues == null || cues.isEmpty()) {
            Ui.toast(this, "请先选择一个能解析的字幕文件");
            return;
        }
        final int idx = outChips.selected();
        final String ext = OUT_EXT[idx];
        pbSub.setVisibility(View.VISIBLE);
        tvSubStatus.setText("正在生成 " + OUT_LABELS[idx] + " …");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String content;
                    switch (ext) {
                        case "vtt": content = Subtitle.toVtt(cues); break;
                        case "ass": content = Subtitle.toAss(cues, false); break;
                        case "ssa": content = Subtitle.toAss(cues, true); break;
                        case "txt": content = Subtitle.toPlainText(cues); break;
                        default: content = Subtitle.toSrt(cues);
                    }
                    final File out = uniqueOut(Ui.baseName(srcName) + "_字幕." + ext);
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        fos.write(content.getBytes("UTF-8"));
                    } finally {
                        fos.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbSub.setVisibility(View.GONE);
                            tvSubStatus.setText("已生成 " + out.getName() + "（" + cues.size() + " 条，"
                                    + Ui.size(out.length()) + "）");
                            addResult(out, cues.size() + " 条 · " + ext.toUpperCase());
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbSub.setVisibility(View.GONE);
                            tvSubStatus.setText("生成失败：" + msg(e));
                        }
                    });
                }
            }
        });
    }

    private void addResult(final File f, String meta) {
        results.add(f);
        subResultHost.addView(Ui.resultRow(this, f, meta, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<File> one = new ArrayList<File>();
                one.add(f);
                Ui.shareFiles(SubtitleActivity.this, one, "字幕文件");
            }
        }));
    }

    private File uniqueOut(String name) {
        File dir = Ui.outDir(this);
        File f = new File(dir, name);
        int n = 2;
        while (f.exists()) {
            f = new File(dir, Ui.baseName(name) + "_" + n + "." + Ui.ext(name));
            n++;
        }
        return f;
    }

    private static String msg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String fmtTime(long ms) {
        long h = ms / 3600000, m = ms % 3600000 / 60000, s = ms % 60000 / 1000;
        return (h > 0 ? h + ":" : "") + String.format("%02d:%02d", m, s);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 1.6.1：旋转不再重建页面，这里只把平板居中列的宽度按新屏幕重算一次
        Ui.refreshTabletLayout(this);
    }
}
