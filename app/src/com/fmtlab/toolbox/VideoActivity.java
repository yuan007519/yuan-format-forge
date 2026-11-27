package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.Log;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;
import com.fmtlab.toolbox.core.GifWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 视频：换封装（MP4/WebM）、转 GIF、抽帧；系统解不了的格式自动交给 FFmpeg 内核 */
public class VideoActivity extends Activity {

    private static final int REQ_PICK = 71;
    private static final int REQ_DIR = 72;

    private static final String[] OUT_LABELS = {"MP4 换壳", "WebM 换壳", "MKV 换壳", "MOV 换壳",
            "GIF 动图", "抽帧图片", "MP3 音轨", "M4A 音轨", "WAV 音轨", "FLAC 音轨",
            "OGG 音轨", "AAC 音轨", "OPUS 音轨", "WMA 音轨"};
    private static final String[] AUDIO_EXPORT_EXT = {"mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "wma"};
    private static final int AUDIO_MODE_FIRST = 6;
    private static final String[] FPS_LABELS = {"8 帧/秒", "10 帧/秒", "12 帧/秒", "15 帧/秒", "20 帧/秒", "24 帧/秒"};
    private static final int[] FPS_VALUES = {8, 10, 12, 15, 20, 24};
    private static final String[] WIDTH_LABELS = {"320 px", "480 px", "640 px", "720 px"};
    private static final int[] WIDTH_VALUES = {320, 480, 640, 720};
    private static final String[] INTERVAL_LABELS = {"每 0.5 秒", "每 1 秒", "每 2 秒"};
    private static final double[] INTERVAL_VALUES = {0.5, 1.0, 2.0};

    private static final String[] MP4_VIDEO = {"video/avc", "video/hevc", "video/mp4v-es"};
    private static final String[] WEBM_VIDEO = {"video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9"};
    private static final String[] MP4_AUDIO = {"audio/mp4a-latm", "audio/mpeg", "audio/3gpp"};
    private static final String[] WEBM_AUDIO = {"audio/opus", "audio/vorbis"};

    private TextView tvVideoInfo, tvVideoStatus, tvOutHint;
    private LinearLayout videoResultHost, gifParamCard, frameParamCard;
    private ProgressBar pbVideo;
    private Button btnVideoConvert, btnVideoSaveAll;
    private EditText etGifStart, etGifDuration;
    private Ui.Chips outChips, fpsChips, widthChips, intervalChips;

    private Uri videoUri;
    private String videoName = "";
    private final List<File> lastFiles = new ArrayList<>();
    private String lastNote = "";

    private File cacheIn;
    private String cacheName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        Ui.centerTabletContent(this);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("视频工具");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("换封装 · 转 GIF · 抽帧 · 系统解不了的走 FFmpeg");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvVideoInfo = (TextView) findViewById(R.id.tvVideoInfo);
        tvVideoStatus = (TextView) findViewById(R.id.tvVideoStatus);
        tvOutHint = (TextView) findViewById(R.id.tvOutHint);
        videoResultHost = (LinearLayout) findViewById(R.id.videoResultHost);
        gifParamCard = (LinearLayout) findViewById(R.id.gifParamCard);
        frameParamCard = (LinearLayout) findViewById(R.id.frameParamCard);
        pbVideo = (ProgressBar) findViewById(R.id.pbVideo);
        btnVideoConvert = (Button) findViewById(R.id.btnVideoConvert);
        btnVideoSaveAll = (Button) findViewById(R.id.btnVideoSaveAll);
        etGifStart = (EditText) findViewById(R.id.etGifStart);
        etGifDuration = (EditText) findViewById(R.id.etGifDuration);

        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.videoOutChips), OUT_LABELS, 0);
        fpsChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.gifFpsChips), FPS_LABELS, 3);
        widthChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.gifWidthChips), WIDTH_LABELS, 1);
        intervalChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.frameIntervalChips), INTERVAL_LABELS, 1);

        outChips.onChange(new Runnable() {
            @Override
            public void run() {
                syncParamCards();
            }
        });
        syncParamCards();

        findViewById(R.id.btnPickVideo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(VideoActivity.this, REQ_PICK, false);
            }
        });

        btnVideoConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });

        btnVideoSaveAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastFiles.isEmpty()) {
                    Ui.toast(VideoActivity.this, "还没有可保存的文件");
                    return;
                }
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try {
                    startActivityForResult(i, REQ_DIR);
                } catch (Exception e) {
                    Ui.toast(VideoActivity.this, "无法打开目录选择器");
                }
            }
        });
    }

    private void syncParamCards() {
        int mode = outChips.selected();
        gifParamCard.setVisibility(mode == 4 ? View.VISIBLE : View.GONE);
        frameParamCard.setVisibility(mode == 5 ? View.VISIBLE : View.GONE);
        switch (mode) {
            case 0:
                tvOutHint.setText("换壳：画面和音质完全不变，只换容器，几秒完成、零画质损失。适合把 mkv / mov / ts / flv / rmvb 变成更通用的 MP4。");
                break;
            case 1:
                tvOutHint.setText("WebM 换壳只对 VP8 / VP9 编码的视频有效；H.264 的视频请用 MP4，否则只能改用 VP9 重编码。");
                break;
            case 2:
                tvOutHint.setText("MKV 换壳由 FFmpeg 做流拷贝：avi / flv / wmv / m2ts / mts / ts / m4s 等容器都能装进 mkv，编码不动、画质不变。");
                break;
            case 3:
                tvOutHint.setText("MOV 换壳：苹果生态与剪辑软件通用的容器，同样是流拷贝，一条视频几秒完成。");
                break;
            case 4:
                tvOutHint.setText("GIF：由 FFmpeg 内核逐帧取画并用 256 色智能调色板量化（palettegen）。单次最长 10 分钟，超过 60 秒会按时长自动降帧率和宽度，免得文件过大。");
                break;
            case 5:
                tvOutHint.setText("抽帧：按固定间隔导出 JPG 图片，用来做封面、截图集、分镜参考；单次最多 300 张，超过时把间隔调大。");
                break;
            default:
                tvOutHint.setText("音轨导出：只保留声音，容器可换 MP3 / M4A / WAV / FLAC / OGG / AAC / OPUS / WMA，视频画面不保留。");
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && Picker.firstUri(data) != null) {
            videoUri = Picker.firstUri(data);
            videoName = Ui.displayName(this, videoUri);
            cacheIn = null;
            cacheName = "";
            tvVideoInfo.setText("已选择：" + videoName + "\n正在读取视频信息…");
            loadInfo();
        } else if (requestCode == REQ_DIR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            final boolean ok = Ui.copyIntoTrees(this, tree, lastFiles, "application/octet-stream");
            Ui.toast(this, ok ? "已保存 " + lastFiles.size() + " 个文件到选定目录" : "保存失败，请换一个目录试试");
        }
    }

    private void loadInfo() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String info = describe(videoUri);
                final String f = info;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvVideoInfo.setText("已选择：" + videoName + "\n" + f);
                    }
                });
            }
        });
    }

    private String describe(Uri uri) {
        StringBuilder sb = new StringBuilder();

        String note = Picker.mismatchNote(this, uri, videoName, Picker.VIDEO);
        if (note != null) sb.append(note).append("\n");

        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        try {
            ret.setDataSource(this, uri);
            String dur = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) sb.append("时长 ").append(fmtTime(Long.parseLong(dur)));
            String w = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w != null && h != null) sb.append(" · ").append(w).append("×").append(h);
            String br = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) {
                long v = Long.parseLong(br);
                sb.append(" · ").append(v / 1000).append(" kbps");
            }
        } catch (Exception e) {
            sb.append("系统解码器读不出时长（交给 FFmpeg 内核处理）");
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
        sb.append("\n");

        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, null);
            int video = 0, audio = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    video++;
                    if (video == 1) sb.append("视频 ").append(mime);
                } else if (mime.startsWith("audio/")) {
                    audio++;
                    if (audio == 1) sb.append(" · 音频 ").append(mime);
                }
            }
            if (video == 0) sb.append("这个文件里没有视频轨道");
            else sb.append(" · 共 ").append(ex.getTrackCount()).append(" 条轨道");
        } catch (Exception e) {
            sb.append("系统解不开这个封装（RMVB / VOB / 老 AVI / WMV），换壳与 GIF 会自动改用 FFmpeg 内核");
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private String fmtTime(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    // ---------------------------------------------------------------- 输入缓存

    /** FFmpeg 换个容器：MKV / MOV 走流拷贝，拷不动就退回重编码 */
    private File remuxFfmpeg(String ext) throws Exception {
        File in = ensureCache();
        File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "_换壳." + ext));
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvVideoStatus.setText("正在换壳为 " + ext.toUpperCase() + "…");
            }
        });
        try {
            com.fmtlab.toolbox.core.Ffmts.run(new java.util.ArrayList<String>(java.util.Arrays.asList(
                    "-i", in.getAbsolutePath(), "-map", "0", "-c", "copy",
                    "-y", out.getAbsolutePath())));
            lastNote = "流拷贝：画面与音质完全不变";
        } catch (Exception copyFail) {
            lastNote = "这条视频的编码不适合直接拷贝，已转为重新编码";
            com.fmtlab.toolbox.core.Ffmts.run(
                    com.fmtlab.toolbox.core.Ffmts.videoArgs(in, out, ext, "medium", null));
        }
        if (!out.exists() || out.length() == 0) throw new Exception("换壳失败");
        return out;
    }

    /** 只导出音轨（MP3 / M4A / WAV / FLAC / OGG / AAC / OPUS / WMA），用 FFmpeg 直接抽音频流 */
    private File exportAudioTrack(String ext) throws Exception {
        File in = ensureCache();
        File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "_音轨." + ext));
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvVideoStatus.setText("正在导出 " + ext.toUpperCase() + " 音轨…");
            }
        });
        com.fmtlab.toolbox.core.Ffmts.run(
                com.fmtlab.toolbox.core.Ffmts.extractAudioArgs(in, out, ext, 192));
        lastNote = "只导出了音轨，视频画面不在这个文件里";
        return out;
    }

    /** FFmpeg 只吃真实路径，先把 Uri 读到应用缓存 */
    private File ensureCache() throws Exception {
        if (cacheIn != null && cacheIn.exists() && cacheIn.length() > 0 && videoName.equals(cacheName)) {
            return cacheIn;
        }
        File dir = Ui.workDir(this, "video");
        if (dir.exists()) {
            File[] olds = dir.listFiles();
            if (olds != null) for (File f : olds) f.delete();
        } else {
            dir.mkdirs();
        }
        Ui.requireSpace(dir, Ui.needForConvert(Ui.sizeOf(this, videoUri)), "这个文件的处理");
        String ext = Ui.ext(videoName);
        File dest = new File(dir, "input." + (ext.length() == 0 ? "bin" : ext));

        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(videoUri);
            if (in == null) throw new Exception("无法读取该文件");
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0, last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total - last > 8L * 1024 * 1024) {
                    last = total;
                    final long t = total;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvVideoStatus.setText("正在读取文件到本机缓存… " + Ui.size(t));
                        }
                    });
                }
            }
            out.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
        cacheIn = dest;
        cacheName = videoName;
        return dest;
    }

    // ---------------------------------------------------------------- 转换

    private void convert() {
        if (videoUri == null) {
            Ui.toast(this, "请先选择视频文件");
            return;
        }
        final int mode = outChips.selected();
        pbVideo.setVisibility(View.VISIBLE);
        btnVideoConvert.setEnabled(false);
        btnVideoSaveAll.setVisibility(View.GONE);
        videoResultHost.removeAllViews();
        lastFiles.clear();
        lastNote = "";
        tvVideoStatus.setText("准备中…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mode == 0) {
                        File f = remux(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                                "mp4", MP4_VIDEO, MP4_AUDIO, "MP4");
                        doneOne(f, lastNote);
                    } else if (mode == 1) {
                        File f = remux(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                                "webm", WEBM_VIDEO, WEBM_AUDIO, "WebM");
                        doneOne(f, lastNote);
                    } else if (mode == 2) {
                        File f = remuxFfmpeg("mkv");
                        doneOne(f, lastNote);
                    } else if (mode == 3) {
                        File f = remuxFfmpeg("mov");
                        doneOne(f, lastNote);
                    } else if (mode == 4) {
                        File f = makeGif();
                        doneOne(f, lastNote);
                    } else if (mode == 5) {
                        List<File> files = exportFrames();
                        doneMany(files, "抽帧图片");
                    } else {
                        int ai = Math.min(mode - AUDIO_MODE_FIRST, AUDIO_EXPORT_EXT.length - 1);
                        File f = exportAudioTrack(AUDIO_EXPORT_EXT[ai]);
                        doneOne(f, "音轨导出 · " + AUDIO_EXPORT_EXT[ai].toUpperCase());
                    }
                } catch (Throwable e) {
                    final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbVideo.setVisibility(View.GONE);
                            btnVideoConvert.setEnabled(true);
                            tvVideoStatus.setText("失败：" + msg);
                        }
                    });
                }
            }
        });
    }

    private void doneOne(final File f, final String meta) {
        final List<File> one = new ArrayList<>();
        one.add(f);
        lastFiles.add(f);
        handler.post(new Runnable() {
            @Override
            public void run() {
                pbVideo.setVisibility(View.GONE);
                btnVideoConvert.setEnabled(true);
                btnVideoSaveAll.setVisibility(View.VISIBLE);
                tvVideoStatus.setText("完成：" + f.getName() + " · " + Ui.size(f.length()));
                videoResultHost.addView(Ui.resultRow(VideoActivity.this, f,
                        meta + " · " + Ui.size(f.length()),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Ui.shareFiles(VideoActivity.this, one, "格式工坊导出");
                            }
                        }));
            }
        });
    }

    private void doneMany(final List<File> files, final String meta) {
        lastFiles.addAll(files);
        handler.post(new Runnable() {
            @Override
            public void run() {
                pbVideo.setVisibility(View.GONE);
                btnVideoConvert.setEnabled(true);
                btnVideoSaveAll.setVisibility(View.VISIBLE);
                long total = 0;
                for (File f : files) total += f.length();
                tvVideoStatus.setText("完成：共 " + files.size() + " 张图片 · " + Ui.size(total));
                File first = files.isEmpty() ? null : files.get(0);
                if (first != null) {
                    videoResultHost.addView(Ui.resultRow(VideoActivity.this, first,
                            meta + " · 共 " + files.size() + " 张，从 " + first.getName() + " 开始",
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    Ui.shareFiles(VideoActivity.this, files, "抽帧图片");
                                }
                            }));
                }
            }
        });
    }

    // ---------------------------------------------------------------- FFmpeg 执行

    private FFmpegSession runFfmpeg(List<String> list, final String what, final long expectMs) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final FFmpegSession[] box = new FFmpegSession[1];
        final long[] lastPost = {0};

        String[] args = list.toArray(new String[0]);
        FFmpegSession s = FFmpegKit.executeWithArgumentsAsync(args,
                new FFmpegSessionCompleteCallback() {
                    @Override
                    public void apply(FFmpegSession session) {
                        box[0] = session;
                        latch.countDown();
                    }
                },
                new LogCallback() {
                    @Override
                    public void apply(Log log) {
                        // 只显示统计信息，日志量太大
                    }
                },
                new StatisticsCallback() {
                    @Override
                    public void apply(Statistics st) {
                        if (st == null) return;
                        long now = System.currentTimeMillis();
                        if (now - lastPost[0] < 350) return;
                        lastPost[0] = now;
                        final double t = st.getTime();
                        final double speed = st.getSpeed();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                StringBuilder sb = new StringBuilder(what);
                                if (expectMs > 0) {
                                    int p = (int) Math.min(100, Math.round(t * 100.0 / expectMs));
                                    sb.append(" · ").append(p).append("%");
                                }
                                sb.append(" · 已处理 ").append(fmtTime((long) t));
                                if (speed > 0) {
                                    sb.append(" · ").append(String.format(java.util.Locale.US, "%.1f", speed)).append("x");
                                }
                                tvVideoStatus.setText(sb.toString());
                            }
                        });
                    }
                });
        if (s == null) throw new Exception("FFmpeg 启动失败（原生库没能加载）");
        if (!latch.await(1800, TimeUnit.SECONDS)) throw new Exception("FFmpeg 处理超时");
        return box[0] != null ? box[0] : s;
    }

    private static String tailLog(FFmpegSession s, int max) {
        String logs = "";
        try { logs = s.getOutput(); } catch (Throwable ignored) {}
        if (logs == null || logs.length() == 0) {
            try { logs = s.getAllLogsAsString(); } catch (Throwable ignored) {}
        }
        if (logs == null) logs = "";
        logs = logs.trim();
        return logs.length() > max ? logs.substring(logs.length() - max) : logs;
    }

    private static String msgOf(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }

    // ---------------------------------------------------------------- 换封装

    private File remux(int muxFormat, String ext, String[] okVideo, String[] okAudio, String label) throws Exception {
        try {
            File f = remuxSystem(muxFormat, ext, okVideo, okAudio, label);
            lastNote = "视频容器已换成 " + label + "，画面音质未改动";
            return f;
        } catch (Throwable e) {
            // 系统解复用器撑不住（RMVB / VOB / WMV 等），交给 FFmpeg
            File f = remuxFfmpeg(ext, "webm".equals(ext), label);
            return f;
        }
    }

    private File remuxSystem(int muxFormat, String ext, String[] okVideo, String[] okAudio, String label) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            ex.setDataSource(this, videoUri, null);
            int vTrack = -1, aTrack = -1;
            MediaFormat vFmt = null, aFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (vTrack < 0 && mime.startsWith("video/")) {
                    vTrack = i;
                    vFmt = f;
                } else if (aTrack < 0 && mime.startsWith("audio/")) {
                    aTrack = i;
                    aFmt = f;
                }
            }
            if (vTrack < 0) throw new Exception("这个文件里没有视频轨道");

            String vMime = vFmt.getString(MediaFormat.KEY_MIME);
            if (!contains(okVideo, vMime)) {
                throw new Exception("视频编码是 " + vMime + "，无法放进 " + label + " 容器");
            }
            if (aTrack >= 0 && aFmt != null) {
                String aMime = aFmt.getString(MediaFormat.KEY_MIME);
                if (!contains(okAudio, aMime)) {
                    aTrack = -1;
                    aFmt = null;
                }
            }

            File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "." + ext));
            if (out.exists()) out.delete();
            muxer = new MediaMuxer(out.getAbsolutePath(), muxFormat);

            int vOut = muxer.addTrack(vFmt);
            int aOut = aTrack >= 0 ? muxer.addTrack(aFmt) : -1;
            muxer.start();

            ex.selectTrack(vTrack);
            if (aTrack >= 0) ex.selectTrack(aTrack);

            int maxIn = 1024 * 1024;
            if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxIn = Math.max(maxIn, vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBufferPool pool = new ByteBufferPool(maxIn);

            long total = 0, reported = 0;
            int lastTrack = -1;
            long lastTime = 0;
            while (true) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
                java.nio.ByteBuffer buf = pool.next();
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long time = ex.getSampleTime();
                if (t == lastTrack && time < lastTime) time = lastTime; // 时间戳必须单调
                bi.set(0, size, time, ex.getSampleFlags());
                muxer.writeSampleData(t == vTrack ? vOut : aOut, buf, bi);
                lastTrack = t;
                lastTime = time;
                total += size;
                ex.advance();
                if (total - reported > 524288) {
                    reported = total;
                    final long n = total;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvVideoStatus.setText("正在换封装… 已写入 " + Ui.size(n));
                        }
                    });
                }
            }
            muxer.stop();
            return out;
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /** 两个小缓冲轮换，避免每帧 new 一个 ByteBuffer */
    private static final class ByteBufferPool {
        private final java.nio.ByteBuffer a;
        private final java.nio.ByteBuffer b;
        private boolean flip = true;

        ByteBufferPool(int size) {
            a = java.nio.ByteBuffer.allocate(size);
            b = java.nio.ByteBuffer.allocate(size);
        }

        java.nio.ByteBuffer next() {
            flip = !flip;
            return flip ? a : b;
        }
    }

    /** FFmpeg 换壳：先试无损 -c copy，容器/编码不兼容再重编码 */
    private File remuxFfmpeg(String ext, boolean webm, String label) throws Exception {
        File in = ensureCache();
        File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "." + ext));
        if (out.exists()) out.delete();

        List<String> copy = new ArrayList<>();
        copy.add("-y");
        copy.add("-i");
        copy.add(in.getAbsolutePath());
        copy.add("-map");
        copy.add("0:v:0");
        copy.add("-map");
        copy.add("0:a:0?");
        copy.add("-c");
        copy.add("copy");
        if (!webm) {
            copy.add("-movflags");
            copy.add("+faststart");
        }
        copy.add(out.getAbsolutePath());

        FFmpegSession s1 = runFfmpeg(copy, "正在换封装（FFmpeg 无损拷贝）", 0);
        if (ReturnCode.isSuccess(s1.getReturnCode()) && out.exists() && out.length() > 0) {
            lastNote = "FFmpeg 无损换壳成 " + label + "，画面音质未改动";
            return out;
        }
        if (out.exists()) out.delete();

        List<String> enc = new ArrayList<>();
        enc.add("-y");
        enc.add("-i");
        enc.add(in.getAbsolutePath());
        enc.add("-map");
        enc.add("0:v:0");
        enc.add("-map");
        enc.add("0:a:0?");
        if (webm) {
            add(enc, "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-row-mt", "1");
            add(enc, "-c:a", "libopus", "-b:a", "128k");
        } else {
            add(enc, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p");
            add(enc, "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart");
        }
        enc.add(out.getAbsolutePath());

        FFmpegSession s2 = runFfmpeg(enc, "正在重编码成 " + label, 0);
        if (!ReturnCode.isSuccess(s2.getReturnCode()) || !out.exists() || out.length() == 0) {
            if (out.exists()) out.delete();
            throw new Exception("换壳失败，最后日志：" + tailLog(s2, 300));
        }
        lastNote = "源封装系统无法直接搬轨，已用 FFmpeg 重编码成 " + label + "（画质略有变化）";
        return out;
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    private static void add(List<String> list, String... items) {
        for (String s : items) list.add(s);
    }

    // ---------------------------------------------------------------- GIF

    private File makeGif() throws Exception {
        int fps = FPS_VALUES[Math.min(fpsChips.selected(), FPS_VALUES.length - 1)];
        int targetW = WIDTH_VALUES[Math.min(widthChips.selected(), WIDTH_VALUES.length - 1)];
        double start = parseSec(etGifStart, 0);
        double dur = parseSec(etGifDuration, 3);
        if (start < 0) start = 0;
        if (dur <= 0) throw new Exception("时长要大于 0 秒");
        if (dur > 600) throw new Exception("GIF 单次最多 10 分钟——再长体积会失控，建议改用「抽帧图片」或直接转成视频格式");
        // 放开 60 秒限制后按时长自动降档，避免动辄几百 MB 的 GIF
        String downgrade = "";
        if (dur > 60) {
            int fpsCap = dur > 300 ? 8 : 10;
            int wCap = dur > 300 ? 320 : 480;
            if (fps > fpsCap) { fps = fpsCap; downgrade += "帧率降到 " + fps + "fps"; }
            if (targetW > wCap) { targetW = wCap; downgrade += (downgrade.isEmpty() ? "" : "、") + "宽度降到 " + targetW + "px"; }
        }

        final long expectMs = (long) Math.round(dur * 1000);
        File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "_动图.gif"));
        if (out.exists()) out.delete();

        try {
            File in = ensureCache();
            List<String> a = new ArrayList<>();
            a.add("-y");
            a.add("-hide_banner");
            if (start > 0.01) {
                add(a, "-ss", num(start));
            }
            add(a, "-t", num(dur));
            add(a, "-i", in.getAbsolutePath());
            add(a, "-vf", "fps=" + fps
                    + ",scale=" + targetW + ":-2:flags=lanczos"
                    + ",split[a][b];[a]palettegen=max_colors=256:stats_mode=diff[p]"
                    + ";[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle");
            add(a, "-loop", "0", "-an");
            a.add(out.getAbsolutePath());

            FFmpegSession s = runFfmpeg(a, "正在生成 GIF（FFmpeg 调色板）", expectMs);
            if (!ReturnCode.isSuccess(s.getReturnCode()) || !out.exists() || out.length() == 0) {
                if (out.exists()) out.delete();
                throw new Exception(tailLog(s, 260));
            }
            lastNote = "GIF 动图 · 第 " + num(start) + "s 起 " + num(dur) + "s · " + fps + "fps · 宽 " + targetW + "px"
                    + (downgrade.isEmpty() ? "" : "（自动" + downgrade + "）");
            return out;
        } catch (Throwable e) {
            if (out.exists()) out.delete();
            try {
                File f = makeGifSystem(fps, targetW, start, dur, expectMs);
                lastNote = "GIF 动图 · 系统解码器版本 · " + fps + "fps · 宽 " + targetW + "px";
                return f;
            } catch (Throwable e2) {
                throw new Exception("GIF 失败：" + msgOf(e) + "；回退系统解码器也失败：" + msgOf(e2));
            }
        }
    }

    /** 回退：系统 MediaMetadataRetriever 逐帧取画 + 自带 GifWriter */
    private File makeGifSystem(int fps, int targetW, double start, double dur, long expectMs) throws Exception {
        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        FileOutputStream fos = null;
        try {
            ret.setDataSource(this, videoUri);
            long durMs = parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            if (durMs <= 0) throw new Exception("读不到视频时长");
            int rawW = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int rawH = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rot = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rawW <= 0 || rawH <= 0) throw new Exception("读不到视频分辨率");

            boolean swapped = rot == 90 || rot == 270;
            int dispW = swapped ? rawH : rawW;
            int dispH = swapped ? rawW : rawH;

            int outW = Math.min(targetW, dispW);
            if (outW < 2) outW = 2;
            int outH = Math.max(2, (int) Math.round(dispH * (outW * 1.0 / dispW)));
            if (outH > 1000) {
                outH = 1000;
                outW = Math.max(2, (int) Math.round(dispW * (outH * 1.0 / dispH)));
            }

            long segMs = Math.min((long) (dur * 1000), durMs - (long) (start * 1000));
            if (segMs <= 0) throw new Exception("起始时间超出了视频长度");
            int frames = (int) Math.round(segMs / 1000.0 * fps);
            // 系统解码器这条路是逐帧进 GIF 编码器，帧太多会占内存也慢；FFmpeg 那条没有这个限制
            frames = Math.max(1, frames);
            if (frames > 900) {
                throw new Exception("这段太长，系统解码器版本最多 900 帧（约 "
                        + Math.round(900.0 / fps) + " 秒），请缩短时长");
            }

            File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(videoName) + "_动图.gif"));
            fos = new FileOutputStream(out);
            GifWriter gif = new GifWriter(fos, outW, outH);
            int delay = Math.max(2, (int) Math.round(100.0 / fps));

            final int totalFrames = frames;
            for (int i = 0; i < frames; i++) {
                long tUs = (long) ((start + i * 1.0 / fps) * 1_000_000L);
                Bitmap bmp = grab(ret, tUs, outW, outH, rawW, rawH, rot);
                if (bmp == null) continue;
                if (bmp.getWidth() != outW || bmp.getHeight() != outH) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true);
                    if (scaled != bmp) bmp.recycle();
                    bmp = scaled;
                }
                int[] px = new int[outW * outH];
                bmp.getPixels(px, 0, outW, 0, 0, outW, outH);
                bmp.recycle();
                gif.addFrame(px, delay);
                final int done = i + 1;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvVideoStatus.setText("正在生成 GIF（系统解码器）… " + done + " / " + totalFrames + " 帧");
                    }
                });
            }
            gif.finish();
            return out;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { ret.release(); } catch (Exception ignored) {}
        }
    }

    /** 取一帧并纠正方向（部分机型取出的帧已经旋转过，用尺寸判断） */
    private Bitmap grab(MediaMetadataRetriever ret, long tUs, int outW, int outH,
                        int rawW, int rawH, int rot) {
        Bitmap bmp;
        try {
            if (rot == 0 && Build.VERSION.SDK_INT >= 27) {
                bmp = ret.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH);
            } else {
                bmp = ret.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
            }
        } catch (Throwable e) {
            try {
                bmp = ret.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable e2) {
                return null;
            }
        }
        if (bmp == null) return null;
        int needRot = 0;
        if (rot == 180) {
            needRot = 180;
        } else if (rot == 90 || rot == 270) {
            boolean alreadyRotated = bmp.getWidth() == rawH && bmp.getHeight() == rawW;
            if (!alreadyRotated) needRot = rot;
        }
        if (needRot == 0) return bmp;
        try {
            Matrix m = new Matrix();
            m.postRotate(needRot);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            return r;
        } catch (Throwable e) {
            return bmp;
        }
    }

    // ---------------------------------------------------------------- 抽帧

    private List<File> exportFrames() throws Exception {
        try {
            return exportFramesSystem();
        } catch (Throwable e) {
            return exportFramesFfmpeg(msgOf(e));
        }
    }

    private List<File> exportFramesSystem() throws Exception {
        double interval = INTERVAL_VALUES[Math.min(intervalChips.selected(), INTERVAL_VALUES.length - 1)];
        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        List<File> files = new ArrayList<>();
        try {
            ret.setDataSource(this, videoUri);
            long durMs = parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            if (durMs <= 0) throw new Exception("读不到视频时长");
            int rawW = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int rawH = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rot = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rawW <= 0 || rawH <= 0) throw new Exception("读不到视频分辨率");

            File dir = new File(Ui.outDir(this), Ui.baseName(videoName) + "_帧图");
            if (!dir.exists()) dir.mkdirs();

            int count = (int) Math.floor(durMs / (interval * 1000.0));
            count = Math.max(1, Math.min(300, count));
            int targets = count;
            for (int i = 0; i < count; i++) {
                long tUs = (long) (i * interval * 1_000_000L);
                Bitmap bmp = grab(ret, tUs, rawW, rawH, rawW, rawH, rot);
                if (bmp == null) continue;
                if (bmp.getWidth() > 1920) {
                    int w2 = 1920;
                    int h2 = Math.max(1, (int) Math.round(bmp.getHeight() * (w2 * 1.0 / bmp.getWidth())));
                    Bitmap sc = Bitmap.createScaledBitmap(bmp, w2, h2, true);
                    if (sc != bmp) bmp.recycle();
                    bmp = sc;
                }
                File f = new File(dir, String.format(java.util.Locale.US, "帧%03d.jpg", i + 1));
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                } finally {
                    try { fos.close(); } catch (Exception ignored) {}
                }
                bmp.recycle();
                files.add(f);
                final int done = i + 1;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvVideoStatus.setText("正在抽帧… " + done + " / " + targets);
                    }
                });
            }
            if (files.isEmpty()) throw new Exception("没有取到任何画面");
            return files;
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
    }

    /** FFmpeg 抽帧：系统解不了的封装（RMVB / VOB / WMV）走这条路 */
    private List<File> exportFramesFfmpeg(String reason) throws Exception {
        double interval = INTERVAL_VALUES[Math.min(intervalChips.selected(), INTERVAL_VALUES.length - 1)];
        File in = ensureCache();
        File dir = new File(Ui.outDir(this), Ui.baseName(videoName) + "_帧图");
        if (!dir.exists()) dir.mkdirs();
        File[] olds = dir.listFiles();
        if (olds != null) for (File f : olds) f.delete();

        double rate = 1.0 / interval;
        List<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-hide_banner");
        a.add("-i");
        a.add(in.getAbsolutePath());
        add(a, "-vf", "fps=" + String.format(java.util.Locale.US, "%.4f", rate),
                "-q:v", "3", "-f", "image2");
        a.add(new File(dir, "p%03d.jpg").getAbsolutePath());

        FFmpegSession s = runFfmpeg(a, "正在抽帧（FFmpeg 内核）", 0);
        if (!ReturnCode.isSuccess(s.getReturnCode())) {
            throw new Exception("系统解码器不行（" + reason + "），FFmpeg 抽帧也失败：" + tailLog(s, 240));
        }
        List<File> files = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            File raw = new File(dir, String.format(java.util.Locale.US, "p%03d.jpg", i));
            if (!raw.exists()) break;
            File dst = new File(dir, String.format(java.util.Locale.US, "帧%03d.jpg", i));
            if (dst.exists()) dst.delete();
            if (!raw.renameTo(dst)) {
                dst = raw;
            }
            files.add(dst);
        }
        // 多出来的图删掉，和系统路径一样最多 300 张
        int extra = 301;
        while (true) {
            File raw = new File(dir, String.format(java.util.Locale.US, "p%03d.jpg", extra));
            if (!raw.exists()) break;
            raw.delete();
            extra++;
        }
        if (files.isEmpty()) throw new Exception("FFmpeg 没有输出任何帧");
        return files;
    }

    private static double parseSec(EditText et, double def) {
        if (et == null) return def;
        String s = et.getText() == null ? "" : et.getText().toString().trim();
        if (TextUtils.isEmpty(s)) return def;
        s = s.replace('。', '.').replace(',', '.').replace('，', '.');
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
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

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 1.6.1：旋转不再重建页面，这里只把平板居中列的宽度按新屏幕重算一次
        Ui.refreshTabletLayout(this);
    }
}
