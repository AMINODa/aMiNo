package com.rinigan.app;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.rinigan.app.BuildConfig;

public class HookMain implements IXposedHookLoadPackage {

    private static volatile boolean configWatcherInitialized = false;

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {

        // ========== callApplicationOnCreate: init Context, permission check, directory migration ==========
        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (param.args[0] instanceof Application) {
                    try {
                        SharedState.toast_content = ((Application) param.args[0]).getApplicationContext();
                    } catch (Exception ee) {
                        XposedBridge.log("[Rinigan]" + ee.toString());
                    }

                    File force_private = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                            "/DCIM/Camera1/private_dir.jpg");
                    if (SharedState.toast_content != null) {
                        int auth_statue = 0;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                auth_statue += (SharedState.toast_content.checkSelfPermission(
                                        Manifest.permission.READ_EXTERNAL_STORAGE) + 1);
                            } catch (Exception ee) {
                                XposedBridge.log("[Rinigan][permission-check]" + ee.toString());
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    auth_statue += (SharedState.toast_content.checkSelfPermission(
                                            Manifest.permission.MANAGE_EXTERNAL_STORAGE) + 1);
                                }
                            } catch (Exception ee) {
                                XposedBridge.log("[Rinigan][permission-check]" + ee.toString());
                            }
                        } else {
                            if (SharedState.toast_content.checkCallingPermission(
                                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                auth_statue = 2;
                            }
                        }

                        // Pitfall 6: on Android 11+ (API 30+) with scoped storage,
                        // checkSelfPermission(READ_EXTERNAL_STORAGE) always returns denied (-1)
                        // checkSelfPermission(MANAGE_EXTERNAL_STORAGE) also returns denied
                        // auth_statue is always 0, causing wrong redirection to the private directory
                        // fix: on Android 11+ use the private directory only when the force_private marker file exists
                        boolean needPrivateDir = force_private.exists();
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            needPrivateDir = needPrivateDir || (auth_statue < 1);
                        }
                        if (needPrivateDir) {
                            File shown_file = new File(
                                    SharedState.toast_content.getExternalFilesDir(null).getAbsolutePath() +
                                    "/Camera1/");
                            if ((!shown_file.isDirectory()) && shown_file.exists()) {
                                shown_file.delete();
                            }
                            if (!shown_file.exists()) {
                                shown_file.mkdir();
                            }
                            shown_file = new File(
                                    SharedState.toast_content.getExternalFilesDir(null).getAbsolutePath() +
                                    "/Camera1/has_shown");
                            File toast_force_file = new File(
                                    Environment.getExternalStorageDirectory().getPath() +
                                    "/DCIM/Camera1/force_show.jpg");
                            if ((!lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) &&
                                    ((!shown_file.exists()) || toast_force_file.exists())) {
                                try {
                                    Toast.makeText(SharedState.toast_content,
                                            lpparam.packageName +
                                            "Storage permission not granted, please check permissions\nCamera1 is currently redirected to " +
                                            SharedState.toast_content.getExternalFilesDir(null).getAbsolutePath() +
                                            "/Camera1/", Toast.LENGTH_SHORT).show();
                                    FileOutputStream fos = new FileOutputStream(
                                            SharedState.toast_content.getExternalFilesDir(null).getAbsolutePath() +
                                            "/Camera1/has_shown");
                                    String info = "shown";
                                    fos.write(info.getBytes());
                                    fos.flush();
                                    fos.close();
                                } catch (Exception e) {
                                    XposedBridge.log("[Rinigan][switch-dir]" + e.toString());
                                }
                            }
                            SharedState.video_path = SharedState.toast_content.getExternalFilesDir(null)
                                    .getAbsolutePath() + "/Camera1/";
                        } else {
                            SharedState.video_path = Environment.getExternalStorageDirectory().getPath() +
                                    "/DCIM/Camera1/";
                        }
                    } else {
                        SharedState.video_path = Environment.getExternalStorageDirectory().getPath() +
                                "/DCIM/Camera1/";
                        File uni_DCIM_path = new File(Environment.getExternalStorageDirectory().getPath() +
                                "/DCIM/Camera1/");
                        if (uni_DCIM_path.canWrite()) {
                            File uni_Camera1_path = new File(SharedState.video_path);
                            if (!uni_Camera1_path.exists()) {
                                uni_Camera1_path.mkdir();
                            }
                        }
                    }
                }
            }
        });

        // ========== Delegate Camera1 handler ==========
        Camera1Handler.init(lpparam);

        // ========== Delegate Camera2 handler ==========
        Camera2Handler.init(lpparam);

        // ========== v6.1: allow http:// live links in every hooked app ==========
        // Android 9+ blocks cleartext (http) traffic unless the APP's manifest opts in.
        // A virtual-camera module cannot edit the host app's manifest, so we hook the
        // policy check. Without this, most non-https video links fail instantly and
        // the user sees a black screen instead of the live link.
        if (!lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) {
            try {
                XC_MethodHook allowCleartext = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                };
                XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy",
                        lpparam.classLoader, "isCleartextTrafficPermitted", allowCleartext);
                XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy",
                        lpparam.classLoader, "isCleartextTrafficPermitted", String.class, allowCleartext);
                XposedBridge.log("[Rinigan]cleartext policy hooked (live links may use http://)");
            } catch (Throwable t) {
                XposedBridge.log("[Rinigan]cleartext hook failed (non-fatal): " + t);
            }
        }

        // ========== Delegate microphone handler ==========
        try {
            MicrophoneHandler.init(lpparam);
        } catch (Throwable e) {
            XposedBridge.log("[Rinigan]MicrophoneHandler init failed: " + e);
        }

        // ========== Config change listening: broadcast + file watching → hot-swap video/sound ==========
        if (!lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) {
            try {
                initConfigWatcher(lpparam);
            } catch (Exception e) {
                XposedBridge.log("[Rinigan]ConfigWatcher init failed: " + e);
            }
        }
    }

    private void initConfigWatcher(final XC_LoadPackage.LoadPackageParam lpparam) {
        // no global static flag anymore so that every process initializes
        // findAndHookMethod is idempotent; multiple calls are harmless

        // initialize after Application onCreate completes (needs a valid Context)
        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (!(param.thisObject instanceof Application)) return;
                final Application app = (Application) param.thisObject;
                if (app.getApplicationContext() == null) return;

                new ConfigWatcher(new ConfigWatcher.Callback() {
                    @Override
                    public void onMediaSourceChanged() {
    XposedBridge.log("[Rinigan]config listener → media source changed, triggering hot-swap");
    try {
        if (com.rinigan.app.HookGuards.isDisabled()) {
            XposedBridge.log("[Rinigan]module disabled, stopping all players");
            com.rinigan.app.Camera1Handler.stopAllPlayers();
            com.rinigan.app.Camera2SessionHook.stopAllPlayers();
            return;
        }
    } catch (Exception e) {
        XposedBridge.log("[Rinigan]disable-check exception: " + e);
    }
    try {
        Camera1Handler.reloadVideo();
    } catch (Exception e) {
        XposedBridge.log("[Rinigan]Camera1 hot-swap exception: " + e);
    }
    try {
        Camera2SessionHook.reloadVideo();
    } catch (Exception e) {
        XposedBridge.log("[Rinigan]Camera2 hot-swap exception: " + e);
    }
}

                    @Override
                    public void onRotationChanged(int degrees) {
                        XposedBridge.log("[Rinigan]config listener → rotation changed: " + degrees + "°");
                    }
                }).init(app.getApplicationContext());
            }
        });
    }
}