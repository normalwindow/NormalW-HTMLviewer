plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 说明:百度网盘开放平台凭据(AppKey/SecretKey)不内置、不注入构建产物,
// 由用户在应用内"设置-云同步-百度开放平台凭据"自行填写,避免开发者个人信息被打包泄露。

android {
    namespace = "xyz.normalwindow.htmlviewer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "xyz.normalwindow.htmlviewer"
        minSdk = 26
        targetSdk = 36
        // 发布版本(v1.2.0):与本地 debug 开发版本号序列独立,互不通用
        versionCode = 8
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 版本变体:full(默认,含 GeckoView 兼容内核)/ lite(仅系统 WebView,体积小)
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "GECKO_ENABLED", "true")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "GECKO_ENABLED", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 发布包默认使用 debug 签名,保证 clone 后可直接构建安装;
            // 正式上架请在 signingConfigs 中配置正式 keystore 并替换此引用。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    splits {
        // 按 ABI 拆分 release 包:GeckoView 内核体积大,单 ABI 包显著减小。
        // lite 版发布为单一 universal 包(构建时加 -PabiSplit=false 关闭拆分),
        // 上传 Release 时按各 ABI 命名以兼容应用内"检查更新"的资产匹配规则。
        abi {
            isEnable = project.findProperty("abiSplit") != "false"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    // Compose / Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // SAF 文件/文件夹导入
    implementation(libs.androidx.documentfile)

    // Network / Image
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    // GeckoView 兼容模式内核 + WebView 版本信息(仅 full 变体,体积约 100MB+)
    "fullImplementation"(libs.geckoview)
    implementation(libs.androidx.webkit)

    // Material3 动态配色(8 种色调方案:TonalSpot/Neutral/Vibrant 等)
    implementation(libs.material.kolor)

    testImplementation(libs.junit)
    // JVM 单测中 android.jar 的 org.json 为桩实现,补真实实现(不进 APK)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
