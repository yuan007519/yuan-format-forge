package com.fmtlab.toolbox.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 最小 OOXML 打包/解包工具（PPTX 读写共用） */
public final class Ooxml {

    private Ooxml() {}

    public static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) bo.write(buf, 0, n);
                files.put(e.getName().replace('\\', '/'), bo.toByteArray());
            }
        } finally {
            try { zis.close(); } catch (Exception ignored) {}
        }
        return files;
    }

    public static void writeZip(File out, Map<String, byte[]> entries) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            zos.setLevel(9);
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                ZipEntry e = new ZipEntry(en.getKey());
                e.setTime(0L);
                byte[] data = en.getValue();
                boolean raw = en.getKey().toLowerCase(java.util.Locale.US).endsWith(".jpeg")
                        || en.getKey().toLowerCase(java.util.Locale.US).endsWith(".jpg")
                        || en.getKey().toLowerCase(java.util.Locale.US).endsWith(".png");
                if (raw) {
                    e.setMethod(ZipEntry.STORED);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data, 0, data.length);
                    e.setCrc(crc.getValue());
                }
                zos.putNextEntry(e);
                zos.write(data);
                zos.closeEntry();
            }
        } finally {
            try { zos.close(); } catch (Exception ignored) {}
        }
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.equals("amp")) rep = "&";
                    else if (ent.equals("lt")) rep = "<";
                    else if (ent.equals("gt")) rep = ">";
                    else if (ent.equals("quot")) rep = "\"";
                    else if (ent.equals("apos")) rep = "'";
                    else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(2), 16)); } catch (Exception ignored) {}
                    } else if (ent.startsWith("#")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(1))); } catch (Exception ignored) {}
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static String str(Map<String, byte[]> files, String name) {
        byte[] b = files.get(name);
        if (b == null) return null;
        return new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    static byte[] bytes(String xml) {
        return xml.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    static String attr(String xml, String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + "[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }
}
