package com.fmtlab.toolbox.core;

import com.fmtlab.toolbox.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档格式矩阵：把 docx / xlsx / pptx / odt / ods / odp / epub / html / md / csv / tsv / json / txt
 * 统一读成中间文本，再写出 txt / md / html / csv / tsv / json / docx / pdf / epub。
 * 只做“内容级”转换：保留文字与表格，复杂版式（多栏、SmartArt、公式、图片位置）会简化。
 */
public final class DocMatrix {

    public static final String[] TEXT_OUT = {"TXT", "Markdown", "HTML", "DOCX", "ODT", "RTF", "PDF", "EPUB"};
    public static final String[] TABLE_OUT = {"CSV", "TSV", "HTML 表格", "Markdown 表格", "XLSX", "ODS", "PDF", "JSON"};

    private DocMatrix() {}

    /** 支持读入的扩展名（小写、不含点） */
    public static boolean readable(String ext) {
        String e = ext == null ? "" : ext.toLowerCase();
        return e.equals("docx") || e.equals("xlsx") || e.equals("pptx")
                || e.equals("odt") || e.equals("ods") || e.equals("odp")
                || e.equals("epub") || e.equals("html") || e.equals("htm")
                || e.equals("md") || e.equals("markdown") || e.equals("rtf")
                || e.equals("txt") || e.equals("log") || e.equals("csv") || e.equals("tsv")
                || e.equals("json") || e.equals("xml") || e.equals("yaml") || e.equals("yml");
    }

    public static boolean isTable(String ext) {
        String e = ext == null ? "" : ext.toLowerCase();
        return e.equals("xlsx") || e.equals("csv") || e.equals("tsv") || e.equals("ods");
    }

    public static boolean isLegacyBinary(String ext) {
        String e = ext == null ? "" : ext.toLowerCase();
        return e.equals("doc") || e.equals("wps") || e.equals("wpt") || e.equals("rtf")
                || e.equals("xls") || e.equals("et") || e.equals("ett")
                || e.equals("ppt") || e.equals("dps") || e.equals("dpt")
                || e.equals("mobi") || e.equals("ofd");
    }

    public static String describe(String ext) {
        String e = ext == null ? "" : ext.toLowerCase();
        if (e.equals("docx")) return "Word 文档（保留段落与标题，图片会丢失）";
        if (e.equals("xlsx")) return "Excel 表格（读第一个工作表）";
        if (e.equals("pptx")) return "PowerPoint 演示（逐页取文字）";
        if (e.equals("odt")) return "OpenDocument 文字";
        if (e.equals("ods")) return "OpenDocument 表格";
        if (e.equals("odp")) return "OpenDocument 演示";
        if (e.equals("epub")) return "电子书（按章节顺序取正文）";
        if (e.equals("html") || e.equals("htm")) return "网页";
        if (e.equals("md") || e.equals("markdown")) return "Markdown";
        if (e.equals("rtf")) return "RTF 富文本";
        if (e.equals("csv")) return "CSV 表格";
        if (e.equals("tsv")) return "TSV 表格";
        if (e.equals("json")) return "JSON 文本";
        return "纯文本";
    }

    /** 读成中间文本；表格类输入保留 CSV 形态（制表符或逗号分隔） */
    public static String read(byte[] data, String ext) throws IOException {
        String e = ext == null ? "" : ext.toLowerCase();
        if (e.equals("docx")) return DocConverter.docxToText(data);
        if (e.equals("xlsx")) return DocConverter.xlsxToCsv(data);
        if (e.equals("pptx")) return DocConverter.pptxToText(data);
        if (e.equals("odt") || e.equals("ods") || e.equals("odp")) return Odf.text(data);
        if (e.equals("epub")) return Epub.text(data);
        if (e.equals("rtf")) return Rtf.text(data);
        if (e.equals("html") || e.equals("htm")) return Epub.htmlToText(new String(data, java.nio.charset.Charset.forName("UTF-8")));
        if (e.equals("md") || e.equals("markdown")) {
            String md = new String(data, java.nio.charset.Charset.forName("UTF-8"));
            return Epub.htmlToText(DocConverter.markdownToHtml(md, ""));
        }
        if (e.equals("csv")) return new String(data, java.nio.charset.Charset.forName("UTF-8")).trim();
        if (e.equals("tsv")) return new String(data, java.nio.charset.Charset.forName("UTF-8")).replace('\t', ',').trim();
        // 其余按文本处理，自动判编码
        return EncodingDetector.detect(data).text;
    }

