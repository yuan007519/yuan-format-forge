package com.fmtlab.toolbox.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Office / 网页 / Markdown 文档互转（纯 Java，基于 OOXML 解包 + 轻量解析）。
 * 支持：DOCX →  TXT / Markdown / HTML、XLSX → CSV、PPTX → TXT、HTML → TXT、Markdown → HTML、TXT → HTML。
 */
public final class DocConverter {

    private static final Pattern P_PARA = Pattern.compile("<w:p\\b[^>]*>(.*?)</w:p>", Pattern.DOTALL);
    private static final Pattern P_PARA_SELF = Pattern.compile("<w:p\\b[^>]*/>");
    private static final Pattern P_PSTYLE = Pattern.compile("<w:pStyle[^>]*w:val=\"([^\"]*)\"");
    private static final Pattern P_WT = Pattern.compile("<w:t\\b[^>]*>(.*?)</w:t>", Pattern.DOTALL);
    private static final Pattern P_ROW = Pattern.compile("<row\\b[^>]*>(.*?)</row>", Pattern.DOTALL);
    private static final Pattern P_CELL = Pattern.compile("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
    private static final Pattern P_SHARED_SI = Pattern.compile("<si\\b[^>]*>(.*?)</si>", Pattern.DOTALL);
    private static final Pattern P_A_T = Pattern.compile("<a:t\\b[^>]*>(.*?)</a:t>", Pattern.DOTALL);
    private static final Pattern P_SLIDE_NAME = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
    private static final Pattern P_SHEET_TAG = Pattern.compile("<sheet\\b[^>]*name=\"([^\"]*)\"");

    private DocConverter() {}

    // ---------------------------------------------------------------- 解包

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) bo.write(buf, 0, n);
                files.put(e.getName().replace('\\', '/'), bo.toByteArray());
            }
        } finally {
            try { zis.close(); } catch (Exception ignored) {}
        }
        return files;
    }

    private static String str(Map<String, byte[]> files, String name) {
        byte[] b = files.get(name);
        if (b == null) return null;
        return new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    // ---------------------------------------------------------------- DOCX

    public static String docxToText(byte[] docx) throws IOException {
        Map<String, byte[]> files = unzip(docx);
        String xml = str(files, "word/document.xml");
        if (xml == null) throw new IOException("不是有效的 DOCX 文件（缺少 word/document.xml）");
        xml = xml.replaceAll("<w:tab\\b[^>]*/>", "\t")
                .replaceAll("<w:br\\b[^>]*/>", "\n")
                .replaceAll("<w:cr\\b[^>]*/>", "\n");
        xml = xml.replace("</w:p>", "\n");
        // 段落属性、域代码等不可见内容先清掉
        xml = xml.replaceAll("<w:instrText\\b[^>]*>.*?</w:instrText>", "");
        xml = stripTags(xml);
        String text = unescapeXml(xml);
        text = text.replace('\u00A0', ' ');
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    public static String docxToMarkdown(byte[] docx) throws IOException {
        Map<String, byte[]> files = unzip(docx);
        String xml = str(files, "word/document.xml");
        if (xml == null) throw new IOException("不是有效的 DOCX 文件（缺少 word/document.xml）");
        xml = xml.replaceAll("<w:tab\\b[^>]*/>", "    ")
                .replaceAll("<w:br\\b[^>]*/>", "  \n");

        StringBuilder sb = new StringBuilder();
        Matcher m = P_PARA.matcher(xml);
        while (m.find()) {
            String body = m.group(1);
            String style = "";
            Matcher sm = P_PSTYLE.matcher(body);
            if (sm.find()) style = sm.group(1);
            String txt = extractWT(body);
            if (txt.trim().isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) sb.append('\n');
                continue;
            }
            int level = headingLevel(style);
            boolean bold = body.contains("<w:b/>") || body.contains("<w:b ") ;
            if (level > 0) {
                sb.append(repeat('#', Math.min(6, level))).append(' ').append(txt.trim()).append("\n\n");
            } else if (style.toLowerCase().contains("list")) {
                sb.append("- ").append(txt.trim()).append('\n');
            } else {
                if (bold && txt.trim().length() < 60 && !txt.trim().endsWith("。")) {
                    sb.append("**").append(txt.trim()).append("**\n\n");
                } else {
                    sb.append(txt.trim()).append("\n\n");
                }
            }
        }
        String out = sb.toString();
        out = out.replaceAll("\n{3,}", "\n\n");
        return out.trim();
    }

    public static String docxToHtml(byte[] docx) throws IOException {
        Map<String, byte[]> files = unzip(docx);
        String xml = str(files, "word/document.xml");
        if (xml == null) throw new IOException("不是有效的 DOCX 文件（缺少 word/document.xml）");
        StringBuilder body = new StringBuilder();
        Matcher m = P_PARA.matcher(xml);
        while (m.find()) {
            String inner = m.group(1);
            String style = "";
            Matcher sm = P_PSTYLE.matcher(inner);
            if (sm.find()) style = sm.group(1);
            String txt = extractWT(inner);
            if (txt.trim().isEmpty()) continue;
            int level = headingLevel(style);
            String tag = level > 0 ? "h" + Math.min(6, level) : "p";
            body.append('<').append(tag).append('>').append(escapeHtml(txt.trim())).append("</").append(tag).append(">\n");
        }
        return wrapHtml(titleOf(files), body.toString());
    }

    private static String extractWT(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = P_WT.matcher(xml);
        while (m.find()) sb.append(unescapeXml(m.group(1)));
        // 段落内换行
        return sb.toString().replace("\t", "\t");
    }

    private static int headingLevel(String style) {
        if (style == null || style.isEmpty()) return 0;
        String s = style.toLowerCase();
        if (s.contains("title")) return 1;
        Matcher m = Pattern.compile("(?:heading|标题|h)(\\d)").matcher(s);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        if (s.contains("heading") || s.contains("标题")) return 1;
        return 0;
    }

    private static String titleOf(Map<String, byte[]> files) {
        try {
            String core = str(files, "docProps/core.xml");
            if (core != null) {
                Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.DOTALL).matcher(core);
                if (m.find() && !m.group(1).trim().isEmpty()) return unescapeXml(m.group(1).trim());
            }
        } catch (Exception ignored) {}
        return "文档";
    }

    // ---------------------------------------------------------------- XLSX

    public static String xlsxToCsv(byte[] xlsx) throws IOException {
        Map<String, byte[]> files = unzip(xlsx);
        if (files.get("xl/workbook.xml") == null) throw new IOException("不是有效的 XLSX 文件（缺少 xl/workbook.xml）");

        List<String> shared = new ArrayList<>();
        String sst = str(files, "xl/sharedStrings.xml");
        if (sst != null) {
            Matcher m = P_SHARED_SI.matcher(sst);
            while (m.find()) shared.add(extractAllT(m.group(1)));
        }

        List<String> sheetNames = new ArrayList<>();
        Matcher nm = P_SHEET_TAG.matcher(str(files, "xl/workbook.xml"));
        while (nm.find()) sheetNames.add(unescapeXml(nm.group(1)));

        List<Integer> idx = new ArrayList<>();
        for (String key : files.keySet()) {
            Matcher m = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml").matcher(key);
            if (m.matches()) idx.add(Integer.parseInt(m.group(1)));
        }
        Collections.sort(idx);
        if (idx.isEmpty()) throw new IOException("工作簿中没有工作表");

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < idx.size(); i++) {
            String name = i < sheetNames.size() ? sheetNames.get(i) : ("Sheet" + idx.get(i));
            if (idx.size() > 1) {
                if (out.length() > 0) out.append('\n');
                out.append("# ").append(name).append('\n');
            }
            out.append(sheetToCsv(str(files, "xl/worksheets/sheet" + idx.get(i) + ".xml"), shared));
        }
        return out.toString().trim();
    }

    private static String sheetToCsv(String xml, List<String> shared) {
        StringBuilder sb = new StringBuilder();
        if (xml == null) return "";
        Matcher rows = P_ROW.matcher(xml);
        while (rows.find()) {
            String rowXml = rows.group(1);
            List<String> cells = new ArrayList<>();
            Matcher cm = P_CELL.matcher(rowXml);
            while (cm.find()) {
                String attrs = cm.group(1) == null ? "" : cm.group(1);
                String inner = cm.group(2) == null ? "" : cm.group(2);
                int col = columnIndex(attrValue(attrs, "r"));
                String type = attrValue(attrs, "t");
                String value = "";
                Matcher vm = Pattern.compile("<v\\b[^>]*>(.*?)</v>", Pattern.DOTALL).matcher(inner);
                Matcher im = Pattern.compile("<t\\b[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(inner);
                if (vm.find()) {
                    value = unescapeXml(vm.group(1));
                    if ("s".equals(type)) {
                        try {
                            int k = Integer.parseInt(value.trim());
                            value = k >= 0 && k < shared.size() ? shared.get(k) : "";
                        } catch (Exception ignored) { value = ""; }
                    } else if ("b".equals(type)) {
                        value = "1".equals(value.trim()) ? "TRUE" : "FALSE";
                    }
                } else if (im.find()) {
                    value = unescapeXml(im.group(1));
                }
                while (cells.size() < col) cells.add("");
                cells.add(value);
            }
            List<String> line = new ArrayList<>();
            for (String c : cells) line.add(csvEscape(c));
            while (!line.isEmpty() && line.get(line.size() - 1).isEmpty()) line.remove(line.size() - 1);
            sb.append(join(line, ",")).append('\n');
        }
        return sb.toString();
    }

    private static String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static int columnIndex(String ref) {
        int col = 0;
        if (ref == null || ref.isEmpty()) return 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            col = col * 26 + (c - 'A' + 1);
        }
        return Math.max(0, col - 1);
    }

    static String csvEscape(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- PPTX

    public static String pptxToText(byte[] pptx) throws IOException {
        Map<String, byte[]> files = unzip(pptx);
        List<Integer> slides = new ArrayList<>();
        Map<Integer, String> keyOf = new HashMap<>();
        for (String key : files.keySet()) {
            Matcher m = P_SLIDE_NAME.matcher(key);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                slides.add(n);
                keyOf.put(n, key);
            }
        }
        Collections.sort(slides);
        if (slides.isEmpty()) throw new IOException("不是有效的 PPTX 文件（找不到幻灯片）");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slides.size(); i++) {
            String xml = str(files, keyOf.get(slides.get(i)));
            sb.append("【第 ").append(i + 1).append(" 页】\n");
            Matcher m = P_A_T.matcher(xml);
            while (m.find()) {
                String t = unescapeXml(m.group(1)).trim();
                if (!t.isEmpty()) sb.append(t).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String extractAllT(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<t\\b[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(xml);
        while (m.find()) sb.append(unescapeXml(m.group(1)));
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTML / Markdown

    public static String htmlToText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<head[^>]*>.*?</head>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)</h[1-6]>", "\n\n")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)</tr>", "\n");
        s = stripTags(s);
        s = unescapeXml(s);
        s = s.replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    public static String markdownToHtml(String md, String title) {
        if (md == null) md = "";
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;
        StringBuilder list = new StringBuilder();
        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    sb.append("</code></pre>\n");
                } else {
                    sb.append("<pre><code>");
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                sb.append(escapeHtml(line)).append('\n');
                continue;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                flushList(sb, list);
                continue;
            }
            Matcher h = Pattern.compile("^#(\\d?)\\s+(.*)$").matcher(t);
            if (h.matches()) {
                flushList(sb, list);
                int lv = h.group(1).isEmpty() ? 1 : Integer.parseInt(h.group(1));
                lv = Math.max(1, Math.min(6, lv));
                sb.append("<h").append(lv).append('>').append(inline(h.group(2))).append("</h").append(lv).append(">\n");
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                list.append("<li>").append(inline(t.substring(2))).append("</li>\n");
                continue;
            }
            if (Pattern.compile("^\\d+\\.\\s+").matcher(t).find()) {
                list.append("<li>").append(inline(t.replaceFirst("^\\d+\\.\\s+", ""))).append("</li>\n");
                continue;
            }
            if (t.matches("^(---|\\*\\*\\*|___)$")) {
                flushList(sb, list);
                sb.append("<hr/>\n");
                continue;
            }
            if (t.startsWith("> ")) {
                flushList(sb, list);
                sb.append("<blockquote>").append(inline(t.substring(2))).append("</blockquote>\n");
                continue;
            }
            flushList(sb, list);
            sb.append("<p>").append(inline(t)).append("</p>\n");
        }
        if (inCode) sb.append("</code></pre>\n");
        flushList(sb, list);
        return wrapHtml(title == null || title.isEmpty() ? "文档" : title, sb.toString());
    }

    private static void flushList(StringBuilder sb, StringBuilder list) {
        if (list.length() > 0) {
            sb.append("<ul>\n").append(list).append("</ul>\n");
            list.setLength(0);
        }
    }

    private static String inline(String s) {
        String out = escapeHtml(s);
        out = out.replaceAll("`([^`]+)`", "<code>$1</code>");
        out = out.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        out = out.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        out = out.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "<img alt=\"$1\" src=\"$2\"/>");
        out = out.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        return out;
    }

    public static String textToHtml(String text, String title) {
        StringBuilder body = new StringBuilder();
        String[] paras = (text == null ? "" : text).replace("\r\n", "\n").split("\n", -1);
        for (String p : paras) {
            if (p.trim().isEmpty()) continue;
            body.append("<p>").append(escapeHtml(p)).append("</p>\n");
        }
        return wrapHtml(title == null || title.isEmpty() ? "文本" : title, body.toString());
    }

    private static String wrapHtml(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\"/>\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n"
                + "<title>" + escapeHtml(title) + "</title>\n"
                + "<style>\n"
                + "  body{max-width:760px;margin:0 auto;padding:32px 20px 64px;color:#1f2937;background:#ffffff;"
                + "font-family:-apple-system,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;line-height:1.8;font-size:16px;}\n"
                + "  h1,h2,h3,h4{color:#0f172a;line-height:1.35;margin:1.6em 0 .6em;}\n"
                + "  h1{font-size:28px;border-bottom:2px solid #e2e8f0;padding-bottom:.4em;}\n"
                + "  h2{font-size:23px;} h3{font-size:19px;}\n"
                + "  p{margin:0 0 1em;} ul{padding-left:1.4em;} li{margin:.3em 0;}\n"
                + "  code{background:#f1f5f9;padding:2px 6px;border-radius:6px;font-size:.92em;}\n"
                + "  pre{background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;padding:16px;overflow:auto;}\n"
                + "  blockquote{border-left:4px solid #cbd5e1;margin:1em 0;padding:.2em 1em;color:#475569;background:#f8fafc;}\n"
                + "  hr{border:none;border-top:1px solid #e2e8f0;margin:2em 0;}\n"
                + "  img{max-width:100%;}\n"
                + "  a{color:#0b6bcb;}\n"
                + "</style>\n</head>\n<body>\n" + body + "</body>\n</html>\n";
    }

    // ---------------------------------------------------------------- 通用

    static String stripTags(String xml) {
        return xml.replaceAll("<[^>]*>", "");
    }

    static String unescapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.equals("amp")) rep = "&";
                    else if (ent.equals("lt")) rep = "<";
                    else if (ent.equals("gt")) rep = ">";
                    else if (ent.equals("quot")) rep = "\"";
                    else if (ent.equals("apos")) rep = "'";
                    else if (ent.equals("nbsp")) rep = "\u00A0";
                    else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try { rep = new String(new char[]{(char) Integer.parseInt(ent.substring(2), 16)}); } catch (Exception ignored) {}
                    } else if (ent.startsWith("#")) {
                        try { rep = new String(new char[]{(char) Integer.parseInt(ent.substring(1))}); } catch (Exception ignored) {}
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
