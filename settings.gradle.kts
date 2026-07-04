rootProject.name = "remote-ble"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(
    ":protocol",
    ":client-sdk",
    ":agent",
    // Live E2E runner: drives the client SDK against a running :agent over WebSocket, exercising
    // the full op set against a test peripheral. Run with `:e2e-runner:jvmRun` (needs hardware).
    ":e2e-runner",
    // The RemoteBLE central demo's UI + orchestration logic (Compose Multiplatform: Android +
    // iOS), consumed by :android-client and by the ios-client launcher shell. Split out from
    // :android-client because AGP 9's new DSL forbids `com.android.application` + `androidTarget()`
    // in the same module — see client-ui/build.gradle.kts.
    ":client-ui",
    // Android emulator client: a phone-side app that scans through the host agent over
    // ws://10.0.2.2:8080/agent (the emulator's alias for the host loopback). No local radio. Thin
    // wrapper around :client-ui.
    ":android-client",
    // On-device agent: a phone-side app hosting :agent's real BLE-central + WebSocket server
    // directly on the phone's own radio. Thin wrapper around :agent's androidTarget.
    ":android-agent",
)
