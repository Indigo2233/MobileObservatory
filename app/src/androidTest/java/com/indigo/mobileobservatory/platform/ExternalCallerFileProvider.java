package com.indigo.mobileobservatory.platform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;

/** Framework-only provider. One fixture token is readable; writes are never accepted. */
public class ExternalCallerFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.indigo.mobileobservatory.testcaller.files";
    public static Uri uri(String token) {
        if (!token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid fixture token");
        return Uri.parse("content://" + AUTHORITY + "/platform-boundary-" + token + ".png");
    }
    static String fileName(Uri uri) throws FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1
                || !uri.getPathSegments().get(0).matches("platform-boundary-[0-9a-f]{32}\\.png"))
            throw new FileNotFoundException("Unknown test file");
        return uri.getPathSegments().get(0);
    }
    @Override public boolean onCreate() { return true; }
    private File source(Uri uri) throws FileNotFoundException {
        return new File(Objects.requireNonNull(getContext()).getFilesDir(), "shared/" + fileName(uri));
    }
    @Override public String getType(Uri uri) {
        try { source(uri); return "image/png"; } catch (FileNotFoundException invalid) { return null; }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try { file = source(uri); } catch (FileNotFoundException invalid) { throw new IllegalArgumentException(invalid); }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns); cursor.addRow(values); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(source(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
}
