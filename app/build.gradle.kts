import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.geekathon.guardpet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geekathon.guardpet"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // sherpa-onnx AAR ships four ABIs (~120MB); phone is arm64
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":reef"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("com.huaban:jieba-analysis:1.0.2")
    // Offline SenseVoice ASR (AAR embeds onnxruntime + jni). Fetch via scripts/fetch-sensevoice-pack.sh
    implementation(files("${rootProject.projectDir}/libs/sherpa-onnx-1.13.8.aar"))
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.material3:material3:1.5.0-alpha20")
    }
}
