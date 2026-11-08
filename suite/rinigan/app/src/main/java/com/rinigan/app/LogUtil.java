package com.rinigan.app;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v6.3 — DUAL-CHANNEL logging (critical LSPatch fix).
 *
 * WHY THIS EXISTS: under LSPatch the module runs inside the TARGET app's
 * process. XposedBridge.log() output goes to the LSPatch manager's log page —
 * a plain "logcat" capture taken from the phone does NOT contain it. The user
 * reported "the link shows nothing" and the log they captured proved it:
 * zero [Rinigan] lines anywhere.
 *
 * FIX: every line is now written BOTH to XposedBridge.log (LSPatch/LSPosed
 * log page) AND to android.util.Log with the plain tag "Rinigan", so a
 * filtered capture works everywhere:
 *
 *     adb logcat -s Rinigan:V
 *
 * The class also keeps a small in-memory ring buffer of the last lines so the
 * in-app Diagnostics card can show recent engine logs without a PC.
 */
public class LogUtil {
    /** Plain logcat tag — filter with: adb logcat -s Rinigan:V */
    private static final String LOGCAT_TAG = "Rinigan";
    /** Prefix still visible inside Xposed log page lines. */
    private static final String XPOSED_PREFIX = "[Rinigan]";

    private static volatile boolean xposedAvailable = false;
    private static volatile boolean xposedChecked = false;

    // ======================== In-memory ring buffer (UI diagnostics) ========================

    private static final int RING_CAPACITY = 120;
    private static final Deque<String> RING = new ArrayDeque<>(RING_CAPACITY);

    private static boolean isXposedAvailable() {
        if (!xposedChecked) {
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                xposedAvailable = true;
            } catch (ClassNotFoundException e) {
                xposedAvailable = false;
            }
            xposedChecked = true;
        }
        return xposedAvailable;
    }

    public static void log(String msg) {
        if (msg == null) return;
        // channel 1: Xposed log page (LSPatch manager → Logs)
        if (isXposedAvailable()) {
            try {
                de.robv.android.xposed.XposedBridge.log(XPOSED_PREFIX + msg);
            } catch (Throwable ignored) {
            }
        }
        // channel 2: plain logcat — THIS is what a phone-side log capture sees
        try {
            Log.i(LOGCAT_TAG, msg);
        } catch (Throwable ignored) {
        }
        // channel 3: in-app ring buffer (Diagnostics card)
        synchronized (RING) {
            if (RING.size() >= RING_CAPACITY) RING.pollFirst();
            RING.addLast(msg);
        }
    }

    public static void log(String prefix, String msg) {
        log(prefix + msg);
    }

    /** Snapshot of the most recent lines (oldest first) for the Diagnostics UI. */
    public static java.util.List<String> recentLines() {
        synchronized (RING) {
            return new java.util.ArrayList<>(RING);
        }
    }

    /** Clears the ring buffer (Diagnostics UI refresh button). */
    public static void clearRecent() {
        synchronized (RING) {
            RING.clear();
        }
    }
}
