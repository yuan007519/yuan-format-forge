package com.fmtlab.toolbox.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取 PPTX：按幻灯片顺序取文字和图片，用于转 PDF */
public final class PptxReader {

    public static final class Slide {
        public String text = "";
        public byte[] image;
    }

    public static final class Deck {
        public int widthPt = 595;
        public int heightPt = 842;
        public final List<Slide> slides = new ArrayList<Slide>();
    }

    private static final Pattern P_PARA = Pattern.compile("<a:p\\b[^>]*>(.*?)</a:p>", Pattern.DOTALL);
    private static final Pattern P_AT = Pattern.compile("<a:t\\b[^>]*>(.*?)</a:t>", Pattern.DOTALL);
    private static final Pattern P_SLDID = Pattern.compile("<p:sldId\\b[^>]*r:id=\"([^\"]+)\"");
    private static final Pattern P_REL = Pattern.compile("<Relationship\\b([^>]*)/>");
    private static final Pattern P_SLIDE_NAME = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");

    private PptxReader() {}

    public static Deck read(byte[] data) throws IOException {
        Map<String, byte[]> files = Ooxml.unzip(data);
        String pres = Ooxml.str(files, "ppt/presentation.xml");
        if (pres == null) throw new IOException("不是有效的 PPTX 文件（缺少 ppt/presentation.xml）");

        Deck deck = new Deck();
        try {
            Matcher sz = Pattern.compile("<p:sldSz\\b[^>]*cx=\"(\\d+)\"[^>]*cy=\"(\\d+)\"").matcher(pres);
            if (sz.find()) {
                deck.widthPt = Math.max(1, Integer.parseInt(sz.group(1)) / 12700);
                deck.heightPt = Math.max(1, Integer.parseInt(sz.group(2)) / 12700);
            }
        } catch (Exception ignored) {}

        String relsXml = Ooxml.str(files, "ppt/_rels/presentation.xml.rels");
        Map<String, String> relTarget = new LinkedHashMap<String, String>();
        if (relsXml != null) {
            Matcher m = P_REL.matcher(relsXml);
            while (m.find()) {
                String attrs = m.group(1);
                String id = attr(attrs, "Id");
                String target = attr(attrs, "Target");
                if (id != null && target != null) relTarget.put(id, target.replace('\\', '/'));
            }
        }

        List<String> order = new ArrayList<String>();
        Matcher sm = P_SLDID.matcher(pres);
        while (sm.find()) {
            String target = relTarget.get(sm.group(1));
            if (target == null) continue;
            order.add(resolve("ppt", target));
        }
        if (order.isEmpty()) {
            // 关系表缺失时按文件名数字排序兜底
            List<Integer> nums = new ArrayList<Integer>();
            Map<Integer, String> keyOf = new LinkedHashMap<Integer, String>();
            for (String key : files.keySet()) {
                Matcher m = P_SLIDE_NAME.matcher(key);
                if (m.matches()) {
                    int n = Integer.parseInt(m.group(1));
                    nums.add(n);
                    keyOf.put(n, key);
                }
            }
            Collections.sort(nums, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return a - b;
                }
            });
            for (Integer n : nums) order.add(keyOf.get(n));
        }
        if (order.isEmpty()) throw new IOException("PPTX 里没有幻灯片");

        for (String path : order) {
            String xml = Ooxml.str(files, path);
            if (xml == null) continue;
            Slide s = new Slide();
            s.text = textOf(xml);
            s.image = imageOf(files, path);
            deck.slides.add(s);
        }
        if (deck.slides.isEmpty()) throw new IOException("PPTX 里没有可读的幻灯片");
        return deck;
    }

    private static String textOf(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher p = P_PARA.matcher(xml);
        while (p.find()) {
            String inner = p.group(1);
            StringBuilder line = new StringBuilder();
            Matcher t = P_AT.matcher(inner);
            while (t.find()) line.append(Ooxml.unesc(t.group(1)));
            if (line.length() > 0) sb.append(line).append('\n');
        }
        // 段落之外散落的 a:t（少见）
        if (sb.length() == 0) {
            Matcher t = P_AT.matcher(xml);
            while (t.find()) {
                String v = Ooxml.unesc(t.group(1)).trim();
                if (v.length() > 0) sb.append(v).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static byte[] imageOf(Map<String, byte[]> files, String slidePath) {
        // ppt/slides/slide3.xml → ppt/slides/_rels/slide3.xml.rels
        int slash = slidePath.lastIndexOf('/');
        if (slash < 0) return null;
        String dir = slidePath.substring(0, slash);
        String name = slidePath.substring(slash + 1);
        String relsPath = dir + "/_rels/" + name + ".rels";
        String rels = Ooxml.str(files, relsPath);
        if (rels == null) return null;

        Matcher m = P_REL.matcher(rels);
        String best = null;
        while (m.find()) {
            String attrs = m.group(1);
            String type = attr(attrs, "Type");
            String target = attr(attrs, "Target");
            if (target == null) continue;
            if (type == null || !type.endsWith("/image")) continue;
            String lower = target.toLowerCase(java.util.Locale.US);
            if (!lower.endsWith(".jpeg") && !lower.endsWith(".jpg") && !lower.endsWith(".png")) continue;
            String full = resolve(dir, target);
            if (files.containsKey(full)) {
                best = full;
                // 优先取面积最大的图片：直接第一个命中的就够用
                break;
            }
        }
        return best == null ? null : files.get(best);
    }

    /** 把 rels 里的相对路径（可能带 ../）解析成 zip 内的绝对路径 */
    private static String resolve(String baseDir, String target) {
        String t = target.replace('\\', '/');
        String joined;
        if (t.startsWith("/")) {
            joined = t.substring(1);
        } else {
            joined = baseDir + "/" + t;
        }
        List<String> out = new ArrayList<String>();
        for (String seg : joined.split("/")) {
            if (seg.length() == 0 || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }
}
