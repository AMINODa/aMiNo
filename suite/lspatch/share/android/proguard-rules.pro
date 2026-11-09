# aMiNo v2.x — consumer rules for share:android inside the single APK. The remote
# service contracts (LSPatchModuleService / LSPatchXposedService and the
# preference + file stores under org.lsposed.lspatch.share.remote) are bound BY
# NAME from the frozen loader dex inside patched apps (byte-exact upstream
# loader), so every class and parcelable here must keep its upstream name.
-keep class org.lsposed.lspatch.share.remote.** { *; }