    /**
     * 输出：outLabel 取自 TEXT_OUT / TABLE_OUT 的标签，返回一句说明。
     */
    public static String write(byte[] data, String ext, String srcName, String outLabel, File out)
            throws IOException {
        String text = read(data, ext);
        String base = Ui.baseName(srcName);
        String e = ext == null ? "" : ext.toLowerCase();
        boolean table = isTable(e);
        List<String[]> rows = table ? parseCsv(text) : null;

        if (outLabel.equals("TXT")) {
            writeBytes(out, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return "纯文本，保留换行";
        }
        if (outLabel.equals("Markdown") || outLabel.equals("Markdown 表格")) {
            String md = (table && rows != null) ? toMarkdownTable(rows, base) : toMarkdown(text, base);
            writeBytes(out, md.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return table ? "Markdown 表格，表头取首行" : "Markdown，段落按空行切分";
        }
        if (outLabel.equals("HTML") || outLabel.equals("HTML 表格")) {
            String html = (table && rows != null) ? toHtmlTable(rows, base) : DocConverter.textToHtml(text, base);
            writeBytes(out, html.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return table ? "带样式的 HTML 表格" : "带样式的 HTML 正文";
        }
        if (outLabel.equals("CSV")) {
            writeBytes(out, (table && rows != null ? toCsv(rows, ',') : text).getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return "逗号分隔，可直接用 Excel 打开";
        }
        if (outLabel.equals("TSV")) {
            writeBytes(out, (table && rows != null ? toCsv(rows, '\t') : text.replace('\t', ' ')).getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return "制表符分隔";
        }
        if (outLabel.equals("JSON")) {
            String json = (table && rows != null) ? toJson(rows) : "{\n  \"text\": \"" + escapeJson(text) + "\"\n}";
            writeBytes(out, json.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return table ? "每行一个对象，键取表头" : "{\"text\": ...} 结构";
        }
        if (outLabel.equals("DOCX")) {
            DocxWriter.write(out, (table && rows != null) ? toMarkdownTable(rows, base) : text, base);
            return "Word 文档（段落 + 标题）";
        }
        if (outLabel.equals("ODT")) {
            Odf.writeText(out, (table && rows != null) ? toMarkdownTable(rows, base) : text, base);
            return "ODT 文档（OpenDocument 文字）";
        }
        if (outLabel.equals("RTF")) {
            writeBytes(out, rtf((table && rows != null) ? toMarkdownTable(rows, base) : text, base)
                    .getBytes(java.nio.charset.Charset.forName("UTF-8")));
            return "RTF 富文本（Word / WPS 可直接打开）";
        }
        if (outLabel.equals("XLSX")) {
            XlsxWriter.write(out, rows == null ? parseCsv(text) : rows, base);
            return "Excel 工作簿（单表，首行表头）";
        }
        if (outLabel.equals("ODS")) {
            Odf.writeSheet(out, rows == null ? parseCsv(text) : rows, base);
            return "ODS 表格（OpenDocument 电子表格）";
        }
        if (outLabel.equals("PDF")) {
            PdfExport.slidesToPdf(out, toDeck((table && rows != null) ? toMarkdownTable(rows, base) : text, base));
            return "A4 版面，纯文字排版（长文会自动续页）";
        }
        if (outLabel.equals("EPUB")) {
            Epub.write(out, (table && rows != null) ? toMarkdownTable(rows, base) : text, base);
            return "EPUB 电子书（单章）";
        }
        throw new IOException("不支持的输出：" + outLabel);
    }

    private static String rtf(String text, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fnil Arial;}}")
          .append("\\viewkind4\\uc1\n");
        if (title != null && !title.isEmpty()) {
            sb.append("\\pard\\f0\\fs36\\b ").append(rtfEsc(title)).append("\\b0\\fs22\\par\n\\pard\n");
        }
        String body = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : body.split("\n", -1)) {
            sb.append("\\pard\\f0\\fs22 ").append(rtfEsc(line)).append("\\par\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String rtfEsc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '{' || c == '}') sb.append('\\').append(c);
            else if (c == '\t') sb.append("\\tab ");
            else if (c < 128) sb.append(c);
            else sb.append("\\u").append((int) c).append('?');
        }
        return sb.toString();
    }

    private static void writeBytes(File out, byte[] data) throws IOException {        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    /** 长文按每页约 42 行切页，交给 PdfExport 排版 */
    private static PptxReader.Deck toDeck(String text, String title) {
        PptxReader.Deck deck = new PptxReader.Deck();
        deck.widthPt = 595;
        deck.heightPt = 842;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int perPage = 34;
        List<String> page = new ArrayList<String>();
        boolean first = true;
        for (String line : lines) {
            page.add(line);
            if (page.size() >= perPage) {
                deck.slides.add(slideOf(page, first ? title : null));
                page.clear();
                first = false;
            }
        }
        if (!page.isEmpty() || deck.slides.isEmpty()) {
            if (page.isEmpty() && first) page.add(title);
            deck.slides.add(slideOf(page, first ? title : null));
        }
        return deck;
    }

    private static PptxReader.Slide slideOf(List<String> lines, String title) {
        PptxReader.Slide s = new PptxReader.Slide();
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) sb.append(title).append("\n\n");
        for (String l : lines) sb.append(l).append('\n');
        s.text = sb.toString();
        return s;
    }

    // ------------------------------------------------------------ 表格工具

    public static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<String[]>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',' || c == '\t') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row.toArray(new String[0]));
                row.clear();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }
        // 丢掉整行都是空字符串的情况
        List<String[]> clean = new ArrayList<String[]>();
        for (String[] r : rows) {
            boolean empty = true;
            for (String v : r) if (v != null && !v.trim().isEmpty()) { empty = false; break; }
            if (!empty) clean.add(r);
        }
        return clean;
    }

