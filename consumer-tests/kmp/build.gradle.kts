plugins {
    kotlin("multiplatform") version "2.4.10"
}

val remoteBleVersion = providers.gradleProperty("remoteBleVersion").orNull
    ?: error("Pass the published SDK version with -PremoteBleVersion=<version>.")

// The Apple variants of a KMP publication resolve through klib metadata and a per-target `*-iosarm64`
// / `*-iossimulatorarm64` module, none of which the JVM consumer fixture touches. A closure that is
// complete for `jvm` can still be broken here — which is exactly the failure the JVM gate caught once
// already, when `:log` was missing from the published set.
kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                // Coordinates only, never a project dependency: this must resolve the way a
                // downstream KMP consumer resolves it.
                implementation("dev.warsha.remoteble:client-sdk:$remoteBleVersion")
            }
        }
    }
}
