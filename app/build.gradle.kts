plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsha.gptrelay"
    compileSdk = 35
    // 显式指定：Google 没有 linux-aarch64 build-tools，
    // 我们只装了这一份并已用 qemu 包装，不能让 AGP 再去下 34.0.0。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.dsha.gptrelay"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-beta"
        ndk {
            // 本机是 arm64（proot 用的是 lib/arm64），只打包这一种架构
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 先用 debug 签名，保证能直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        // 关键：核心二进制伪装成 libsingbox.so 放在 jniLibs，
        // 必须让系统把它解压成真实文件，才能拿到可执行的 nativeLibraryDir 路径
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // 网页模式：把 WebView 出网指向本机 sing-box 代理
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
