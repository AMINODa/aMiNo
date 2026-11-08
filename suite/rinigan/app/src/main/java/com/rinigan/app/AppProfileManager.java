package com.rinigan.app;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic per-app configuration manager.
 *
 * Resolution order for a calling package (first match wins):
 *   1. Custom user profile from cs_config.json   (key "app_profiles", editable from the app UI)
 *   2. Custom user profile from app_profiles.json (advanced, file placed in DCIM/Camera1)
 *   3. Built-in preset for well-known calling apps
 *   4. Universal default fallback (portrait normalization + FIT_CENTER)
 *
 * Matching supports exact package names and dotted prefixes, so a single
 * "com.whatsapp" profile also covers "com.whatsapp.w4b" and "com.whatsapp.w4b.debug".
 *
 * Profiles hot-reload: cs_config.json changes propagate through the existing
 * ConfigWatcher broadcast/poll mechanism, app_profiles.json is checked by mtime.
 */
public final class AppProfileManager {

    public static final String EXTERNAL_PROFILES_FILE_NAME = "app_profiles.json";
    public static final String KEY_UNIVERSAL_DEFAULTS = "universal_defaults";
    public static final String KEY_PROFILES = "profiles";

    private static final AppProfileManager INSTANCE = new AppProfileManager();

    public static AppProfileManager getInstance() {
        return INSTANCE;
    }

    private AppProfileManager() {
    }

    // ======================== Built-in presets ========================

    private static final Map<String, TransformProfile> BUILTINS = buildBuiltins();

