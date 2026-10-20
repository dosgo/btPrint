import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.dosgo.btPrint"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dosgo.btPrint"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MuPDF 自带 4 个 ABI 的 so（共约 39MB），这台机器是 arm64，只打这一个
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // MuPDF：字体回退完整的渲染引擎（WPS 同类），用于面单这类不嵌字体的 PDF。
    // Artifex 官方仓到本机只有 19KB/s，AAR 已放到 app/libs 本地引用，避免每次联网拉 21MB。
    implementation(files("libs/fitz-1.28.5.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
