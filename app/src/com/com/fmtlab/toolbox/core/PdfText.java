package com.fmtlab.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * PDF 文本抽取（尽力而为）：解开 Flate 压缩流，扫描 Tj / TJ 文本操作符。
 * 只认识直接写在内容流里的字符串；用 CID 字体（多数中文 PDF）时可能只能得到部分文字，
 * 因为缺少 ToUnicode 映射表。遇到这种情况由界面提示“建议改用 PDF 转图片”。
 */
public final class PdfText {

    private static final Pattern TJ_ARRAY = Pattern.compile("\\[((?:[^\\[\\]]|\\\\.)*)\\]\\s*TJ", Pattern.DOTALL);
    private static final Pattern TJ_STRING = Pattern.compile("\\((?:\\\\.|[^\\\\()])*\\)");
    private static final Pattern NEWLINE_OP = Pattern.compile("(Td|TD|T\\*|ET|BT)");

    private PdfText() {}

    public static String extract(byte[] pdf) throws IOException {
        if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            throw new IOException("不是有效的 PDF 文件");
        }
        List<byte[]> streams = streams(pdf);
        StringBuilder out = new StringBuilder();
        for (byte[] s : streams) {
            String content = new String(s, java.nio.charset.Charset.forName("ISO-8859-1"));
            if (!content.contains("Tj") && !content.contains("TJ")) continue;
            String text = scanContent(content);
            if (!text.trim().isEmpty()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(text.trim());
            }
        }
        String result = tidy(out.toString());
        if (result.isEmpty()) {
            throw new IOException("这个 PDF 里没有可直接抽取的文本层（可能是扫描件或 CID 字体），请改用 PDF 转图片");
        }
        return result;
    }

    /** 找出所有 stream…endstream 段并尝试 Flate 解压，失败就原样保留 */
    private static List<byte[]> streams(byte[] pdf) {
        List<byte[]> out = new ArrayList<byte[]>();
        String raw = new String(pdf, java.nio.charset.Charset.forName("ISO-8859-1"));
        int idx = 0;
        while (true) {
            int s = raw.indexOf("stream", idx);
            if (s < 0) break;
            int start = s + 6;
            if (start < raw.length() && raw.charAt(start) == '\r') start++;
            if (start < raw.length() && raw.charAt(start) == '\n') start++;
            int e = raw.indexOf("endstream", start);
            if (e < 0) break;
            int end = e;
            while (end > start && (raw.charAt(end - 1) == '\n' || raw.charAt(end - 1) == '\r')) end--;
            if (end > start) {
                byte[] slice = new byte[end - start];
                System.arraycopy(pdf, start, slice, 0, slice.length);
                byte[] inflated = inflate(slice);
                out.add(inflated != null ? inflated : slice);
            }
            idx = e + 9;
        }
        return out;
    }

    private static byte[] inflate(byte[] data) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[16384];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                bo.write(buf, 0, n);
            }
            inf.end();
            return bo.size() == 0 ? null : bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String scanContent(String content) {
        StringBuilder sb = new StringBuilder();
        Matcher arrayMatcher = TJ_ARRAY.matcher(content);
        Matcher stringMatcher = Pattern.compile("\\((?:\\\\.|[^\\\\()])*\\)\\s*Tj").matcher(content);
        int pos = 0;
        while (true) {
            int a = nextMatch(arrayMatcher, pos);
            int b = nextMatch(stringMatcher, pos);
            int next;
            if (a < 0 && b < 0) break;
            if (a >= 0 && (b < 0 || a < b)) {
                String body = arrayMatcher.group(1);
                Matcher sm = TJ_STRING.matcher(body);
                while (sm.find()) sb.append(decode(sm.group()));
                next = arrayMatcher.end();
            } else {
                sb.append(decode(stringMatcher.group().replaceAll("\\s*Tj$", "")));
                next = stringMatcher.end();
            }
            // 看两个操作之间有没有换行类操作符
            String between = content.substring(pos, next);
            if (NEWLINE_OP.matcher(between).find()) sb.append('\n');
            pos = next;
        }
        return sb.toString();
    }

    private static int nextMatch(Matcher m, int from) {
        m.region(from, m.regionEnd());
        return m.find() ? m.start() : -1;
    }

    private static String decode(String literal) {
        String s = literal.trim();
        if (s.startsWith("(")) s = s.substring(1);
        if (s.endsWith("Tj")) s = s.substring(0, s.length() - 2).trim();
        if (s.endsWith(")")) s = s.substring(0, s.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '(': sb.append('('); break;
                    case ')': sb.append(')'); break;
                    case '\\': sb.append('\\'); break;
                    default:
                        if (n >= '0' && n <= '7') {
                            int v = n - '0';
                            int k = 0;
                            while (k < 2 && i + 1 < s.length()
                                    && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') {
                                v = v * 8 + (s.charAt(++i) - '0');
                                k++;
                            }
                            sb.append((char) v);
                        } else {
                            sb.append(n);
                        }
                }
            } else {
                sb.append(c);
            }
        }
        return smart(sb.toString());
    }

    /** PDF 里字符串是单字节字符，遇到真正的 UTF-8 文本要还原一次，否则就是拉丁字符 */
    private static String smart(String s) {
        boolean high = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) { high = true; break; }
        }
        if (!high) return s;
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return s;
            bytes[i] = (byte) (s.charAt(i) & 0xFF);
        }
        try {
            return java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static String tidy(String s) {
        String r = s;
        r = r.replaceAll("[ \\t]+\\n", "\n");
        r = r.replaceAll("\\n{3,}", "\n\n");
        return r.trim();
    }
}
