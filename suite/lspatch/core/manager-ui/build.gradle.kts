// aMiNo v2.x — core/manager-ui compiled inside the aMiNo build (was: composite-build module of
// the vendored Vector core, substituted as `vector:manager-ui`). Holds the reusable manager UI
// layer — panel header, search field, ambience, theme seed, the store HTML renderer, the shared
// OkHttp/DoH network stack — consumed by the LSPatch manager that now runs inside the single
// aMiNo APK. Sources are upstream JingMatrix v1.2 verbatim; only this build file moved onto
// aMiNo's AGP 8.11.1 / Kotlin 2.2.20 chain (library aliases keep the upstream `vector` catalog).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

android {
    namespace = "org.matrix.vector.ui"
    buildFeatures { compose = true }
}

dependencies {
    implementation(vector.gson)
    implementation(vector.okhttp)
    implementation(vector.okhttp.dnsoverhttps)
    implementation(vector.androidx.core.ktx)
    implementation(platform(vector.androidx.compose.bom))
    implementation(vector.androidx.compose.ui)
    implementation(vector.androidx.compose.ui.graphics)
    implementation(vector.androidx.compose.material3)
    implementation(vector.androidx.compose.material3.adaptive.navigation.suite)
    implementation(vector.androidx.compose.material.icons.extended)
    implementation(vector.androidx.lifecycle.viewmodel.compose)
    // NavKey and NavBackStack appear in this module's public signatures -- TopLevelDestination
    // carries a route, PanelBar takes one -- so a consumer cannot use the container without them.
    api(vector.androidx.navigation3.runtime)
    implementation(vector.androidx.activity.compose)
    implementation(vector.androidx.webkit)
}
