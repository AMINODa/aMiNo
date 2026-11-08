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

# ---------------------------------------------------------------------------
# aMiNo v2.x — the LSPatch manager merged into this APK (suite/lspatch/manager).
# Rules carried verbatim from the manager's own proguard-rules.pro (jcommander
# lines dropped: the CLI is not part of the frozen engine).
# ---------------------------------------------------------------------------

# A saved back stack names its entries by class, and the platform hands that state back to an
# activity it recreates -- including after an update, which is exactly when a shrinker will have
# renamed the class it names. Keeping the route names makes a restored stack readable by the build
# that receives it rather than only by the one that wrote it.
-keepnames class org.lsposed.lspatch.ui.navigation.** { *; }

# The navigation routes are serialised by kotlinx.serialization, which reaches a class's generated
# serializer through the companion it was compiled onto. Neither is called from anywhere the
# shrinker can see.
-keepclassmembers class **$$serializer { *** descriptor; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The Shizuku user service is loaded and instantiated by name in a separate app_process (shell uid)
# by Shizuku's starter, and its methods are reached only over a binder -- neither of which R8 can
# see, because the class is not a manifest component and nothing in-app constructs it. Without these
# it is renamed or stripped in release, and the shell service never starts: dexopt, silent installs
# and the whole log collector go dead while Shizuku still reads "granted". Keep the service and the
# generated AIDL (interface, Stub, Proxy) it dispatches through.
-keep class org.lsposed.lspatch.ShizukuService { *; }
-keep class org.lsposed.lspatch.IShizukuService** { *; }

# The framework IPC surface crosses a binder to the patched app, whose loader is not obfuscated.
-keep class org.matrix.vector.ipc.** { *; }
-keep class org.lsposed.lspatch.database.** { *; }
-keep class org.lsposed.lspatch.Patcher$Options { *; }
-keep class org.lsposed.lspatch.share.LSPConfig { *; }
-keep class org.lsposed.lspatch.share.PatchConfig { *; }

# Reflective JSON is a contract written in field names and generic signatures, and the shrinker
# honours neither for a class nothing else pins down: it renames the fields the keys are made of,
# drops the element type of a collection -- leaving a raw list that deserialises into maps -- and
# removes anything it can prove is never read. These models are persisted and read back, so their
# shape is API.
-keep class org.lsposed.lspatch.data.model.** { *; }
-keepclassmembers class org.lsposed.patch.LSPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue

# Room entities/DAOs of the merged manager: the frozen loader (and the AIDL surface) reference
# them by name across processes.
-keep class org.lsposed.lspatch.database.entity.** { *; }

# aMiNo v2.x — R8 missing-class suppressions generated for the merged manager stack
# (vendor window/sidecar extension stubs are compile-only by design; documentfile is a real
# dependency added to :lspatch-manager — the rule only silences the reflection-time probe).
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-dontwarn androidx.window.core.**
