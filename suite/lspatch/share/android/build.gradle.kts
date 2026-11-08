// aMiNo v2.x — share/android compiled inside the aMiNo build (upstream LSPatch module
// :share:android, substituted as a plain project dependency of the manager). Remote service
// contracts (LSPatchModuleService / LSPatchXposedService / preference + file stores / scope
// source) plus the module loader helpers, consumed by both the patch-loader (frozen dex inside
// patched apps) and the manager that now lives inside the single aMiNo APK. Sources verbatim.
plugins {
    id("com.android.library")
}

android {
    namespace = "org.lsposed.lspatch.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation(project(":lspatch-daemon-service"))
}
