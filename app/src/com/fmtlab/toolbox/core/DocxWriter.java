package com.fmtlab.toolbox.core;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 纯文本 → docx（Word 2007 起的最小可用包：一个正文部件 + 一套基础样式）。 */
public final class DocxWriter {

    private DocxWriter() {}

    public static void write(File out, String text, String title) throws IOException {
        String body = paragraphsXml(text);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("[Content_Types].xml", contentTypes(false));
        entries.put("_rels/.rels", rootRels());
        entries.put("word/document.xml", document(body));
        entries.put("word/_rels/document.xml.rels", docRels());
        entries.put("word/styles.xml", styles());
        entries.put("docProps/core.xml", core(title));
        entries.put("docProps/app.xml", app());
        Ooxml.writeZip(out, entries);
    }

    /**
     * 每页一张图的 Word（用于扫描件、字体缺编码表的 PDF）。
     * sizes 是每张图的像素宽高，按 A4 正文区域等比缩放。
     */
    public static void writeImages(File out, List<File> images, List<int[]> sizes, String title)
            throws IOException {
        if (images == null || images.isEmpty()) throw new IOException("没有可写入的页面图片");
        // A4 正文区域：11906×16838 twip，页边距 1134 twip；1 twip = 635 EMU
        final int contentW = (11906 - 1134 * 2) * 635;
        final int contentH = (16838 - 1134 * 2) * 635;

        StringBuilder body = new StringBuilder();
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        StringBuilder rels = new StringBuilder();
        rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            .append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");

        for (int i = 0; i < images.size(); i++) {
            File img = images.get(i);
            byte[] data = readAll(img);
            int[] wh = sizes != null && i < sizes.size() ? sizes.get(i) : null;
            int pw = wh != null && wh[0] > 0 ? wh[0] : 1000;
            int ph = wh != null && wh[1] > 0 ? wh[1] : 1414;
            long cx = contentW;
            long cy = (long) Math.round((double) contentW * ph / pw);
            if (cy > contentH) {
                cy = contentH;
                cx = (long) Math.round((double) contentH * pw / ph);
            }
            int rid = i + 1;
            entries.put("word/media/image" + rid + ".png", data);
            rels.append("<Relationship Id=\"rIdImg").append(rid)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                .append(rid).append(".png\"/>");
            body.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"0\"/></w:pPr><w:r><w:drawing>")
                .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:docPr id=\"").append(rid).append("\" name=\"Page ").append(rid).append("\"/>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(rid).append("\" name=\"image")
                .append(rid).append(".png\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"rIdImg").append(rid).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"")
                .append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
                .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
            if (i + 1 < images.size()) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
        }
        rels.append("</Relationships>");

        entries.put("[Content_Types].xml", contentTypes(true));
        entries.put("_rels/.rels", rootRels());
        entries.put("word/document.xml", document(body.toString()));
        entries.put("word/_rels/document.xml.rels", rels.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("word/styles.xml", styles());
        entries.put("docProps/core.xml", core(title));
        entries.put("docProps/app.xml", app());
        Ooxml.writeZip(out, entries);
    }

    private static String paragraphsXml(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean first = true;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!first) sb.append("<w:p/>");
                continue;
            }
            sb.append("<w:p><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/>")
              .append(first ? "<w:jc w:val=\"left\"/>" : "")
              .append("</w:pPr><w:r><w:rPr>")
              .append(first ? "<w:b/><w:sz w:val=\"32\"/>" : "<w:sz w:val=\"22\"/>")
              .append("<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Microsoft YaHei\"/>")
              .append("</w:rPr><w:t xml:space=\"preserve\">").append(Ooxml.esc(t)).append("</w:t></w:r></w:p>");
            first = false;
        }
        return sb.toString();
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.max(1, f.length())];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return buf;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] contentTypes(boolean withPng) {
        String png = withPng
                ? "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                : "";
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + png
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                + "</Types>").getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] rootRels() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>").getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] document(String body) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>"
                + "</w:body></w:document>";
        return xml.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] docRels() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>").getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] styles() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Microsoft YaHei\"/>"
                + "<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Normal\" w:default=\"1\"><w:name w:val=\"Normal\"/></w:style>"
                + "</w:styles>";
        return xml.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] core(String title) {
        String t = title == null ? "" : Ooxml.esc(title);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + t + "</dc:title><dc:creator>袁神的格式工坊</dc:creator>"
                + "<cp:lastModifiedBy>袁神的格式工坊</cp:lastModifiedBy></cp:coreProperties>";
        return xml.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] app() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Application>袁神的格式工坊</Application></Properties>";
        return xml.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }
}
