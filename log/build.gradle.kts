plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    // This is an API dependency of :client-sdk. Publish it alongside the SDK so a consumer can
    // resolve the SDK's generated POM without depending on this source tree.
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    jvm()
    android {
        namespace = "dev.warsha.remoteble.log"
        compileSdk = libs.versions.android.compile.get().toInt()
        minSdk = libs.versions.android.min.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    // Apple Silicon Macs only, matching the iOS side's lack of x64 simulator targets. The Apple
    // actuals live in appleMain, shared by iOS and macOS.
    macosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
