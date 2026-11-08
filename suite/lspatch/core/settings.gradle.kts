enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenLocal() // [aMiNo suite] hand-installed KSP closure lives here
        // [aMiNo suite] Central 429s from this network and Gradle ABORTS on 5xx — mirrors BEFORE
        // the portal/mavenCentral (the portal 303-redirects many markers to Central).
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        google()
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
        }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenLocal() // [aMiNo suite] hand-installed KSP closure lives here
        google()
        // [aMiNo suite] same mirror ladder as pluginManagement (Central 429 / aliyun 502)
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
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
