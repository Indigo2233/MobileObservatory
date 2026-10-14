package com.indigo.mobileobservatory.indeconvprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ProbeActivity extends Activity {
    static {
        System.loadLibrary("MNN");
        System.loadLibrary("indeconv_probe");
    }

    private static native int runNative(
            String modelPath,
            String inputPath,
            String outputDirectory,
            String mode,
            int warmups,
            int runs,
            int threads,
            String logPath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(32, 32, 32, 32);
        status.setText("Preparing InDeConv OpenCL probe...");
        setContentView(status);

        String modesValue = getIntent().getStringExtra("modes");
        final String[] modes = (modesValue == null || modesValue.isBlank())
                ? new String[]{"opencl-image", "opencl-buffer"}
                : modesValue.split(",");
        final int warmups = getIntent().getIntExtra("warmups", 1);
        final int runs = getIntent().getIntExtra("runs", 4);
        final int threads = getIntent().getIntExtra("threads", 4);

        new Thread(() -> execute(status, modes, warmups, runs, threads), "indeconv-probe").start();
    }

    private void execute(TextView status, String[] modes, int warmups, int runs, int threads) {
        File files = getFilesDir();
        File model = new File(files, "InDeConv-fp16.mnn");
        File input = new File(files, "real-input.bin");
        File results = new File(files, "results");
        deleteRecursively(results);
        results.mkdirs();

        List<String> outcomes = new ArrayList<>();
        for (String rawMode : modes) {
            String mode = rawMode.trim();
            if (mode.isEmpty()) continue;
            File output = new File(results, mode);
            output.mkdirs();
            File log = new File(output, "run.log");
            int result = runNative(
                    model.getAbsolutePath(),
                    input.getAbsolutePath(),
                    output.getAbsolutePath(),
                    mode,
                    warmups,
                    runs,
                    threads,
                    log.getAbsolutePath());
            outcomes.add(mode + "=" + result);
        }

        File done = new File(results, "done.txt");
        try (FileWriter writer = new FileWriter(done, false)) {
            writer.write(String.join("\n", outcomes));
            writer.write("\n");
        } catch (IOException error) {
            outcomes.add("done-file-error=" + error);
        }

        String text = String.join("\n", outcomes);
        runOnUiThread(() -> status.setText(text));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}
