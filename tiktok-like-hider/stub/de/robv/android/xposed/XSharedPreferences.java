package de.robv.android.xposed;

import java.io.File;

/** Compile-only stub (excluded from dex). */
public final class XSharedPreferences {
    public XSharedPreferences(String packageName) {
    }

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public boolean hasFile() {
        return false;
    }

    public void reload() {
    }

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    public int getInt(String key, int defValue) {
        return defValue;
    }

    public String getString(String key, String defValue) {
        return defValue;
    }
}
