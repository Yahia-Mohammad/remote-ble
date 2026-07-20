import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

// Benign KLIB "same unique_name found in more than one library" warnings (savedstate /
// lifecycle-common / lifecycle-runtime) come from *inside* Compose Multiplatform 1.11.1: its
// `org.jetbrains.compose.ui:ui` transitively pulls both Google's `androidx.savedstate` /
// `androidx.lifecycle` KMP artifacts and JetBrains' `org.jetbrains.androidx.*` fork during CMP's
// upstream migration. This module declares no lifecycle/savedstate dependency of its own, so there
// is no clean project-side fix; forcing an exclusion of either side risks breaking Compose. The
// build is green and this module ships no published artifact — the warnings resolve when CMP
// finishes migrating to Google's KMP androidx. Do not chase them here.

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    // The whole UI ([RemoteBleApp]) and orchestration logic ([RemoteBleController]) for the
    // RemoteBLE central demo, shared between the thin :android-client app and the ios-client
    // launcher shell. Split out from :android-client itself because AGP 9's new DSL doesn't let
    // a `com.android.application` module also declare `androidTarget()` (KMP + application must
    // live in separate subprojects — see https://kotl.in/gradle/agp-new-kmp); this mirrors
    // :client-sdk's own androidLibrary + iOS split.
    android {
        // Distinct from :android-client's own namespace (dev.warsha.remoteble.androidclient) —
        // AGP requires every module/library's namespace to be unique, even though this library's
        // Kotlin sources keep the `dev.warsha.remoteble.androidclient` package for continuity.
        namespace = "dev.warsha.remoteble.androidclient.ui"
        compileSdk = libs.versions.android.compile.get().toInt()
        minSdk = libs.versions.android.min.get().toInt()
        // Runs commonTest (the pure GATT/hex/model unit tests) on the JVM via the Android Unit
        // Test runner — this module has no jvm() target of its own.
        withHostTest {}
    }
    // No iosX64() (Intel Simulator): Compose Multiplatform 1.11.1 doesn't publish for it (Apple
    // dropped Intel Simulator runtimes; :client-sdk/:protocol still declare it since they don't
    // depend on compose.*, but this UI module can't). Apple Silicon Macs cover iosArm64 (device)
    // + iosSimulatorArm64 (simulator) either way.
    val appleTargets = listOf(iosArm64(), iosSimulatorArm64())

    // Opt-in Obj-C/Swift framework export for the ios-client launcher shell. Off by default so a
    // plain CLT-only `./gradlew build` stays green (framework *linking* needs a full Xcode
    // toolchain). On a Mac with Xcode:
    //   ./gradlew :client-ui:assembleRemoteBleClientReleaseXCFramework -PiosFramework
    if (providers.gradleProperty("iosFramework").isPresent) {
        val xcframework = XCFramework("RemoteBleClient")
        appleTargets.forEach { target ->
            target.binaries.framework {
                baseName = "RemoteBleClient"
                xcframework.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The whole point: the app talks to a remote agent through the central SDK.
            api(project(":client-sdk"))
            implementation(libs.kotlinx.coroutines.core)
            // To name the HttpClient returned by defaultWebSocketHttpClient().
            implementation(libs.ktor.client.core)

            // Compose Multiplatform UI — same androidx.compose.* package names as Jetpack
            // Compose, so the moved-in Composables need no import changes. `api` so
            // :android-client's MainActivity (which calls setContent { RemoteBleApp(...) })
            // doesn't need to redeclare these.
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        commonTest.dependencies {
            // Unit tests for the pure GATT/hex/model core.
            implementation(kotlin("test"))
        }
    }
}

// Building/running iOS binaries needs a full Xcode toolchain; the klibs still compile under
// CLT-only environments. Mirrors :client-sdk.
tasks.matching { it.name.contains("Test") && it.name.contains("ios", ignoreCase = true) }
    .configureEach { enabled = false }
