plugins {
    // AGP 9 provides built-in Kotlin compilation; no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    // Needed because MainActivity calls `setContent { RemoteBleApp(...) }` — a composable
    // lambda — even though this module defines no @Composable functions of its own. The UI
    // itself (and its compose.* dependencies) live in :client-ui.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

android {
    namespace = "dev.warsha.ble.remoteble.androidclient"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "dev.warsha.ble.remoteble.androidclient"
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
    // The whole UI and orchestration logic — see :client-ui/build.gradle.kts for why this is a
    // separate module rather than living directly in this app (AGP 9's new DSL forbids
    // `com.android.application` + `androidTarget()` in the same module).
    implementation(project(":client-ui"))
    // Android Main dispatcher for the UI coroutine scope.
    implementation(libs.kotlinx.coroutines.android)
    // ComponentActivity/setContent + the rotation-surviving ViewModel wrapper around
    // RemoteBleController.
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
}
