plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    // Maven Central (Central Portal) publishing. Configured entirely by the POM_* /
    // SONATYPE_HOST / RELEASE_SIGNING_ENABLED properties in gradle.properties + this
    // module's gradle.properties; auto-wires the KMP + per-target publications.
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    // JVM drives the agent + fast tests; Android/iOS are the client app targets (Phase 7).
    // All protocol code is pure commonMain (kotlinx-serialization only), so the platform
    // targets add no source — they just publish the klibs the client SDK consumes.
    jvm()
    // AGP 9 KMP library DSL (replaces androidTarget {} + the top-level android {} block).
    android {
        namespace = "dev.warsha.remoteble.protocol"
        compileSdk = libs.versions.android.compile.get().toInt()
        minSdk = libs.versions.android.min.get().toInt()
    }
    // iosArm64 (device) + iosSimulatorArm64 (Apple Silicon simulator). No iosX64 (Intel-Mac
    // simulator): the rest of the repo already omits it (:agent, :client-ui), Apple Silicon is the
    // standard dev machine, and every published KMP target costs ~24 files against the Central Portal
    // monthly *file-count* quota — dropping it here + in :client-sdk saves ~48 files per release (see
    // ai-context/maven-central-publish-footprint notes).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// iOS test binaries only link under a full Xcode toolchain, not the bare Command Line
// Tools. Run them when Xcode is active (DEVELOPER_DIR set, or `xcode-select -p` points
// inside an Xcode.app); otherwise skip so a CLT-only `./gradlew build` still succeeds
// (the klibs still compile, and the shared logic is covered by the JVM suite). Probe only
// on macOS: `xcode-select` doesn't exist on Linux/Windows CI hosts, where invoking it fails
// process *startup* (not just with a non-zero exit) — and the iOS test tasks are already
// host-disabled by Kotlin there, so there's nothing to skip.
val isMacOsHost = providers.systemProperty("os.name")
    .map { it.startsWith("Mac") }.getOrElse(false)
if (isMacOsHost) {
    val activeDeveloperDir = providers.environmentVariable("DEVELOPER_DIR").orElse(
        providers.exec {
            commandLine("xcode-select", "-p")
            isIgnoreExitValue = true
        }.standardOutput.asText.map(String::trim),
    )
    if (!activeDeveloperDir.map { it.contains("Xcode") }.getOrElse(false)) {
        tasks.matching { it.name.contains("Test") && it.name.contains("ios", ignoreCase = true) }
            .configureEach { enabled = false }
    }
}
