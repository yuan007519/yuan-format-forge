package com.fmtlab.toolbox.core;

import android.content.Context;
import android.net.Uri;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpeg 通用转换引擎：音频 / 视频 / 图片的格式矩阵都走这里。
 * 统一约定：先把 Uri 复制到缓存目录变成真实路径，再用 -y 覆盖输出，失败时把日志尾部当异常信息抛出。
 */
public final class Ffmts {

    /** 音频输出格式：标签 → 扩展名 → 编码器参数 */
    public static final String[] AUDIO_FORMATS = {"mp3", "wav", "flac", "m4a", "ogg", "aac", "opus", "wma"};
    /** 视频容器 */
    public static final String[] VIDEO_FORMATS = {"mp4", "mkv", "mov", "webm", "gif"};
    /** 图片输出：Bitmap 走主路径，FFmpeg 兜底 */
    public static final String[] IMAGE_FORMATS = {"png", "jpg", "webp", "bmp", "tiff", "tga", "qoi", "ico", "ppm"};

    private Ffmts() {}

    /** 把选中的 Uri 复制到缓存目录，返回真实文件；文件名固定为 input.<ext> */
    public static File stage(Context c, Uri uri, String ext) throws IOException {
        File dir = new File(c.getCacheDir(), "matrix");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建缓存目录");
        File out = new File(dir, "input" + (ext == null || ext.isEmpty() ? "" : "." + ext));
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取选中的文件");
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    public static void clearStage(Context c) {
        File dir = new File(c.getCacheDir(), "matrix");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** 同步执行 ffmpeg，成功返回空字符串，失败抛出带日志尾部的 IOException */
    public static String run(List<String> args) throws IOException {
        List<String> full = new ArrayList<String>();
        full.add("-hide_banner");
        full.add("-y");
        full.addAll(args);
        FFmpegSession session = FFmpegKit.executeWithArguments(full.toArray(new String[0]));
        if (ReturnCode.isSuccess(session.getReturnCode())) return "";
        String log = session.getAllLogsAsString();
        if (log == null) log = "";
        String tail = log.length() > 1400 ? log.substring(log.length() - 1400) : log;
        throw new IOException(tail.trim().isEmpty() ? "FFmpeg 执行失败" : tail.trim());
    }

    /** 音频：按输出扩展名选编码器 */
    public static List<String> audioArgs(File in, File out, String ext, int bitrateKbps) {
        List<String> a = new ArrayList<String>();
        a.add("-i");
        a.add(in.getAbsolutePath());
        a.add("-vn");
        String e = ext.toLowerCase();
        if (e.equals("mp3")) {
            a.add("-c:a"); a.add("libmp3lame"); a.add("-b:a"); a.add(bitrateKbps + "k");
        } else if (e.equals("wav")) {
            a.add("-c:a"); a.add("pcm_s16le");
        } else if (e.equals("flac")) {
            a.add("-c:a"); a.add("flac");
        } else if (e.equals("m4a")) {
            a.add("-c:a"); a.add("aac"); a.add("-b:a"); a.add(bitrateKbps + "k");
        } else if (e.equals("aac")) {
            a.add("-c:a"); a.add("aac"); a.add("-b:a"); a.add(bitrateKbps + "k"); a.add("-f"); a.add("adts");
        } else if (e.equals("ogg")) {
            a.add("-c:a"); a.add("libvorbis"); a.add("-q:a"); a.add("5");
        } else if (e.equals("opus")) {
            a.add("-c:a"); a.add("libopus"); a.add("-b:a"); a.add(Math.min(bitrateKbps, 160) + "k");
        } else if (e.equals("wma")) {
            a.add("-c:a"); a.add("wmav2"); a.add("-b:a"); a.add(bitrateKbps + "k");
        } else {
            a.add("-c:a"); a.add("aac");
        }
        a.add(out.getAbsolutePath());
        return a;
    }

    /** 视频容器：gif 走调色板，mp4/mkv/mov 用 H.264，webm 用 VP9 + Opus */
    public static List<String> videoArgs(File in, File out, String ext, String quality, Integer targetW) {
        List<String> a = new ArrayList<String>();
        a.add("-i");
        a.add(in.getAbsolutePath());
        String crf = "23";
        if ("high".equals(quality)) crf = "19";
        else if ("low".equals(quality)) crf = "28";
        String e = ext.toLowerCase();
        if (e.equals("gif")) {
            StringBuilder vf = new StringBuilder();
            if (targetW != null && targetW > 0) vf.append("scale=").append(targetW).append(":-2:flags=lanczos,");
            vf.append("fps=15,split[a][b];[a]palettegen=max_colors=256:stats_mode=diff[p];")
              .append("[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle");
            a.add("-vf"); a.add(vf.toString());
            a.add("-loop"); a.add("0");
            a.add("-an");
        } else if (e.equals("webm")) {
            a.add("-c:v"); a.add("libvpx-vp9");
            a.add("-crf"); a.add("32");
            a.add("-b:v"); a.add("0");
            a.add("-row-mt"); a.add("1");
            a.add("-c:a"); a.add("libopus"); a.add("-b:a"); a.add("128k");
        } else {
            a.add("-c:v"); a.add("libx264");
            a.add("-preset"); a.add("veryfast");
            a.add("-crf"); a.add(crf);
            a.add("-pix_fmt"); a.add("yuv420p");
            a.add("-c:a"); a.add("aac"); a.add("-b:a"); a.add("160k");
            if (e.equals("mp4") || e.equals("mov")) {
                a.add("-movflags"); a.add("+faststart");
            }
        }
        a.add(out.getAbsolutePath());
        return a;
    }

    /** 图片：FFmpeg 一口气转换成任意目标格式（qoi / tga / tiff 等系统 Bitmap 不支持） */
    public static List<String> imageArgs(File in, File out, String ext, int quality) {
        List<String> a = new ArrayList<String>();
        a.add("-i");
        a.add(in.getAbsolutePath());
        String e = ext.toLowerCase();
        if (e.equals("jpg") || e.equals("jpeg")) {
            a.add("-q:v"); a.add(String.valueOf(Math.max(2, 31 - quality / 4)));
        } else if (e.equals("webp")) {
            a.add("-c:v"); a.add("libwebp"); a.add("-quality"); a.add(String.valueOf(quality));
        } else if (e.equals("png")) {
            a.add("-c:v"); a.add("png");
        } else if (e.equals("tiff")) {
            a.add("-c:v"); a.add("tiff"); a.add("-compression_algo"); a.add("deflate");
        } else if (e.equals("qoi")) {
            a.add("-c:v"); a.add("qoi");
        } else if (e.equals("bmp")) {
            a.add("-c:v"); a.add("bmp");
        } else if (e.equals("tga")) {
            a.add("-c:v"); a.add("targa");
        } else if (e.equals("ico")) {
            a.add("-c:v"); a.add("bmp");
        } else if (e.equals("ppm")) {
            a.add("-c:v"); a.add("ppm");
        }
        a.add(out.getAbsolutePath());
        return a;
    }

    /** 音轨导出（视频 → 音频） */
    public static List<String> extractAudioArgs(File in, File out, String ext, int bitrateKbps) {
        List<String> a = audioArgs(in, out, ext, bitrateKbps);
        // audioArgs 里已经带了 -vn，视频输入可直接复用
        return a;
    }
}
