package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fmtlab.toolbox.core.Ffmts;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 音视频提取：解码为 WAV，或直接抽出 M4A 音轨 */
public class AudioActivity extends Activity {

    private static final int REQ_PICK = 61;

    private static final String[] OUT_LABELS = {"WAV", "M4A", "AAC 裸流", "MP3", "FLAC", "OGG", "OPUS", "WMA"};
    private static final String[] OUT_EXT = {"wav", "m4a", "aac", "mp3", "flac", "ogg", "opus", "wma"};

    private TextView tvMediaInfo, tvAudioStatus;
    private LinearLayout audioResultHost;
    private ProgressBar pbAudio;
    private Button btnAudioConvert;
    private Ui.Chips outChips;

    private Uri mediaUri;
    private String mediaName = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio);
        Ui.centerTabletContent(this);
        exec = Executors.newSingleThreadExecutor();

        ((TextView) findViewById(R.id.tvTitle)).setText("音频提取");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("视频 / 音频转 WAV · M4A · AAC 裸流");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvMediaInfo = (TextView) findViewById(R.id.tvMediaInfo);
        tvAudioStatus = (TextView) findViewById(R.id.tvAudioStatus);
        audioResultHost = (LinearLayout) findViewById(R.id.audioResultHost);
        pbAudio = (ProgressBar) findViewById(R.id.pbAudio);
        btnAudioConvert = (Button) findViewById(R.id.btnAudioConvert);
        outChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.audioOutChips), OUT_LABELS, 0);

        findViewById(R.id.btnPickMedia).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Picker.launch(AudioActivity.this, REQ_PICK, false);
            }
        });

        btnAudioConvert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                convert();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && Picker.firstUri(data) != null) {
            mediaUri = Picker.firstUri(data);
            mediaName = Ui.displayName(this, mediaUri);
            tvMediaInfo.setText("已选择：" + mediaName + "\n正在读取媒体信息…");
            loadInfo();
        }
    }

    private void loadInfo() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String info = describe(mediaUri);
                final String f = info;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvMediaInfo.setText("已选择：" + mediaName + "\n" + f);
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
            if (dur != null) {
                long ms = Long.parseLong(dur);
                sb.append("时长 ").append(fmtTime(ms));
            }
        } catch (Exception e) {
            sb.append("无法读取时长");
        } finally {
            try { ret.release(); } catch (Exception ignored) {}
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, null);
            int audio = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audio++;
                    if (audio == 1) {
                        sb.append(" · 音轨 ").append(mime);
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sb.append(" · ").append(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)).append(" Hz");
                        }
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            sb.append(" · ").append(f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).append(" 声道");
                        }
                    }
                }
            }
            sb.append(" · 共 ").append(ex.getTrackCount()).append(" 条轨道");
            if (audio == 0) sb.append("\n注意：这个文件里没有音频轨道");
        } catch (Exception e) {
            sb.append("\n读取轨道失败");
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

    private void convert() {
        if (mediaUri == null) {
            Ui.toast(this, "请先选择音频或视频文件");
            return;
        }
        final int mode = outChips.selected();
        pbAudio.setVisibility(View.VISIBLE);
        btnAudioConvert.setEnabled(false);
        tvAudioStatus.setText("正在处理…");
        audioResultHost.removeAllViews();

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File out;
                    if (mode == 0) {
                        out = extractWav(mediaUri, mediaName);
                    } else if (mode == 1) {
                        out = extractM4a(mediaUri, mediaName);
                    } else if (mode == 2) {
                        out = extractAdts(mediaUri, mediaName);
                    } else {
                        out = ffmpegAudio(mediaUri, mediaName, OUT_EXT[mode]);
                    }
                    final File fout = out;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbAudio.setVisibility(View.GONE);
                            btnAudioConvert.setEnabled(true);
                            tvAudioStatus.setText("完成：" + fout.getName() + " · " + Ui.size(fout.length()));
                            audioResultHost.addView(Ui.resultRow(AudioActivity.this, fout,
                                    Ui.size(fout.length()) + " · 已保存到应用目录",
                                    new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            List<File> one = new ArrayList<>();
                                            one.add(fout);
                                            Ui.shareFiles(AudioActivity.this, one, "提取的音频");
                                        }
                                    }));
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbAudio.setVisibility(View.GONE);
                            btnAudioConvert.setEnabled(true);
                            tvAudioStatus.setText("失败：" + msg);
                        }
                    });
                }
            }
        });
    }

    /** 解码成 16bit PCM 并写成标准 WAV */
    private File extractWav(Uri uri, String name) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        RandomAccessFile raf = null;
        int sampleRate = 44100, channels = 2;
        try {
            ex.setDataSource(this, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) throw new Exception("这个文件里没有音频轨道");

            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = fmt.getString(MediaFormat.KEY_MIME);

            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            String outName = uniqueName(Ui.baseName(name) + "_提取.wav");
            File out = new File(Ui.outDir(this), outName);
            raf = new RandomAccessFile(out, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long totalBytes = 0, reported = 0;
            int idle = 0;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int size = 0;
                        if (ib != null) {
                            ib.clear();
                            size = ex.readSampleData(ib, 0);
                        }
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    idle++;
                    if (inputDone && idle > 2000) break;
                } else {
                    idle = 0;
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] data = new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.get(data);
                        raf.write(data);
                        totalBytes += info.size;
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
                final long done = totalBytes;
                if (done - reported > 262144) {
                    reported = done;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAudioStatus.setText("正在解码… 已写入 " + Ui.size(done));
                        }
                    });
                }
            }

            patchWavHeader(raf, totalBytes, sampleRate, channels);
            return out;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private void patchWavHeader(RandomAccessFile raf, long dataLen, int sampleRate, int channels) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes("US-ASCII"));
        bb.putInt((int) (36 + dataLen));
        bb.put("WAVE".getBytes("US-ASCII"));
        bb.put("fmt ".getBytes("US-ASCII"));
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * channels * 2);
        bb.putShort((short) (channels * 2));
        bb.putShort((short) 16);
        bb.put("data".getBytes("US-ASCII"));
        bb.putInt((int) dataLen);
        raf.seek(0);
        raf.write(bb.array());
        raf.getFD().sync();
    }

    /** 直接把原音轨封装成 m4a（不重新编码） */
    private File extractM4a(Uri uri, String name) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            ex.setDataSource(this, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) throw new Exception("这个文件里没有音频轨道");
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (!"audio/mp4a-latm".equals(mime)) {
                throw new Exception("音轨是 " + mime + "，不是 AAC，无法直接封装 M4A，请改用 WAV");
            }
            ex.selectTrack(track);
            String outName = uniqueName(Ui.baseName(name) + "_音轨.m4a");
            File out = new File(Ui.outDir(this), outName);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(fmt);
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long total = 0;
            while (true) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                bi.set(0, size, ex.getSampleTime(), ex.getSampleFlags());
                muxer.writeSampleData(outTrack, buf, bi);
                total += size;
                ex.advance();
                final long done = total;
                if ((done & 0xFFFFF) < 8192) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAudioStatus.setText("正在提取音轨… 已复制 " + Ui.size(done));
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

    /** 把 AAC 音轨写成 ADTS 裸流（.aac），每帧前补 7 字节头 */
    private File extractAdts(Uri uri, String name) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        RandomAccessFile raf = null;
        try {
            ex.setDataSource(this, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) throw new Exception("这个文件里没有音频轨道");
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (!"audio/mp4a-latm".equals(mime)) {
                throw new Exception("音轨是 " + mime + "，只有 AAC 音轨能导出裸流，请改用 WAV");
            }

            int profile = 1, freqIdx = 4, chCfg = 2;
            byte[] csd = null;
            if (fmt.containsKey("csd-0")) {
                ByteBuffer cb = fmt.getByteBuffer("csd-0");
                if (cb != null) {
                    csd = new byte[cb.remaining()];
                    cb.get(csd);
                }
            }
            if (csd != null && csd.length >= 2) {
                int aot = (csd[0] >> 3) & 0x1F;
                profile = Math.max(0, Math.min(3, aot - 1));
                freqIdx = ((csd[0] & 0x07) << 1) | ((csd[1] >> 7) & 0x01);
                chCfg = (csd[1] >> 3) & 0x0F;
            } else {
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    freqIdx = freqIndexOf(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                }
                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    chCfg = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            if (freqIdx < 0 || freqIdx > 12) throw new Exception("采样率异常，无法生成 ADTS 头");
            if (chCfg <= 0 || chCfg > 7) chCfg = 2;

            File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(name) + "_音轨.aac"));
            raf = new RandomAccessFile(out, "rw");
            raf.setLength(0);

            ex.selectTrack(track);
            ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
            long total = 0, reported = 0;
            while (true) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                int frameLen = size + 7;
                byte[] h = new byte[7];
                h[0] = (byte) 0xFF;
                h[1] = (byte) 0xF1; // MPEG-4, layer 0, 无 CRC
                h[2] = (byte) (((profile & 0x03) << 6) | ((freqIdx & 0x0F) << 2) | ((chCfg >> 2) & 0x01));
                h[3] = (byte) (((chCfg & 0x03) << 6) | ((frameLen >> 11) & 0x03));
                h[4] = (byte) ((frameLen >> 3) & 0xFF);
                h[5] = (byte) (((frameLen & 0x07) << 5) | 0x1F);
                h[6] = (byte) 0xFC;
                raf.write(h);
                byte[] data = new byte[size];
                buf.position(0);
                buf.limit(size);
                buf.get(data);
                raf.write(data);
                total += size;
                ex.advance();
                if (total - reported > 262144) {
                    reported = total;
                    final long n = total;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAudioStatus.setText("正在导出 AAC… 已写入 " + Ui.size(n));
                        }
                    });
                }
            }
            return out;
        } finally {
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /** MP3 / FLAC / OGG / OPUS / WMA 等走 FFmpeg 内核 */
    private File ffmpegAudio(Uri uri, String name, String ext) throws Exception {
        final String stageExt = Ui.ext(name);
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvAudioStatus.setText("正在读取文件到本机缓存…");
            }
        });
        File in = Ffmts.stage(this, uri, stageExt);
        File out = new File(Ui.outDir(this), uniqueName(Ui.baseName(name) + "_提取." + ext));
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvAudioStatus.setText("FFmpeg 正在转成 " + ext.toUpperCase() + " …");
            }
        });
        Ffmts.run(Ffmts.audioArgs(in, out, ext, 192));
        return out;
    }

    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350};

    private static int freqIndexOf(int sampleRate) {
        int best = 4;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < AAC_RATES.length; i++) {
            int d = Math.abs(AAC_RATES[i] - sampleRate);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
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
