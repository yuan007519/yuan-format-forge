package com.fmtlab.toolbox.core;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 生成可被 PowerPoint / WPS / Keynote 打开的 PPTX：
 * 每一页 PDF 渲染成一张 JPEG，放进一张“满版图片”幻灯片。
 * 结构遵循 ECMA-376（presentation + slideMaster + slideLayout + theme + media）。
 */
public final class PptxWriter {

    private static final int EMU_PER_PT = 12700;

    private static final String NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";

    private PptxWriter() {}

    public static void fromImages(File out, List<byte[]> jpegs, int[] pageWpt, int[] pageHpt, String title)
            throws IOException {
        if (jpegs == null || jpegs.isEmpty()) throw new IOException("没有可写入的页面");

        int wpt = 595, hpt = 842;
        for (int i = 0; i < pageWpt.length; i++) {
            if (jpegs.get(i) != null && pageWpt[i] > 0 && pageHpt[i] > 0) {
                wpt = pageWpt[i];
                hpt = pageHpt[i];
                break;
            }
        }
        long slideW = (long) wpt * EMU_PER_PT;
        long slideH = (long) hpt * EMU_PER_PT;

        int n = jpegs.size();
        Map<String, byte[]> z = new LinkedHashMap<String, byte[]>();

        StringBuilder ct = new StringBuilder();
        ct.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        ct.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>");
        ct.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>");
        ct.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>");
        ct.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>");
        ct.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
        for (int i = 1; i <= n; i++) {
            ct.append("<Override PartName=\"/ppt/slides/slide").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
        }
        ct.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
        ct.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        ct.append("</Types>");
        z.put("[Content_Types].xml", Ooxml.bytes(ct.toString()));

        String relsRoot = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
        z.put("_rels/.rels", Ooxml.bytes(relsRoot));

        StringBuilder pres = new StringBuilder();
        pres.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        pres.append("<p:presentation ").append(NS).append(" saveSubsetFonts=\"1\">");
        pres.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>");
        pres.append("<p:sldIdLst>");
        for (int i = 1; i <= n; i++) {
            pres.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        pres.append("</p:sldIdLst>");
        pres.append("<p:sldSz cx=\"").append(slideW).append("\" cy=\"").append(slideH).append("\" type=\"custom\"/>");
        pres.append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>");
        pres.append("</p:presentation>");
        z.put("ppt/presentation.xml", Ooxml.bytes(pres.toString()));

        StringBuilder presRels = new StringBuilder();
        presRels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        presRels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        presRels.append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
        for (int i = 1; i <= n; i++) {
            presRels.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                    .append(i).append(".xml\"/>");
        }
        presRels.append("</Relationships>");
        z.put("ppt/_rels/presentation.xml.rels", Ooxml.bytes(presRels.toString()));

        z.put("ppt/slideMasters/slideMaster1.xml", Ooxml.bytes(slideMaster()));
        z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", Ooxml.bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                        + "</Relationships>"));
        z.put("ppt/slideLayouts/slideLayout1.xml", Ooxml.bytes(slideLayout()));
        z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", Ooxml.bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>"
                        + "</Relationships>"));
        z.put("ppt/theme/theme1.xml", Ooxml.bytes(theme()));

