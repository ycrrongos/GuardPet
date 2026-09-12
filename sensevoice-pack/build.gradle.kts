plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.geekathon.guardpet.sensevoice"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geekathon.guardpet.sensevoice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Model-only APK: no sherpa-onnx runtime here.
}
