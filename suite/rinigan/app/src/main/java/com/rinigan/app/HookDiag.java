package com.rinigan.app;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.3 — Hook-side status reporter (THE diagnostics channel).
 *
 * The hook runs inside the TARGET app process (LSPatch). Its logs go to the
 * LSPatch log page which the user could not locate — the result was "the link
 * shows nothing" with zero usable information.
 *
 * Fix: every pipeline stage reports its state through the Rinigan
 * ContentProvider back into the Rinigan APP process, where the Diagnostics
 * card displays it in plain language. Reports are throttled (2s per unique
 * type+detail pair) to keep binder traffic negligible.
 */
public final class HookDiag {

    private HookDiag() {}

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 2000;

    /**
     * Reports a pipeline event. Safe to call from any thread, never throws.
     *
     * @param type   short event key: camera_opened / relay / fallback / error / source
     * @param detail human-readable detail
     */
    public static void report(Context context, String type, String detail) {
        try {
            String key = type + "|" + detail;
            long now = System.currentTimeMillis();
            Long last = LAST.get(key);
            if (last != null && now - last < THROTTLE_MS) return;
            LAST.put(key, now);

            LogUtil.log("[HookDiag]" + type + " " + (detail == null ? "" : detail));
            if (context == null) return;
            String pkg = HookGuards.isAppProcess() ? "rinigan-app" : context.getPackageName();
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString(IpcContract.EXTRA_HOOK_TYPE, type);
            extras.putString(IpcContract.EXTRA_HOOK_DETAIL, detail == null ? "" : detail);
            extras.putLong("ts", now);
            context.getContentResolver().call(
                    IpcContract.CONTENT_URI, IpcContract.METHOD_REPORT_HOOK, pkg, extras);
        } catch (Throwable ignored) {
        }
    }

    /** Convenience: asks the Rinigan app to start the Stream Engine service. */
    public static void ensureEngine(Context context) {
        try {
            if (context == null) return;
            context.getContentResolver().call(
                    IpcContract.CONTENT_URI, IpcContract.METHOD_ENSURE_ENGINE, null, null);
        } catch (Throwable ignored) {
        }
    }
}
