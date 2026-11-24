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

    private static final long EMU_PER_PT = 12700L;

    /** 按 PDF 原始坐标输出：文字用无边框文本框，图片与表格线用锚定图形 */
    public static void writeLayout(File out, List<PdfLayout.PageEl> pages, String title)
            throws IOException {
        if (pages == null || pages.isEmpty()) throw new IOException("没有可写入的页面");
        StringBuilder body = new StringBuilder();
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        StringBuilder rels = new StringBuilder();
        rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            .append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");

        int[] pid = new int[]{200};
        int media = 0;
        for (int pi = 0; pi < pages.size(); pi++) {
            PdfLayout.PageEl pg = pages.get(pi);
            body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>")
                .append("<w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr>");
            for (PdfLayout.ShapeEl s : pg.shapes) body.append(anchorShape(s, pid));
            for (PdfLayout.PicEl pic : pg.pics) {
                media++;
                String ext = "jpg".equals(pic.ext) ? "jpg" : "png";
                entries.put("word/media/image" + media + "." + ext, pic.data);
                rels.append("<Relationship Id=\"rIdImg").append(media)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                    .append(media).append('.').append(ext).append("\"/>");
                body.append(anchorPic(pic, media, pid));
            }
            for (PdfLayout.TextEl t : pg.texts) body.append(anchorText(t, pid));
            body.append("</w:p>");
            if (pi + 1 < pages.size()) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
        }
        rels.append("</Relationships>");

        float pw = pages.get(0).w, ph = pages.get(0).h;
        String sect = "<w:sectPr><w:pgSz w:w=\"" + Math.round(pw * 20) + "\" w:h=\"" + Math.round(ph * 20)
                + "\"/><w:pgMar w:top=\"0\" w:right=\"0\" w:bottom=\"0\" w:left=\"0\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>";

        entries.put("[Content_Types].xml", contentTypes(true));
        entries.put("_rels/.rels", rootRels());
        entries.put("word/document.xml", document(body.toString(), sect));
        entries.put("word/_rels/document.xml.rels", rels.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")));
        entries.put("word/styles.xml", styles());
        entries.put("docProps/core.xml", core(title));
        entries.put("docProps/app.xml", app());
        Ooxml.writeZip(out, entries);
    }

    private static String anchorText(PdfLayout.TextEl t, int[] pid) {
        long x = Math.max(0, (long) (t.x * EMU_PER_PT));
        long y = Math.max(0, (long) (t.y * EMU_PER_PT));
        long cx = Math.max((long) (4 * EMU_PER_PT), (long) (t.width * EMU_PER_PT));
        long cy = Math.max((long) (8 * EMU_PER_PT), (long) (t.size * 1.4f * EMU_PER_PT));
        int sz = Math.max(2, Math.round(t.size * 2));
        StringBuilder rPr = new StringBuilder();
        rPr.append("<w:rPr><w:rFonts w:ascii=\"").append(Ooxml.esc(t.font)).append("\" w:hAnsi=\"")
           .append(Ooxml.esc(t.font)).append("\" w:eastAsia=\"").append(Ooxml.esc(t.font)).append("\"/>")
           .append("<w:color w:val=\"").append(t.color).append("\"/>")
           .append("<w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/>");
        if (t.bold) rPr.append("<w:b/>");
        if (t.italic) rPr.append("<w:i/>");
        rPr.append("</w:rPr>");
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"" + (pid[0]) + "\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + x + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + y + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + (pid[0]++) + "\" name=\"T" + pid[0] + "\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>"
                + "<wps:txbx><w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>"
                + rPr + "</w:pPr><w:r>" + rPr + "<w:t xml:space=\"preserve\">" + Ooxml.esc(t.text) + "</w:t></w:r></w:p></w:txbxContent></wps:txbx>"
                + "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr>"
                + "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private static String anchorPic(PdfLayout.PicEl p, int media, int[] pid) {
        long x = Math.max(0, (long) (p.x * EMU_PER_PT));
        long y = Math.max(0, (long) (p.y * EMU_PER_PT));
        long cx = Math.max(1, (long) (p.w * EMU_PER_PT));
        long cy = Math.max(1, (long) (p.h * EMU_PER_PT));
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"" + (pid[0]) + "\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + x + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + y + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + (pid[0]++) + "\" name=\"P" + pid[0] + "\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + media + "\" name=\"image" + media + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rIdImg" + media + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private static String anchorShape(PdfLayout.ShapeEl s, int[] pid) {
        long x = Math.max(0, (long) (s.x * EMU_PER_PT));
        long y = Math.max(0, (long) (s.y * EMU_PER_PT));
        long cx = Math.max(1, (long) (s.w * EMU_PER_PT));
        long cy = Math.max(1, (long) (s.h * EMU_PER_PT));
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"" + (pid[0]) + "\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + x + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + y + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + (pid[0]++) + "\" name=\"S" + pid[0] + "\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
                + "<a:solidFill><a:srgbClr val=\"" + s.color + "\"/></a:solidFill><a:ln><a:noFill/></a:ln></wps:spPr>"
                + "<wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
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
                + "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
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
        return document(body,
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                        + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>");
    }

    private static byte[] document(String body, String sect) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
                + "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<w:body>" + body
                + sect
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
