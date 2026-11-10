package com.amino.tiktoklikehider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * v1.3: receives diagnostic lines sent by DiagReporter (inside patched TikTok)
 * and persists them to filesDir/amino_diag.txt — a log that never disappears.
 */
public class DiagReceiver extends BroadcastReceiver {
    public static final String DIAG_FILE = "amino_diag.txt";
    private static final int CAP_CHARS = 400000;
    private static final int TRIM_TO_CHARS = 220000;
    static final Object LOCK = new Object();

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String line = intent.getStringExtra("line");
            if (line == null || line.isEmpty()) return;
            String kind = intent.getStringExtra("kind");
            long ts = intent.getLongExtra("ts", System.currentTimeMillis());
            String out = DateFormat.format("MM-dd HH:mm:ss", ts).toString()
                    + " [" + (kind == null ? "?" : kind) + "] " + line + "\n";
            synchronized (LOCK) {
                File f = new File(context.getFilesDir(), DIAG_FILE);
                long len = f.exists() ? f.length() : 0;
                if (len > CAP_CHARS) {
                    trimFile(f);
                }
                FileWriter w = new FileWriter(f, true);
                w.write(out);
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Keep only the newest TRIM_TO_CHARS characters. */
    private static void trimFile(File f) {
        try {
            String all = readAll(f);
            if (all.length() > TRIM_TO_CHARS) {
                all = all.substring(all.length() - TRIM_TO_CHARS);
            }
            FileWriter w = new FileWriter(f, false);
            w.write(all);
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    static String readAll(File f) throws Exception {
        FileReader r = new FileReader(f);
        StringBuilder sb = new StringBuilder();
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
