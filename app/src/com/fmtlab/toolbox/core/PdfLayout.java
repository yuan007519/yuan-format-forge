package com.fmtlab.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * PDF 版式解析：把每页的文字、图片、线条按原始坐标取出来，供 DocxWriter 写成绝对定位的 Word。
 * 只做“看得见的版式还原”，不重排段落，也不做表格语义识别。
 */
public final class PdfLayout {

    public static final class TextEl {
        public float x;      // 左边界（pt，原点左上）
        public float y;      // 文本框顶边（pt）
        public float size;   // 字号（pt）
        public float width;  // 估算宽度（pt）
        public String text;
        public String font;
        public String color = "000000";
        public boolean bold;
        public boolean italic;
    }

    public static final class PicEl {
        public float x, y, w, h;
        public byte[] data;
        public String ext = "png";
    }

    public static final class ShapeEl {
        public float x, y, w, h;
        public String color = "000000";
    }

    public static final class PageEl {
        public float w = 595, h = 842;
        public final List<TextEl> texts = new ArrayList<TextEl>();
        public final List<PicEl> pics = new ArrayList<PicEl>();
        public final List<ShapeEl> shapes = new ArrayList<ShapeEl>();
    }

    private static final java.nio.charset.Charset CS = java.nio.charset.Charset.forName("ISO-8859-1");

    private PdfLayout() {}

    public static final class Font {
        byte[] dict;
        String base = "Helvetica";
        String family = "Arial";
        boolean bold, italic, cid;
        PdfText.CMap cm;
        int firstChar;
        float[] widths;
        final Map<Integer, Float> wmap = new HashMap<Integer, Float>();
    }

    // ------------------------------------------------------------------ 页与资源

    public static List<PageEl> read(byte[] pdf) throws Exception {
        Map<String, byte[]> objs = PdfText.readObjects(pdf);
        List<PageEl> pages = new ArrayList<PageEl>();
        for (String num : pageOrder(objs)) {
            byte[] obj = objs.get(num);
            if (obj == null) continue;
            PageEl page = readPage(latin(obj), objs);
            if (page != null && (!page.texts.isEmpty() || !page.pics.isEmpty() || !page.shapes.isEmpty())) {
                pages.add(page);
            }
        }
        return pages;
    }

