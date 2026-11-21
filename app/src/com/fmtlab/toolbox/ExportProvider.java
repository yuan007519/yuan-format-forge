package com.fmtlab.toolbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把自己的输出文件以 content:// 形式提供给其它 App（分享用）。
 * 不依赖 AndroidX 的 FileProvider。
 */
public class ExportProvider extends ContentProvider {

    public static final String AUTHORITY = "com.fmtlab.toolbox.files";

    public static Uri uriFor(android.content.Context ctx, File file) {
        String raw = file.getAbsolutePath();
        String enc = Base64.encodeToString(raw.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return Uri.parse("content://" + AUTHORITY + "/" + enc);
    }

    private File fileOf(Uri uri) throws FileNotFoundException {
        String enc = uri.getLastPathSegment();
        if (enc == null) throw new FileNotFoundException("bad uri");
        String path;
        try {
            path = new String(Base64.decode(enc, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        } catch (Exception e) {
            throw new FileNotFoundException("bad uri");
        }
        File f = new File(path);
        if (!f.exists()) throw new FileNotFoundException(path);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileOf(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return Ui.mimeFor(fileOf(uri).getName());
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        try {
            File f = fileOf(uri);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
        } catch (Exception ignored) {}
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
