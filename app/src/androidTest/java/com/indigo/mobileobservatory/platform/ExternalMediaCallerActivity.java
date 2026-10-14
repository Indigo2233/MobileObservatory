package com.indigo.mobileobservatory.platform;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** Separate test-APK task/UID. Plain Java avoids target-APK-only Kotlin/AndroidX dependencies. */
public class ExternalMediaCallerActivity extends Activity {
    private File source;
    private TextView sourceStatus;
    private ResultReceiver closeReceiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = Uri.parse(Objects.requireNonNull(getIntent().getStringExtra("source_uri")));
        String name;
        try { name = ExternalCallerFileProvider.fileName(uri); }
        catch (IOException failure) { throw new IllegalArgumentException("Invalid fixture source", failure); }
        source = new File(getFilesDir(), "shared/" + name);
        Objects.requireNonNull(source.getParentFile()).mkdirs();
        if (getIntent().getBooleanExtra("close_fixture", false)) {
            closeReceiver = getIntent().getParcelableExtra("close_receiver");
            finish(); return;
        }
        // A configuration recreation must keep the caller's source. Each test owns its own token.
        if (!source.isFile()) {
            Bitmap bitmap = Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(40, 70, 110));
            try (FileOutputStream output = new FileOutputStream(source)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            catch (IOException failure) { throw new IllegalStateException("Cannot create caller fixture", failure); }
            finally { bitmap.recycle(); }
        }
        String requested = getIntent().getStringExtra("open_action");
        String action = requested == null ? Intent.ACTION_VIEW : requested;
        String target = Objects.requireNonNull(getIntent().getStringExtra("target_package"));
        String targetClass = Objects.requireNonNull(getIntent().getStringExtra("target_activity_class"));
        String suppliedMime = getIntent().getStringExtra("source_mime");
        String mime = suppliedMime == null ? "image/png" : suppliedMime;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this); title.setText("External media caller"); title.setTextSize(24f); content.addView(title);
        sourceStatus = new TextView(this); content.addView(sourceStatus);
        Button open = new Button(this); open.setText("Open shared file");
        open.setContentDescription("Open shared file " + name); content.addView(open);
        open.setOnClickListener(view -> {
            Intent share = new Intent(action).setComponent(new ComponentName(target, targetClass));
            if (Intent.ACTION_VIEW.equals(action)) share.setDataAndType(uri, mime);
            else share.setType(mime).putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newRawUri("Shared file", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share);
        });
        setContentView(content);
    }

    @Override protected void onResume() {
        super.onResume();
        if (sourceStatus == null) return;
        if (!source.isFile()) { sourceStatus.setText("Source missing"); return; }
        try (FileInputStream input = new FileInputStream(source); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder text = new StringBuilder("Source kept: ");
            for (byte value : hash) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            sourceStatus.setText(text);
        } catch (IOException | NoSuchAlgorithmException failure) { sourceStatus.setText("Source unreadable"); }
    }

    @Override protected void onDestroy() {
        boolean closed = source == null || !source.exists();
        if (isFinishing() && source != null) closed = !source.exists() || source.delete();
        super.onDestroy();
        if (closeReceiver != null) closeReceiver.send(closed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, Bundle.EMPTY);
    }
}
