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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fmtlab.toolbox.core.GifWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 视频：换封装（MP4/WebM）、转 GIF、抽帧 */
public class VideoActivity extends Activity {

    private static final int REQ_PICK = 71;
    private static final int REQ_DIR = 72;

    private static final String[] OUT_LABELS = {"MP4 换壳", "WebM 换壳", "GIF 动图", "抽帧图片"};
    private static final String[] FPS_LABELS = {"8 帧/秒", "12 帧/秒", "15 帧/秒"};
    private static final int[] FPS_VALUES = {8, 12, 15};
    private static final String[] WIDTH_LABELS = {"320 px", "480 px", "640 px"};
    private static final int[] WIDTH_VALUES = {320, 480, 640};
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
    private Ui.Chips outChips, fpsChips, widthChips, intervalChips;

    private Uri videoUri;
    private String videoName = "";
    private final List<File> lastFiles = new ArrayList<>();
    private String lastSubject = "转换结果";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("视频工具");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("换封装 · 转 GIF · 抽帧，全部本机完成");
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

        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.videoOutChips), OUT_LABELS, 0);
        fpsChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.gifFpsChips), FPS_LABELS, 1);
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
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*"});
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    Ui.toast(VideoActivity.this, "无法打开文件选择器");
                }
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
        gifParamCard.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        frameParamCard.setVisibility(mode == 3 ? View.VISIBLE : View.GONE);
        switch (mode) {
            case 0:
                tvOutHint.setText("换壳：画面和音质完全不变，只换容器，几秒完成、零画质损失。适合把 mkv / mov / ts / flv 变成更通用的 MP4。");
                break;
            case 1:
                tvOutHint.setText("WebM 换壳只对 VP8 / VP9 编码的视频有效；H.264 的视频请用 MP4，否则系统无法封装。");
                break;
            case 2:
                tvOutHint.setText("GIF：逐帧取画并量化为 256 色，适合做表情包、教程动图；长视频会很慢、文件也大。");
                break;
            default:
                tvOutHint.setText("抽帧：按固定间隔导出 JPG 图片，用来做封面、截图集、分镜参考。");
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            videoUri = data.getData();
            videoName = Ui.displayName(this, videoUri);
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
            sb.append("无法读取时长");
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }

        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, null);
            int video = 0, audio = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    video++;
                    if (video == 1) sb.append(" · 视频 ").append(mime);
                } else if (mime.startsWith("audio/")) {
                    audio++;
                    if (audio == 1) sb.append(" · 音频 ").append(mime);
                }
            }
            if (video == 0) sb.append("\n这个文件里没有视频轨道");
            else sb.append("\n共 ").append(ex.getTrackCount()).append(" 条轨道");
        } catch (Exception e) {
            sb.append("\n系统解不开这个封装（WMV / RMVB / 老 AVI 等需要 FFmpeg 内核），本版本不支持");
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
        tvVideoStatus.setText("准备中…");

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mode == 0) {
                        File f = remux(videoUri, videoName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                                "mp4", MP4_VIDEO, MP4_AUDIO, "MP4");
                        doneOne(f, "视频容器已换成 MP4，画面音质未改动");
                    } else if (mode == 1) {
                        File f = remux(videoUri, videoName, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                                "webm", WEBM_VIDEO, WEBM_AUDIO, "WebM");
                        doneOne(f, "视频容器已换成 WebM，画面音质未改动");
                    } else if (mode == 2) {
                        File f = makeGif(videoUri, videoName);
                        doneOne(f, "GIF 动图");
                    } else {
                        List<File> files = exportFrames(videoUri, videoName);
                        doneMany(files, "抽帧图片");
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
        lastSubject = meta;
        handler.post(new Runnable() {
            @Override
            public void run() {
                pbVideo.setVisibility(View.GONE);
                btnVideoConvert.setEnabled(true);
                btnVideoSaveAll.setVisibility(View.VISIBLE);
                tvVideoStatus.setText("完成：" + f.getName() + " · " + Ui.size(f.length()));
                videoResultHost.addView(Ui.resultRow(VideoActivity.this, f, meta + " · " + Ui.size(f.length()),
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
        lastSubject = meta;
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

    // ---------------------------------------------------------------- 换封装

    private File remux(Uri uri, String name, int muxFormat, String ext,
                       String[] okVideo, String[] okAudio, String label) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            ex.setDataSource(this, uri, null);
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
                throw new Exception("视频编码是 " + vMime + "，无法放进 " + label + " 容器"
                        + (label.equals("WebM") ? "（H.264 请改用 MP4）" : "（VP8/VP9 请改用 WebM）"));
            }
            if (aTrack >= 0 && aFmt != null) {
                String aMime = aFmt.getString(MediaFormat.KEY_MIME);
                if (!contains(okAudio, aMime)) {
                    // 音频不兼容就只封视频轨，避免整单失败
                    aTrack = -1;
                    aFmt = null;
                }
            }

            String outName = uniqueName(Ui.baseName(name) + "." + ext);
            File out = new File(Ui.outDir(this), outName);
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
            ByteBuffer buf = ByteBuffer.allocate(maxIn);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            long total = 0, reported = 0;
            int lastTrack = -1;
            long lastTime = 0;
            while (true) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
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

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    // ---------------------------------------------------------------- GIF

    private File makeGif(Uri uri, String name) throws Exception {
        int fps = FPS_VALUES[Math.min(fpsChips.selected(), FPS_VALUES.length - 1)];
        int targetW = WIDTH_VALUES[Math.min(widthChips.selected(), WIDTH_VALUES.length - 1)];

        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        FileOutputStream fos = null;
        try {
            ret.setDataSource(this, uri);
            long durMs = parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            if (durMs <= 0) throw new Exception("读不到视频时长，无法转 GIF（试试先换壳成 MP4）");
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

            int frames = (int) Math.round(durMs / 1000.0 * fps);
            frames = Math.max(1, Math.min(120, frames));

            File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(name) + "_动图.gif"));
            fos = new FileOutputStream(out);
            GifWriter gif = new GifWriter(fos, outW, outH);
            int delay = Math.max(2, (int) Math.round(100.0 / fps));

            final int totalFrames = frames;
            for (int i = 0; i < frames; i++) {
                long tUs = (long) (i * 1_000_000L / fps);
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
                        tvVideoStatus.setText("正在生成 GIF… " + done + " / " + totalFrames + " 帧");
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

    private List<File> exportFrames(Uri uri, String name) throws Exception {
        double interval = INTERVAL_VALUES[Math.min(intervalChips.selected(), INTERVAL_VALUES.length - 1)];
        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        List<File> files = new ArrayList<>();
        try {
            ret.setDataSource(this, uri);
            long durMs = parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            if (durMs <= 0) throw new Exception("读不到视频时长");
            int rawW = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int rawH = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rot = (int) parseLong(ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rawW <= 0 || rawH <= 0) throw new Exception("读不到视频分辨率");

            File dir = new File(Ui.outDir(this), Ui.baseName(name) + "_帧图");
            if (!dir.exists()) dir.mkdirs();

            int count = (int) Math.floor(durMs / (interval * 1000.0));
            count = Math.max(1, Math.min(60, count));
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
            if (files.isEmpty()) throw new Exception("没有取到任何画面，这个视频可能系统解不了");
            return files;
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
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
}
