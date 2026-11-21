package com.fmtlab.toolbox.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 把 PPTX 内容写成 PDF：有图片的幻灯片整页铺图，纯文字幻灯片按版面排版。 */
public final class PdfExport {

    private PdfExport() {}

    public static int slidesToPdf(File out, PptxReader.Deck deck) throws IOException {
        PdfDocument doc = new PdfDocument();
        int pageNo = 0;
        try {
            for (int i = 0; i < deck.slides.size(); i++) {
                PptxReader.Slide s = deck.slides.get(i);
                Bitmap bmp = null;
                if (s.image != null) {
                    try {
                        bmp = BitmapFactory.decodeByteArray(s.image, 0, s.image.length);
                    } catch (Throwable ignored) {}
                }
                int pw = clamp(deck.widthPt), ph = clamp(deck.heightPt);

                if (bmp != null) {
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, ++pageNo).create();
                    PdfDocument.Page page = doc.startPage(info);
                    Canvas c = page.getCanvas();
                    c.drawColor(Color.WHITE);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    RectF dst = fitRect(bmp.getWidth(), bmp.getHeight(), pw, ph);
                    c.drawBitmap(bmp, null, dst, paint);
                    doc.finishPage(page);
                    bmp.recycle();
                    continue;
                }

                // 纯文字：按行排版，超过一页继续开新页
                String text = s.text == null ? "" : s.text;
                List<String> lines = wrapLines(text, pw - 80, 12f);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                p.setTextSize(12f);

                int margin = 40;
                int lineH = 18;
                int y = margin + 12;
                int idx = 0;
                while (idx < lines.size() || y <= margin + 12 && idx == 0) {
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, ++pageNo).create();
                    PdfDocument.Page page = doc.startPage(info);
                    Canvas c = page.getCanvas();
                    c.drawColor(Color.WHITE);
                    while (idx < lines.size() && y <= ph - margin) {
                        String line = lines.get(idx);
                        boolean title = idx == 0 && line.length() <= 40;
                        if (title) {
                            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                            p.setTextSize(17f);
                            c.drawText(line, margin, y + 6, p);
                            y += 24;
                            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                            p.setTextSize(12f);
                        } else {
                            c.drawText(line, margin, y, p);
                            y += lineH;
                        }
                        idx++;
                    }
                    doc.finishPage(page);
                    y = margin + 12;
                    if (idx >= lines.size()) break;
                }
            }
        } finally {
            doc.close();
        }
        FileOutputStream fos = new FileOutputStream(out);
        try {
            doc.writeTo(fos);
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
        }
        return pageNo;
    }

    private static int clamp(int v) {
        if (v < 72) return 595;
        if (v > 14400) return 14400; // PDF 单边上限 200 英寸
        return v;
    }

    private static RectF fitRect(int bw, int bh, int pw, int ph) {
        double s = Math.min((double) pw / bw, (double) ph / bh);
        double w = bw * s, h = bh * s;
        double left = (pw - w) / 2.0, top = (ph - h) / 2.0;
        return new RectF((float) left, (float) top, (float) (left + w), (float) (top + h));
    }

    /** 按宽度折行：中文按字，英文尽量按词断开 */
    private static List<String> wrapLines(String text, int maxWidth, float textSize) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTextSize(textSize);
        String[] paras = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String para : paras) {
            String p = para.replace('\t', ' ').trim();
            if (p.length() == 0) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < p.length()) {
                char c = p.charAt(i);
                if (c < 128 && c != ' ' && line.length() > 0) {
                    // 英文单词整体搬运，避免断词太难看
                    int j = i;
                    while (j < p.length() && p.charAt(j) < 128 && p.charAt(j) != ' ') j++;
                    String word = p.substring(i, j);
                    String tryLine = line + word;
                    if (m.measureText(tryLine) > maxWidth) {
                        out.add(line.toString());
                        line.setLength(0);
                        line.append(word);
                    } else {
                        line.append(word);
                    }
                    i = j;
                    continue;
                }
                String tryLine = line.toString() + c;
                if (m.measureText(tryLine) > maxWidth) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(c);
                } else {
                    line.append(c);
                }
                i++;
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }
}
