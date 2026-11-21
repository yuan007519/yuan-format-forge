package com.fmtlab.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * 纯 Java 的 GIF89a 编码器（含 LZW 压缩与调色板量化），不依赖 Android。
 * 用法：每个动画帧调用一次 addFrame(ARGB 像素数组, 延时百分之一秒)，最后 finish()。
 */
public final class GifWriter {

    private final OutputStream out;
    private final int width;
    private final int height;

    // 15bit（5-5-5）直方图
    private static final int BINS = 32768;
    private final int[] binCount = new int[BINS];
    private final int[] binSumR = new int[BINS];
    private final int[] binSumG = new int[BINS];
    private final int[] binSumB = new int[BINS];
    private final int[] binMap = new int[BINS]; // 15bit bin -> palette index, -1 未分配
    private final int[] palette = new int[256]; // 0xRRGGBB

    // 216 色 web 调色板
    private static final int[] WEB_LEVELS = {0, 51, 102, 153, 204, 255};
    private static final int[] WEB_PALETTE = buildWebPalette();

    private boolean started;
    private int frameCount;

    // LZW 哈希表
    private static final int HSIZE = 8192;
    private final int[] hashKey = new int[HSIZE];
    private final int[] hashVal = new int[HSIZE];

    public GifWriter(OutputStream out, int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("GIF 尺寸不合法");
        this.out = out;
        this.width = width;
        this.height = height;
    }