    private static Map<String, TransformProfile> buildBuiltins() {
        Map<String, TransformProfile> m = new LinkedHashMap<>();

        // --- WhatsApp (portrait calls, FIT keeps the whole frame visible) ---
        m.put("com.whatsapp", TransformProfile.create("com.whatsapp", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("com.whatsapp.w4b", TransformProfile.create("com.whatsapp.w4b", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));

        // --- TikTok fills the screen: CENTER_CROP avoids black bars ---
        m.put("com.zhiliaoapp.musically", TransformProfile.create("com.zhiliaoapp.musically", 0,
                TransformProfile.SCALE_CENTER_CROP, 0, 0, false, false, true, true, true));
        m.put("com.ss.android.ugc.trill", TransformProfile.create("com.ss.android.ugc.trill", 0,
                TransformProfile.SCALE_CENTER_CROP, 0, 0, false, false, true, true, true));

        // --- Instagram: portrait feed / reels ---
        m.put("com.instagram.android", TransformProfile.create("com.instagram.android", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));

        // --- Telegram family ---
        m.put("org.telegram.messenger", TransformProfile.create("org.telegram.messenger", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("org.telegram.plus", TransformProfile.create("org.telegram.plus", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));

        // --- Facebook / Messenger ---
        m.put("com.facebook.orca", TransformProfile.create("com.facebook.orca", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("com.facebook.katana", TransformProfile.create("com.facebook.katana", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));

        // --- Video conferencing: landscape-tolerant, FIT_CENTER is the safest ---
        m.put("us.zoom.videomeetings", TransformProfile.create("us.zoom.videomeetings", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, false, true, true));
        m.put("com.google.android.apps.tachyon", TransformProfile.create(
                "com.google.android.apps.tachyon", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("com.microsoft.teams", TransformProfile.create("com.microsoft.teams", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, false, true, true));
        m.put("com.skype.raider", TransformProfile.create("com.skype.raider", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, false, true, true));
        m.put("com.snapchat.android", TransformProfile.create("com.snapchat.android", 0,
                TransformProfile.SCALE_CENTER_CROP, 0, 0, false, false, true, true, true));
        m.put("com.viber.voip", TransformProfile.create("com.viber.voip", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("com.discord", TransformProfile.create("com.discord", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("com.tencent.mm", TransformProfile.create("com.tencent.mm", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));
        m.put("jp.naver.line.android", TransformProfile.create("jp.naver.line.android", 0,
                TransformProfile.SCALE_FIT_CENTER, 0, 0, false, false, true, true, true));

        return Collections.unmodifiableMap(m);
    }

    // ======================== State ========================

    private volatile TransformProfile universalDefault = TransformProfile.universalDefault();
    private final ConcurrentHashMap<String, TransformProfile> customCache = new ConcurrentHashMap<>();
    private volatile long externalFileMtime = 0;
    private volatile long lastConfigCheck = 0;
    private volatile String lastProfilesJson = null;

    // ======================== Public API ========================

    public TransformProfile getUniversalDefault() {
        refreshIfDirty();
        return universalDefault;
    }

    /** Built-in presets (read-only), used by the settings UI. */
    public Map<String, TransformProfile> getBuiltins() {
        return BUILTINS;
    }

    /** Custom (user) profiles currently loaded, used by the settings UI. */
    public Map<String, TransformProfile> getCustomProfiles() {
        refreshIfDirty();
        return new LinkedHashMap<>(customCache);
    }

    /**
     * Resolves the effective profile for a package.
     * Priority: custom (cs_config.json) &gt; external file &gt; built-in &gt; universal default.
     * Disabled profiles are skipped so that removing a bad override is always possible.
     */
    public TransformProfile getProfile(String pkg) {
        if (pkg == null || pkg.isEmpty()) return getUniversalDefault();
        refreshIfDirty();

        // 1 + 2: custom profiles (exact first, then longest prefix)
        TransformProfile p = lookupWithPrefix(customCache, pkg);
        if (p != null) return p;

        // 3: built-in presets
        p = lookupWithPrefixBuiltins(pkg);
        if (p != null) return p;

        // 4: universal fallback
        return universalDefault;
    }

    private TransformProfile lookupWithPrefix(Map<String, TransformProfile> map, String pkg) {
        TransformProfile exact = map.get(pkg);
        if (exact != null) return exact.enabled ? exact : null;
        // longest dotted-prefix match
        String best = null;
        for (String key : map.keySet()) {
            if (key.equals(pkg)) continue;
            if (pkg.startsWith(key + ".") && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        if (best != null) {
            TransformProfile p = map.get(best);
            if (p != null && p.enabled) return p;
        }
        return null;
    }

    private TransformProfile lookupWithPrefixBuiltins(String pkg) {
        TransformProfile exact = BUILTINS.get(pkg);
        if (exact != null) return exact;
        String best = null;
        for (String key : BUILTINS.keySet()) {
            if (pkg.startsWith(key + ".") && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best != null ? BUILTINS.get(best) : null;
    }

    // ======================== Hot reload ========================

    /** Forces the next getProfile() to re-read config sources. */
    public void invalidate() {
        lastConfigCheck = 0;
        lastProfilesJson = null;
    }

    private void refreshIfDirty() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck < 400) return; // light throttle
        synchronized (this) {
            if (System.currentTimeMillis() - lastConfigCheck < 400) return;
            lastConfigCheck = now;

            // --- source A: cs_config.json "app_profiles" (via HookGuards config) ---
            String profilesJson = readConfigProfilesJson();
            if (!equalsSafe(profilesJson, lastProfilesJson)) {
                lastProfilesJson = profilesJson;
                customCache.clear();
                if (profilesJson != null && !profilesJson.isEmpty()) {
                    parseInto(profilesJson, customCache, false);
                }
                LogUtil.log("[Transform]profiles reloaded from config (" + customCache.size() + " custom)");
            }

            // --- source B: universal defaults + external file overrides ---
            File external = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/DCIM/Camera1/", EXTERNAL_PROFILES_FILE_NAME);
            long mtime = 0;
            try {
                mtime = external.exists() ? external.lastModified() : 0;
            } catch (Exception ignored) {
            }
            if (mtime != externalFileMtime) {
                externalFileMtime = mtime;
                TransformProfile ud = TransformProfile.universalDefault();
                if (mtime > 0) {
                    String content = readFileQuietly(external);
                    if (content != null) {
                        try {
                            JSONObject root = new JSONObject(content);
                            JSONObject udJson = root.optJSONObject(KEY_UNIVERSAL_DEFAULTS);
                            if (udJson != null) {
                                JSONObject withName = new JSONObject(udJson.toString());
                                try {
                                    withName.put("packageName", TransformProfile.WILDCARD);
                                } catch (Exception ignored) {
                                }
                                TransformProfile parsed = TransformProfile.fromJson(withName, true);
                                if (parsed != null) ud = parsed;
                            }
                            // external file profiles also land in the custom cache
                            // (config profiles keep higher priority because they are parsed
                            //  after the file on cache clear + refill; to respect that we
                            //  re-apply the config JSON on top)
                            LinkedHashMap<String, TransformProfile> merged = new LinkedHashMap<>();
                            parseInto(readFileQuietly(external), merged, false);
                            if (profilesJson != null && !profilesJson.isEmpty()) {
                                parseInto(profilesJson, merged, false);
                            }
                            customCache.clear();
                            customCache.putAll(merged);
                            LogUtil.log("[Transform]external profiles merged ("
                                    + customCache.size() + " total)");
                        } catch (Exception e) {
                            LogUtil.log("[Transform]app_profiles.json parse failed: " + e);
                        }
                    }
                }
                universalDefault = ud.enabled ? ud : TransformProfile.universalDefault();
                LogUtil.log("[Transform]universal default → " + universalDefault);
            }
        }
    }

    private String readConfigProfilesJson() {
        try {
            if (HookGuards.isAppProcess()) return null; // never self-hook
            return HookGuards.getConfig().getString(ConfigManager.KEY_APP_PROFILES, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void parseInto(String json, Map<String, TransformProfile> out, boolean builtin) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            // accepted shapes: {"pkg": {...}} | {"profiles": {"pkg": {...}}} | [ {...}, ... ]
            JSONObject profiles = root.optJSONObject(KEY_PROFILES);
            if (profiles != null) {
                java.util.Iterator<String> it = profiles.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    JSONObject o = profiles.optJSONObject(key);
                    if (o == null) continue;
                    JSONObject withName = new JSONObject(o.toString());
                    try {
                        withName.put("packageName", key);
                    } catch (Exception ignored) {
                    }
                    TransformProfile p = TransformProfile.fromJson(withName, builtin);
                    if (p != null) out.put(p.packageName, p);
                }
                return;
            }
            JSONArray arr = root.optJSONArray(KEY_PROFILES);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    TransformProfile p = TransformProfile.fromJson(o, builtin);
                    if (p != null) out.put(p.packageName, p);
                }
                return;
            }
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                JSONObject withName = new JSONObject(o.toString());
                try {
                    withName.put("packageName", key);
                } catch (Exception ignored) {
                }
                TransformProfile p = TransformProfile.fromJson(withName, builtin);
                if (p != null) out.put(p.packageName, p);
            }
        } catch (Exception e) {
            LogUtil.log("[Transform]profiles JSON parse failed: " + e);
        }
    }

    private static String readFileQuietly(File f) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Package names covered by custom or builtin presets (for UI summaries). */
    public Set<String> knownPackages() {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        s.addAll(customCache.keySet());
        s.addAll(BUILTINS.keySet());
        return s;
    }
}
