package com.amino.tiktoklikehider;

import android.content.Context;
import android.content.Intent;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * v1.3 persistent diagnostics channel.
 * Runs inside the patched TikTok process; forwards diagnostic lines as broadcasts
 * to the module app (com.amino.tiktoklikehider), where DiagReceiver persists them
 * to a file that never disappears — replacing the ephemeral LSPatch log view.
 */
public final class DiagReporter {
    public static final String ACTION_DIAG = "com.amino.tiktoklikehider.ACTION_DIAG";
    private static final String MODULE_PKG = "com.amino.tiktoklikehider";

    private static volatile Context appContext = null;
    private static final Set<String> SENT = Collections.synchronizedSet(new HashSet<String>());

    private DiagReporter() {
    }

    /** Called once from MainHook when the host Application attaches. */
    public static void init(Context context) {
        if (context != null) {
            try {
                appContext = context.getApplicationContext();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Send one diagnostic line to the module app.
     * kind: INFO | HID | CAND | ERR | CFG
     * Identical lines are sent once per session to keep broadcast volume bounded.
     */
    public static void send(String kind, String line) {
        try {
            if (line == null) return;
            if (line.length() > 900) line = line.substring(0, 900) + "…";
            String key = kind + "|" + line;
            if (SENT.size() > 1200) {
                SENT.clear();
            }
            if (!SENT.add(key)) return;
            Context c = appContext;
            if (c == null) return;
            Intent i = new Intent(ACTION_DIAG);
            i.setPackage(MODULE_PKG);
            i.putExtra("kind", kind);
            i.putExtra("line", line);
            i.putExtra("ts", System.currentTimeMillis());
            c.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
