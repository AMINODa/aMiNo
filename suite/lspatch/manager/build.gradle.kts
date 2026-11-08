import java.util.Locale

// aMiNo v2.x — LSPatch manager as an ANDROID LIBRARY inside the single aMiNo APK.
//
// Upstream (JingMatrix v1.2) this was a com.android.application built by AGP 9.3 with its
// built-in Kotlin 2.4.10, producing the standalone "LSPatch" manager app. The user's order for
// aMiNo (verbatim): «لا اريد ضهور الايقونات منفصلة عن amino ... اريدهم مدموجيم في تطبيق امينو
// تثبت عند تثبيت amino بدون اتلاف الادوار او الواجهات» — no separate icons, everything merged
// into the one aMiNo app, roles and interfaces untouched. So this module is now a library on
// aMiNo's own chain (AGP 8.11.1 / Kotlin 2.2.20); every SCREEN, SETTING and OPTION of the
// original manager UI is compiled unmodified, and the only behavioural change stays the colour
// pass already applied to Theme.kt/Color.kt (aMiNo palette). Components declared in the
// vendored AndroidManifest.xml (MainActivity, ModuleService, XposedPullService,
// ManagerResidentService, BootReceiver, the patched-apk FileProvider) merge into aMiNo's
// manifest; the LAUNCHER intent-filter is gone so no second icon ever appears.
//
// Manager-mode bindings keep working for freshly patched apps without touching the frozen
// loader: Patcher.kt writes lspApp.packageName (= com.amino.manager, aMiNo's own package) into
// each patch's PatchConfig.managerPackageName, and the loader's RemoteApplicationService
// resolves that field before falling back to Constants.MANAGER_PACKAGE_NAME.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "org.lsposed.lspatch"

    defaultConfig {
        // The languages the picker offers, listed from the resource folders that carry our own
        // strings.xml -- so one appears the moment a translator's folder lands, and none is offered
        // for a language nothing is translated into. Deliberately not AssetManager.getLocales(),
        // which reports every locale any dependency ships a resource for, pseudo-locales included.
        // (Upstream logic verbatim — the picker is part of the original UI.)
        val translations =
            (listOf("en") +
                    file("src/main/res")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("values-") }
                        .filter { File(it, "strings.xml").exists() }
                        .map { it.name.removePrefix("values-").replace("-r", "-") })
                .sorted()
        buildConfigField("String", "TRANSLATIONS", "\"${translations.joinToString(",")}\"")
    }

    buildTypes {
        release {
            // The single APK is minified by the aMiNo :manager app module; the library hands its
            // shrink contract to R8 through consumer rules instead of minifying itself.
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // The frozen v1.2 patching engine aMiNo already embeds (:patch + :apkzlib + share/java with
    // the official LSPConfig + the vector axml editor). The manager drives THE SAME engine the
    // in-app quick-patch screen uses — one copy of every class in the final APK.
    api(project(":lspatch-engine"))
    implementation(project(":lspatch-share-android"))
    implementation(project(":lspatch-daemon-service"))
    implementation(project(":lspatch-manager-ui"))
    // ShizukuApi binds aMiNo's own privileged-server AIDL (moe.shizuku.server.IShizukuService).
    implementation(project(":aidl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform(lspatch.androidx.compose.bom))
    compileOnly(lspatch.rikka.hidden.stub)
    implementation(lspatch.androidx.activity.compose)
    implementation(lspatch.androidx.compose.material.icons.extended)
    implementation(lspatch.androidx.compose.material3)
    implementation(lspatch.androidx.compose.material3.adaptive.navigation.suite)
    implementation(lspatch.androidx.compose.ui)
    implementation(lspatch.androidx.compose.ui.tooling.preview)
    implementation(lspatch.androidx.core.ktx)
    implementation(lspatch.androidx.lifecycle.viewmodel.compose)
    implementation(lspatch.androidx.navigation3.runtime)
    implementation(lspatch.androidx.navigation3.ui)
    implementation(lspatch.androidx.lifecycle.viewmodel.navigation3)
    implementation(lspatch.androidx.preference)
    implementation(lspatch.androidx.room.ktx)
    implementation(lspatch.androidx.room.runtime)
    implementation(lspatch.google.accompanist.pager)
    implementation(lspatch.google.accompanist.swiperefresh)
    implementation(lspatch.material)
    implementation(vector.gson)
    implementation(vector.okhttp)
    implementation(vector.okhttp.dnsoverhttps)
    implementation(lspatch.rikka.shizuku.api)
    implementation(lspatch.rikka.shizuku.provider)
    implementation(lspatch.rikka.refine)
    implementation(lspatch.hiddenapibypass)
    ksp(lspatch.androidx.room.compiler)
}
