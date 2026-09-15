import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.geekathon.guardpet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geekathon.guardpet"
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "1.0.11"
        ndk {
            // sherpa-onnx AAR ships four ABIs (~120MB); phone is arm64
            abiFilters += listOf("arm64-v8a")
        }
        val deepseekKey = localProps.getProperty("deepseek.api.key", "")
        val openaiKey = localProps.getProperty("openai.api.key", "")
        val openaiBase = localProps.getProperty("openai.base.url", "https://api.openai.com/v1")
        val dashscopeKey = localProps.getProperty("dashscope.api.key", "")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${escapeBuildConfig(deepseekKey)}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${escapeBuildConfig(openaiKey)}\"")
        buildConfigField("String", "OPENAI_BASE_URL", "\"${escapeBuildConfig(openaiBase)}\"")
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${escapeBuildConfig(dashscopeKey)}\"")
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.activity)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    // Embedded MIT calendar: https://github.com/kizitonwose/Calendar
    implementation("com.kizitonwose.calendar:view:2.10.1")
    implementation("com.kizitonwose.calendar:compose:2.10.1")
    implementation("com.huaban:jieba-analysis:1.0.2")
    // Offline SenseVoice ASR (AAR embeds onnxruntime + jni). Fetch via scripts/fetch-sensevoice-pack.sh
    implementation(files("${rootProject.projectDir}/libs/sherpa-onnx-1.13.8.aar"))
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.material3:material3:1.5.0-alpha20")
    }
}
