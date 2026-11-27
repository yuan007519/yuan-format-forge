package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
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

/** 万能转码：内置 FFmpeg，支持 AVI / WMV / RMVB / MKV / MPG 等任意封装互转 */
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

    private TextView tvAnyInfo, tvAnyStatus, tvAnyLog;
    private LinearLayout anyResultHost;
    private ProgressBar pbAny;
    private Button btnAnyConvert, btnAnyCancel;
    private Ui.Chips outChips, qualityChips;

    private Uri srcUri;
    private List<Uri> hlsSel;
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
        ((TextView) findViewById(R.id.tvSubtitle)).setText("内置 FFmpeg · AVI / WMV / RMVB 也能转");
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

        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.anyOutChips), OUT_LABELS, 0);
        qualityChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.anyQualityChips), Q_LABELS, 1);
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
                + "本地 M3U8（HLS）：索引和同目录分片都在手机上时可以直接转成单个 MP4。\n"
                + "选文件时长按 m3u8 进多选，把同目录分片一起选中最稳；"
                + "在线 m3u8 链接和需要联网取密钥的加密 HLS 不支持（本应用不联网）。");

        findViewById(R.id.btnPickAny).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionId >= 0) {
                    Ui.toast(ConvertActivity.this, "请先等当前转码结束或取消");
                    return;
                }
                // 用多选模式：这台 vivo 的单选模式点「打开」不回传结果（只把结果塞进 ClipData
                // 的机型实测单选拿不到），多选模式稳定回传，这里只取第一个文件。
                Picker.launch(ConvertActivity.this, REQ_PICK, true);
            }
        });

        btnAnyConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConvert();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri picked = resultCode == RESULT_OK ? Picker.firstUri(data) : null;
        int pickedCount = 1;
        if (data != null && data.getClipData() != null) pickedCount = data.getClipData().getItemCount();
        if (requestCode == REQ_PICK && picked != null) {
            // 多选时 ClipData 的顺序不一定等于点击顺序，先把 m3u8 索引挑出来
            List<Uri> all = Picker.allUris(data);
            Uri hlsUri = null;
            for (Uri u : all) {
                if (Hls.isHls(Ui.displayName(this, u))) { hlsUri = u; break; }
            }
            srcUri = hlsUri != null ? hlsUri : picked;
            srcName = Ui.displayName(this, srcUri);
            anyResultHost.removeAllViews();
            tvAnyStatus.setText(pickedCount > 1
                    ? "已选择 " + pickedCount + " 项，本次只转第一个：" + srcName
                    : "已选择：" + srcName + "，准备中…");
            tvAnyLog.setText("");
            if (hlsUri != null) {
                // m3u8 只是索引，真正要转的数据在同目录的分片里
                hlsSel = all;
                prepareHls(null);
                return;
            }
            prepare();
        } else if (requestCode == REQ_TREE && picked != null) {
            if (srcUri != null && Hls.isHls(srcName)) prepareHls(picked);
        }
    }

    /** 本地 HLS：索引 + 同目录分片一起搬进缓存，全程离线 */
    private void prepareHls(final Uri tree) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String text = Hls.readText(ConvertActivity.this, srcUri);
                    final int refs = Hls.refCount(text);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAnyStatus.setText("HLS 索引 · 共 " + refs + " 个分片，正在收集本地分片…");
                        }
                    });
                    final File index = (hlsSel != null && hlsSel.size() > 1)
                            ? Hls.stageSelected(ConvertActivity.this, hlsSel, Hls.dir(ConvertActivity.this))
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
                            tvAnyStatus.setText(sb.toString() + "\n已就绪，点“开始转码”。");
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
                    final String msg = e.getMessage() == null ? "读取失败" : e.getMessage();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAnyStatus.setText("失败：" + msg);
                        }
                    });
                }
            }
        });
    }

    private void prepare() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final File f = copyToCache();
                if (f == null) return;
                cacheFile = f;
                probe();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(srcName).append(" · ").append(Ui.size(f.length()));
                        if (durationSec > 0) sb.append(" · ").append(fmtTime(durationSec * 1000));
                        if (srcHeight > 0) sb.append(" · 高 ").append(srcHeight).append("px");
                        tvAnyStatus.setText(sb.toString() + "\n已就绪，点“开始转码”。");
                    }
                });
            }
        });
    }

    private File copyToCache() {
        File dir = new File(getCacheDir(), "convert");
        if (!dir.exists()) dir.mkdirs();
        for (File old : dir.listFiles()) old.delete();
        String ext = Ui.ext(srcName);
        File dest = new File(dir, "input." + (ext.length() == 0 ? "bin" : ext));
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvAnyStatus.setText("正在读取文件到本机缓存…");
            }
        });
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(srcUri);
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
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAnyStatus.setText("正在读取文件到本机缓存… " + Ui.size(t));
                        }
                    });
                }
            }
            out.flush();
            return dest;
        } catch (final Exception e) {
            final String msg = e.getMessage() == null ? "读取失败" : e.getMessage();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    tvAnyStatus.setText("失败：" + msg);
                }
            });
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private void probe() {
        durationSec = 0;
        srcHeight = 0;
        android.media.MediaMetadataRetriever ret = new android.media.MediaMetadataRetriever();
        try {
            ret.setDataSource(getApplicationContext(), srcUri);
            String d = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) durationSec = Long.parseLong(d) / 1000;
            String h = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (h != null) srcHeight = Integer.parseInt(h);
        } catch (Throwable ignored) {
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ 转码

    private void startConvert() {
        if (cacheFile == null || !cacheFile.exists()) {
            Ui.toast(this, "请先选择要转换的文件");
            return;
        }
        final int outIdx = outChips.selected();
        final int q = qualityChips.selected();
        final String ext = OUT_EXT[outIdx];
        String base = Ui.baseName(srcName);
        final File out = new File(Ui.outDir(this), uniqueName(base + "_转码." + ext));

        List<String> args = buildArgs(cacheFile, out, outIdx, q);
        pbAny.setVisibility(View.VISIBLE);
        btnAnyConvert.setEnabled(false);
        btnAnyCancel.setEnabled(true);
        anyResultHost.removeAllViews();
        tvAnyStatus.setText("正在启动 FFmpeg…");
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
                                    onFinished(s, out);
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
                                    double t = st.getTime();
                                    String pct = "";
                                    if (durationSec > 0) {
                                        int p = (int) Math.min(100,
                                                Math.round(t * 100.0 / (durationSec * 1000.0)));
                                        pct = " · " + p + "%";
                                    }
                                    tvAnyStatus.setText("正在转码" + pct + " · 已处理 "
                                            + fmtTime((long) t) + " · "
                                            + String.format(java.util.Locale.US, "%.1f", st.getSpeed()) + "x");
                                }
                            });
                        }
                    });
            sessionId = session == null ? -1 : session.getSessionId();
        } catch (Throwable e) {
            pbAny.setVisibility(View.GONE);
            btnAnyConvert.setEnabled(true);
            btnAnyCancel.setEnabled(false);
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            tvAnyStatus.setText("FFmpeg 启动失败：" + m);
        }
    }

    private void onFinished(FFmpegSession s, File out) {
        sessionId = -1;
        pbAny.setVisibility(View.GONE);
        btnAnyConvert.setEnabled(true);
        btnAnyCancel.setEnabled(false);
        if (ReturnCode.isSuccess(s.getReturnCode())) {
            if (!out.exists() || out.length() == 0) {
                tvAnyStatus.setText("转码结束但没有输出文件，换个格式再试");
                return;
            }
            tvAnyStatus.setText("完成：" + out.getName() + " · " + Ui.size(out.length()));
            final File f = out;
            anyResultHost.addView(Ui.resultRow(this, f,
                    "转码完成 · " + Ui.size(f.length()),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            List<File> one = new ArrayList<>();
                            one.add(f);
                            Ui.shareFiles(ConvertActivity.this, one, "转码结果");
                        }
                    }));
        } else if (ReturnCode.isCancel(s.getReturnCode())) {
            tvAnyStatus.setText("已取消。");
            if (out.exists()) out.delete();
        } else {
            String logs = "";
            try {
                logs = s.getOutput();
            } catch (Throwable ignored) {}
            if (logs == null || logs.length() == 0) {
                try { logs = s.getAllLogsAsString(); } catch (Throwable ignored) {}
            }
            if (logs == null) logs = "";
            if (logs.length() > 400) logs = logs.substring(logs.length() - 400);
            tvAnyStatus.setText("转码失败。最后日志：\n" + logs.trim());
        }
    }

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
