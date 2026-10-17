plugins {
    id("com.android.application")
}

android {
    namespace = "dev.layatest.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.layatest.agent"
        minSdk = 30 // getWindows forest, takeScreenshot and fitInsetsTypes are all API 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" } // Pixel 8 only; keeps the ORT native libs (and APK) lean
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }  // Native.kt
}

dependencies {
    // Phase 2: run Laya's exported ONNX on-device. Full build (not -mobile) for the ModernBERT op set.
    // 1.31: 8-bit MatMulNBits (research/18). Its AAR adds INTERNET, which the manifest removes.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    // On-device tokenizer: load the checkpoint's tokenizer.json byte-identically (D-022). 0.33.0 is the
    // only release shipping the Android arm64 .so; abiFilters above keeps just that one.
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")
}
