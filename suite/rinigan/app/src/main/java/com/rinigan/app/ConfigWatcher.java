package com.rinigan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

/**
 * Config change listener: ContentObserver + FileObserver + BroadcastReceiver.
 * v5.2 fix: debounce switched to trailing mode,
 * rapid consecutive changes only run the last one, so no intermediate state is lost.
 */
public final class ConfigWatcher {

    public interface Callback {
        void onMediaSourceChanged();
        void onRotationChanged(int degrees);
    }

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private android.database.ContentObserver configObserver;
    private FileObserver configFileObserver;
    private Runnable pendingCallback;
    private static final long DEBOUNCE_MS = 300;

    public ConfigWatcher(Callback callback) {
        this.callback = callback;
    }

    public void init(final Context context) {
        if (configObserver != null) return;

        LogUtil.log("[CS]initializing config listener");
        LogUtil.log("[DIAG]ConfigWatcher.init → start, context=" + context.getPackageName());
        
        // ---- ensure the ConfigManager held by HookGuards has a Context (enables cross-process config reads via Provider) ----
        HookGuards.ensureConfigHasContext(context);
        LogUtil.log("[DIAG]ConfigWatcher.init → ensureConfigHasContext done");

        // ---- ContentObserver (provider changes) ----
        configObserver = new android.database.ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                LogUtil.log("[CS]provider config change");
                HookGuards.invalidateConfig();
                fireCallback();
            }
        };

        boolean observerRegistered = false;
        try {
            context.getContentResolver().registerContentObserver(IpcContract.URI_CONFIG, true, configObserver);
            observerRegistered = true;
            LogUtil.log("[DIAG]ConfigWatcher.init → ContentObserver registered: " + IpcContract.URI_CONFIG);
        } catch (Exception e) {
            LogUtil.log("[DIAG]ConfigWatcher.init → ContentObserver registration failed: " + e);
        }

        if (!observerRegistered) {
            LogUtil.log("[CS]falling back to FileObserver listening");
            try {
                String configDir = ConfigManager.DEFAULT_CONFIG_DIR;
                configFileObserver = new FileObserver(configDir,
                        FileObserver.MODIFY | FileObserver.CREATE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.endsWith(".json")) {
                            LogUtil.log("[CS]file changed: " + path);
                            mainHandler.post(() -> {
                                HookGuards.invalidateConfig();
                                fireCallback();
                            });
                        }
                    }
                };
                configFileObserver.startWatching();
                LogUtil.log("[CS]FileObserver started: " + configDir);
            } catch (Exception e) {
                LogUtil.log("[CS]FileObserver failed to start: " + e);
            }

            mainHandler.postDelayed(() ->
                    getConfig(context).requestConfig(context), 1000);
        }

        registerBroadcastReceiver(context);

        // ---- fallback: periodic file polling (5s interval) in case both ContentObserver/FileObserver fail ----
        LogUtil.log("[DIAG]ConfigWatcher.init → starting file polling fallback");
        mainHandler.postDelayed(new Runnable() {
            private long lastMtime = new java.io.File(ConfigManager.DEFAULT_CONFIG_DIR,
                    ConfigManager.CONFIG_FILE_NAME).lastModified();
            @Override
            public void run() {
                try {
                    long mtime = new java.io.File(ConfigManager.DEFAULT_CONFIG_DIR,
                            ConfigManager.CONFIG_FILE_NAME).lastModified();
                    if (mtime > lastMtime) {
                        LogUtil.log("[DIAG]ConfigWatcher.poll → mtime changed (" + lastMtime + "→" + mtime + "), firing callback");
                        lastMtime = mtime;
                        HookGuards.invalidateConfig();
                        fireCallback();
                    }
                } catch (Exception e) {
                    LogUtil.log("[DIAG]ConfigWatcher.poll → exception: " + e);
                }
                mainHandler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    /** Trailing debounce: each call resets the timer; the callback fires after 300ms without new events */
    private void fireCallback() {
        LogUtil.log("[DIAG]ConfigWatcher.fireCallback invoked");
        if (pendingCallback != null) {
            mainHandler.removeCallbacks(pendingCallback);
        }
        pendingCallback = new Runnable() {
            @Override
            public void run() {
                LogUtil.log("[DIAG]ConfigWatcher → debounce ended, firing onMediaSourceChanged");
                pendingCallback = null;
                callback.onMediaSourceChanged();
            }
        };
        mainHandler.postDelayed(pendingCallback, DEBOUNCE_MS);
    }

    private void registerBroadcastReceiver(final Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (IpcContract.ACTION_UPDATE_CONFIG.equals(intent.getAction())) {
                        handleConfigUpdate(intent);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(IpcContract.ACTION_UPDATE_CONFIG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            LogUtil.log("[CS]broadcast receiver registered");
        } catch (Exception e) {
            LogUtil.log("[CS]failed to register broadcast receiver: " + e);
        }
    }

    private void handleConfigUpdate(Intent intent) {
        String configJson = intent.getStringExtra(IpcContract.EXTRA_CONFIG_JSON);
        if (configJson == null) return;

        // inject the ConfigManager held by HookGuards directly instead of creating a new instance
        ConfigManager config = HookGuards.getConfig();

        String oldVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String oldImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String oldMode = config.getString(ConfigManager.KEY_REPLACE_MODE, ConfigManager.REPLACE_MODE_VIDEO);
        boolean oldFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int oldRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        String oldSourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        String oldStreamUrl = config.getString(ConfigManager.KEY_STREAM_URL, "");
        boolean oldSound = config.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
        boolean oldDisable = config.getBoolean(ConfigManager.KEY_DISABLE_MODULE, false);
        boolean oldToast = config.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        String oldProfiles = config.getString(ConfigManager.KEY_APP_PROFILES, null);
        boolean oldEngine = config.getBoolean(ConfigManager.KEY_PERAPP_ENGINE_DISABLED, false);

        config.updateConfigFromJSON(configJson);

        String newVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String newImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String newMode = config.getString(ConfigManager.KEY_REPLACE_MODE, ConfigManager.REPLACE_MODE_VIDEO);
        boolean newFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int newRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        String newSourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        String newStreamUrl = config.getString(ConfigManager.KEY_STREAM_URL, "");
        boolean newSound = config.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
        boolean newDisable = config.getBoolean(ConfigManager.KEY_DISABLE_MODULE, false);
        boolean newToast = config.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        String newProfiles = config.getString(ConfigManager.KEY_APP_PROFILES, null);
        boolean newEngine = config.getBoolean(ConfigManager.KEY_PERAPP_ENGINE_DISABLED, false);

        boolean profilesChanged = !java.util.Objects.equals(oldProfiles, newProfiles)
                || (oldEngine != newEngine);
        if (profilesChanged) {
            // drop resolved profiles + live relays; hot-swap below re-wraps surfaces
            TransformApplicator.invalidateCaches();
            TransformApplicator.releaseAll();
            LogUtil.log("[CS]config update: transform profiles changed");
        }

        boolean mediaChanged = profilesChanged ||
                !oldVideo.equals(newVideo) ||
                !oldImage.equals(newImage) ||
                !oldMode.equals(newMode) ||
                (oldFpd != newFpd) ||
                !oldSourceType.equals(newSourceType) ||
                !oldStreamUrl.equals(newStreamUrl) ||
                (oldSound != newSound) ||
                (oldDisable != newDisable) ||
                (oldToast != newToast);

        if (mediaChanged) {
            // broadcast JSON already injected into memory, but invalidateConfig ensures later getConfig re-validates from file
            HookGuards.invalidateConfig();
            fireCallback();
            LogUtil.log("[CS]config update: media source/switch changed");
        } else if (oldRotation != newRotation) {
            LogUtil.log("[CS]config update: rotation " + newRotation + "°");
            callback.onRotationChanged(newRotation);
        } else {
            LogUtil.log("[CS]config update: no change");
        }
    }

    private static ConfigManager getConfig(Context context) {
        ConfigManager cm = new ConfigManager(false);
        if (context != null) cm.setContext(context);
        cm.reload();
        return cm;
    }
}