    /** 按页树的 /Kids 顺序取页对象号，避免 HashMap 迭代把多页文档排乱 */
    private static List<String> pageOrder(Map<String, byte[]> objs) {
        List<String> out = new ArrayList<String>();
        String root = null;
        for (byte[] obj : objs.values()) {
            String d = latin(obj);
            if (!Pattern.compile("/Type\\s*/Catalog").matcher(d).find()) continue;
            Matcher m = Pattern.compile("/Pages\\s+(\\d+)\\s+0\\s+R").matcher(d);
            if (m.find()) {
                root = m.group(1);
                break;
            }
        }
        if (root != null) walkPages(root, objs, out, 0);
        if (out.isEmpty()) {
            for (Map.Entry<String, byte[]> e : objs.entrySet()) {
                String d = latin(e.getValue());
                if (Pattern.compile("/Type\\s*/Page\\b").matcher(d).find()
                        && !Pattern.compile("/Type\\s*/Pages\\b").matcher(d).find()) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    private static void walkPages(String num, Map<String, byte[]> objs, List<String> out, int depth) {
        if (depth > 40) return;
        byte[] b = objs.get(num);
        if (b == null) return;
        String d = latin(b);
        Matcher kids = Pattern.compile("/Kids\\s*\\[([^\\]]*)\\]", Pattern.DOTALL).matcher(d);
        if (Pattern.compile("/Type\\s*/Pages\\b").matcher(d).find() && kids.find()) {
            Matcher ref = Pattern.compile("(\\d+)\\s+0\\s+R").matcher(kids.group(1));
            while (ref.find()) walkPages(ref.group(1), objs, out, depth + 1);
            return;
        }
        if (Pattern.compile("/Type\\s*/Page\\b").matcher(d).find()) out.add(num);
    }

    private static PageEl readPage(String dict, Map<String, byte[]> objs) {
        float[] box = numbers(dict, "/MediaBox");
        if (box == null || box.length < 4) box = numbers(dict, "/CropBox");
        PageEl page = new PageEl();
        if (box != null && box.length >= 4 && box[2] > box[0] && box[3] > box[1]) {
            page.w = box[2] - box[0];
            page.h = box[3] - box[1];
        }
        String resources = inlineDict(dict, "/Resources");
        if (resources == null) {
            Matcher m = Pattern.compile("/Resources\\s+(\\d+)\\s+0\\s+R").matcher(dict);
            if (m.find()) {
                byte[] r = objs.get(m.group(1));
                if (r != null) resources = latin(r);
            }
        }
        Map<String, byte[]> fontRefs = namedDict(resources, "/Font", objs);
        Map<String, byte[]> xobjRefs = namedDict(resources, "/XObject", objs);
        Map<String, Font> fonts = new HashMap<String, Font>();
        for (Map.Entry<String, byte[]> e : fontRefs.entrySet()) {
            fonts.put(e.getKey(), loadFont(e.getValue(), objs));
        }
        String content = pageContent(dict, objs);
        if (content == null) return null;
        new Interp(page, fonts, xobjRefs, objs).run(content);
        return page;
    }

    private static Font loadFont(byte[] obj, Map<String, byte[]> objs) {
        Font f = new Font();
        f.dict = obj;
        String d = latin(obj);
        f.cid = Pattern.compile("/Subtype\\s*/Type0").matcher(d).find();
        String base = name(d, "/BaseFont");
        if (base != null) {
            base = base.replace("#20", " ").replace("#2B", "+").replace("#2D", "-");
            int plus = base.indexOf('+');
            f.base = plus == 6 ? base.substring(7) : base;
        }
        String low = f.base.toLowerCase();
        f.bold = low.contains("bold") || low.contains("heavy") || low.contains("black");
        f.italic = low.contains("italic") || low.contains("oblique");
        f.family = familyOf(f.base);
        f.cm = PdfText.cmapFor(obj, objs);
        float[] first = numbers(d, "/FirstChar");
        f.firstChar = first != null && first.length > 0 ? (int) first[0] : 0;
        f.widths = numbers(d, "/Widths");
        if (f.cid) parseW(d, f);
        return f;
    }

    private static String familyOf(String base) {
        String s = base.replace("-", "").replace("_", "").toLowerCase();
        if (s.contains("simsun") || s.contains("songti")) return "宋体";
        if (s.contains("simhei") || s.contains("heiti")) return "黑体";
        if (s.contains("kaiti")) return "楷体";
        if (s.contains("fangsong")) return "仿宋";
        if (s.contains("yahei")) return "微软雅黑";
        if (s.contains("times")) return "Times New Roman";
        if (s.contains("arial")) return "Arial";
        if (s.contains("calibri")) return "Calibri";
        if (s.contains("cambria")) return "Cambria";
        if (s.contains("courier")) return "Courier New";
        if (s.contains("helvetica")) return "Arial";
        return base.replace("+", "").isEmpty() ? "Arial" : base.replace("+", "");
    }

    /** Type0 的 /W：[ c [w…] c1 c2 w ] 混合结构 */
    private static void parseW(String dict, Font f) {
        int at = dict.indexOf("/W");
        if (at < 0) return;
        int s = dict.indexOf('[', at);
        if (s < 0) return;
        int depth = 0;
        int e = s;
        for (; e < dict.length(); e++) {
            char c = dict.charAt(e);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
        }
        if (e >= dict.length()) return;
        String body = dict.substring(s + 1, e);
        List<Object> toks = new ArrayList<Object>();
        Matcher m = Pattern.compile("<[0-9A-Fa-f]+>|\\[[^\\]]*\\]|[-+]?[0-9]*\\.?[0-9]+").matcher(body);
        while (m.find()) {
            String t = m.group();
            if (t.startsWith("[")) {
                List<Float> arr = new ArrayList<Float>();
                Matcher n = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+").matcher(t);
                while (n.find()) arr.add(Float.parseFloat(n.group()));
                toks.add(arr);
            } else {
                toks.add(Float.parseFloat(t));
            }
        }
        int i = 0;
        while (i < toks.size()) {
            Object a = toks.get(i);
            if (!(a instanceof Float)) { i++; continue; }
            int c1 = ((Float) a).intValue();
            if (i + 1 >= toks.size()) break;
            Object b = toks.get(i + 1);
            if (b instanceof List) {
                @SuppressWarnings("unchecked")
                List<Float> arr = (List<Float>) b;
                for (int k = 0; k < arr.size(); k++) f.wmap.put(c1 + k, arr.get(k));
                i += 2;
            } else if (i + 2 < toks.size()) {
                int c2 = ((Float) b).intValue();
                float wv = ((Float) toks.get(i + 2));
                for (int c = c1; c <= c2 && c - c1 < 65536; c++) f.wmap.put(c, wv);
                i += 3;
            } else {
                break;
            }
        }
    }

    private static float advanceOf(Font f, int code) {
        if (f.cid) {
            Float w = f.wmap.get(code);
            return w != null ? w : 1000f;
        }
        if (f.widths != null) {
            int i = code - f.firstChar;
            if (i >= 0 && i < f.widths.length) return f.widths[i];
        }
        return defaultWidth(code);
    }

    private static float defaultWidth(int code) {
        if (code < 0x20) return 0f;
        if (code < 0x80) return code == 32 ? 278f : 556f;
        return 1000f;
    }

    // ------------------------------------------------------------------ 内容流

    private static final class State {
        float[] ctm = {1, 0, 0, 1, 0, 0};
        float[] tm = {1, 0, 0, 1, 0, 0};
        float[] tlm = {1, 0, 0, 1, 0, 0};
        float fontSize = 12, leading = 0, charSpace = 0, wordSpace = 0, hScale = 100, rise = 0;
        String fill = "000000", stroke = "000000";
        float lineWidth = 1;
        Font font;

        State copy() {
            State s = new State();
            s.ctm = ctm.clone();
            s.tm = tm.clone();
            s.tlm = tlm.clone();
            s.fontSize = fontSize;
            s.leading = leading;
            s.charSpace = charSpace;
            s.wordSpace = wordSpace;
            s.hScale = hScale;
            s.rise = rise;
            s.fill = fill;
            s.stroke = stroke;
            s.lineWidth = lineWidth;
            s.font = font;
            return s;
        }
    }

    private static final class Tok {
        static final int NUM = 1, NAME = 2, STR = 3, ARR = 4, OP = 5;
        final int type;
        final String s;
        final float num;
        final List<Tok> arr;

        Tok(int type, String s, float num, List<Tok> arr) {
            this.type = type;
            this.s = s;
            this.num = num;
            this.arr = arr;
        }
    }

    private static final class Interp {
        final PageEl page;
        final Map<String, Font> fonts;
        final Map<String, byte[]> xobjs;
        final Map<String, byte[]> objs;
        final Deque<State> stack = new ArrayDeque<State>();
        State st = new State();
        final List<float[]> segs = new ArrayList<float[]>();
        final List<float[]> rects = new ArrayList<float[]>();

        Interp(PageEl page, Map<String, Font> fonts, Map<String, byte[]> xobjs, Map<String, byte[]> objs) {
            this.page = page;
            this.fonts = fonts;
            this.xobjs = xobjs;
            this.objs = objs;
        }

        void run(String content) {
            List<Tok> toks = tokens(content);
            List<Tok> ops = new ArrayList<Tok>();
            for (Tok t : toks) {
                if (t.type != Tok.OP) {
                    ops.add(t);
                    continue;
                }
                exec(t.s, ops);
                ops.clear();
            }
        }

        private void exec(String op, List<Tok> ops) {
            if (op.equals("q")) {
                stack.push(st.copy());
            } else if (op.equals("Q")) {
                if (!stack.isEmpty()) st = stack.pop();
            } else if (op.equals("cm") && ops.size() >= 6) {
                st.ctm = mul(m(ops, 0), st.ctm);
            } else if (op.equals("BT")) {
                st.tm = new float[]{1, 0, 0, 1, 0, 0};
                st.tlm = st.tm.clone();
            } else if (op.equals("ET") || op.equals("gs")) {
                // 无状态变化
            } else if (op.equals("Tf") && ops.size() >= 2) {
                st.font = fonts.get(ops.get(0).s);
                st.fontSize = ops.get(1).num;
            } else if (op.equals("Tm") && ops.size() >= 6) {
                st.tm = m(ops, 0);
                st.tlm = st.tm.clone();
            } else if ((op.equals("Td") || op.equals("TD")) && ops.size() >= 2) {
                float tx = ops.get(0).num, ty = ops.get(1).num;
                if (op.equals("TD")) st.leading = -ty;
                st.tlm = mul(trans(tx, ty), st.tlm);
                st.tm = st.tlm.clone();
            } else if (op.equals("T*")) {
                st.tlm = mul(trans(0, -st.leading), st.tlm);
                st.tm = st.tlm.clone();
            } else if (op.equals("TL") && ops.size() >= 1) {
                st.leading = ops.get(0).num;
            } else if (op.equals("Tc") && ops.size() >= 1) {
                st.charSpace = ops.get(0).num;
            } else if (op.equals("Tw") && ops.size() >= 1) {
                st.wordSpace = ops.get(0).num;
            } else if (op.equals("Tz") && ops.size() >= 1) {
                st.hScale = ops.get(0).num;
            } else if (op.equals("Ts") && ops.size() >= 1) {
                st.rise = ops.get(0).num;
            } else if (op.equals("Tj") && ops.size() >= 1) {
                show(single(ops.get(ops.size() - 1)));
            } else if (op.equals("TJ") && ops.size() >= 1) {
                Tok arr = ops.get(ops.size() - 1);
                if (arr.type == Tok.ARR) {
                    for (Tok e : arr.arr) {
                        if (e.type == Tok.STR) show(e.s);
                        else if (e.type == Tok.NUM) advance(-e.num / 1000f * st.fontSize * st.hScale / 100f);
                    }
                }
            } else if (op.equals("'") && ops.size() >= 1) {
                line();
                show(single(ops.get(ops.size() - 1)));
            } else if (op.equals("\"") && ops.size() >= 3) {
                st.wordSpace = ops.get(0).num;
                st.charSpace = ops.get(1).num;
                line();
                show(single(ops.get(ops.size() - 1)));
            } else if (op.equals("rg") && ops.size() >= 3) {
                st.fill = hex(ops.get(0).num, ops.get(1).num, ops.get(2).num);
            } else if (op.equals("RG") && ops.size() >= 3) {
                st.stroke = hex(ops.get(0).num, ops.get(1).num, ops.get(2).num);
            } else if (op.equals("g") && ops.size() >= 1) {
                float v = ops.get(0).num;
                st.fill = hex(v, v, v);
            } else if (op.equals("G") && ops.size() >= 1) {
                float v = ops.get(0).num;
                st.stroke = hex(v, v, v);
            } else if (op.equals("k") && ops.size() >= 4) {
                st.fill = cmyk(ops);
            } else if (op.equals("K") && ops.size() >= 4) {
                st.stroke = cmyk(ops);
            } else if (op.equals("sc") || op.equals("scn")) {
                if (ops.size() >= 3) st.fill = hex(ops.get(0).num, ops.get(1).num, ops.get(2).num);
            } else if (op.equals("SC") || op.equals("SCN")) {
                if (ops.size() >= 3) st.stroke = hex(ops.get(0).num, ops.get(1).num, ops.get(2).num);
            } else if (op.equals("w") && ops.size() >= 1) {
                st.lineWidth = ops.get(0).num;
            } else if (op.equals("m") && ops.size() >= 2) {
                moveTo(ops.get(0).num, ops.get(1).num);
            } else if (op.equals("l") && ops.size() >= 2) {
                lineTo(ops.get(0).num, ops.get(1).num);
            } else if ((op.equals("c") || op.equals("v") || op.equals("y")) && ops.size() >= 2) {
                lineTo(ops.get(ops.size() - 2).num, ops.get(ops.size() - 1).num);
            } else if (op.equals("re") && ops.size() >= 4) {
                addRect(ops.get(0).num, ops.get(1).num, ops.get(2).num, ops.get(3).num);
            } else if (op.equals("h")) {
                // 闭合：不额外画线
            } else if (op.equals("S") || op.equals("s")) {
                strokePath();
                segs.clear();
                rects.clear();
            } else if (op.equals("f") || op.equals("F") || op.equals("f*")) {
                fillPath(false);
                segs.clear();
                rects.clear();
            } else if (op.equals("B") || op.equals("b") || op.equals("B*") || op.equals("b*")) {
                fillPath(true);
                segs.clear();
                rects.clear();
            } else if (op.equals("n")) {
                segs.clear();
                rects.clear();
            } else if (op.equals("Do") && ops.size() >= 1) {
                drawXObject(ops.get(0).s);
            }
        }

        private void line() {
            st.tlm = mul(trans(0, -st.leading), st.tlm);
            st.tm = st.tlm.clone();
        }

        private void advance(float amount) {
            st.tm = mul(trans(amount, 0), st.tm);
            st.tlm = mul(trans(amount, 0), st.tlm);
        }

        private String single(Tok t) {
            return t.type == Tok.STR ? t.s : "";
        }

        private void show(String raw) {
            Font f = st.font;
            if (f == null) return;
            int len = f.cm != null ? f.cm.codeLen : 1;
            if (len <= 0) len = 1;
            StringBuilder sb = new StringBuilder();
            float adv = 0;
            for (int i = 0; i + len <= raw.length() || (len == 1 && i < raw.length()); i += len) {
                int code = 0;
                for (int k = 0; k < len; k++) code = (code << 8) | (raw.charAt(i + k) & 0xFF);
                String u;
                if (f.cm != null) u = mapCode(f, code);
                else u = PdfText.smartText(new String(new char[]{(char) code}));
                if (u != null) sb.append(u);
                adv += advanceOf(f, code) / 1000f * st.fontSize + st.charSpace;
                if (code == 32) adv += st.wordSpace;
            }
            if (sb.length() == 0) {
                advance(adv * st.hScale / 100f);
                return;
            }
            float[] o = devicePoint(0, st.rise);
            float scaleX = deviceScaleX();
            float scaleY = deviceScaleY();
            float size = st.fontSize * scaleY * Math.max(0.2f, st.hScale / 100f);
            TextEl t = new TextEl();
            t.x = o[0];
            t.y = page.h - o[1] - size * 0.82f;
            t.size = size;
            t.width = Math.max(4f, adv * st.hScale / 100f * scaleX);
            t.text = sb.toString();
            t.font = f.family;
            t.color = st.fill;
            t.bold = f.bold;
            t.italic = f.italic;
            page.texts.add(t);
            advance(adv * st.hScale / 100f);
        }

        private String mapCode(Font f, int code) {
            String u = f.cm.map.get(code);
            if (u != null) return u;
            if (f.cid) return null;
            return PdfText.smartText(new String(new char[]{(char) code}));
        }

        private float[] devicePoint(float x, float y) {
            float[] p = {st.tm[0] * x + st.tm[2] * y + st.tm[4], st.tm[1] * x + st.tm[3] * y + st.tm[5]};
            return new float[]{
                    p[0] * st.ctm[0] + p[1] * st.ctm[2] + st.ctm[4],
                    p[0] * st.ctm[1] + p[1] * st.ctm[3] + st.ctm[5]};
        }

        private float deviceScaleX() {
            float x = st.tm[0] * st.ctm[0] + st.tm[1] * st.ctm[2];
            float y = st.tm[0] * st.ctm[1] + st.tm[1] * st.ctm[3];
            return (float) Math.hypot(x, y);
        }

        private float deviceScaleY() {
            float x = st.tm[2] * st.ctm[0] + st.tm[3] * st.ctm[2];
            float y = st.tm[2] * st.ctm[1] + st.tm[3] * st.ctm[3];
            return (float) Math.hypot(x, y);
        }

        // 路径
        float cx, cy, sx, sy;

        private void moveTo(float x, float y) {
            float[] p = dev(x, y);
            cx = p[0]; cy = p[1]; sx = p[0]; sy = p[1];
        }

        private void lineTo(float x, float y) {
            float[] p = dev(x, y);
            segs.add(new float[]{cx, cy, p[0], p[1]});
            cx = p[0]; cy = p[1];
        }

        private void addRect(float x, float y, float w, float h) {
            float[] a = dev(x, y), b = dev(x + w, y), c = dev(x + w, y + h), d = dev(x, y + h);
            segs.add(new float[]{a[0], a[1], b[0], b[1]});
            segs.add(new float[]{b[0], b[1], c[0], c[1]});
            segs.add(new float[]{c[0], c[1], d[0], d[1]});
            segs.add(new float[]{d[0], d[1], a[0], a[1]});
            rects.add(new float[]{Math.min(a[0], c[0]), Math.min(a[1], c[1]),
                    Math.abs(b[0] - d[0]), Math.abs(b[1] - d[1])});
            cx = a[0];
            cy = a[1];
        }

        private float[] dev(float x, float y) {
            return new float[]{
                    x * st.ctm[0] + y * st.ctm[2] + st.ctm[4],
                    x * st.ctm[1] + y * st.ctm[3] + st.ctm[5]};
        }

        private void strokePath() {
            float lw = Math.max(0.4f, Math.abs(st.lineWidth * deviceScaleX()));
            for (float[] s : segs) {
                float dx = Math.abs(s[2] - s[0]), dy = Math.abs(s[3] - s[1]);
                if (dx < 0.8f && dy < 0.8f) continue;
                ShapeEl el = new ShapeEl();
                el.color = st.stroke;
                if (dx < 0.8f) {
                    el.x = Math.min(s[0], s[2]) - lw / 2;
                    el.y = page.h - Math.max(s[1], s[3]);
                    el.w = lw;
                    el.h = dy;
                } else if (dy < 0.8f) {
                    el.x = Math.min(s[0], s[2]);
                    el.y = page.h - Math.max(s[1], s[3]) - lw / 2;
                    el.w = dx;
                    el.h = lw;
                } else {
                    continue; // 斜线暂不画，避免矩形色块
                }
                page.shapes.add(el);
            }
        }

        private void fillPath(boolean stroke) {
            for (float[] r : rects) {
                ShapeEl el = new ShapeEl();
                el.color = st.fill;
                el.x = r[0];
                el.y = page.h - (r[1] + r[3]);
                el.w = r[2];
                el.h = r[3];
                if (el.w > 0.5f && el.h > 0.5f) page.shapes.add(el);
            }
            if (stroke) strokePath();
        }

        private void drawXObject(String name) {
            byte[] obj = xobjs.get(name);
            if (obj == null) return;
            String d = latin(obj);
            String subtype = name(d, "/Subtype");
            if (subtype == null) return;
            if (subtype.equals("Form")) {
                float[] matrix = numbers(d, "/Matrix");
                State saved = st.copy();
                if (matrix != null && matrix.length >= 6) st.ctm = mul(matrix, st.ctm);
                String res = inlineDict(d, "/Resources");
                Map<String, byte[]> fx = namedDict(res, "/XObject", objs);
                Map<String, Font> ff = new HashMap<String, Font>(fonts);
                for (Map.Entry<String, byte[]> e : namedDict(res, "/Font", objs).entrySet()) {
                    ff.put(e.getKey(), loadFont(e.getValue(), objs));
                }
                byte[] data = PdfText.streamOfObject(obj);
                if (data != null) {
                    Interp sub = new Interp(page, ff, fx, objs);
                    sub.st = st.copy();
                    sub.run(new String(PdfText.tryInflate(data) != null ? PdfText.tryInflate(data) : data, CS));
                }
                st = saved;
            } else if (subtype.equals("Image")) {
                addImage(obj, d);
            }
        }

        private void addImage(byte[] obj, String d) {
            byte[] raw = PdfText.streamOfObject(obj);
            if (raw == null) return;
            boolean dct = d.contains("DCTDecode");
            boolean flate = d.contains("FlateDecode");
            int w = (int) numOr(d, "/Width", 0);
            int h = (int) numOr(d, "/Height", 0);
            byte[] data = raw;
            String ext = "png";
            if (dct) {
                ext = "jpg";
            } else if (flate || w > 0) {
                byte[] inf = PdfText.tryInflate(raw);
                if (inf != null) raw = inf;
                int comps = colorComponents(d);
                if (hasPredictor(d)) raw = unfilter(raw, d, w, comps);
                if (w > 0 && h > 0 && raw.length >= w * h * comps) {
                    data = png(w, h, comps, raw);
                } else {
                    return;
                }
            } else {
                return;
            }
            float[] a = dev(0, 0), b = dev(1, 0), c = dev(0, 1), e = dev(1, 1);
            float x0 = Math.min(Math.min(a[0], b[0]), Math.min(c[0], e[0]));
            float x1 = Math.max(Math.max(a[0], b[0]), Math.max(c[0], e[0]));
            float y0 = Math.min(Math.min(a[1], b[1]), Math.min(c[1], e[1]));
            float y1 = Math.max(Math.max(a[1], b[1]), Math.max(c[1], e[1]));
            PicEl p = new PicEl();
            p.x = x0;
            p.y = page.h - y1;
            p.w = x1 - x0;
            p.h = y1 - y0;
            p.data = data;
            p.ext = ext;
            if (p.w > 1 && p.h > 1) page.pics.add(p);
        }
    }

    private static boolean hasPredictor(String d) {
        Matcher m = Pattern.compile("/Predictor\\s+(\\d+)").matcher(d);
        return m.find() && Integer.parseInt(m.group(1)) >= 10;
    }

    /** 解开 PDF 的 PNG 行预测（Predictor 10–15），输出纯像素 */
    private static byte[] unfilter(byte[] in, String d, int width, int comps) {
        int colors = (int) numOr(d, "/Colors", comps);
        int bpc = (int) numOr(d, "/BitsPerComponent", 8);
        int columns = (int) numOr(d, "/Columns", width);
        int bpp = Math.max(1, colors * bpc / 8);
        int row = columns * bpp;
        int rows = in.length / (row + 1);
        byte[] out = new byte[rows * row];
        byte[] prev = new byte[row];
        for (int y = 0; y < rows; y++) {
            int ft = in[y * (row + 1)] & 0xFF;
            int base = y * (row + 1) + 1;
            byte[] cur = new byte[row];
            for (int i = 0; i < row; i++) {
                int raw = in[base + i] & 0xFF;
                int left = i >= bpp ? cur[i - bpp] : 0;
                int up = prev[i];
                int ul = i >= bpp ? prev[i - bpp] : 0;
                int v;
                switch (ft) {
                    case 1: v = raw + left; break;
                    case 2: v = raw + up; break;
                    case 3: v = raw + ((left + up) >> 1); break;
                    case 4: v = raw + paeth(left, up, ul); break;
                    default: v = raw;
                }
                cur[i] = (byte) v;
            }
            System.arraycopy(cur, 0, out, y * row, row);
            prev = cur;
        }
        return out;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
    }

    private static float numOr(String d, String key, float def) {
        Matcher m = Pattern.compile(key + "\\s+(-?[0-9]+)").matcher(d);
        return m.find() ? Float.parseFloat(m.group(1)) : def;
    }

    private static int colorComponents(String d) {        if (d.contains("/DeviceGray") || d.contains("/CalGray")) return 1;
        if (d.contains("/DeviceCMYK")) return 4;
        if (d.contains("/DeviceRGB") || d.contains("/CalRGB")) return 3;
        String n = name(d, "/ColorSpace");
        if (n != null && n.equals("DeviceGray")) return 1;
        return 3;
    }

    // ------------------------------------------------------------------ 工具

    private static String pageContent(String dict, Map<String, byte[]> objs) {
        int at = dict.indexOf("/Contents");
        if (at < 0) return null;
        StringBuilder all = new StringBuilder();
        Matcher m = Pattern.compile("(\\d+)\\s+0\\s+R").matcher(dict.substring(at));
        int limit = dict.indexOf('/', at + 10);
        String sub = limit > 0 ? dict.substring(at, limit) : dict.substring(at);
        Matcher mm = Pattern.compile("(\\d+)\\s+0\\s+R").matcher(sub);
        int count = 0;
        while (mm.find() && count < 64) {
            byte[] obj = objs.get(mm.group(1));
            if (obj == null) continue;
            byte[] data = PdfText.streamOfObject(obj);
            if (data == null) continue;
            byte[] plain = PdfText.tryInflate(data);
            all.append(new String(plain != null ? plain : data, CS)).append('\n');
            count++;
        }
        return count == 0 ? null : all.toString();
    }

    private static Map<String, byte[]> namedDict(String resources, String key, Map<String, byte[]> objs) {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        if (resources == null) return out;
        String block = inlineDict(resources, key);
        if (block == null) {
            Matcher m = Pattern.compile(key + "\\s+(\\d+)\\s+0\\s+R").matcher(resources);
            if (m.find()) {
                byte[] o = objs.get(m.group(1));
                if (o != null) block = latin(o);
            }
        }
        if (block == null) return out;
        Matcher m = Pattern.compile("/\\s*([^\\s/<>\\[\\]]+)\\s+(\\d+)\\s+0\\s+R").matcher(block);
        while (m.find()) {
            byte[] o = objs.get(m.group(2));
            if (o != null) out.put(m.group(1), o);
        }
        return out;
    }

    /** 取 << … >> 的整段文本，支持嵌套 */
    private static String inlineDict(String dict, String key) {
        if (dict == null) return null;
        int at = dict.indexOf(key);
        if (at < 0) return null;
        int s = dict.indexOf("<<", at + key.length());
        if (s < 0 || s - (at + key.length()) > 4) return null;
        int depth = 0;
        for (int i = s; i < dict.length() - 1; i++) {
            if (dict.charAt(i) == '<' && dict.charAt(i + 1) == '<') { depth++; i++; }
            else if (dict.charAt(i) == '>' && dict.charAt(i + 1) == '>') {
                depth--;
                i++;
                if (depth == 0) return dict.substring(s + 2, i - 1);
            }
        }
        return null;
    }

    private static float[] numbers(String dict, String key) {
        if (dict == null) return null;
        int at = dict.indexOf(key);
        if (at < 0) return null;
        int s = dict.indexOf('[', at);
        if (s < 0 || s - at > 40) return null;
        int e = dict.indexOf(']', s);
        if (e < 0) return null;
        Matcher m = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+").matcher(dict.substring(s + 1, e));
        List<Float> list = new ArrayList<Float>();
        while (m.find()) list.add(Float.parseFloat(m.group()));
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static String name(String dict, String key) {
        if (dict == null) return null;
        Matcher m = Pattern.compile(key + "\\s*/([^\\s/<>\\[\\]()]+)").matcher(dict);
        return m.find() ? m.group(1) : null;
    }

    private static String[] filterNames(String d) {
        int at = d.indexOf("/Filter");
        if (at < 0) return new String[0];
        int e = d.indexOf('/', at + 7);
        String sub = e > 0 ? d.substring(at + 7, e) : d.substring(at + 7);
        Matcher m = Pattern.compile("/([^\\s/<>\\[\\]()]+)").matcher(sub);
        List<String> out = new ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out.toArray(new String[0]);
    }

    private static String latin(byte[] b) {
        return new String(b, java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    private static float[] trans(float x, float y) {
        return new float[]{1, 0, 0, 1, x, y};
    }

    /** 两个 PDF 矩阵相乘：a × b */
    private static float[] mul(float[] a, float[] b) {
        return new float[]{
                a[0] * b[0] + a[1] * b[2],
                a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2],
                a[2] * b[1] + a[3] * b[3],
                a[4] * b[0] + a[5] * b[2] + b[4],
                a[4] * b[1] + a[5] * b[3] + b[5]};
    }

    private static float[] m(List<Tok> ops, int from) {
        return new float[]{ops.get(from).num, ops.get(from + 1).num, ops.get(from + 2).num,
                ops.get(from + 3).num, ops.get(from + 4).num, ops.get(from + 5).num};
    }

    private static String hex(float r, float g, float b) {
        int ri = clamp((int) Math.round(r * 255));
        int gi = clamp((int) Math.round(g * 255));
        int bi = clamp((int) Math.round(b * 255));
        return String.format("%02X%02X%02X", ri, gi, bi);
    }

    private static String cmyk(List<Tok> ops) {
        float c = ops.get(0).num, m = ops.get(1).num, y = ops.get(2).num, k = ops.get(3).num;
        return hex((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ------------------------------------------------------------------ 词法

    private static List<Tok> tokens(String s) {
        List<Tok> root = new ArrayList<Tok>();
        Deque<List<Tok>> stack = new ArrayDeque<List<Tok>>();
        List<Tok> cur = root;
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '%') {
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
            } else if (c == '(') {
                StringBuilder sb = new StringBuilder();
                int depth = 1;
                i++;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        sb.append(ch).append(s.charAt(i + 1));
                        i += 2;
                    } else if (ch == '(') {
                        depth++;
                        sb.append(ch);
                        i++;
                    } else if (ch == ')') {
                        depth--;
                        i++;
                        if (depth == 0) break;
                        sb.append(ch);
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                cur.add(new Tok(Tok.STR, unescape(sb.toString()), 0, null));
            } else if (c == '<') {
                if (i + 1 < n && s.charAt(i + 1) == '<') {
                    int depth = 0;
                    while (i < n - 1) {
                        if (s.charAt(i) == '<' && s.charAt(i + 1) == '<') { depth++; i += 2; }
                        else if (s.charAt(i) == '>' && s.charAt(i + 1) == '>') {
                            depth--;
                            i += 2;
                            if (depth == 0) break;
                        } else i++;
                    }
                } else {
                    int e = s.indexOf('>', i + 1);
                    if (e < 0) break;
                    byte[] b = PdfText.hexToBytes(s.substring(i + 1, e));
                    cur.add(new Tok(Tok.STR, new String(b, CS), 0, null));
                    i = e + 1;
                }
            } else if (c == '[') {
                stack.push(cur);
                cur = new ArrayList<Tok>();
                i++;
            } else if (c == ']') {
                List<Tok> arr = cur;
                cur = stack.isEmpty() ? root : stack.pop();
                cur.add(new Tok(Tok.ARR, null, 0, arr));
                i++;
            } else if (c == '/') {
                int e = i + 1;
                while (e < n && !isDelim(s.charAt(e))) e++;
                cur.add(new Tok(Tok.NAME, s.substring(i + 1, e), 0, null));
                i = e;
            } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') {
                i++;
            } else {
                int e = i;
                while (e < n && !isDelim(s.charAt(e))) e++;
                String w = s.substring(i, e);
                i = e == i ? i + 1 : e;
                if (isNumber(w)) cur.add(new Tok(Tok.NUM, w, Float.parseFloat(w), null));
                else cur.add(new Tok(Tok.OP, w, 0, null));
            }
        }
        return root;
    }

    private static boolean isDelim(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f'
                || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '/' || c == '%';
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.' && c != '-' && c != '+') return false;
        }
        return true;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case '0': case '1': case '2': case '3':
                case '4': case '5': case '6': case '7': {
                    int v = n - '0';
                    int k = 0;
                    while (k < 2 && i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') {
                        v = v * 8 + (s.charAt(++i) - '0');
                        k++;
                    }
                    out.append((char) v);
                    break;
                }
                default: out.append(n);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ PNG 编码

    static byte[] png(int w, int h, int comps, byte[] raw) {
        int colorType = comps == 1 ? 0 : (comps == 4 ? 6 : 2);
        int bpp = comps;
        ByteArrayOutputStream scan = new ByteArrayOutputStream(h * (w * bpp + 1));
        for (int y = 0; y < h; y++) {
            scan.write(0);
            scan.write(raw, y * w * bpp, w * bpp);
        }
        byte[] z = deflate(scan.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        out.write(sig, 0, sig.length);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.write(8);
        ihdr.write(colorType);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", z);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] t = type.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] buf = new byte[16384];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }
}



