package com.fmtlab.toolbox.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文本乱码复原。
 *
 * 原理：乱码几乎都是“A 编码写出的字节，被程序当成 B 编码读出来”。
 * 所以只要把当前文本按 B 重新编码成字节，再按 A 解码，就能还原原文。
 * 这里穷举常见的 A/B 组合，用“解出来的文本像不像正常文本”打分，取最像的那个，
 * 并支持多轮（双重乱码）修复。
 */
public final class MojibakeFixer {

    public static final class Option {
        public final String text;
        public final String label;
        public final double score;
        public final int rounds;

        Option(String text, String label, double score, int rounds) {
            this.text = text;
            this.label = label;
            this.score = score;
            this.rounds = rounds;
        }
    }

    public static final class Result {
        public final String text;
        public final String applied;
        public final double scoreBefore;
        public final double scoreAfter;
        public final boolean changed;
        public final String warning;
        public final List<Option> options;

        Result(String text, String applied, double before, double after, boolean changed, String warning, List<Option> options) {
            this.text = text;
            this.applied = applied;
            this.scoreBefore = before;
            this.scoreAfter = after;
            this.changed = changed;
            this.warning = warning;
            this.options = options;
        }

        public int percentBefore() { return (int) Math.round(scoreBefore * 100); }

        public int percentAfter() { return (int) Math.round(scoreAfter * 100); }
    }

    /** 当作“原文编码”的候选 */
    private static final String[] AS_SOURCE = {
            TextCodec.GBK, TextCodec.BIG5, TextCodec.SJIS, TextCodec.EUCKR,
            TextCodec.CP1252, TextCodec.LATIN1, TextCodec.UTF8
    };

    /** 当作“被误读成”的候选 */
    private static final String[] AS_TARGET = {
            TextCodec.UTF8, TextCodec.GBK, TextCodec.BIG5, TextCodec.SJIS, TextCodec.EUCKR
    };

    private static final int MAX_ROUNDS = 3;
    private static final double IMPROVE = 0.10;

    private MojibakeFixer() {}

    public static Result fix(String input) {
        if (input == null) input = "";
        double before = CommonChars.sanity(input);
        String cur = input;
        List<String> applied = new ArrayList<>();
        List<Option> all = new ArrayList<>();
        all.add(new Option(input, "原文（不修改）", before, 0));

        // 损坏过半时任何“还原”都只是猜字，直接如实告知
        int bad = CommonChars.countReplacement(input);
        if (input.length() > 6 && bad * 3 > input.length()) {
            return new Result(input, "无法还原", before, before, false,
                    "超过三分之一的内容是替换符（●），说明原始字节在保存时就已大量丢失，纯文本层面无法还原。建议找回原始文件，用“文本编码”功能重新打开。",
                    all);
        }

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            List<Option> children = children(cur, round);
            for (Option o : children) all.add(o);
            if (children.isEmpty()) break;
            Option best = children.get(0);
            double curScore = CommonChars.sanity(cur);
            if (best.score > curScore + IMPROVE) {
                cur = best.text;
                applied.add(best.label);
            } else {
                break;
            }
        }

        double after = CommonChars.sanity(cur);
        Collections.sort(all, new Comparator<Option>() {
            @Override
            public int compare(Option a, Option b) {
                if (Math.abs(b.score - a.score) > 0.02) return Double.compare(b.score, a.score);
                return Integer.compare(a.rounds, b.rounds);
            }
        });
        // 去重（文本相同只保留第一条）
        List<Option> uniq = new ArrayList<>();
        for (Option o : all) {
            boolean dup = false;
            for (Option u : uniq) {
                if (u.text.equals(o.text)) { dup = true; break; }
            }
            if (!dup) uniq.add(o);
        }
        if (uniq.size() > 6) uniq = new ArrayList<>(uniq.subList(0, 6));

        StringBuilder label = new StringBuilder();
        for (int i = 0; i < applied.size(); i++) {
            if (i > 0) label.append(" → ");
            label.append(applied.get(i));
        }

        String warning = null;
        if (input.indexOf("锟斤拷") >= 0 || CommonChars.countReplacement(input) > 0) {
            warning = "文本里有“锟斤拷 / ●”这类替换符，说明部分原始字节已经丢失，只能尽力还原，个别字符（通常在末尾）可能缺失。";
        }
        if (!cur.equals(input) && after < 0.6) {
            warning = "已做最大努力，但仍不像正常文本，请尝试在下方手动选择其它修复结果。";
        }
        boolean changed = !cur.equals(input);
        return new Result(cur, label.length() == 0 ? "无需修复" : label.toString(), before, after, changed, warning, uniq);
    }

    /** 从当前文本出发，穷举一次“重编码再解码”，返回按分数排序的候选 */
    private static List<Option> children(String text, int round) {
        List<Option> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        // 解码时被替换成 U+FFFD 的坏字节无法还原，先剔除，避免整段无法回环编码
        String base = text.indexOf('\uFFFD') >= 0 ? text.replace("\uFFFD", "") : text;
        if (base.isEmpty()) return out;
        for (String src : AS_SOURCE) {
            byte[] bytes = TextCodec.encodeIfRoundTrips(base, src);
            if (bytes == null || bytes.length == 0) continue;
            for (String dst : AS_TARGET) {
                if (src.equals(dst)) continue;
                String decoded = TextCodec.decodeStrict(bytes, dst);
                if (decoded == null) decoded = decodeLenientTrimmed(bytes, dst);
                if (decoded == null || decoded.isEmpty() || decoded.equals(text)) continue;
                double s = CommonChars.sanity(decoded);
                String label = TextCodec.friendlyName(dst) + " → 按 " + TextCodec.friendlyName(src) + " 还原";
                out.add(new Option(decoded, label, s, round));
            }
        }
        Collections.sort(out, new Comparator<Option>() {
            @Override
            public int compare(Option a, Option b) {
                return Double.compare(b.score, a.score);
            }
        });
        return out;
    }

    /** 末尾被截断时用宽松解码，并去掉末尾的替换符 */
    private static String decodeLenientTrimmed(byte[] bytes, String charset) {
        String s = TextCodec.decodeLenient(bytes, charset);
        while (s.endsWith("\uFFFD")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 混合乱码修复：按行分别判断，只修“明显不正常”的行，避免把正常行改坏。
     * 适合那种整段里只有一部分行乱码的文件。
     */
    public static String fixMixed(String input) {
        if (input == null) return "";
        String[] lines = input.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int changed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) sb.append('\n');
            if (line.length() < 2) { sb.append(line); continue; }
            double s = CommonChars.sanity(line);
            if (s >= 0.82) { sb.append(line); continue; }
            Result r = fix(line);
            if (r.scoreAfter > s + 0.15 && r.scoreAfter > 0.7) {
                sb.append(r.text);
                changed++;
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
