pluginManagement {
    repositories {
        // ===== 沙箱离线脚手架（临时，不提交）：工作区 maven 镜像置顶 =====
        maven { url = uri("/Users/weimingchen/Documents/deepseek-harness/default-workspace/dimina-work/gradle-home/m2mirror") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // ===== 沙箱离线脚手架：foojay 注掉（gradlePluginPortal 不可达；JDK17 本机就绪无需工具链下载） =====
    // id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ===== 沙箱离线脚手架（临时，不提交）=====
        maven { url = uri("/Users/weimingchen/Documents/deepseek-harness/default-workspace/dimina-work/gradle-home/m2mirror") }
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Dimina Android"
include(":app")
include(":engine_qjs")
include(":dimina")

// Optional vendor adapter: applications include :map-amap only when they choose AMap.
include(":map-amap")
