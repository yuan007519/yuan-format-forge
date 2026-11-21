package com.fmtlab.toolbox.core;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenDocument 读取（odt / ods / odp）：只取正文文字与表格单元格，忽略样式。 */
public final class Odf {

    private Odf() {}

    public static String text(byte[] data) throws IOException {
        Map<String, byte[]> files = Ooxml.unzip(data);
        String content = Ooxml.str(files, "content.xml");
        if (content == null) throw new IOException("不是有效的 OpenDocument 文件（缺少 content.xml）");

        // 特殊空白元素先替换成真实字符
        String s = content;
        Matcher sp = Pattern.compile("<text:s\\b[^>]*text:c=\"(\\d+)\"[^>]*/>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (sp.find()) {
            StringBuilder pad = new StringBuilder();
            int n = Integer.parseInt(sp.group(1));
            for (int i = 0; i < n; i++) pad.append(' ');
            sp.appendReplacement(sb, Matcher.quoteReplacement(pad.toString()));
        }
        sp.appendTail(sb);
        s = sb.toString();
        s = s.replace("<text:s/>", " ").replace("<text:s />", " ");
        s = s.replaceAll("<text:tab\\b[^>]*/>", "\t");
        s = s.replaceAll("<text:line-break\\b[^>]*/>", "\n");

        // 块级结构与表格
        s = s.replaceAll("</text:p>", "\n");
        s = s.replaceAll("</text:h>", "\n");
        s = s.replaceAll("<text:p\\b[^>]*/>", "\n");
        s = s.replaceAll("</table:table-cell>", "\t");
        s = s.replaceAll("<table:table-cell\\b[^>]*/>", "\t");
        s = s.replaceAll("</table:table-row>", "\n");
        s = s.replaceAll("</table:table>", "\n");
        s = s.replaceAll("(?is)<dc:.*?>", "");

        s = s.replaceAll("<[^>]+>", "");
        s = Ooxml.unesc(s);
        s = s.replace('\u00A0', ' ');

        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replaceAll("[ \\t]+", " ").trim();
            if (t.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(t).append('\n');
        }
        String result = out.toString().replaceAll("\n{3,}", "\n\n").trim();
        if (result.isEmpty()) throw new IOException("OpenDocument 里没有可读的文字");
        return result;
    }

    /** 纯文本 → .odt：只写正文段落，Word / WPS / LibreOffice 均可打开 */
    public static void writeText(java.io.File out, String text, String title) throws IOException {
        String safe = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder body = new StringBuilder();
        for (String line : safe.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                body.append("<text:p text:style-name=\"TextBody\"/>");
            } else {
                body.append("<text:p text:style-name=\"TextBody\">")
                        .append(Ooxml.esc(line))
                        .append("</text:p>");
            }
        }
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
                + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
                + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
                + " office:version=\"1.3\">"
                + "<office:body><office:text>" + body + "</office:text></office:body>"
                + "</office:document-content>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-styles"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
                + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
                + " office:version=\"1.3\"><office:styles>"
                + "<style:style style:name=\"TextBody\" style:family=\"paragraph\">"
                + "<style:paragraph-properties fo:margin-bottom=\"0.2cm\"/>"
                + "<style:text-properties fo:font-size=\"11pt\"/>"
                + "</style:style></office:styles></office:document-styles>";
        String manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\""
                + " manifest:version=\"1.3\">"
                + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
                + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
                + "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>"
                + "</manifest:manifest>";
        String meta = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-meta"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " office:version=\"1.3\"><office:meta>"
                + "<dc:title>" + Ooxml.esc(title == null ? "" : title) + "</dc:title>"
                + "</office:meta></office:document-meta>";

        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("mimetype", "application/vnd.oasis.opendocument.text"
                .getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("content.xml", content.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("styles.xml", styles.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("meta.xml", meta.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("META-INF/manifest.xml", manifest.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        Ooxml.writeZip(out, entries);
    }
}
