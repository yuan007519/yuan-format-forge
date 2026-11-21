package com.fmtlab.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 24 位 BMP 编码器（Android 的 BitmapFactory 可以直接读取 BMP，但只能解不能编，所以自己写一个）。 */
public final class Bmp {

    private Bmp() {}

    public static byte[] encode(int[] argb, int width, int height) throws IOException {
        int rowBytes = width * 3;
        int padding = (4 - (rowBytes % 4)) % 4;
        int pixelBytes = (rowBytes + padding) * height;
        int fileSize = 14 + 40 + pixelBytes;

        ByteArrayOutputStream out = new ByteArrayOutputStream(fileSize + 16);
        out.write(new byte[]{'B', 'M'});
        writeIntLE(out, fileSize);
        writeShortLE(out, 0);
        writeShortLE(out, 0);
        writeIntLE(out, 54);

        writeIntLE(out, 40);
        writeIntLE(out, width);
        writeIntLE(out, height);
        writeShortLE(out, 1);
        writeShortLE(out, 24);
        writeIntLE(out, 0);
        writeIntLE(out, pixelBytes);
        writeIntLE(out, 2835);
        writeIntLE(out, 2835);
        writeIntLE(out, 0);
        writeIntLE(out, 0);

        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int px = argb[y * width + x];
                out.write(px & 0xFF);
                out.write((px >> 8) & 0xFF);
                out.write((px >> 16) & 0xFF);
            }
            for (int p = 0; p < padding; p++) out.write(0);
        }
        out.flush();
        return out.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
