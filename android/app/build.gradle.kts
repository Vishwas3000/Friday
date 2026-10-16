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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
