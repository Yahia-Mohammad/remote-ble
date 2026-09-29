plugins {
    // AGP 9 has built-in Kotlin support; the separate org.jetbrains.kotlin.android plugin is gone
    // (applying it is a hard error). But AGP's *bundled* Kotlin is 2.2.0, which can only read
    // metadata up to 2.3.0 — and this SDK publishes 2.4.0 metadata. Declaring KGP here with
    // `apply false` puts 2.4.x on the build classpath so AGP's built-in compilation uses it, the
    // same mechanism the root build relies on for :android-client. A downstream Android consumer
    // on AGP 9 must do this too; without it the SDK simply will not compile.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("com.android.library") version "9.3.0"
}

val remoteBleVersion = providers.gradleProperty("remoteBleVersion").orNull
    ?: error("Pass the published SDK version with -PremoteBleVersion=<version>.")

// The Android variant of a KMP publication resolves through a separate `*-android` module and an
// `.aar`, selected by Gradle metadata attributes the JVM consumer never exercises. A closure that is
// complete for `jvm` can still be broken here, so this is a distinct gate rather than a duplicate.
// compileSdk sits one below the SDK's own on purpose: a consumer must not be forced up to whatever
// the SDK happens to compile against. 36 is the floor the SDK's AAR metadata declares (Kable's
// androidx.core requires it anyway); the gate runs `checkDebugAarMetadata` to hold it there.
android {
    namespace = "dev.warsha.remoteble.consumer.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    // Coordinates only, never a project dependency.
    implementation("dev.warsha.remoteble:client-sdk:$remoteBleVersion")
}
