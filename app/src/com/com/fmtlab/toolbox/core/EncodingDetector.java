package com.fmtlab.toolbox.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 文本编码自动识别：按“解出来是否像正常文本”打分，取最高分。 */
public final class EncodingDetector {

    public static final class Candidate {
        public final String charset;
        public final String text;
        public final double score;
        public final int cjkCount;

        Candidate(String charset, String text, double score, int cjkCount) {
            this.charset = charset;
            this.text = text;
            this.score = score;
            this.cjkCount = cjkCount;
        }

        public String label() {
            return TextCodec.friendlyName(charset);
        }
    }

    public static final class Result {
        public final String charset;
        public final String text;
        public final double score;
        public final List<Candidate> candidates;
        public final boolean bom;
        public final boolean ascii;

        Result(String charset, String text, double score, List<Candidate> candidates, boolean bom, boolean ascii) {
            this.charset = charset;
            this.text = text;
            this.score = score;
            this.candidates = candidates;
            this.bom = bom;
            this.ascii = ascii;
        }

        public String label() {
            return TextCodec.friendlyName(charset);
        }

        public int confidence() {
            return (int) Math.round(Math.max(0, Math.min(1, score)) * 100);
        }
    }

    private static final String[] PROBES = {
            TextCodec.UTF8, TextCodec.GBK, TextCodec.BIG5, TextCodec.SJIS,
            TextCodec.EUCKR, TextCodec.CP1252
    };

    private EncodingDetector() {}

    public static Result detect(byte[] data) {
        if (data == null) data = new byte[0];
        if (data.length == 0) {
            return new Result(TextCodec.UTF8, "", 1.0, new ArrayList<Candidate>(), false, true);
        }

        String bom = TextCodec.bomName(data);
        if (bom != null) {
            String text = TextCodec.decodeLenient(TextCodec.stripBom(data), bom);
            List<Candidate> list = new ArrayList<>();
            list.add(new Candidate(bom, text, 1.0, countCjk(text)));
            return new Result(bom, text, 1.0, list, true, false);
        }

        boolean ascii = TextCodec.isAscii(data, Math.min(data.length, 256 * 1024));

        List<Candidate> list = new ArrayList<>();
        for (String name : PROBES) {
            String text = decodeSample(data, name);
            if (text == null) continue;
            list.add(new Candidate(name, text, scoreText(text, name), countCjk(text)));
        }
        // GB18030 作为兜底（它能解的 GBK 都能解，但反之不行）
        if (list.size() < 2) {
            String text = decodeSample(data, TextCodec.GB18030);
            if (text != null) list.add(new Candidate(TextCodec.GB18030, text, scoreText(text, TextCodec.GB18030), countCjk(text)));
        }

        if (list.isEmpty()) {
            String text = TextCodec.decodeLenient(data, TextCodec.UTF8);
            List<Candidate> only = new ArrayList<>();
            only.add(new Candidate(TextCodec.UTF8, text, 0.15, countCjk(text)));
            return new Result(TextCodec.UTF8, text, 0.15, only, false, ascii);
        }

        Collections.sort(list, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Double.compare(b.score, a.score);
            }
        });

        Candidate best = list.get(0);
        // 最佳候选只用于识别打分；正文一律用最优编码做“宽松解码”，避免样本截断导致整段读不出
        String fullText = ascii ? TextCodec.decodeLenient(data, TextCodec.UTF8)
                : TextCodec.decodeLenient(data, best.charset);
        return new Result(best.charset, fullText, best.score, list, false, ascii);
    }

    /** 大文件只取开头一段做识别，并自动裁掉截断产生的半个字符 */
    private static String decodeSample(byte[] data, String charset) {
        byte[] sample = data;
        if (data.length > 300 * 1024) sample = java.util.Arrays.copyOfRange(data, 0, 300 * 1024);
        String text = TextCodec.decodeStrict(sample, charset);
        if (text != null) return text;
        for (int trim = 1; trim <= 4; trim++) {
            if (sample.length > trim) {
                byte[] t = java.util.Arrays.copyOfRange(sample, 0, sample.length - trim);
                text = TextCodec.decodeStrict(t, charset);
                if (text != null) return text;
            }
        }
        return null;
    }

    static int countCjk(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (CommonChars.isCjk(s.charAt(i))) n++;
        return n;
    }

    /** 单个编码解码结果的可信度打分 */
    static double scoreText(String text, String charset) {
        if (text == null || text.isEmpty()) return 0;
        double readable = CommonChars.readableRatio(text);
        double cjkRatio = CommonChars.cjkRatio(text);
        double common = CommonChars.commonCjkRatio(text);
        double marks = CommonChars.mojibakeMarkRatio(text);

        double score;
        if (common >= 0 && cjkRatio > 0.03) {
            score = readable * 0.40 + common * 0.42 + Math.min(1.0, cjkRatio * 3) * 0.10;
        } else {
            // 没有汉字，按“是否像西文/日文/韩文”判断
            double other = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (CommonChars.isKana(c) || CommonChars.isHangul(c)) other++;
            }
            score = readable * 0.55 + 0.35 + Math.min(1.0, (double) other / Math.max(1, text.length()) * 6) * 0.1;
            score -= Math.min(0.45, marks * 2.5);
        }

        // 乱码特征字符（Ã Â å æ ... ）在正常西文里极少成片出现
        score -= Math.min(0.5, marks * 1.6);

        // 严格解码通过本身就说明字节合法；UTF-8 的多字节结构几乎不会误判
        if (TextCodec.UTF8.equals(charset)) {
            score += 0.10;
        } else if (TextCodec.CP1252.equals(charset) || TextCodec.LATIN1.equals(charset)) {
            score -= 0.05;
        }
        return Math.max(0, Math.min(1, score));
    }

    /** 给 UI 用的一句话结论 */
    public static String describe(Result r) {
        if (r.ascii) return "纯 ASCII 文本（可用 UTF-8 保存）";
        if (r.bom) return "检测到 BOM，编码为 " + r.label();
        int c = r.confidence();
        if (c >= 80) return "高度可信：" + r.label() + "（" + c + "%）";
        if (c >= 55) return "较可信：" + r.label() + "（" + c + "%）";
        return "置信度偏低：" + r.label() + "（" + c + "%），建议手动选择编码";
    }
}
