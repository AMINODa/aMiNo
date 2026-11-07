package com.rinigan.app;

import android.content.Context;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public class VideoManager {
    public static String video_path = "/storage/emulated/0/DCIM/Camera1/";
    public static String current_video_path = null;
    public static final String CAM_VIDEO_NAME = "virtual.mp4";
    private static final Object pathLock = new Object();
    private static Context toast_content;
    private static ConfigManager configManager;

    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mov", ".avi", ".mkv" };

    public static File[] listVideoFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext)) return true;
            }
            return false;
        });
        if (files != null && files.length > 0) {
            Arrays.sort(files);
            return files;
        }
        return null;
    }

    public static void setContext(Context context) {
        toast_content = context;
        if (configManager != null) configManager.setContext(context);
    }

    public static void setConfigManager(ConfigManager manager) {
        configManager = manager;
    }

    public static ConfigManager getConfig() {
        if (configManager == null) {
            configManager = new ConfigManager();
            if (toast_content != null) configManager.setContext(toast_content);
        }
        return configManager;
    }

    /**
     * Resolves the file for the selected_video value. Supports absolute and relative paths (relative to video_path).
     * Returns null when the file does not exist.
     */
    private static File resolveVideoFile(String selectedValue, File dir) {
        if (selectedValue == null || selectedValue.isEmpty()) return null;
        // absolute path
        if (selectedValue.startsWith("/")) {
            File f = new File(selectedValue);
            if (f.exists() && !f.isDirectory()) return f;
            return null;
        }
        // relative path
        File f = new File(dir, selectedValue);
        if (f.exists() && !f.isDirectory()) return f;
        return null;
    }

    public static void updateVideoPath(boolean forceRandom) {
        synchronized (pathLock) {
            ConfigManager config = getConfig();
            File dir = new File(video_path);

            // random play mode
            if (config.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
                if (forceRandom) pickRandomVideoToConfig(config);
                String selected = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
                if (selected != null) {
                    File f = resolveVideoFile(selected, dir);
                    if (f != null) {
                        current_video_path = f.getAbsolutePath();
                        LogUtil.log("[CS][Random] using: " + current_video_path);
                        return;
                    }
                }
            }

            // prefer the video selected in config
            String selectedName = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
            if (selectedName != null && !selectedName.isEmpty()) {
                File selectedFile = resolveVideoFile(selectedName, dir);
                if (selectedFile != null) {
                    current_video_path = selectedFile.getAbsolutePath();
                    LogUtil.log("[CS][Video] using configured path: " + current_video_path);
                    return;
                }
            }

            // fallback: virtual.mp4 → any video in the directory
            File camFile = new File(video_path, CAM_VIDEO_NAME);
            if (camFile.exists()) {
                current_video_path = camFile.getAbsolutePath();
                return;
            }
            File[] files = listVideoFiles(dir);
            if (files != null) {
                current_video_path = files[0].getAbsolutePath();
                LogUtil.log("[CS][Video] auto-selected: " + files[0].getName());
                return;
            }
            current_video_path = camFile.getAbsolutePath();
            LogUtil.log("[CS][Video] warning: no usable video file in directory");
        }
    }

    private static void pickRandomVideoToConfig(ConfigManager config) {
        File dir = new File(video_path);
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = listVideoFiles(dir);
        if (files != null && files.length > 0) {
            int index = ThreadLocalRandom.current().nextInt(files.length);
            config.setString(ConfigManager.KEY_SELECTED_VIDEO, files[index].getName());
            LogUtil.log("[CS][Random] selected: " + files[index].getName());
        }
    }

    public static String getCurrentVideoPath() {
        synchronized (pathLock) {
            if (current_video_path == null) updateVideoPath(false);
            return current_video_path;
        }
    }

    public static boolean isStreamMode() {
        ConfigManager config = getConfig();
        String type = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        return ConfigManager.MEDIA_SOURCE_STREAM.equals(type);
    }

    public static boolean hasUsableMediaSource() {
        return getCurrentMediaSource().isValid();
    }

    public static MediaSourceDescriptor getCurrentMediaSource() {
        ConfigManager config = getConfig();
        String sourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        if (ConfigManager.MEDIA_SOURCE_STREAM.equals(sourceType)) {
            String url = config.getString(ConfigManager.KEY_STREAM_URL, "");
            if (url != null && !url.isEmpty()) {
                return MediaSourceDescriptor.stream(url)
                        .autoReconnect(config.getBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, true))
                        .enableLocalFallback(config.getBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, true))
                        .transportHint(config.getString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, "auto"))
                        .timeoutMs(config.getLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, 8000L))
                        .build();
            }
            return MediaSourceDescriptor.stream("").build();
        }
        String path = getCurrentVideoPath();
        return MediaSourceDescriptor.localFile(path != null ? path : "").build();
    }

    public static boolean switchVideo(boolean next) {
        File dir = new File(video_path);
        File[] files = listVideoFiles(dir);
        if (files == null || files.length == 0) return false;

        int currentIndex = -1;
        if (current_video_path != null) {
            // check whether the current video is inside the video_path directory
            String currentParent = new File(current_video_path).getParent();
            if (currentParent != null && currentParent.equals(dir.getAbsolutePath())) {
                for (int i = 0; i < files.length; i++) {
                    if (files[i].getAbsolutePath().equals(current_video_path)) {
                        currentIndex = i;
                        break;
                    }
                }
            }
            // if the current video is external (absolute path), keep currentIndex -1 and fall back to the first directory item
        }
        int newIndex = (currentIndex == -1) ? 0
                : (next ? (currentIndex + 1) % files.length : (currentIndex - 1 + files.length) % files.length);
        current_video_path = files[newIndex].getAbsolutePath();
        getConfig().setString(ConfigManager.KEY_SELECTED_VIDEO, files[newIndex].getName());
        return true;
    }
}