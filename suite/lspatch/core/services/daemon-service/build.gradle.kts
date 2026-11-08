// aMiNo v2.x — core/services/daemon-service compiled inside the aMiNo build (was: composite-build
// module substituted as `vector:daemon-service`). Daemon IPC surface: the IVectorDaemon /
// IManagerService / IModuleService / IFrameworkService AIDLs and their parcelables, shared by the
// manager that now lives inside the single aMiNo APK. Sources verbatim from JingMatrix v1.2.
plugins {
    id("com.android.library")
}

android {
    namespace = "org.matrix.vector.daemonservice"

    buildFeatures { aidl = true }

    buildTypes { release { isMinifyEnabled = false } }

    sourceSets {
        named("main") {
            java.directories.addAll(listOf("src/main/java", "../libxposed/service/src/main"))
            aidl.directories.addAll(listOf("src/main/aidl", "../libxposed/interface/src/main/aidl"))
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.10.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    compileOnly(libs.hidden.stub)
}
