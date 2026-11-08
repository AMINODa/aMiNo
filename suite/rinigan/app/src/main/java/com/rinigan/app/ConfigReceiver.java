package com.rinigan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (IpcContract.ACTION_REQUEST_CONFIG.equals(intent.getAction())) {
            String requesterPackage = intent.getStringExtra(IpcContract.EXTRA_REQUESTER_PACKAGE);
            if (requesterPackage == null || requesterPackage.isEmpty()) {
                LogUtil.log("[CS-Host]config request missing requester_package, ignored");
                return;
            }
            LogUtil.log("[CS-Host]config request received, sending current config");

            ConfigManager cm = new ConfigManager();
            cm.setContext(context);

            try {
                context.getPackageManager().getPackageInfo(requesterPackage, 0);
            } catch (Exception e) {
                LogUtil.log("[CS-Host]config request source package does not exist: " + requesterPackage);
                return;
            }

            java.util.Set<String> targetPackages = cm.getTargetPackages();
            if (!requesterPackage.equals(context.getPackageName())
                    && !targetPackages.isEmpty()
                    && !targetPackages.contains(requesterPackage)) {
                LogUtil.log("[CS-Host]refused to send config to unauthorized package: " + requesterPackage);
                return;
            }
            cm.sendConfigBroadcast(context, requesterPackage);
        }
    }
}