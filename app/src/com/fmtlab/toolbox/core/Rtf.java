package com.fmtlab.toolbox.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * RTF 纯文本抽取：只处理正文文字、段落与换行，字体表、颜色表、图片、样式定义整组跳过。
 * 中文以 "uNNNN?" 转义形式写入的能正确还原；'\''hh 字节按单字节近似处理。
 */
public final class Rtf {

    private static final Set<String> SKIP_GROUPS = new HashSet<String>(Arrays.asList(
            "fonttbl", "colortbl", "stylesheet", "pict", "info", "header", "footer",
            "listtable", "listoverridetable", "rsidtbl", "generator", "themedata",
            "colorschememapping", "latentstyles", "datastore", "xmlns"));

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private Rtf() {}

    public static String text(byte[] data) throws IOException {
        if (data == null || data.length < 5) throw new IOException("不是有效的 RTF 文件");
        String raw = new String(data, LATIN1);
        if (!raw.startsWith("{\\rtf")) throw new IOException("不是有效的 RTF 文件（缺少 {\\rtf 头）");

        StringBuilder out = new StringBuilder();
        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            if (c == '{') {
                int depth = 1;
                int j = i + 1;
                // 读组内第一个控制字，判断是否整组跳过
                StringBuilder word = new StringBuilder();
                int k = j;
                if (k < len && raw.charAt(k) == '\\') {
                    k++;
                    while (k < len && Character.isLetter(raw.charAt(k))) {
                        word.append(raw.charAt(k));
                        k++;
                    }
                }
                boolean skip = SKIP_GROUPS.contains(word.toString());
                if (skip) {
                    while (j < len && depth > 0) {
                        char d = raw.charAt(j);
                        if (d == '{') depth++;
                        else if (d == '}') depth--;
                        j++;
                    }
                    i = j;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                continue;
            }
            if (c == '\\') {
                i++;
                if (i >= len) break;
                char n = raw.charAt(i);
                if (n == '\\' || n == '{' || n == '}') {
                    out.append(n);
                    i++;
                } else if (n == '\'') {
                    if (i + 2 < len) {
                        String hex = raw.substring(i + 1, i + 3);
                        try {
                            int code = Integer.parseInt(hex, 16);
                            out.append((char) code);
                        } catch (NumberFormatException ignored) {
                        }
                        i += 3;
                    } else {
                        i++;
                    }
                } else if (n == '*') {
                    // \* 表示目标组，整组跳过
                    int depth = 0;
                    int j = i;
                    while (j < len) {
                        char d = raw.charAt(j);
                        if (d == '{') depth++;
                        else if (d == '}') {
                            if (depth == 0) { j++; break; }
                            depth--;
                        }
                        j++;
                    }
                    i = j;
                } else if (Character.isLetter(n)) {
                    StringBuilder word = new StringBuilder();
                    while (i < len && Character.isLetter(raw.charAt(i))) {
                        word.append(raw.charAt(i));
                        i++;
                    }
                    StringBuilder num = new StringBuilder();
                    if (i < len && (raw.charAt(i) == '-' || Character.isDigit(raw.charAt(i)))) {
                        if (raw.charAt(i) == '-') i++;
                        while (i < len && Character.isDigit(raw.charAt(i))) {
                            num.append(raw.charAt(i));
                            i++;
                        }
                    }
                    String w = word.toString();
                    if (w.equals("par") || w.equals("line")) {
                        out.append('\n');
                    } else if (w.equals("tab")) {
                        out.append('\t');
                    } else if (w.equals("u")) {
                        int code = 0;
                        try {
                            code = num.length() == 0 ? 0 : Integer.parseInt(num.toString());
                        } catch (NumberFormatException ignored) {
                        }
                        if (code < 0) code += 65536;
                        out.append((char) code);
                        // uNNNN 转义后面常跟一个 ? 或空格作为替代字符，跳过
                        if (i < len && raw.charAt(i) == '?') i++;
                        else if (i < len && raw.charAt(i) == ' ') i++;
                    } else if (w.equals("emdash")) {
                        out.append('—');
                    } else if (w.equals("endash")) {
                        out.append('–');
                    } else if (w.equals("lquote") || w.equals("rquote")) {
                        out.append('\'');
                    } else if (w.equals("ldblquote") || w.equals("rdblquote")) {
                        out.append('"');
                    }
                    // 控制字后的单个空格属于分隔符，吃掉
                    if (i < len && raw.charAt(i) == ' ') i++;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            out.append(c);
            i++;
        }

        StringBuilder tidy = new StringBuilder();
        for (String line : out.toString().replace("\r", "").split("\n")) {
            String t = line.replaceAll("[ \\t]+", " ").trim();
            if (t.isEmpty()) {
                if (tidy.length() > 0 && tidy.charAt(tidy.length() - 1) != '\n') tidy.append('\n');
                continue;
            }
            if (tidy.length() > 0 && tidy.charAt(tidy.length() - 1) != '\n') tidy.append('\n');
            tidy.append(t).append('\n');
        }
        String result = tidy.toString().replaceAll("\n{3,}", "\n\n").trim();
        if (result.isEmpty()) throw new IOException("这个 RTF 里没有可读的文字");
        return result;
    }
}
