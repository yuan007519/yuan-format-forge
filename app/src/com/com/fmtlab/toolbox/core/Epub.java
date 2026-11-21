package com.fmtlab.toolbox.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EPUB 读写：读走 container.xml → opf → spine，写生成最小合法 EPUB 3 包。 */
public final class Epub {

    private static final Pattern ITEM = Pattern.compile("<item\\b([^>]*)/?>");
    private static final Pattern ITEMREF = Pattern.compile("<itemref\\b([^>]*)/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private Epub() {}

    public static String text(byte[] data) throws IOException {
        Map<String, byte[]> files = Ooxml.unzip(data);
        String opfPath = null;
        String container = Ooxml.str(files, "META-INF/container.xml");
        if (container != null) {
            Matcher m = Pattern.compile("<rootfile\\b[^>]*full-path=\"([^\"]+)\"").matcher(container);
            if (m.find()) opfPath = m.group(1);
        }
        if (opfPath == null) {
            for (String k : files.keySet()) {
                if (k.toLowerCase().endsWith(".opf")) {
                    opfPath = k;
                    break;
                }
            }
        }
        if (opfPath == null) throw new IOException("EPUB 里找不到 .opf 清单");
        String opf = Ooxml.str(files, opfPath);
        if (opf == null) throw new IOException("EPUB 清单读取失败");

        Map<String, String> manifest = new LinkedHashMap<String, String>();
        Matcher im = ITEM.matcher(opf);
        while (im.find()) {
            String attrs = im.group(1);
            String id = attr(attrs, "id");
            String href = attr(attrs, "href");
            String type = attr(attrs, "media-type");
            if (id != null && href != null && (type == null || type.contains("html") || type.contains("xml"))) {
                manifest.put(id, href);
            }
        }
        String baseDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        List<String> order = new ArrayList<String>();
        Matcher ir = ITEMREF.matcher(opf);
        while (ir.find()) {
            String idref = attr(ir.group(1), "idref");
            if (idref != null && manifest.containsKey(idref)) order.add(manifest.get(idref));
        }
        if (order.isEmpty()) order.addAll(manifest.values());

        StringBuilder sb = new StringBuilder();
        for (String href : order) {
            String path = resolve(baseDir, href);
            byte[] b = files.get(path);
            if (b == null) continue;
            String html = new String(b, java.nio.charset.Charset.forName("UTF-8"));
            String plain = htmlToText(html);
            if (plain.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(plain.trim());
        }
        if (sb.length() == 0) throw new IOException("EPUB 里没有可读的正文");
        return sb.toString();
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String resolve(String baseDir, String href) {
        String p = href;
        int hash = p.indexOf('#');
        if (hash >= 0) p = p.substring(0, hash);
        if (p.startsWith("/")) return p.substring(1);
        String joined = baseDir + p;
        String[] parts = joined.split("/");
        List<String> out = new ArrayList<String>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    /** HTML/XHTML 转纯文本：块级标签换行，行内标签去掉，实体还原 */
    public static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>", "\n");
        s = s.replaceAll("(?i)<(p|div|h[1-6]|li|tr|section|article|blockquote)\\b[^>]*>", "\n");
        s = s.replaceAll("(?i)<td\\b[^>]*>", "\t");
        s = s.replaceAll("(?i)</td>", "\t");
        s = TAG.matcher(s).replaceAll("");
        s = Ooxml.unesc(s);
        s = s.replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("\n{3,}", "\n\n");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) sb.append(line.trim()).append('\n');
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ 写

    public static void write(File out, String text, String title) throws IOException {
        String safeTitle = title == null || title.isEmpty() ? "未命名" : title;
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("mimetype", "application/epub+zip".getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("META-INF/container.xml", bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                        + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                        + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"));
        entries.put("OEBPS/content.opf", bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">"
                        + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                        + "<dc:identifier id=\"bookid\">fmtlab-" + Math.abs(safeTitle.hashCode()) + "</dc:identifier>"
                        + "<dc:title>" + Ooxml.esc(safeTitle) + "</dc:title>"
                        + "<dc:language>zh-CN</dc:language>"
                        + "<dc:creator>袁神的格式工坊</dc:creator>"
                        + "<meta property=\"dcterms:modified\">1970-01-01T00:00:00Z</meta>"
                        + "</metadata><manifest>"
                        + "<item id=\"nav\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                        + "</manifest><spine><itemref idref=\"nav\"/></spine></package>"));
        entries.put("OEBPS/chapter.xhtml", bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh-CN\">"
                        + "<head><meta charset=\"utf-8\"/><title>" + Ooxml.esc(safeTitle) + "</title>"
                        + "<style>body{font-family:serif;line-height:1.7;margin:1em;}"
                        + "p{margin:0 0 0.6em 0;white-space:pre-wrap;}</style></head><body>"
                        + "<h1>" + Ooxml.esc(safeTitle) + "</h1>" + paragraphs(text)
                        + "</body></html>"));
        Ooxml.writeZip(out, entries);
    }

    private static String paragraphs(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                flush(sb, block);
                continue;
            }
            if (block.length() > 0) block.append('\n');
            block.append(line);
        }
        flush(sb, block);
        if (sb.length() == 0) sb.append("<p></p>");
        return sb.toString();
    }

    private static void flush(StringBuilder out, StringBuilder block) {
        if (block.length() == 0) return;
        out.append("<p>").append(Ooxml.esc(block.toString())).append("</p>");
        block.setLength(0);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }
}
