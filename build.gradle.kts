plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
    // Declared here (apply false) so every subproject shares one AGP classpath —
    // required for AGP's version inference under the plugins DSL.
    alias(libs.plugins.android.kmp.library) apply false
    // The Android app client (:android-client). AGP 9 has built-in Kotlin support, so no
    // separate kotlin-android plugin is needed.
    alias(libs.plugins.android.application) apply false
}

// One merged JVM report across the published Kotlin modules. Kover automatically instruments the
// JVM tests from these projects; Android device and Kotlin/Native coverage remain hardware/platform
// evidence and are intentionally not represented by this metric.
dependencies {
    kover(project(":protocol"))
    kover(project(":log"))
    kover(project(":client-sdk"))
    kover(project(":agent"))
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Generates merged Kotlin JVM XML and HTML coverage reports."
    dependsOn("koverXmlReport", "koverHtmlReport")
}

// Coordinates come from the tracked root gradle.properties (GROUP / VERSION_NAME) — the same
// keys the vanniktech maven-publish plugin reads, so the published POM and the Gradle project
// stay in lockstep. Bump VERSION_NAME to cut a release.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// The release conformance matrix spans the Kotlin reference agent, the Kotlin client, and the
// Android-host runner. Keep it as a named entry point so CI can run the matrix intentionally
// instead of treating incidental coverage from a broad `build` as release evidence. Rust runs its
// matching adapters in the `conformance` CI job because it has a separate build system.
tasks.register("conformanceTest") {
    group = "verification"
    description = "Runs Kotlin's 0.9.1/0.10.0 release-conformance adapters."
    dependsOn(
        ":agent:jvmTest",
        ":agent:testAndroidHostTest",
        ":client-sdk:jvmTest",
    )
}
