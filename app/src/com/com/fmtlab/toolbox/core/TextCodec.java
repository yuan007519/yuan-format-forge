package com.fmtlab.toolbox.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 编码工具：严格解码、编码回环校验、BOM 处理。 */
public final class TextCodec {

    public static final String UTF8 = "UTF-8";
    public static final String GBK = "GBK";
    public static final String GB18030 = "GB18030";
    public static final String BIG5 = "Big5";
    public static final String SJIS = "Shift_JIS";
    public static final String EUCKR = "EUC-KR";
    public static final String CP1252 = "windows-1252";
    public static final String LATIN1 = "ISO-8859-1";
    public static final String UTF16LE = "UTF-16LE";
    public static final String UTF16BE = "UTF-16BE";

    /** 供 UI 展示与用户选择的目标编码 */
    public static final String[] WRITE_CHOICES = {
            "UTF-8", "GBK", "GB18030", "Big5", "UTF-16LE", "UTF-16BE", "ISO-8859-1"
    };

    /** 乱码修复时可尝试的“被误读的编码” */
    public static final String[] SOURCE_GUESSES = {
            GBK, BIG5, SJIS, EUCKR, CP1252, LATIN1
    };

    private TextCodec() {}

    public static Charset charset(String name) {
        try {
            if (Charset.isSupported(name)) return Charset.forName(name);
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean supported(String name) {
        return charset(name) != null;
    }

    /** 严格解码；失败返回 null */
    public static String decodeStrict(byte[] data, String name) {
        Charset cs = charset(name);
        if (cs == null) return null;
        try {
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(data));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽松解码（坏字节用 U+FFFD 代替），永不抛异常 */
    public static String decodeLenient(byte[] data, String name) {
        Charset cs = charset(name);
        if (cs == null) cs = Charset.forName(UTF8);
        try {
            return new String(data, cs);
        } catch (Exception e) {
            return new String(data, Charset.forName(UTF8));
        }
    }

    /** 编码为字节；若有字符无法表示则返回 null */
    public static byte[] encodeStrict(String text, String name) {
        Charset cs = charset(name);
        if (cs == null) return null;
        try {
            CharsetEncoder enc = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = enc.encode(CharBuffer.wrap(text));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 text 当成“用 name 编码写出来、后来被别的程序当成 UTF-16 之类读走”的情况还原：
     * 只有编码后再用同一编码解码能 100% 还原原文时才算安全。
     */
    public static byte[] encodeIfRoundTrips(String text, String name) {
        byte[] bytes = encodeStrict(text, name);
        if (bytes == null) return null;
        String back = decodeStrict(bytes, name);
        if (back == null || !back.equals(text)) return null;
        return bytes;
    }

    public static String bomName(byte[] data) {
        if (data == null || data.length < 2) return null;
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) return UTF8;
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) return UTF16LE;
        if (data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) return UTF16BE;
        return null;
    }

    public static int bomLength(byte[] data) {
        String n = bomName(data);
        if (n == null) return 0;
        return UTF8.equals(n) ? 3 : 2;
    }

    /** 去掉 BOM 后的正文 */
    public static byte[] stripBom(byte[] data) {
        int n = bomLength(data);
        if (n == 0) return data;
        return Arrays.copyOfRange(data, n, data.length);
    }

    /** 判断是否“纯 ASCII” */
    public static boolean isAscii(byte[] data, int limit) {
        int n = Math.min(data.length, limit);
        for (int i = 0; i < n; i++) if (data[i] < 0) return false;
        return true;
    }

    public static String friendlyName(String charset) {
        if (charset == null) return "未知";
        if (UTF8.equals(charset)) return "UTF-8";
        if (GBK.equals(charset) || GB18030.equals(charset)) return "GBK / GB18030";
        if (BIG5.equals(charset)) return "Big5（繁体）";
        if (SJIS.equals(charset)) return "Shift_JIS（日文）";
        if (EUCKR.equals(charset)) return "EUC-KR（韩文）";
        if (CP1252.equals(charset)) return "Windows-1252";
        if (LATIN1.equals(charset)) return "ISO-8859-1";
        if (UTF16LE.equals(charset)) return "UTF-16LE";
        if (UTF16BE.equals(charset)) return "UTF-16BE";
        return charset;
    }

    public static List<String> allSourceGuesses() {
        List<String> list = new ArrayList<>();
        list.addAll(Arrays.asList(SOURCE_GUESSES));
        return list;
    }
}
