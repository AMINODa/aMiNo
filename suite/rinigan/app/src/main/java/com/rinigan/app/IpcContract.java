package com.rinigan.app;

import android.net.Uri;

public final class IpcContract {
    public static final String AUTHORITY = "com.rinigan.app.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String PATH_CONFIG = "config";
    public static final String PATH_VIDEO = "video";
    public static final String PATH_AUDIO = "audio";

    public static final Uri URI_CONFIG = Uri.withAppendedPath(CONTENT_URI, PATH_CONFIG);
    public static final Uri URI_VIDEO = Uri.withAppendedPath(CONTENT_URI, PATH_VIDEO);
    public static final Uri URI_AUDIO = Uri.withAppendedPath(CONTENT_URI, PATH_AUDIO);

    public static final String ACTION_UPDATE_CONFIG = "com.rinigan.app.ACTION_UPDATE_CONFIG";
    public static final String ACTION_REQUEST_CONFIG = "com.rinigan.app.ACTION_REQUEST_CONFIG";
    public static final String ACTION_NEXT = "com.rinigan.app.ACTION_CAMSWAP_NEXT";
    public static final String ACTION_ROTATE = "com.rinigan.app.ACTION_CAMSWAP_ROTATE";
    public static final String ACTION_EXIT = "com.rinigan.app.ACTION_CAMSWAP_EXIT";

    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_REQUESTER_PACKAGE = "requester_package";
    public static final String EXTRA_VIDEO_BUNDLE = "video_bundle";
    public static final String EXTRA_VIDEO_BINDER = "video_binder";
    public static final String EXTRA_CHANGED = "changed";

    public static final String METHOD_NEXT = "next";
    public static final String METHOD_PREV = "prev";
    public static final String METHOD_RANDOM = "random";

    // ======================== v6.3 relay / diagnostics IPC ========================

    /** Abstract-namespace unix socket carrying NV21 frames from the engine. */
    public static final String RELAY_SOCKET_NAME = "rinigan.relay";
    public static final int RELAY_MAGIC = 0x52474E33; // "RNG3"
    public static final int RELAY_VERSION = 3;

    /** Provider call(): hook side asks the app to start the engine service. */
    public static final String METHOD_ENSURE_ENGINE = "ensure_engine";
    /** Provider call(): hook side reports pipeline state for the Diagnostics card. */
    public static final String METHOD_REPORT_HOOK = "report_hook";
    /** Bundle keys for METHOD_REPORT_HOOK. */
    public static final String EXTRA_HOOK_TYPE = "hook_type";
    public static final String EXTRA_HOOK_DETAIL = "hook_detail";

    private IpcContract() {}
}