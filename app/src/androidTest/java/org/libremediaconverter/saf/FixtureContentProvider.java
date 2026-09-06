package org.libremediaconverter.saf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A plain {@link ContentProvider} serving the committed media fixtures over {@code content://}.
 *
 * <p><b>Why this exists alongside {@link FixtureDocumentsProvider}.</b> Every passing convert and
 * join test hands the worker a {@code Uri.fromFile(...)}, which takes the {@code uri.path} arm and
 * never touches {@code FFmpegKitConfig.getSafParameterForRead}. That bridge is on 100% of real user
 * conversions and was on 0% of tested ones; only its failure side was covered, by
 * {@code UnopenableUriTest} pointing at an authority that does not exist.
 *
 * <p><b>Why not the documents provider.</b> It cannot be reached. Measured three ways on an API 34
 * emulator: a {@code DOCUMENTS_PROVIDER} declared without {@code MANAGE_DOCUMENTS} is refused at
 * install ("Provider must be protected by MANAGE_DOCUMENTS"); instrumentation runs in the target
 * app's process, so {@code Instrumentation.getContext()} still carries the app's uid and is denied;
 * and {@code adoptShellPermissionIdentity(MANAGE_DOCUMENTS)} is denied identically. The denial says
 * what is required — <i>"you obtain access using ACTION_OPEN_DOCUMENT or related APIs"</i> — so a
 * documents provider is reachable only through a picker-issued grant. See issue #226.
 *
 * <p>The bridge does not need one. {@code getSafParameterForRead} opens a file descriptor through
 * the resolver and hands FFmpeg a {@code saf:} path; any readable {@code content://} URI exercises
 * it. An ordinary provider may be exported without a permission, so this one is, and the whole test
 * stays headless — no DocumentsUI, and none of the flake #190 records.
 *
 * <p>Unlike {@link FixtureDocumentsProvider} this may use {@code androidx} and Kotlin freely — it is
 * loaded into the app process like any other provider, not into the bare test process. It is kept
 * in Java anyway, next to its sibling, so the two read alike.
 */
public final class FixtureContentProvider extends ContentProvider {

    /** Authority. Distinct from the documents provider's, and from anything the app declares. */
    public static final String AUTHORITY = "org.libremediaconverter.test.content";

    /** Builds a URI for one of this source set's committed assets, e.g. {@code sample_h264.mp4}. */
    public static Uri uriFor(String assetName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(assetName).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("this provider is read-only: " + mode);
        }
        return ParcelFileDescriptor.open(unpack(assetOf(uri)), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Enough of {@link OpenableColumns} for {@code InputQuery.describe} to name and size the input.
     *
     * <p>Without these the app reaches the "Size unknown" screen, which is a different test.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String asset = assetOf(uri);
        File file;
        try {
            file = unpack(asset);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(
            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.newRow().add(OpenableColumns.DISPLAY_NAME, asset).add(OpenableColumns.SIZE, file.length());
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return assetOf(uri).endsWith(".m4a") ? "audio/mp4" : "video/mp4";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only fixture provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only fixture provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only fixture provider");
    }

    private static String assetOf(Uri uri) {
        String asset = uri.getLastPathSegment();
        return asset == null ? "" : asset;
    }

    /**
     * The asset on disk, unpacked the first time anything asks.
     *
     * <p>Reported as {@link FileNotFoundException} rather than swallowed: a provider answering with
     * a zero-byte file would fail the conversion for a reason nothing states.
     */
    private File unpack(String asset) throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), "provided_" + asset);
        if (file.length() > 0L) {
            return file;
        }
        try (InputStream source = getContext().getAssets().open(asset);
                OutputStream sink = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("could not unpack " + asset + ": " + e);
        }
        return file;
    }
}
