-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

-keepnames class moe.shizuku.api.BinderContainer

# Missing class android.app.IProcessObserver$Stub
# Missing class android.app.IUidObserver$Stub
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}

-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

# Entrance of Shizuku service
-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}

# Entrance of user service starter
-keep class moe.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}

# Entrance of shell
-keep class moe.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-assumenosideeffects class moe.shizuku.manager.utils.Logger {
    public *** d(...);
}

#noinspection ShrinkerUnresolvedReference
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}

# com.reandroid.ARSCLIB implements android/xmlpull stubs which R8 tries to strip, causing crashes on release builds
# https://github.com/REAndroid/ARSCLib/issues/95
-keep public interface android.util.AttributeSet { *; }
-keep public interface android.content.res.XmlResourceParser { *; }
-keep public interface org.xmlpull.v1.** { *; }

-allowaccessmodification
-repackageclasses rikka.shizuku
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==== aMiNo v2 — embedded LSPatch engine (:lspatch-engine, frozen v1.2-487) ====
# The frozen loader runtime (loader.dex, shipped as an asset and embedded into patched
# APKs) parses assets/lspatch/config.json with gson against the ORIGINAL field names of
# the share classes — R8 must never rename them. The engine classes are also surfaced in
# honest logs/crash traces, so keep their names whole.
-keep class org.lsposed.lspatch.share.** { *; }
-keep class org.lsposed.patch.** { *; }
-keep class com.wind.meditor.** { *; }
-keep class pxb.android.axml.** { *; }
# gson reflection surface used by the engine's PatchConfig serialization
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes *Annotation*