    private static String toCsv(List<String[]> rows, char sep) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(sep);
                String v = r[i] == null ? "" : r[i];
                if (v.indexOf(sep) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String toMarkdownTable(List<String[]> rows, String title) {
        if (rows.isEmpty()) return "# " + title + "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        String[] head = rows.get(0);
        sb.append('|');
        for (String h : head) sb.append(' ').append(h.replace("|", "\\|")).append(" |");
        sb.append("\n|");
        for (int i = 0; i < head.length; i++) sb.append(" --- |");
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            sb.append('|');
            for (int i = 0; i < head.length; i++) {
                String v = i < row.length ? row[i] : "";
                sb.append(' ').append(v.replace("|", "\\|").replace("\n", " ")).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String toHtmlTable(List<String[]> rows, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
          .append("<title>").append(Ooxml.esc(title)).append("</title>")
          .append("<style>body{font-family:-apple-system,'Microsoft YaHei',sans-serif;margin:24px;}")
          .append("table{border-collapse:collapse;width:100%;font-size:13px;}")
          .append("th,td{border:1px solid #ccc;padding:6px 8px;text-align:left;}")
          .append("th{background:#f2f5f9;}h1{font-size:18px;}</style></head><body>")
          .append("<h1>").append(Ooxml.esc(title)).append("</h1><table>");
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            sb.append("<tr>");
            for (String v : row) {
                String tag = r == 0 ? "th" : "td";
                sb.append('<').append(tag).append('>').append(Ooxml.esc(v)).append("</").append(tag).append('>');
            }
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String toJson(List<String[]> rows) {
        if (rows.isEmpty()) return "[]";
        String[] head = rows.get(0);
        StringBuilder sb = new StringBuilder("[\n");
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            sb.append("  {");
            for (int i = 0; i < head.length; i++) {
                if (i > 0) sb.append(", ");
                String key = head[i].trim().isEmpty() ? "列" + (i + 1) : head[i].trim();
                String v = i < row.length ? row[i] : "";
                sb.append('"').append(escapeJson(key)).append("\": \"").append(escapeJson(v)).append('"');
            }
            sb.append("}");
            sb.append(r + 1 < rows.size() ? ",\n" : "\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toMarkdown(String text, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        for (String block : text.replace("\r\n", "\n").split("\n\\s*\n")) {
            String t = block.trim();
            if (t.isEmpty()) continue;
            sb.append(t).append("\n\n");
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
