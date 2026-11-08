enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven("https://maven.aliyun.com/repository/public") // [aMiNo suite] Central is rate-limited here — try the mirror first
        mavenCentral()
        }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public") // [aMiNo suite] Central is rate-limited here — try the mirror first
        mavenCentral()
        }
}

rootProject.name = "Vector"

include(
    ":daemon",
    ":dex2oat",
    ":external:axml",
    ":external:apache",
    ":hiddenapi:stubs",
    ":hiddenapi:bridge",
    ":legacy",
    ":manager",
    ":manager-ui",
    ":services:manager-service",
    ":services:daemon-service",
    ":xposed",
    ":zygisk",
)
