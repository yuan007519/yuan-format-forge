package com.fmtlab.toolbox.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕解析与生成：srt / vtt / ass / ssa 四种格式互转，外加纯文本导出。
 * 只保留时间轴与文字，样式、定位、字体等元信息在转换时丢弃（格式间无法一一对应）。
 */
public final class Subtitle {

    public static final class Cue {
        public long startMs;
        public long endMs;
        public String text = "";

        Cue(long s, long e, String t) {
            startMs = s;
            endMs = e;
            text = t;
        }
    }

    private static final Pattern TIME_SRT = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern TIME_VTT = TIME_SRT;
    private static final Pattern TIME_ASS = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})\\.(\\d{1,3})");
    private static final Pattern TAG = Pattern.compile("\\{[^}]*}");

    private Subtitle() {}

    public static String kind(String ext) {
        if (ext == null) return "";
        String e = ext.toLowerCase();
        if (e.equals("srt")) return "srt";
        if (e.equals("vtt")) return "vtt";
        if (e.equals("ass")) return "ass";
        if (e.equals("ssa")) return "ssa";
        return "";
    }

    public static List<Cue> parse(String content, String kind) {
        if ("ass".equals(kind) || "ssa".equals(kind)) return parseAss(content);
        return parseText(content, "vtt".equals(kind));
    }

    /** 把若干毫秒串解析成 srt / vtt 字幕 */
    private static List<Cue> parseText(String content, boolean vtt) {
        List<Cue> out = new ArrayList<Cue>();
        String normalized = content.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int timeLine = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length && i < 4; i++) {
                m = TIME_SRT.matcher(lines[i]);
                if (m.find()) {
                    timeLine = i;
                    break;
                }
            }
            if (m == null || timeLine < 0) continue;
            long start = ms(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder sb = new StringBuilder();
            for (int i = timeLine + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION")) continue;
                if (line.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(cleanInline(line));
            }
            if (sb.length() == 0) continue;
            out.add(new Cue(start, end, sb.toString()));
        }
        return out;
    }

    private static List<Cue> parseAss(String content) {
        List<Cue> out = new ArrayList<Cue>();
        String normalized = content.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        boolean inEvents = false;
        String[] fields = null;
        for (String raw : normalized.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inEvents = line.toLowerCase().startsWith("[events]");
                continue;
            }
            if (!inEvents) continue;
            if (line.toLowerCase().startsWith("format:")) {
                fields = line.substring(7).split(",");
                for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim().toLowerCase();
                continue;
            }
            if (!line.toLowerCase().startsWith("dialogue:")) continue;
            if (fields == null) fields = new String[]{"layer", "start", "end", "style", "name",
                    "marginl", "marginr", "marginv", "effect", "text"};
            String body = line.substring(line.indexOf(':') + 1);
            String[] parts = body.split(",", fields.length);
            if (parts.length < fields.length) continue;
            int iStart = indexOf(fields, "start");
            int iEnd = indexOf(fields, "end");
            int iText = indexOf(fields, "text");
            if (iStart < 0 || iEnd < 0 || iText < 0) continue;
            long st = assTime(parts[iStart].trim());
            long en = assTime(parts[iEnd].trim());
            if (st < 0 || en < 0) continue;
            String text = TAG.matcher(parts[iText]).replaceAll("").replace("\\N", "\n").replace("\\n", "\n").trim();
            if (text.isEmpty()) continue;
            out.add(new Cue(st, en, text));
        }
        return out;
    }

    private static int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) if (key.equals(arr[i])) return i;
        return -1;
    }

    private static String cleanInline(String line) {
        String s = line;
        s = s.replaceAll("<[^>]+>", "");
        s = s.replaceAll("\\{\\\\[^}]*}", "");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.trim();
    }

    private static long ms(String h, String m, String s, String frac) {
        long f = Long.parseLong(pad(frac, 3));
        return (Long.parseLong(h) * 3600 + Long.parseLong(m) * 60 + Long.parseLong(s)) * 1000 + f;
    }

    private static long assTime(String t) {
        Matcher m = TIME_ASS.matcher(t);
        if (!m.find()) return -1;
        long cs = Long.parseLong(pad(m.group(4), 3));
        return (Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60
                + Long.parseLong(m.group(3))) * 1000 + cs;
    }

    private static String pad(String v, int len) {
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < len) sb.append('0');
        return sb.substring(0, len);
    }

    // ------------------------------------------------------------ 输出

    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            sb.append(i + 1).append('\n');
            sb.append(stampSrt(c.startMs)).append(" --> ").append(stampSrt(c.endMs)).append('\n');
            sb.append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    public static String toVtt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            sb.append(stampVtt(c.startMs)).append(" --> ").append(stampVtt(c.endMs)).append('\n');
            sb.append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    public static String toAss(List<Cue> cues, boolean ssa) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Script Info]\nTitle: 袁神的格式工坊\nScriptType: v4.00")
                .append(ssa ? "" : "+").append('\n')
                .append("WrapStyle: 0\nPlayResX: 1080\nPlayResY: 1920\n\n");
        sb.append(ssa ? "[V4 Styles]\n" : "[V4+ Styles]\n");
        if (ssa) {
            sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, "
                    + "BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, "
                    + "MarginV, AlphaLevel, Encoding\n");
            sb.append("Style: Default,Arial,48,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,"
                    + "0,0,1,2,1,2,20,20,20,0,134\n\n");
        } else {
            sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                    + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, "
                    + "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
            sb.append("Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,"
                    + "0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1\n\n");
        }
        sb.append("[Events]\n");
        sb.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Cue c : cues) {
            sb.append("Dialogue: 0,").append(stampAss(c.startMs)).append(',').append(stampAss(c.endMs))
                    .append(",Default,,0,0,0,,")
                    .append(c.text.replace("\n", "\\N"))
                    .append('\n');
        }
        return sb.toString();
    }

    public static String toPlainText(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : cues) {
            String line = c.text.replace("\n", " ").trim();
            if (!line.isEmpty()) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String stampSrt(long ms) {
        long h = ms / 3600000;
        long m = ms % 3600000 / 60000;
        long s = ms % 60000 / 1000;
        long f = ms % 1000;
        return two(h) + ":" + two(m) + ":" + two(s) + "," + String.format("%03d", f);
    }

    private static String stampVtt(long ms) {
        return stampSrt(ms).replace(',', '.');
    }

    private static String stampAss(long ms) {
        long h = ms / 3600000;
        long m = ms % 3600000 / 60000;
        long s = ms % 60000 / 1000;
        long c = ms % 1000 / 10;
        return h + ":" + two(m) + ":" + two(s) + "." + two(c);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
