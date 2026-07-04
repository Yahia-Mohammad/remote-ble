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

// Coordinates come from the tracked root gradle.properties (GROUP / VERSION_NAME) — the same
// keys the vanniktech maven-publish plugin reads, so the published POM and the Gradle project
// stay in lockstep. Bump VERSION_NAME to cut a release.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}
