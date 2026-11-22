package com.fmtlab.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * PDF 文本抽取（尽力而为）：解开 Flate 压缩流，按 Tj / TJ 顺序取字符串。
 * 简单字体直接按字节解码；中文 PDF 常见的 CID 字体则先读 /ToUnicode CMap，
 * 把两字节编码映射回 Unicode，再做输出。取不到映射时退回原样输出。
 */
public final class PdfText {

    private static final Charset LATIN = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");

    private static final Pattern OBJ_HEAD = Pattern.compile("(\\d+)\\s+\\d+\\s+obj");
    private static final Pattern FONT_DICT = Pattern.compile("/Font\\s*<<(.+?)>>", Pattern.DOTALL);
    private static final Pattern FONT_ENTRY = Pattern.compile("/\\s*([^\\s/<>\\[\\]]+)\\s+(\\d+)\\s+0\\s+R");
    private static final Pattern TO_UNICODE = Pattern.compile("/ToUnicode\\s+(\\d+)\\s+0\\s+R");
    private static final Pattern BF_CHAR = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
    private static final Pattern BF_RANGE = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]+>|\\[[^\\]]*\\])");

    private PdfText() {}

    /** 一个字体的 ToUnicode 映射：编码字节数 + 编码到 Unicode 的对照表 */
    private static final class CMap {
        int codeLen = 2;
        final Map<Integer, String> map = new HashMap<Integer, String>();
    }

    private static final class Tok {
        final char type; // S 字面字符串 / H 十六进制字符串 / N 名字 / X 数字 / A 数组
        final String val;
        Tok(char type, String val) { this.type = type; this.val = val; }
    }

    public static String extract(byte[] pdf) throws IOException {
        if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            throw new IOException("不是有效的 PDF 文件");
        }
        Map<String, CMap> fontMaps = fontMaps(objects(pdf));
        List<byte[]> streams = streams(pdf);
        StringBuilder out = new StringBuilder();
        for (byte[] s : streams) {
            String content = new String(s, LATIN);
            if (!content.contains("Tj") && !content.contains("TJ")) continue;
            String text = scan(content, fontMaps);
            if (!text.trim().isEmpty()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(text.trim());
            }
        }
        String result = tidy(out.toString());
        if (result.isEmpty()) {
            throw new IOException("这个 PDF 里没有可直接抽取的文本层（可能是扫描件或字体缺少编码表），请改用 PDF 转图片");
        }
        return result;
    }

    // ---------------------------------------------------------------- 对象与字体

    /** 按 “n 0 obj … endobj” 粗切对象，只用于找字体和 ToUnicode 表 */
    private static Map<String, byte[]> objects(byte[] pdf) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        String raw = new String(pdf, LATIN);
        Matcher m = OBJ_HEAD.matcher(raw);
        while (m.find()) {
            int end = raw.indexOf("endobj", m.end());
            if (end < 0) end = raw.length();
            byte[] slice = new byte[end - m.end()];
            System.arraycopy(pdf, m.end(), slice, 0, slice.length);
            map.put(m.group(1), slice);
        }
        return map;
    }

    /** 资源里的字体名（F1、F2…）对应到各自的 ToUnicode 表 */
    private static Map<String, CMap> fontMaps(Map<String, byte[]> objs) {
        Map<String, CMap> result = new HashMap<String, CMap>();
        for (Map.Entry<String, byte[]> e : objs.entrySet()) {
            String dict = new String(e.getValue(), LATIN);
            Matcher fm = FONT_DICT.matcher(dict);
            while (fm.find()) {
                Matcher fe = FONT_ENTRY.matcher(fm.group(1));
                while (fe.find()) {
                    String name = fe.group(1);
                    byte[] font = objs.get(fe.group(2));
                    if (font == null) continue;
                    Matcher tu = TO_UNICODE.matcher(new String(font, LATIN));
                    if (!tu.find()) continue;
                    byte[] cmapObj = objs.get(tu.group(1));
                    if (cmapObj == null) continue;
                    CMap cm = parseCMap(streamOf(cmapObj));
                    if (cm != null) result.put(name, cm);
                }
            }
        }
        return result;
    }

    private static CMap parseCMap(byte[] data) {
        if (data == null || data.length == 0) return null;
        byte[] plain = inflate(data);
        String text = new String(plain != null ? plain : data, LATIN);
        CMap cm = new CMap();
        int cs = text.indexOf("begincodespacerange");
        if (cs >= 0) {
            Matcher hex = Pattern.compile("<([0-9A-Fa-f]+)>").matcher(text.substring(cs));
            if (hex.find()) cm.codeLen = Math.max(1, Math.min(3, hex.group(1).length() / 2));
        }
        Matcher bc = Pattern.compile("beginbfchar(.+?)endbfchar", Pattern.DOTALL).matcher(text);
        while (bc.find()) {
            Matcher pair = BF_CHAR.matcher(bc.group(1));
            while (pair.find()) {
                cm.map.put(codeOf(pair.group(1)), unicodeOf(pair.group(2)));
            }
        }
        Matcher br = Pattern.compile("beginbfrange(.+?)endbfrange", Pattern.DOTALL).matcher(text);
        while (br.find()) {
            Matcher tri = BF_RANGE.matcher(br.group(1));
            while (tri.find()) {
                int lo = codeOf(tri.group(1));
                int hi = codeOf(tri.group(2));
                String dst = tri.group(3);
                if (dst.startsWith("[")) {
                    Matcher d = Pattern.compile("<([0-9A-Fa-f]+)>").matcher(dst);
                    int code = lo;
                    while (d.find() && code <= hi) {
                        cm.map.put(code, unicodeOf(d.group(1)));
                        code++;
                    }
                } else {
                    String start = dst.substring(1, dst.length() - 1);
                    int base = start.length() >= 4 ? Integer.parseInt(start.substring(0, 4), 16) : 0;
                    for (int code = lo; code <= hi && code - lo < 65536; code++) {
                        int u = base + (code - lo);
                        StringBuilder sb = new StringBuilder();
                        sb.append((char) (u & 0xFFFF));
                        cm.map.put(code, sb.toString());
                    }
                }
            }
        }
        return cm.map.isEmpty() ? null : cm;
    }

    private static int codeOf(String hex) {
        return hex.length() <= 4 ? Integer.parseInt(hex, 16)
                : Integer.parseInt(hex.substring(hex.length() - 4), 16);
    }

    private static String unicodeOf(String hex) {
        if (hex.length() >= 4) {
            try {
                return new String(new byte[]{
                        (byte) Integer.parseInt(hex.substring(0, 2), 16),
                        (byte) Integer.parseInt(hex.substring(2, 4), 16)}, UTF16BE);
            } catch (Exception ignored) {
                // 退回单字节处理
            }
        }
        return hex;
    }

    /** 单个对象里的 stream…endstream 内容 */
    private static byte[] streamOf(byte[] obj) {
        String raw = new String(obj, LATIN);
        int s = raw.indexOf("stream");
        if (s < 0) return null;
        int start = s + 6;
        if (start < raw.length() && raw.charAt(start) == '\r') start++;
        if (start < raw.length() && raw.charAt(start) == '\n') start++;
        int e = raw.indexOf("endstream", start);
        if (e < 0) return null;
        while (e > start && (raw.charAt(e - 1) == '\n' || raw.charAt(e - 1) == '\r')) e--;
        if (e <= start) return null;
        byte[] slice = new byte[e - start];
        System.arraycopy(obj, start, slice, 0, slice.length);
        return slice;
    }

    // ---------------------------------------------------------------- 流与扫描

    /** 找出所有 stream…endstream 段并尝试 Flate 解压，失败就原样保留 */
    private static List<byte[]> streams(byte[] pdf) {
        List<byte[]> out = new ArrayList<byte[]>();
        String raw = new String(pdf, LATIN);
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

    /** 逐字符扫描内容流，遇到 Tj / TJ 就按当前字体解码，遇到换行类操作符补空行 */
    private static String scan(String c, Map<String, CMap> fontMaps) {
        StringBuilder sb = new StringBuilder();
        List<String> fonts = new ArrayList<String>();
        CMap current = null;
        List<Tok> operands = new ArrayList<Tok>();
        List<Tok> arrayStrings = new ArrayList<Tok>();
        int n = c.length();
        int i = 0;
        while (i < n) {
            char ch = c.charAt(i);
            if (ch == '%') {
                while (i < n && c.charAt(i) != '\n' && c.charAt(i) != '\r') i++;
            } else if (ch == '(') {
                int[] endPos = new int[1];
                String raw = readLiteral(c, i, endPos);
                operands.add(new Tok('S', raw));
                i = endPos[0];
            } else if (ch == '<') {
                if (i + 1 < n && c.charAt(i + 1) == '<') {
                    int close = c.indexOf(">>", i + 2);
                    i = close < 0 ? n : close + 2;
                } else {
                    int close = c.indexOf('>', i + 1);
                    if (close < 0) break;
                    String hex = c.substring(i + 1, close).replaceAll("\\s+", "");
                    Tok t = new Tok('H', hex);
                    if (!arrayStrings.isEmpty() || lastIsArray(operands)) arrayStrings.add(t);
                    else operands.add(t);
                    i = close + 1;
                }
            } else if (ch == '[') {
                arrayStrings.clear();
                operands.add(new Tok('A', null));
                i++;
            } else if (ch == ']') {
                i++;
            } else if (ch == '/') {
                int start = i + 1;
                int j = start;
                while (j < n && !isDelimiter(c.charAt(j))) j++;
                operands.add(new Tok('N', c.substring(start, j)));
                i = j;
            } else if (isDelimiter(ch)) {
                i++;
            } else {
                int start = i;
                int j = i;
                while (j < n && !isDelimiter(c.charAt(j))) j++;
                String word = c.substring(start, j);
                i = j;
                if (isNumber(word)) {
                    operands.add(new Tok('X', word));
                } else {
                    if ("Tf".equals(word)) {
                        String name = lastName(operands);
                        if (name != null) {
                            if (!fonts.contains(name)) fonts.add(name);
                            current = fontMaps.get(name);
                        }
                    } else if ("Tj".equals(word) || "'".equals(word) || "\"".equals(word)) {
                        Tok s = lastString(operands);
                        if (s != null) sb.append(decode(s, current));
                        operands.clear();
                    } else if ("TJ".equals(word)) {
                        for (Tok s : arrayStrings) sb.append(decode(s, current));
                        arrayStrings.clear();
                        operands.clear();
                    } else if ("Td".equals(word) || "TD".equals(word)) {
                        // 只有纵向位移才算换行，横向位移是同行的字距调整
                        String ty = lastNumber(operands);
                        if (ty != null && Math.abs(parseNumber(ty)) > 0.5
                                && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                            sb.append('\n');
                        }
                        operands.clear();
                    } else if ("T*".equals(word)) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                        operands.clear();
                    } else {
                        operands.clear();
                        arrayStrings.clear();
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean lastIsArray(List<Tok> operands) {
        return !operands.isEmpty() && operands.get(operands.size() - 1).type == 'A';
    }

    private static Tok lastString(List<Tok> operands) {
        for (int k = operands.size() - 1; k >= 0; k--) {
            char t = operands.get(k).type;
            if (t == 'S' || t == 'H') return operands.get(k);
        }
        return null;
    }

    private static String lastName(List<Tok> operands) {
        for (int k = operands.size() - 1; k >= 0; k--) {
            if (operands.get(k).type == 'N') return operands.get(k).val;
        }
        return null;
    }

    private static String lastNumber(List<Tok> operands) {
        for (int k = operands.size() - 1; k >= 0; k--) {
            if (operands.get(k).type == 'X') return operands.get(k).val;
        }
        return null;
    }

    private static double parseNumber(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isDelimiter(char ch) {
        return ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f'
                || ch == '(' || ch == ')' || ch == '<' || ch == '>' || ch == '[' || ch == ']'
                || ch == '{' || ch == '}' || ch == '/' || ch == '%';
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.' && c != '-' && c != '+') return false;
        }
        return true;
    }

    /** 读 (…) 字面字符串，处理转义，返回未解码的原始字节串（Latin-1 形式） */
    private static String readLiteral(String c, int start, int[] endPos) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int i = start + 1;
        while (i < c.length()) {
            char ch = c.charAt(i);
            if (ch == '\\' && i + 1 < c.length()) {
                char nx = c.charAt(i + 1);
                switch (nx) {
                    case 'n': sb.append('\n'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case '(': sb.append('('); i += 2; break;
                    case ')': sb.append(')'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    default:
                        if (nx >= '0' && nx <= '7') {
                            int v = nx - '0';
                            int k = 0;
                            i += 2;
                            while (k < 2 && i < c.length() && c.charAt(i) >= '0' && c.charAt(i) <= '7') {
                                v = v * 8 + (c.charAt(i) - '0');
                                i++;
                                k++;
                            }
                            sb.append((char) v);
                        } else {
                            sb.append(nx);
                            i += 2;
                        }
                }
            } else if (ch == '(') {
                depth++;
                sb.append(ch);
                i++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    endPos[0] = i + 1;
                    return sb.toString();
                }
                sb.append(ch);
                i++;
            } else {
                sb.append(ch);
                i++;
            }
        }
        endPos[0] = c.length();
        return sb.toString();
    }

    /** 按当前字体的映射解码字符串；没有映射就按单字节 + UTF-8 启发式 */
    private static String decode(Tok t, CMap cm) {
        if (t.type == 'H') {
            byte[] bytes = hexBytes(t.val);
            if (cm != null) return fromCMap(bytes, cm);
            return guess(bytes);
        }
        byte[] bytes = t.val.getBytes(LATIN);
        if (cm != null) return fromCMap(bytes, cm);
        return smart(t.val);
    }

    private static String fromCMap(byte[] bytes, CMap cm) {
        int len = cm.codeLen <= 0 ? 2 : cm.codeLen;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < bytes.length) {
            int code = 0;
            int used = 0;
            for (int k = 0; k < len && i + k < bytes.length; k++) {
                code = (code << 8) | (bytes[i + k] & 0xFF);
                used++;
            }
            String u = cm.map.get(code);
            if (u != null) {
                sb.append(u);
            } else if (used == 2) {
                sb.append(guess(new byte[]{bytes[i], bytes[i + 1]}));
            }
            i += used;
        }
        return sb.toString();
    }

    private static byte[] hexBytes(String hex) {
        String h = hex.length() % 2 == 0 ? hex : hex + "0";
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** 字节串先当 UTF-8，再当 UTF-16BE，都不行就按 Latin-1 原样保留 */
    private static String guess(byte[] bytes) {
        try {
            return UTF8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignored) {
            // 继续尝试 UTF-16BE
        }
        if (bytes.length >= 2 && bytes.length % 2 == 0) {
            try {
                return new String(bytes, UTF16BE);
            } catch (Exception ignored) {
                // 放弃
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append((char) (b & 0xFF));
        return sb.toString();
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
            return UTF8.newDecoder()
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
