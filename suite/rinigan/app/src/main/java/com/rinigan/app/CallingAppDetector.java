package com.rinigan.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic calling-package detection.
 *
 * Determines which application is actually requesting the virtual camera stream:
 *
 *  1. Primary source: the host process context (each app's camera call runs inside its own
 *     process, so Context#getPackageName() identifies the consumer).
 *  2. Cross-check / fallback: Binder.getCallingUid() resolved through PackageManager
 *     (getPackagesForUid / getNameForUid) - covers cases where the context is a
 *     non-UI context or an isolated process (e.g. "...:isolated" / "com.google.android...").
 *  3. As a last resort the Xposed loadPackage param captured at hook time is used.
 *
 * Results are cached per UID; isolated-process suffixes are stripped so that
 * "com.whatsapp:isolated" style names still resolve to "com.whatsapp".
 */
public final class CallingAppDetector {

    private static final ConcurrentHashMap<Integer, String> UID_CACHE = new ConcurrentHashMap<>();
    private static volatile String lastDetected = null;

    private CallingAppDetector() {
    }

    /**
     * Detects the package that owns the current camera session.
     *
     * @param context      any context living in the hooked app process (may be null)
     * @param hookPackage  package name captured from LoadPackageParam (may be null)
     * @return best-effort caller package name, never null ("" when nothing is known)
     */
    public static String detect(Context context, String hookPackage) {
        // 1. context package = process owner
        if (context != null) {
            try {
                String ctxPkg = context.getPackageName();
                if (isValidPackage(ctxPkg)) {
                    lastDetected = normalize(ctxPkg);
                    return lastDetected;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Binder UID resolution
        String byUid = detectByUid(context);
        if (isValidPackage(byUid)) {
            lastDetected = byUid;
            return lastDetected;
        }

        // 3. static hook package
        if (isValidPackage(hookPackage)) {
            lastDetected = normalize(hookPackage);
            return lastDetected;
        }

        return lastDetected != null ? lastDetected : "";
    }

    /** Convenience overload used from hook callbacks. */
    public static String detect(Context context) {
        return detect(context, null);
    }

    /**
     * Resolves the current Binder calling UID to a package name.
     * Uses PackageManager so shared-UID apps and isolated processes are handled.
     */
    public static String detectByUid(Context context) {
        try {
            int uid = Binder.getCallingUid();
            if (uid <= 0) uid = Process.myUid();
            String cached = UID_CACHE.get(uid);
            if (cached != null) return cached;

            String resolved = "";
            if (context != null) {
                PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    String[] pkgs = pm.getPackagesForUid(uid);
                    if (pkgs != null && pkgs.length > 0) {
                        resolved = pkgs[0];
                    } else {
                        String name = pm.getNameForUid(uid);
                        if (name != null) resolved = name;
                    }
                }
            }
            resolved = normalize(resolved);
            UID_CACHE.put(uid, resolved);
            LogUtil.log("[Transform]uid " + uid + " → package '" + resolved + "'");
            return resolved;
        } catch (Throwable t) {
            LogUtil.log("[Transform]uid detection failed: " + t);
            return "";
        }
    }

    /**
     * Strips process suffixes (":isolated", ":service", ...) and returns a clean package name.
     */
    public static String normalize(String pkg) {
        if (pkg == null) return "";
        int idx = pkg.indexOf(':');
        return idx > 0 ? pkg.substring(0, idx) : pkg;
    }

    private static boolean isValidPackage(String pkg) {
        return pkg != null && !pkg.trim().isEmpty() && !"android".equals(normalize(pkg));
    }

    /** Last detected package (for logging / diagnostics). */
    public static String lastDetected() {
        return lastDetected != null ? lastDetected : "";
    }

    /** Clears the UID cache (called when profiles change). */
    public static void clearCache() {
        UID_CACHE.clear();
    }
}