        for (int i = 1; i <= n; i++) {
            byte[] jpeg = jpegs.get(i - 1);
            long pw = (long) (pageWpt[i - 1] > 0 ? pageWpt[i - 1] : wpt) * EMU_PER_PT;
            long ph = (long) (pageHpt[i - 1] > 0 ? pageHpt[i - 1] : hpt) * EMU_PER_PT;
            long ox = 0, oy = 0, cw = slideW, ch = slideH;
            // 页面比例和幻灯片不一致时等比缩放并居中（留白而不是拉伸变形）
            double s = Math.min((double) slideW / pw, (double) slideH / ph);
            cw = Math.round(pw * s);
            ch = Math.round(ph * s);
            ox = (slideW - cw) / 2;
            oy = (slideH - ch) / 2;

            StringBuilder sl = new StringBuilder();
            sl.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            sl.append("<p:sld ").append(NS).append(">");
            sl.append("<p:cSld><p:spTree>");
            sl.append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>");
            sl.append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>")
                    .append("<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>");
            sl.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(i + 1).append("\" name=\"第 ").append(i)
                    .append(" 页\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>");
            sl.append("<p:blipFill><a:blip r:embed=\"rId1\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>");
            sl.append("<p:spPr><a:xfrm><a:off x=\"").append(ox).append("\" y=\"").append(oy)
                    .append("\"/><a:ext cx=\"").append(cw).append("\" cy=\"").append(ch)
                    .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>");
            sl.append("</p:pic>");
            sl.append("</p:spTree></p:cSld>");
            sl.append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>");
            sl.append("</p:sld>");
            z.put("ppt/slides/slide" + i + ".xml", Ooxml.bytes(sl.toString()));

            String srels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image"
                    + i + ".jpeg\"/>"
                    + "</Relationships>";
            z.put("ppt/slides/_rels/slide" + i + ".xml.rels", Ooxml.bytes(srels));
            z.put("ppt/media/image" + i + ".jpeg", jpeg);
        }

        String safeTitle = Ooxml.esc(title == null || title.length() == 0 ? "PDF 转幻灯片" : title);
        z.put("docProps/core.xml", Ooxml.bytes("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + safeTitle + "</dc:title>"
                + "<dc:creator>袁神的格式工坊</dc:creator>"
                + "<cp:lastModifiedBy>袁神的格式工坊</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">2024-01-01T00:00:00Z</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">2024-01-01T00:00:00Z</dcterms:modified>"
                + "</cp:coreProperties>"));
        z.put("docProps/app.xml", Ooxml.bytes("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<ExtendedProperties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Application>袁神的格式工坊</Application><Slides>" + n + "</Slides>"
                + "</ExtendedProperties>"));

        Ooxml.writeZip(out, z);
    }

    private static String slideMaster() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldMaster " + NS + ">"
                + "<p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                + "</p:spTree></p:cSld>"
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\""
                + " hlink=\"hlink\" folHlink=\"folHlink\"/>"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                + "<p:txStyles>"
                + "<p:titleStyle><a:lvl1pPr><a:defRPr sz=\"3200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"
                + "<a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/></a:defRPr></a:lvl1pPr></p:titleStyle>"
                + "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"
                + "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>"
                + "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"
                + "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/></a:defRPr></a:lvl1pPr></p:otherStyle>"
                + "</p:txStyles>"
                + "</p:sldMaster>";
    }

    private static String slideLayout() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldLayout " + NS + " type=\"blank\" preserve=\"1\">"
                + "<p:cSld name=\"空白\"><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                + "</p:spTree></p:cSld>"
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"
                + "</p:sldLayout>";
    }

    private static String theme() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\">"
                + "<a:themeElements>"
                + "<a:clrScheme name=\"Office\">"
                + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
                + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
                + "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2>"
                + "<a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>"
                + "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>"
                + "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>"
                + "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>"
                + "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>"
                + "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>"
                + "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>"
                + "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>"
                + "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>"
                + "</a:clrScheme>"
                + "<a:fontScheme name=\"Office\">"
                + "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
                + "</a:fontScheme>"
                + "<a:fmtScheme name=\"Office\">"
                + "<a:fillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "</a:fillStyleLst>"
                + "<a:lnStyleLst>"
                + "<a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
                + "<a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
                + "<a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
                + "</a:lnStyleLst>"
                + "<a:effectStyleLst>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "</a:effectStyleLst>"
                + "<a:bgFillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "</a:bgFillStyleLst>"
                + "</a:fmtScheme>"
                + "</a:themeElements>"
                + "</a:theme>";
    }
}
