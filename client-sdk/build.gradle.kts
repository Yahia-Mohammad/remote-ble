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

    // v1 client targets are Android + iOS; JVM is kept for fast tests of the
    // session/transport layers (which are BLE-agnostic). Each target supplies its own
    // Ktor engine for the default HttpClient (see WebSocketClient.<platform>.kt).
    jvm()
    // AGP 9 KMP library DSL (replaces androidTarget {} + the top-level android {} block).
    android {
        namespace = "dev.warsha.remoteble.client"
        compileSdk = libs.versions.android.compile.get().toInt()
        minSdk = libs.versions.android.min.get().toInt()
    }
    // iosArm64 (device) + iosSimulatorArm64 (Apple Silicon simulator). No iosX64 (Intel-Mac
    // simulator): matches :protocol and the rest of the repo, and trims ~24 files per release off the
    // Central Portal monthly file-count quota (see ai-context/maven-central-publish-footprint notes).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(project(":log"))
            implementation(libs.kotlinx.coroutines.core)
            // WebSocket transport. The HttpClient engine is supplied per-platform via
            // the `defaultWebSocketHttpClient()` expect/actual.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // Kable: RemotePeripheral implements its Peripheral interface, so the
            // types are part of this module's public API (hence `api`).
            api(libs.kable.core)
            // Koin: an optional composition-root module (see client/di/ClientModule.kt).
            // `api` because remoteBleClientModule() returns a Koin `Module` that consumers
            // compose into their own startKoin {}; the library internals stay on
            // constructor injection and never reference Koin.
            api(libs.koin.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The end-to-end session tests wire the client against the agent's FakeAgent
        // over an in-memory transport. This is a test-only dependency — production
        // :client-sdk never depends on :agent. The agent is JVM-only, so these tests
        // (and the JVM HttpClient helper) live on the JVM target.
        jvmTest.dependencies {
            implementation(project(":agent"))
            // A small closing WebSocket server verifies that the SDK observes Ktor's actual
            // session close reason, rather than relying on a synthetic transport state.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            // Verifies the Koin graph resolves (ClientKoinTest); no network/radio.
            implementation(libs.koin.test)
        }
    }
}

// The rapid-session-churn harness is a *reproduction* for an unfixed intermittent defect
// (issue #12), not a regression test: when it fails it is reporting that the bug it hunts appeared,
// which is indistinguishable — to anyone reading a red build — from something in this change having
// regressed. It has its own CI job (`Rapid session churn`, dispatch/schedule only), and gating that
// job alone proved insufficient: `conformanceTest` depends on `:client-sdk:jvmTest`, so the harness
// still ran on every push and duly failed a release candidate on 2026-08-18.
//
// Opt in with `-Premoteble.churnHarness=true`, which is what that job passes.
tasks.named<Test>("jvmTest") {
    if (!providers.gradleProperty("remoteble.churnHarness").isPresent) {
        filter {
            excludeTestsMatching("*RapidSessionChurnTest")
            // The exclusion must not make an otherwise-empty filter fail the task when a caller
            // runs `--tests` for something else in this module.
            isFailOnNoMatchingTests = false
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
