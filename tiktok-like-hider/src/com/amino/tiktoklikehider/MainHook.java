package com.amino.tiktoklikehider;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String PROVIDER_URI = "content://com.amino.tiktoklikehider.config";
    private static final String TAG = "aMiNoLikeHider";
    private static final Map<String, Boolean> CFG = new HashMap<String, Boolean>();
    private static final Map<View, Boolean> MARKED = Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean initialized = false;
    private static volatile boolean receiverRegistered = false;
    private static volatile boolean configLoaded = false;
    private static volatile boolean diagEnabled = true;
    private static final Pattern COUNT_TEXT = Pattern.compile("^[0-9٠-٩][0-9٠-٩.,\\s]{0,6}[KMBkmb٠-٩]?$");
    private static final String[] CAND_SUBSTRINGS = {
        "like", "digg", "comment", "share", "collect", "favor", "bookmark",
        "follow", "desc", "nick", "user", "music", "song", "caption", "sound"
    };
    /** v1.3: budget of non-substring views logged as CAND (catches obfuscated ids). */
    private static int candBudget = 800;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (lpp == null) return;
        try {
            if (("com.zhiliaoapp.musically".equals(lpp.packageName)
                    || "com.zhiliaoapp.musically.lite".equals(lpp.packageName)) && !initialized) {
                initialized = true;
                synchronized (CFG) {
                    CFG.clear();
                    CFG.putAll(Config.defaultMap());
                }
                log("module loaded: pkg=" + lpp.packageName + " proc=" + lpp.processName);
                installAppHook();
                installAttachHook();
                installVisibilityHook();
                installContentDescriptionHook();
                log("hooks installed OK");
            }
        } catch (Throwable t) {
            try {
                logErr(t);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installAppHook() {
        try {
            XposedBridge.hookAllMethods(ContextWrapper.class, "attachBaseContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!receiverRegistered && (param.thisObject instanceof Application)) {
                            Application app = (Application) param.thisObject;
                            receiverRegistered = true;
                            DiagReporter.init(app);
                            loadConfigAtStartup(app);
                            registerConfigReceiver(app);
                            log("startup done | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
                        }
                    } catch (Throwable t) {
                        logErr(t);
                    }
                }
            });
        } catch (Throwable t) {
            logErr(t);
        }
    }

    private static void loadConfigAtStartup(Context context) {
        if (configLoaded) return;
        configLoaded = true;
        if (fetchProviderConfig(context) || loadCacheFile(context)) {
            return;
        }
        loadXsp();
    }

    private static boolean fetchProviderConfig(Context context) {
        try {
            String json = null;
            Bundle call = context.getContentResolver().call(Uri.parse(PROVIDER_URI), "get", null, null);
            if (call != null) {
                json = call.getString("config_json");
            }
            if (json == null || json.length() <= 0) {
                log("provider returned empty config");
                return false;
            }
            log("provider raw: " + trunc(json, 200));
            applyJson(json);
            writeCache(context, json);
            log("config loaded from ContentProvider | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
            return true;
        } catch (Throwable t) {
            log("provider fetch failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean loadCacheFile(Context context) {
        try {
            File filesDir = context.getFilesDir();
            File f = filesDir == null ? null : new File(filesDir, Config.CACHE_FILE);
            if (f == null || !f.exists()) return false;
            String json = readFile(f);
            log("cache raw: " + trunc(json, 200));
            applyJson(json);
            log("config loaded from host cache file | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void loadXsp() {
        try {
            XSharedPreferences xsp = new XSharedPreferences("com.amino.tiktoklikehider", Config.PREFS_NAME);
            xsp.reload();
            if (xsp.hasFile()) {
                synchronized (CFG) {
                    for (Config.Spec spec : Config.CATEGORIES) {
                        CFG.put(spec.key, Boolean.valueOf(xsp.getBoolean(spec.key, CFG.get(spec.key).booleanValue())));
                    }
                    diagEnabled = xsp.getBoolean(Config.KEY_DIAG, diagEnabled);
                }
                log("config loaded from XSharedPreferences | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
            } else {
                log("XSharedPreferences has no file — using defaults | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
            }
        } catch (Throwable t) {
            log("XSharedPreferences unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void applyJson(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            synchronized (CFG) {
                CFG.clear();
                CFG.putAll(Config.defaultMap());
                JSONObject o = new JSONObject(json);
                for (Config.Spec spec : Config.CATEGORIES) {
                    CFG.put(spec.key, Boolean.valueOf(o.optBoolean(spec.key, CFG.get(spec.key).booleanValue())));
                }
                diagEnabled = o.optBoolean(Config.KEY_DIAG, true);
            }
        } catch (Throwable t) {
            log("applyJson failed: " + t.getClass().getSimpleName());
        }
    }

    private static String readFile(File f) throws Exception {
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

    private static void writeCache(Context context, String json) {
        try {
            File filesDir = context.getFilesDir();
            if (filesDir == null) return;
            FileWriter w = new FileWriter(new File(filesDir, Config.CACHE_FILE));
            w.write(json);
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log("cache write failed: " + t.getClass().getSimpleName());
        }
    }

    private static String enabledSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (CFG) {
            for (Config.Spec spec : Config.CATEGORIES) {
                if (Boolean.TRUE.equals(CFG.get(spec.key))) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(spec.key);
                }
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private static boolean enabled(Config.Spec spec) {
        synchronized (CFG) {
            return Boolean.TRUE.equals(CFG.get(spec.key));
        }
    }

    private static boolean enabledByKey(String key) {
        synchronized (CFG) {
            return Boolean.TRUE.equals(CFG.get(key));
        }
    }

    private static void registerConfigReceiver(Application app) {
        try {
            BroadcastReceiver br = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String json;
                    try {
                        json = intent == null ? null : intent.getStringExtra("config_json");
                    } catch (Throwable t) {
                        return;
                    }
                    if (json != null) {
                        log("broadcast raw: " + trunc(json, 200));
                        applyJson(json);
                        writeCache(context, json);
                        log("config updated via broadcast | enabled: " + enabledSummary() + " | diag=" + diagEnabled);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Config.ACTION_CONFIG_UPDATE);
            // v1.3 fix: must be RECEIVER_EXPORTED (4) — the config broadcast comes from
            // the module app (different UID); NOT_EXPORTED blocked it on Android 13+.
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(br, filter, 4);
            } else {
                app.registerReceiver(br, filter);
            }
            log("config receiver registered");
        } catch (Throwable t) {
            logErr(t);
        }
    }

    private static void installAttachHook() {
        Method m = findMethod(View.class, "dispatchAttachedToWindow");
        if (m == null) m = findMethod(View.class, "onAttachedToWindow");
        if (m == null) {
            log("attach hook: method not found");
            return;
        }
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof View) {
                            inspect((View) param.thisObject, "attach");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("attach hook on View." + m.getName());
        } catch (Throwable t) {
            logErr(t);
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (name.equals(m.getName())) return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void installVisibilityHook() {
        try {
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof View) || !MARKED.containsKey(self)
                                || param.args == null || param.args.length <= 0) {
                            return;
                        }
                        param.args[0] = Integer.valueOf(8);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("visibility hook OK");
        } catch (Throwable t) {
            logErr(t);
        }
    }

    private static void installContentDescriptionHook() {
        try {
            XposedBridge.hookAllMethods(View.class, "setContentDescription", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        View v = (View) self;
                        if (MARKED.containsKey(v) || param.args == null || param.args.length == 0) return;
                        Object a0 = param.args[0];
                        if (a0 instanceof CharSequence) {
                            String key = matchByLabel(a0.toString());
                            if (key != null) {
                                markAndHide(v, key, "cd");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("contentDescription hook OK");
        } catch (Throwable t) {
            logErr(t);
        }
    }

    private static void inspect(View v, String via) {
        if (v == null) return;
        try {
            if (MARKED.containsKey(v)) return;
            String key = matchedCategory(v);
            if (key == null) {
                maybeCandidateLog(v);
            } else {
                markAndHide(v, key, via);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void markAndHide(View v, String key, String via) {
        try {
            MARKED.put(v, Boolean.TRUE);
            forceGone(v);
            logOnce("HID", v, key + "@" + via);
            String countKey = Config.COUNT_CATEGORY_OF.get(key);
            if (countKey != null && enabledByKey(countKey)) {
                hideNumericSiblings(v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceGone(View v) {
        try {
            v.setVisibility(8);
        } catch (Throwable ignored) {
        }
    }

    private static String matchedCategory(View v) {
        try {
            int id = v.getId();
            if (id != -1) {
                String resName = v.getResources().getResourceEntryName(id);
                if (resName != null) {
                    for (Config.Spec spec : Config.CATEGORIES) {
                        if (enabled(spec) && Config.matches(spec, resName, false)) {
                            return spec.key;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String simple = v.getClass().getSimpleName();
            if (simple != null && !simple.isEmpty()) {
                for (Config.Spec spec : Config.CATEGORIES) {
                    if (enabled(spec) && Config.matches(spec, simple, true)) {
                        return spec.key;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                return matchByLabel(cd.toString());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String matchByLabel(String label) {
        try {
            for (Config.Spec spec : Config.CATEGORIES) {
                if (spec.cdLabels != null && enabled(spec) && Config.labelMatches(spec, label)) {
                    return spec.key;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void hideNumericSiblings(View v) {
        try {
            if (v instanceof ViewGroup) {
                hideNumericChildren((ViewGroup) v);
            }
            ViewParent parent = v.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) parent;
            int idx = -1;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) == v) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return;
            int from = Math.max(0, idx - 1);
            int to = Math.min(g.getChildCount() - 1, idx + 1);
            for (int i = from; i <= to; i++) {
                View child = g.getChildAt(i);
                if (child != v && (child instanceof TextView) && isNumericCount(((TextView) child).getText())) {
                    MARKED.put(child, Boolean.TRUE);
                    forceGone(child);
                    logOnce("HID", child, "numeric-sibling");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hideNumericChildren(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            try {
                View child = g.getChildAt(i);
                if ((child instanceof TextView) && isNumericCount(((TextView) child).getText())) {
                    MARKED.put(child, Boolean.TRUE);
                    forceGone(child);
                    logOnce("HID", child, "numeric-child");
                }
            } catch (Throwable t) {
                return;
            }
        }
    }

    private static boolean isNumericCount(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty() || s.length() > 8) return false;
        return COUNT_TEXT.matcher(s).matches();
    }

    /** v1.3: broadened candidate logging — logs id/class/cd/text for unique views,
     *  including obfuscated ids (first 800 unique), so the diagnostics screen in the
     *  module app reveals the real TikTok identifiers. */
    private static void maybeCandidateLog(View v) {
        try {
            String idName = "";
            try {
                int id = v.getId();
                if (id != -1) idName = v.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
            }
            String cls = v.getClass().getSimpleName();
            String cd = "";
            try {
                CharSequence c = v.getContentDescription();
                if (c != null) cd = c.toString();
            } catch (Throwable ignored) {
            }
            String txt = "";
            if (v instanceof TextView) {
                try {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null) txt = t.toString();
                } catch (Throwable ignored) {
                }
            }
            String dedupe = "CAND|" + v.getClass().getName() + "|" + idName + "#" + Integer.toHexString(v.getId())
                    + "|" + cd + "|" + txt;
            if (!LOGGED.add(dedupe)) return;
            String lower = (idName + " " + cls + " " + cd).toLowerCase(Locale.ROOT);
            boolean interesting = false;
            for (String s : CAND_SUBSTRINGS) {
                if (!s.isEmpty() && lower.contains(s)) {
                    interesting = true;
                    break;
                }
            }
            if (!interesting) {
                if (candBudget <= 0) return;
                candBudget--;
            }
            StringBuilder line = new StringBuilder();
            line.append(v.getClass().getName())
                .append(" id=").append(idName.isEmpty() ? "no-id" : idName)
                .append("#").append(Integer.toHexString(v.getId()));
            if (!cd.isEmpty()) line.append(" cd=\"").append(trunc(cd, 40)).append("\"");
            if (!txt.isEmpty()) line.append(" txt=\"").append(trunc(txt, 40)).append("\"");
            String out = "[CAND] " + line;
            XposedBridge.log(TAG + " " + out);
            if (diagEnabled) {
                DiagReporter.send("CAND", out);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logOnce(String kind, View v, String via) {
        try {
            String cls = v.getClass().getName();
            String idStr = "no-id";
            try {
                int id = v.getId();
                if (id != -1) idStr = v.getResources().getResourceEntryName(id) + "#" + Integer.toHexString(id);
            } catch (Throwable ignored) {
            }
            String cd = "";
            try {
                CharSequence c = v.getContentDescription();
                if (c != null) cd = c.toString();
            } catch (Throwable ignored) {
            }
            String dedupe = kind + "|" + cls + "|" + idStr + "|" + via;
            if (!LOGGED.add(dedupe)) return;
            String out = "[" + kind + "] " + cls + " id=" + idStr + " cd=\"" + trunc(cd, 40) + "\" via=" + via;
            XposedBridge.log(TAG + " " + out);
            DiagReporter.send(kind, out);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
            DiagReporter.send("INFO", msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logErr(Throwable t) {
        try {
            XposedBridge.log(TAG + " error:");
            XposedBridge.log(t);
            DiagReporter.send("ERR", t.getClass().getName() + ": " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
