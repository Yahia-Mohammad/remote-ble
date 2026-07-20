plugins {
    // AGP 9 provides built-in Kotlin compilation; no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    // Needed because MainActivity calls `setContent { AgentApp(...) }` — a composable lambda —
    // even though this module defines no @Composable functions of its own. The UI itself (and
    // its compose.* dependencies) live in :agent's mobileMain source set.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

android {
    namespace = "dev.warsha.remoteble.androidagent"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "dev.warsha.remoteble.androidagent"
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.compile.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

dependencies {
    // The BLE/server logic, Compose UI, and AgentService all live in :agent (its androidTarget
    // + mobileMain source sets) — see :agent/build.gradle.kts for why this stays one module
    // rather than mirroring :client-ui's separate-UI-module split.
    implementation(project(":agent"))
    implementation(libs.kotlinx.coroutines.android)
    // ComponentActivity/setContent + the runtime-permission request contract.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
}
