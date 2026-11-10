package com.amino.tiktoklikehider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Reliable config channel: patched TikTok calls call("get") at startup and receives
 * the settings JSON from the module app's prefs (even if the module app is closed).
 */
public class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            if ("get".equals(method)) {
                Context c = getContext();
                if (c == null) return null;
                String json = c.getSharedPreferences(Config.PREFS_NAME, 0)
                        .getString(Config.PREF_KEY_JSON, null);
                Bundle out = new Bundle();
                if (json == null || json.length() <= 0) {
                    json = Config.defaultJson();
                }
                out.putString("config_json", json);
                return out;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
