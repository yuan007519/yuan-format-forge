package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 万能转码：内置 FFmpeg，支持 AVI / WMV / RMVB / MKV / MPG 等任意封装互转。
 *
 * 1.6.5：多选时按队列逐个转码，一个失败不影响后面的文件；
 * 输出目录重名可选「覆盖」；HLS 少勾分片会在选中后立刻提示。
 */
public class ConvertActivity extends Activity {

    private static final int REQ_PICK = 81;
    private static final int REQ_TREE = 82;

    private static final String[] OUT_LABELS = {
            "MP4 · H.264", "MP4 · H.265", "MKV", "MOV", "AVI", "WMV", "FLV", "WebM",
            "MP3", "M4A", "AAC", "WAV", "FLAC", "OGG"
    };
    private static final String[] OUT_EXT = {
            "mp4", "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm",
            "mp3", "m4a", "aac", "wav", "flac", "ogg"
    };
    private static final boolean[] OUT_AUDIO = {
            false, false, false, false, false, false, false, false,
            true, true, true, true, true, true
    };
    private static final String[] Q_LABELS = {"高清", "标准", "小体积"};
    private static final String[] DUP_LABELS = {"另存", "覆盖"};

    private TextView tvAnyInfo, tvAnyStatus, tvAnyLog;
    private LinearLayout anyResultHost;
    private ProgressBar pbAny;
    private Button btnAnyConvert, btnAnyCancel;
    private Ui.Chips outChips, qualityChips, dupChips;
    private QueueUi queueUi;

    /** 每个队列项对应一组 uri：普通文件只有一个，本地 HLS 是「索引 + 分片」整组 */
    private final List<List<Uri>> jobUris = new ArrayList<List<Uri>>();
    /** 队列的显示状态，只在本页内存里，用于画 QueueUi */
    private TaskHold.State batch = new TaskHold.State();
    private int curIndex = -1;
    private boolean running;

