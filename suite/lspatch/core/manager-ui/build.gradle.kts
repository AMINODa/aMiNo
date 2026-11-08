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
    // aMiNo v2.x: the androidx pins come from the lspatch catalog (the manager's own line —
    // BOM 2026.06.01 / core-ktx 1.16 / lifecycle-viewmodel-compose 2.9.2). The vector catalog's
    // newer pins (BOM 2026.08.00 / core 1.19 / lifecycle 2.11) carry AAR metadata demanding
    // AGP 9.1+, which aMiNo's 8.11.1 build refuses; the root build.gradle additionally forces
    // material3 to 1.5.0-alpha17 (the newest line still allowing AGP 8.x).
    implementation(lspatch.androidx.core.ktx)
    implementation(platform(lspatch.androidx.compose.bom))
    implementation(lspatch.androidx.compose.ui)
    implementation("androidx.compose.ui:ui-graphics") // BOM-governed; the lspatch catalog has no alias for it
    implementation(lspatch.androidx.compose.material3)
    implementation(lspatch.androidx.compose.material3.adaptive.navigation.suite)
    implementation(lspatch.androidx.compose.material.icons.extended)
    implementation(lspatch.androidx.lifecycle.viewmodel.compose)
    // NavKey and NavBackStack appear in this module's public signatures -- TopLevelDestination
    // carries a route, PanelBar takes one -- so a consumer cannot use the container without them.
    api(lspatch.androidx.navigation3.runtime)
    implementation(lspatch.androidx.activity.compose)
    implementation(vector.androidx.webkit)
}
