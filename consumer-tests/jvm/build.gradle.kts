plugins {
    kotlin("jvm") version "2.4.0"
}

val remoteBleVersion = providers.gradleProperty("remoteBleVersion").orNull
    ?: error("Pass the published SDK version with -PremoteBleVersion=<version>.")

dependencies {
    // Deliberately resolve only coordinates, never project dependencies: this fixture verifies the
    // published POM and its transitive protocol dependency as a downstream consumer sees them.
    implementation("dev.warsha.remoteble:client-sdk:$remoteBleVersion")
}

kotlin {
    jvmToolchain(17)
}