    /** 当前正在处理的那一项 */
    private Uri srcUri;
    private String srcName = "";
    private File cacheFile;
    private long durationSec = 0;
    private int srcHeight = 0;
    private long sessionId = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;
    private long lastLogPost = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_convert);
        Ui.centerTabletContent(this);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("万能转码");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("内置 FFmpeg · 可多选排队 · 本地 M3U8");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvAnyInfo = (TextView) findViewById(R.id.tvAnyInfo);
        tvAnyStatus = (TextView) findViewById(R.id.tvAnyStatus);
        tvAnyLog = (TextView) findViewById(R.id.tvAnyLog);
        anyResultHost = (LinearLayout) findViewById(R.id.anyResultHost);
        pbAny = (ProgressBar) findViewById(R.id.pbAny);
        btnAnyConvert = (Button) findViewById(R.id.btnAnyConvert);
        btnAnyCancel = (Button) findViewById(R.id.btnAnyCancel);
        queueUi = new QueueUi(this, (LinearLayout) findViewById(R.id.cardAnyAction));

        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.anyOutChips), OUT_LABELS, 0);
        qualityChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.anyQualityChips), Q_LABELS, 1);
        dupChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.anyDupChips), DUP_LABELS, 0);
        outChips.onChange(new Runnable() {
            @Override
            public void run() {
                qualityChips.setVisible(!OUT_AUDIO[outChips.selected()]);
            }
        });
        btnAnyCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionId >= 0) {
                    FFmpegKit.cancel(sessionId);
                    tvAnyStatus.setText("正在取消…");
                }
            }
        });

        String ver = "6.0";
        try {
            String v = FFmpegKitConfig.getFFmpegVersion();
            if (v != null && v.length() > 0) ver = v;
        } catch (Throwable ignored) {}
        tvAnyInfo.setText("内核 FFmpeg " + ver + "，已内置 x264 / x265 / VP9 / FLAC / MP3 编码器。\n"
                + "支持输入：AVI / WMV / RMVB / MKV / MPG / VOB / TS / FLV / MOV / MP4 等，以及几乎所有音频格式。\n"
                + "多选多个文件会按顺序排队转，一个失败不影响后面的。\n"
                + "本地 M3U8（HLS）：索引和同目录分片都在手机上时可以直接转成单个 MP4。\n"
                + "选文件时长按 m3u8 进多选，把同目录分片一起选中最稳；"
                + "在线 m3u8 链接和需要联网取密钥的加密 HLS 不支持（本应用不联网）。");

        findViewById(R.id.btnPickAny).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    Ui.toast(ConvertActivity.this, "请先等当前队列跑完或取消");
                    return;
                }
                // 用多选模式：这台 vivo 的单选模式点「打开」不回传结果（只把结果塞进 ClipData
                // 的机型实测单选拿不到），多选模式稳定回传。
                Picker.launch(ConvertActivity.this, REQ_PICK, true);
            }
        });

        btnAnyConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBatch();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri picked = resultCode == RESULT_OK ? Picker.firstUri(data) : null;
        if (requestCode == REQ_PICK && picked != null) {
            List<Uri> all = Picker.allUris(data);
            if (all.isEmpty()) all.add(picked);
            onPicked(all);
        } else if (requestCode == REQ_TREE && picked != null) {
            // 目录授权已不再是主路径，保留兼容：重新按单文件 HLS 收集
            if (srcUri != null && Hls.isHls(srcName)) prepareHls(allOf(srcUri), picked);
        }
    }

    private static List<Uri> allOf(Uri u) {
        List<Uri> l = new ArrayList<Uri>();
        l.add(u);
        return l;
    }

    /** 选中文件之后：m3u8 优先当 HLS 组，其余按文件逐个排队 */
    private void onPicked(List<Uri> all) {
        Uri hlsUri = null;
        for (Uri u : all) {
            if (Hls.isHls(Ui.displayName(this, u))) {
                hlsUri = u;
                break;
            }
        }
        jobUris.clear();
        anyResultHost.removeAllViews();
        tvAnyLog.setText("");
        batch = new TaskHold.State();
        curIndex = -1;

        if (hlsUri != null) {
            // 整组当一个任务：索引 + 分片
            jobUris.add(all);
            srcUri = hlsUri;
            srcName = Ui.displayName(this, hlsUri);
            buildJobs();
            tvAnyStatus.setText("已选择：" + srcName + "（连同 " + (all.size() - 1) + " 个分片），准备中…");
            prepareHls(all, null);
            return;
        }

        for (Uri u : all) jobUris.add(allOf(u));
        buildJobs();
        if (jobUris.size() == 1) {
            srcUri = jobUris.get(0).get(0);
            srcName = Ui.displayName(this, srcUri);
            tvAnyStatus.setText("已选择：" + srcName + "，准备中…");
            prepareOne(srcUri);
        } else {
            renderQueue();
            tvAnyStatus.setText("已选择 " + jobUris.size() + " 个文件，点“开始转码”按顺序逐个处理。");
        }
    }

    /** 把 jobUris 转成队列行（每行只有名字，状态从等待开始） */
    private void buildJobs() {
        batch = new TaskHold.State();
        batch.mode = "convert";
        for (List<Uri> group : jobUris) {
            String name = Ui.displayName(this, group.get(0));
            batch.jobs.add(new TaskHold.Job(name, group.get(0)));
        }
        renderQueue();
    }

    /** 单任务时不画队列行（状态行已经说得够清楚），两个以上才显示排队 */
    private void renderQueue() {
        if (batch.jobs.size() <= 1) {
            queueUi.hide();
        } else {
            queueUi.render(batch);
        }
    }

    // ------------------------------------------------------------ 准备（单个文件）

    /** 就绪预览：复制进缓存 + 探时长/高度，供单文件与队列第一项复用 */
    private void prepareOne(final Uri uri) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = copyToCacheSync(uri);
                    cacheFile = f;
                    probe(uri);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder sb = new StringBuilder();
                            sb.append(srcName).append(" · ").append(Ui.size(f.length()));
                            if (durationSec > 0) sb.append(" · ").append(fmtTime(durationSec * 1000));
                            if (srcHeight > 0) sb.append(" · 高 ").append(srcHeight).append("px");
                            tvAnyStatus.setText(sb.toString() + "\n" + readyHint());
                        }
                    });
                } catch (final Exception e) {
                    postStatus("失败：" + msgOf(e));
                }
            }
        });
    }

    // ------------------------------------------------------------ 准备（本地 HLS）

    /** 本地 HLS：索引 + 同目录分片一起搬进缓存，全程离线 */
    private void prepareHls(final List<Uri> uris, final Uri tree) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String text = Hls.readText(ConvertActivity.this, srcUri);
                    final int refs = Hls.refCount(text);

                    // 1.6.5：多选时先看少没少分片，选中就提示，不用等复制失败。
                    // 只选了一个 m3u8 时仍走「同目录兄弟文档」的老路子，不在这里拦。
                    if (uris.size() > 1) {
                        List<String> missing = Hls.missingSelected(ConvertActivity.this, uris);
                        if (!missing.isEmpty()) {
                            final String msg = missingText(missing);
                            final int cnt = missing.size();
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tvAnyStatus.setText("少选了 " + cnt + " 个分片：" + msg
                                            + "\n重新点「选择文件」，把 m3u8 和同目录的分片一起勾上。");
                                }
                            });
                            return;
                        }
                    }

                    // 空间预检：先把选中文件的体积加起来，别等复制到一半才写满
                    long sum = 0;
                    for (Uri u : uris) {
                        long s = Ui.sizeOf(ConvertActivity.this, u);
                        if (s > 0) sum += s;
                    }
                    Ui.requireSpace(Hls.dir(ConvertActivity.this), Ui.needForConvert(sum), "这段 HLS");
                    postStatus("HLS 索引 · 共 " + refs + " 个分片，正在收集本地分片…");
                    final File index = (uris.size() > 1)
                            ? Hls.stageSelected(ConvertActivity.this, uris, Hls.dir(ConvertActivity.this))
                            : Hls.stage(ConvertActivity.this, srcUri, tree, text);
                    cacheFile = index;
                    durationSec = Hls.totalSeconds(text);
                    srcHeight = 0;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder sb = new StringBuilder();
                            sb.append(srcName).append(" · 本地 HLS · ").append(refs).append(" 个分片");
                            if (durationSec > 0) sb.append(" · ").append(fmtTime(durationSec * 1000));
                            sb.append(" · ").append(Ui.size(index.length()));
                            tvAnyStatus.setText(sb.toString() + "\n" + readyHint());
                        }
                    });
                } catch (final Hls.NeedFolder nf) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // 目录授权这条路在部分 ROM 上弹不出可用的根目录，直接教用户多选更靠谱
                            tvAnyStatus.setText(nf.getMessage() + "\n"
                                    + "解决办法：重新点「选择文件」，长按 m3u8 进入多选，"
                                    + "把同目录的分片一起勾上再点「打开」。");
                        }
                    });
                } catch (final Exception e) {
                    postStatus("失败：" + msgOf(e));
                }
            }
        });
    }

    private static String missingText(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < missing.size() && i < 3; i++) {
            if (i > 0) sb.append("、");
            sb.append(missing.get(i));
        }
        if (missing.size() > 3) sb.append(" 等 ").append(missing.size()).append(" 个");
        return sb.toString();
    }

    /** 就绪提示：长视频额外提醒耗电与切后台的风险 */
    private String readyHint() {
        if (durationSec >= 300) {
            return "已就绪，点“开始转码”。这段较长（" + fmtTime(durationSec * 1000)
                    + "），转码期间请保持本页在前台、最好插着电。";
        }
        return "已就绪，点“开始转码”。";
    }

    private File copyToCacheSync(Uri uri) throws Exception {
        File dir = Ui.workDir(this, "convert");
        for (File old : dir.listFiles()) old.delete();
        // 空间预检：输入副本 + 预计输出 + 余量，不够就直接说清楚
        long srcSize = Ui.sizeOf(this, uri);
        Ui.requireSpace(dir, Ui.needForConvert(srcSize), "这个文件的转码");
        String name = Ui.displayName(this, uri);
        String ext = Ui.ext(name == null ? "" : name);
        File dest = new File(dir, "input." + (ext.length() == 0 ? "bin" : ext));
        postStatus("正在读取文件到本机缓存…");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取该文件");
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0, last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total - last > 4 * 1024 * 1024) {
                    last = total;
                    final long t = total;
                    postStatus("正在读取文件到本机缓存… " + Ui.size(t));
                }
            }
            out.flush();
            return dest;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private void probe(Uri uri) {
        durationSec = 0;
        srcHeight = 0;
        android.media.MediaMetadataRetriever ret = new android.media.MediaMetadataRetriever();
        try {
            ret.setDataSource(getApplicationContext(), uri);
            String d = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) durationSec = Long.parseLong(d) / 1000;
            String h = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (h != null) srcHeight = Integer.parseInt(h);
        } catch (Throwable ignored) {
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ 批量队列

    private void startBatch() {
        if (jobUris.isEmpty()) {
            Ui.toast(this, "请先选择要转换的文件");
            return;
        }
        if (running) return;
        // 已经准备好第一项（单文件预览时复制过）也重跑一遍，保证队列从干净状态开始
        buildJobs();
        anyResultHost.removeAllViews();
        running = true;
        btnAnyConvert.setEnabled(false);
        btnAnyCancel.setEnabled(true);
        nextJob(0);
    }

    private void nextJob(int from) {
        if (!running) return;
        int i = from;
        while (i < batch.jobs.size() && batch.jobs.get(i).state != TaskHold.WAIT) i++;
        if (i >= batch.jobs.size()) {
            finishBatch();
            return;
        }
        runJob(i);
    }

    private void runJob(final int i) {
        curIndex = i;
        final TaskHold.Job job = batch.jobs.get(i);
        job.state = TaskHold.RUN;
        renderQueue();
        srcName = job.name;
        srcUri = job.uri;
        durationSec = 0;
        srcHeight = 0;
        tvAnyLog.setText("");
        tvAnyStatus.setText(batchLabel(i) + "准备中…");
        pbAny.setVisibility(View.VISIBLE);

        final List<Uri> uris = jobUris.get(i);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File in;
                    if (uris.size() > 1) {
                        // 多选：索引 + 分片都被勾中，先核对缺没缺，缺就明确报出来
                        String text = Hls.readText(ConvertActivity.this, srcUri);
                        List<String> missing = Hls.missingSelected(ConvertActivity.this, uris);
                        if (!missing.isEmpty()) {
                            throw new Exception("少选了 " + missing.size() + " 个分片："
                                    + missingText(missing));
                        }
                        long sum = 0;
                        for (Uri u : uris) {
                            long s = Ui.sizeOf(ConvertActivity.this, u);
                            if (s > 0) sum += s;
                        }
                        Ui.requireSpace(Hls.dir(ConvertActivity.this), Ui.needForConvert(sum), "这段 HLS");
                        in = Hls.stageSelected(ConvertActivity.this, uris, Hls.dir(ConvertActivity.this));
                        durationSec = Hls.totalSeconds(text);
                        srcHeight = 0;
                    } else if (Hls.isHls(srcName)) {
                        // 只勾了索引：沿用「同目录兄弟文档」读分片的老路子
                        String text = Hls.readText(ConvertActivity.this, srcUri);
                        Ui.requireSpace(Hls.dir(ConvertActivity.this),
                                Ui.needForConvert(Ui.sizeOf(ConvertActivity.this, srcUri)), "这段 HLS");
                        in = Hls.stage(ConvertActivity.this, srcUri, null, text);
                        durationSec = Hls.totalSeconds(text);
                        srcHeight = 0;
                    } else {
                        in = copyToCacheSync(srcUri);
                        probe(srcUri);
                    }
                    cacheFile = in;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (running) beginFfmpeg(i);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            failJob(i, msgOf(e));
                        }
                    });
                }
            }
        });
    }

    private String batchLabel(int i) {
        if (batch.jobs.size() <= 1) return "";
        return "第 " + (i + 1) + "/" + batch.jobs.size() + " 个 · ";
    }

    private void beginFfmpeg(final int i) {
        final int outIdx = outChips.selected();
        final int q = qualityChips.selected();
        final String ext = OUT_EXT[outIdx];
        String base = Ui.baseName(srcName);
        final File out;
        if (dupChips.selected() == 1) {
            // 覆盖模式：同名直接替换，方便反复测同一段素材
            out = new File(Ui.outDir(this), base + "_转码." + ext);
        } else {
            out = new File(Ui.outDir(this), uniqueName(base + "_转码." + ext));
        }

        List<String> args = buildArgs(cacheFile, out, outIdx, q);
        tvAnyStatus.setText(batchLabel(i) + "正在启动 FFmpeg…");
        tvAnyLog.setText("");

        final String[] arr = args.toArray(new String[0]);
        try {
            FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(arr,
                    new FFmpegSessionCompleteCallback() {
                        @Override
                        public void apply(final FFmpegSession s) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    onFinished(s, out, i);
                                }
                            });
                        }
                    },
                    new LogCallback() {
                        @Override
                        public void apply(Log log) {
                            if (log == null) return;
                            final String msg = log.getMessage();
                            long now = System.currentTimeMillis();
                            if (now - lastLogPost < 400) return;
                            lastLogPost = now;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (msg != null) tvAnyLog.setText(msg.trim());
                                }
                            });
                        }
                    },
                    new StatisticsCallback() {
                        @Override
                        public void apply(final Statistics st) {
                            if (st == null) return;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!running) return;
                                    double t = st.getTime();
                                    String pct = "";
                                    String eta = "";
                                    double speed = st.getSpeed();
                                    if (durationSec > 0) {
                                        int p = (int) Math.min(100,
                                                Math.round(t * 100.0 / (durationSec * 1000.0)));
                                        pct = " · " + p + "%";
                                        double restMs = durationSec * 1000.0 - t;
                                        if (restMs > 0 && speed > 0.05) {
                                            eta = " · 预计还要 " + fmtTime((long) (restMs / speed));
                                        }
                                    }
                                    tvAnyStatus.setText(batchLabel(i) + "正在转码" + pct + " · 已处理 "
                                            + fmtTime((long) t) + eta + " · "
                                            + String.format(java.util.Locale.US, "%.1f", speed) + "x");
                                }
                            });
                        }
                    });
            sessionId = session == null ? -1 : session.getSessionId();
        } catch (Throwable e) {
            failJob(i, "FFmpeg 启动失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void failJob(int i, String msg) {
        if (i >= 0 && i < batch.jobs.size()) {
            TaskHold.Job j = batch.jobs.get(i);
            j.state = TaskHold.FAIL;
            j.note = msg;
            batch.fail++;
        }
        renderQueue();
        pbAny.setVisibility(View.GONE);
        tvAnyStatus.setText(batchLabel(i) + "失败：" + msg + "\n继续处理下一个…");
        nextJob(i + 1);
    }

    private void onFinished(FFmpegSession s, File out, int i) {
        sessionId = -1;
        pbAny.setVisibility(View.GONE);
        TaskHold.Job job = (i >= 0 && i < batch.jobs.size()) ? batch.jobs.get(i) : null;

        if (ReturnCode.isSuccess(s.getReturnCode())) {
            if (!out.exists() || out.length() == 0) {
                if (job != null) {
                    job.state = TaskHold.FAIL;
                    job.note = "转码结束但没有输出文件";
                    batch.fail++;
                }
                renderQueue();
                tvAnyStatus.setText(batchLabel(i) + "转码结束但没有输出文件，换个格式再试");
                nextJob(i + 1);
                return;
            }
            if (job != null) {
                job.state = TaskHold.OK;
                job.note = out.getName() + " · " + Ui.size(out.length());
                job.outputs.add(out);
                batch.ok++;
                batch.results.add(out);
            }
            renderQueue();
            tvAnyStatus.setText(batchLabel(i) + "完成：" + out.getName() + " · " + Ui.size(out.length()));
            // 大文件副本不留在手机里：转完就删，避免几 GB 的临时文件长期占空间；
            // 小文件留着，方便换个输出格式再转一次而不用重新选。
            if (cacheFile != null && cacheFile.length() > 64L * 1024 * 1024) {
                cacheFile.delete();
                cacheFile = null;
            }
            long staged = 0;
            File[] stagedFiles = Hls.dir(this).listFiles();
            if (stagedFiles != null) for (File x : stagedFiles) staged += x.length();
            if (staged > 64L * 1024 * 1024) Hls.clean(this);
            final File f = out;
            anyResultHost.addView(Ui.resultRow(this, f,
                    "转码完成 · " + Ui.size(f.length()),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            List<File> one = new ArrayList<File>();
                            one.add(f);
                            Ui.shareFiles(ConvertActivity.this, one, "转码结果");
                        }
                    }));
            nextJob(i + 1);
            return;
        }

        if (ReturnCode.isCancel(s.getReturnCode())) {
            if (out.exists()) out.delete();
            if (job != null) {
                job.state = TaskHold.FAIL;
                job.note = "已取消";
                batch.fail++;
            }
            // 取消整批：剩下排队的一并标记，别让用户以为还在跑
            for (TaskHold.Job j : batch.jobs) {
                if (j.state == TaskHold.WAIT) {
                    j.state = TaskHold.FAIL;
                    j.note = "已取消";
                    batch.fail++;
                }
            }
            running = false;
            sessionId = -1;
            btnAnyConvert.setEnabled(true);
            btnAnyCancel.setEnabled(false);
            batch.finished = true;
            renderQueue();
            tvAnyStatus.setText("已取消。");
            return;
        }

        String logs = "";
        try {
            logs = s.getOutput();
        } catch (Throwable ignored) {}
        if (logs == null || logs.length() == 0) {
            try { logs = s.getAllLogsAsString(); } catch (Throwable ignored) {}
        }
        if (logs == null) logs = "";
        if (logs.length() > 300) logs = logs.substring(logs.length() - 300);
        if (job != null) {
            job.state = TaskHold.FAIL;
            job.note = "转码失败";
            batch.fail++;
        }
        renderQueue();
        tvAnyStatus.setText(batchLabel(i) + "转码失败。最后日志：\n" + logs.trim());
        nextJob(i + 1);
    }

    private void finishBatch() {
        running = false;
        sessionId = -1;
        pbAny.setVisibility(View.GONE);
        btnAnyConvert.setEnabled(true);
        btnAnyCancel.setEnabled(false);
        batch.finished = true;
        renderQueue();
        int total = batch.jobs.size();
        if (total <= 1) {
            // 单文件时状态行已经写了「完成 / 失败：…」，末尾不再重复
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("全部结束：共 ").append(total)
                .append(" 个，成功 ").append(batch.ok)
                .append("、失败 ").append(batch.fail).append("。");
        if (!batch.results.isEmpty()) sb.append("\n结果都在下面，可逐个分享。");
        tvAnyStatus.setText(sb.toString());
    }

    // ------------------------------------------------------------ FFmpeg 参数

    private List<String> buildArgs(File in, File out, int outIdx, int q) {
        List<String> a = new ArrayList<String>();
        a.add("-y");
        if (Hls.isHls(srcName)) {
            // 分片扩展名五花八门时 FFmpeg 的 HLS 解复用器默认会拒绝，这里全放开
            addArgs(a, "-allowed_extensions", "ALL");
        }
        a.add("-i");
        a.add(in.getAbsolutePath());

        boolean audio = OUT_AUDIO[outIdx];
        int targetH = targetHeight(q);
        if (!audio && targetH > 0) {
            a.add("-vf");
            a.add("scale=-2:" + targetH);
        }

        String crf = q == 0 ? "20" : (q == 1 ? "23" : "28");
        String abr = q == 0 ? "192k" : (q == 1 ? "128k" : "96k");

        if (audio) {
            a.add("-vn");
            switch (OUT_EXT[outIdx]) {
                case "mp3":
                    addArgs(a, "-c:a", "libmp3lame", "-b:a", abr);
                    break;
                case "m4a":
                    addArgs(a, "-c:a", "aac", "-b:a", abr);
                    break;
                case "aac":
                    addArgs(a, "-c:a", "aac", "-b:a", abr, "-f", "adts");
                    break;
                case "wav":
                    addArgs(a, "-c:a", "pcm_s16le");
                    break;
                case "flac":
                    addArgs(a, "-c:a", "flac");
                    break;
                case "ogg":
                    addArgs(a, "-c:a", "libvorbis", "-q:a", "4");
                    break;
                default:
                    break;
            }
        } else {
            switch (outIdx) {
                case 1: // MP4 H.265
                    addArgs(a, "-c:v", "libx265", "-preset", "veryfast", "-crf", crf,
                            "-tag:v", "hvc1", "-c:a", "aac", "-b:a", abr, "-movflags", "+faststart");
                    break;
                case 0: // MP4 H.264
                    addArgs(a, "-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", abr, "-movflags", "+faststart");
                    break;
                case 2: // MKV
                    addArgs(a, "-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", abr);
                    break;
                case 3: // MOV
                    addArgs(a, "-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", abr);
                    break;
                case 4: // AVI
                    addArgs(a, "-c:v", "mpeg4", "-q:v", "4", "-c:a", "libmp3lame", "-b:a", abr);
                    break;
                case 5: // WMV
                    addArgs(a, "-c:v", "wmv2", "-b:v", q == 0 ? "4M" : (q == 1 ? "2M" : "1M"),
                            "-c:a", "wmav2", "-b:a", abr);
                    break;
                case 6: // FLV
                    addArgs(a, "-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                            "-pix_fmt", "yuv420p", "-ar", "44100", "-ac", "2",
                            "-c:a", "aac", "-b:a", abr);
                    break;
                case 7: // WebM
                    addArgs(a, "-c:v", "libvpx-vp9", "-crf", q == 0 ? "28" : (q == 1 ? "32" : "36"),
                            "-b:v", "0", "-row-mt", "1", "-c:a", "libopus", "-b:a", abr);
                    break;
                default:
                    break;
            }
        }
        a.add(out.getAbsolutePath());
        return a;
    }

    private static void addArgs(List<String> list, String... items) {
        for (String s : items) list.add(s);
    }

    private int targetHeight(int q) {
        if (q == 0) return 0;
        if (q == 1) return srcHeight > 1080 ? 1080 : 0;
        return srcHeight > 720 ? 720 : 0;
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

    private String fmtTime(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private static String msgOf(Exception e) {
        return e.getMessage() == null ? "读取失败" : e.getMessage();
    }

    private void postStatus(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvAnyStatus.setText(text);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sessionId >= 0) {
            try { FFmpegKit.cancel(sessionId); } catch (Throwable ignored) {}
        }
        if (exec != null) exec.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 1.6.1：旋转不再重建页面，这里只把平板居中列的宽度按新屏幕重算一次
        Ui.refreshTabletLayout(this);
    }
}
