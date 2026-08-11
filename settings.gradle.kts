pluginManagement {
    repositories {
        // 国内镜像优先,失败自动回退官方源
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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

// 修复:AGP 8.13 插件 classpath 携带 javapoet 1.10,而 Hilt 插件需要 javapoet>=1.13
// (ClassName.canonicalName() 签名差异导致 hiltAggregateDeps 崩溃)。
// settings 的 buildscript classpath 与插件 classpath 共享,强制升到 1.13.0。
buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先(google 组件、androidx、common 库)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // GeckoView 内核(Phase 5 引入;国内访问 maven.mozilla.org 较慢,置于镜像之后)
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "HTMLviewer"
include(":app")
