enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenLocal() // [aMiNo suite] KSP 2.3.2 marker+jar hand-installed here (Central 429s)
        google()
        // [aMiNo suite] Central is rate-limited (429) from this build network; Gradle ABORTS
        // resolution on a 5xx instead of trying the next repo, so the mirrors must come BEFORE
        // the plugin portal / mavenCentral (the portal 303-redirects many markers to Central).
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
        }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal() // [aMiNo suite] hand-installed KSP closure lives here
        google()
        // [aMiNo suite] same mirror ladder as pluginManagement (Central 429 / aliyun 502)
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        }
    versionCatalogs {
        create("libs") {
            from(files("core/gradle/libs.versions.toml"))
        }
        create("lspatch") {
            from(files("gradle/lspatch.versions.toml"))
        }
    }
}

rootProject.name = "LSPatch"
include(
    ":apkzlib",
    ":jar",
    ":manager",
    ":meta-loader",
    ":patch",
    ":patch-loader",
    ":share:android",
    ":share:java",
)

includeBuild("core") {
    dependencySubstitution {
        substitute(module("vector:axml")).using(project(":external:axml"))
        substitute(module("vector:bridge")).using(project(":hiddenapi:bridge"))
        substitute(module("vector:xposed")).using(project(":xposed"))
        substitute(module("vector:legacy")).using(project(":legacy"))
        substitute(module("vector:daemon-service")).using(project(":services:daemon-service"))
        substitute(module("vector:stubs")).using(project(":hiddenapi:stubs"))
        substitute(module("vector:manager-ui")).using(project(":manager-ui"))
    }
}