    private static int[] buildWebPalette() {
        int[] p = new int[216];
        int i = 0;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    p[i++] = (WEB_LEVELS[r] << 16) | (WEB_LEVELS[g] << 8) | WEB_LEVELS[b];
                }
            }
        }
        return p;
    }

    private void writeHeader() throws IOException {
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(width);
        writeShort(height);
        out.write(0x70); // 无全局色表，色彩分辨率 8bit
        out.write(0);    // 背景色索引
        out.write(0);    // 像素宽高比
        // NETSCAPE2.0 无限循环
        out.write(0x21);
        out.write(0xFF);
        out.write(0x0B);
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(0x03);
        out.write(0x01);
        writeShort(0); // 0 = 无限
        out.write(0x00);
        started = true;
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    /** 添加一帧。argb 长度必须等于 width*height，延时单位为 1/100 秒。 */
    public void addFrame(int[] argb, int delayCentis) throws IOException {
        if (!started) writeHeader();
        if (argb == null || argb.length < width * height) throw new IOException("帧数据尺寸不符");

        int n = width * height;
        Arrays.fill(binCount, 0);
        Arrays.fill(binSumR, 0);
        Arrays.fill(binSumG, 0);
        Arrays.fill(binSumB, 0);
        Arrays.fill(binMap, -1);

        int distinct = 0;
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int bin = binOf(c);
            if (binCount[bin] == 0) distinct++;
            binCount[bin]++;
            binSumR[bin] += (c >> 16) & 0xFF;
            binSumG[bin] += (c >> 8) & 0xFF;
            binSumB[bin] += c & 0xFF;
        }

        byte[] idx = new byte[n];
        int paletteSize;
        int bits;

        if (distinct <= 256) {
            // 颜色不多（截图、图标、动画、字幕片）：直接精确映射，近无损
            paletteSize = distinct;
            int k = 0;
            for (int bin = 0; bin < BINS; bin++) {
                if (binCount[bin] == 0) continue;
                int cnt = binCount[bin];
                int r = (binSumR[bin] + cnt / 2) / cnt;
                int g = (binSumG[bin] + cnt / 2) / cnt;
                int b = (binSumB[bin] + cnt / 2) / cnt;
                palette[k] = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                binMap[bin] = k;
                k++;
            }
            for (int i = 0; i < n; i++) {
                idx[i] = (byte) binMap[binOf(argb[i])];
            }
        } else {
            // 照片/视频：216 色 + Floyd-Steinberg 抖动
            paletteSize = 216;
            System.arraycopy(WEB_PALETTE, 0, palette, 0, 216);
            dither(argb, idx, n);
        }

        bits = bitsFor(paletteSize);
        writeFrame(idx, bits, paletteSize, delayCentis);
        frameCount++;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int binOf(int c) {
        int r = ((c >> 16) & 0xFF) >> 3;
        int g = ((c >> 8) & 0xFF) >> 3;
        int b = (c & 0xFF) >> 3;
        return (r << 10) | (g << 5) | b;
    }

    private static int bitsFor(int paletteSize) {
        int bits = 1;
        while ((1 << bits) < paletteSize) bits++;
        return Math.max(2, Math.min(8, bits));
    }

    /** 固定 216 色 + 蛇形扫描的 Floyd-Steinberg 误差扩散 */
    private void dither(int[] argb, byte[] idx, int n) {
        int w = width, h = height;
        float[] cr = new float[w + 2], cg = new float[w + 2], cb = new float[w + 2];
        float[] nr = new float[w + 2], ng = new float[w + 2], nb = new float[w + 2];
        float[] tmp;
        for (int y = 0; y < h; y++) {
            Arrays.fill(nr, 0f);
            Arrays.fill(ng, 0f);
            Arrays.fill(nb, 0f);
            boolean right = (y & 1) == 0;
            for (int step = 0; step < w; step++) {
                int x = right ? step : w - 1 - step;
                int c = argb[y * w + x];
                int r = clamp8((int) Math.round(((c >> 16) & 0xFF) + cr[x + 1]));
                int g = clamp8((int) Math.round(((c >> 8) & 0xFF) + cg[x + 1]));
                int b = clamp8((int) Math.round((c & 0xFF) + cb[x + 1]));
                int ri = levelIndex(r), gi = levelIndex(g), bi = levelIndex(b);
                idx[y * w + x] = (byte) ((ri * 36) + (gi * 6) + bi);
                float dr = r - WEB_LEVELS[ri];
                float dg = g - WEB_LEVELS[gi];
                float db = b - WEB_LEVELS[bi];
                int f = right ? 1 : -1;
                cr[x + 1 + f] += dr * 7f / 16f;
                nr[x + 1 - f] += dr * 3f / 16f;
                nr[x + 1] += dr * 5f / 16f;
                nr[x + 1 + f] += dr * 1f / 16f;
                cg[x + 1 + f] += dg * 7f / 16f;
                ng[x + 1 - f] += dg * 3f / 16f;
                ng[x + 1] += dg * 5f / 16f;
                ng[x + 1 + f] += dg * 1f / 16f;
                cb[x + 1 + f] += db * 7f / 16f;
                nb[x + 1 - f] += db * 3f / 16f;
                nb[x + 1] += db * 5f / 16f;
                nb[x + 1 + f] += db * 1f / 16f;
            }
            tmp = cr; cr = nr; nr = tmp;
            tmp = cg; cg = ng; ng = tmp;
            tmp = cb; cb = nb; nb = tmp;
        }
    }

    private static int levelIndex(int v) {
        int i = v / 51;
        if (i > 5) i = 5;
        return i;
    }

    private void writeFrame(byte[] indices, int bits, int paletteSize, int delayCentis) throws IOException {
        // 图形控制扩展
        out.write(0x21);
        out.write(0xF9);
        out.write(0x04);
        out.write(0x08); // disposal = 保留上一帧
        writeShort(Math.max(0, delayCentis));
        out.write(0x00); // 无透明色
        out.write(0x00);

        int tableSize = 1 << bits;
        // 图像描述符
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0x80 | (bits - 1)); // 带局部色表

        // 局部色表
        for (int i = 0; i < tableSize; i++) {
            int c = i < paletteSize ? palette[i] : 0;
            out.write((c >> 16) & 0xFF);
            out.write((c >> 8) & 0xFF);
            out.write(c & 0xFF);
        }

        // LZW 数据
        out.write(bits);
        ByteArrayOutputStream data = new ByteArrayOutputStream(Math.max(1024, indices.length / 2));
        lzw(indices, bits, data);
        byte[] bytes = data.toByteArray();
        int off = 0;
        while (off < bytes.length) {
            int len = Math.min(255, bytes.length - off);
            out.write(len);
            out.write(bytes, off, len);
            off += len;
        }
        out.write(0x00);
    }

    private void lzw(byte[] indices, int minCodeSize, ByteArrayOutputStream data) {
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int nBits = minCodeSize + 1;
        int maxCode = (1 << nBits) - 1;
        int freeEnt = eoiCode + 1;
        Arrays.fill(hashVal, 0);

        int curAccum = 0, curBits = 0;

        // 起始码：clear
        curAccum |= (clearCode << curBits);
        curBits += nBits;
        while (curBits >= 8) {
            data.write(curAccum & 0xFF);
            curAccum >>= 8;
            curBits -= 8;
        }

        if (indices.length > 0) {
            int prefix = indices[0] & 0xFF;
            for (int i = 1; i < indices.length; i++) {
                int suffix = indices[i] & 0xFF;
                int key = (prefix << 8) | suffix;
                int code = find(key);
                if (code >= 0) {
                    prefix = code;
                } else {
                    // 输出 prefix
                    curAccum |= (prefix << curBits);
                    curBits += nBits;
                    while (curBits >= 8) {
                        data.write(curAccum & 0xFF);
                        curAccum >>= 8;
                        curBits -= 8;
                    }
                    if (freeEnt > maxCode) {
                        nBits++;
                        maxCode = nBits == 12 ? 4096 : (1 << nBits) - 1;
                    }
                    if (freeEnt < 4096) {
                        insert(key, freeEnt);
                        freeEnt++;
                    } else {
                        // 表满，输出 clear 并重置
                        curAccum |= (clearCode << curBits);
                        curBits += nBits;
                        while (curBits >= 8) {
                            data.write(curAccum & 0xFF);
                            curAccum >>= 8;
                            curBits -= 8;
                        }
                        Arrays.fill(hashVal, 0);
                        freeEnt = eoiCode + 1;
                        nBits = minCodeSize + 1;
                        maxCode = (1 << nBits) - 1;
                    }
                    prefix = suffix;
                }
            }
            // 输出最后前缀
            curAccum |= (prefix << curBits);
            curBits += nBits;
            while (curBits >= 8) {
                data.write(curAccum & 0xFF);
                curAccum >>= 8;
                curBits -= 8;
            }
            if (freeEnt > maxCode) {
                nBits++;
                maxCode = nBits == 12 ? 4096 : (1 << nBits) - 1;
            }
        }
        // EOI
        curAccum |= (eoiCode << curBits);
        curBits += nBits;
        while (curBits >= 8) {
            data.write(curAccum & 0xFF);
            curAccum >>= 8;
            curBits -= 8;
        }
        if (curBits > 0) {
            data.write(curAccum & 0xFF);
        }
    }

    private int hashSlot(int key) {
        int h = (key ^ (key >>> 9)) & (HSIZE - 1);
        int start = h;
        while (true) {
            if (hashVal[h] == 0) return h;
            if (hashKey[h] == key) return h;
            h = (h + 1) & (HSIZE - 1);
            if (h == start) return -1;
        }
    }

    private int find(int key) {
        int slot = hashSlot(key);
        if (slot < 0) return -1;
        if (hashVal[slot] == 0) return -1;
        return hashKey[slot] == key ? hashVal[slot] : -1;
    }

    private void insert(int key, int code) {
        int slot = hashSlot(key);
        if (slot < 0) return;
        if (hashVal[slot] == 0) {
            hashKey[slot] = key;
            hashVal[slot] = code;
        }
    }

    public void finish() throws IOException {
        if (!started) writeHeader();
        out.write(0x3B);
        out.flush();
    }

    public int frameCount() {
        return frameCount;
    }
}
