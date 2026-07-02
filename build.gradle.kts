plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Declared here (apply false) so every subproject shares one AGP classpath —
    // required for AGP's version inference under the plugins DSL.
    alias(libs.plugins.android.kmp.library) apply false
    // The Android app client (:android-client). AGP 9 has built-in Kotlin support, so no
    // separate kotlin-android plugin is needed.
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "dev.warsha.ble.remoteble"
    version = "0.1.0-SNAPSHOT"
}
