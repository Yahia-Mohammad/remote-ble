plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    // A JVM runnable (like :agent) that drives a *live* agent over WebSocket. Uses the Kable
    // Peripheral surface so it doubles as proof that app code written against Kable runs
    // unchanged against a remote agent. Run with `:e2e-runner:jvmRun`.
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("dev.warsha.remoteble.e2e.MainKt")
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":client-sdk"))
            implementation(libs.kotlinx.coroutines.core)
            // For the HttpClient type returned by defaultWebSocketHttpClient() (the engine,
            // CIO, comes transitively from :client-sdk's runtime classpath).
            implementation(libs.ktor.client.core)
        }
    }
}

// Scan-only client (ScanMain.kt): connects to a running agent and lists every BLE
// advertisement its radio sees, then exits. The client has no radio of its own — it
// only sends ops over WebSocket — so it runs as a plain JVM (no .app/TCC dance needed).
//   ./gradlew :e2e-runner:scanRun --args "ws://localhost:8080/agent 15"
tasks.register<JavaExec>("scanRun") {
    group = "application"
    description = "Run the scan-only client against a live agent."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.ScanMainKt")
}

// Connected-RSSI (F2) live check (RssiMain.kt): connect to a peripheral and print the connected
// link RSSI once a second. Needs an agent with the `rssi` capability (Kable Android/Apple backend);
// the JVM/btleplug + agent-rs backends don't advertise it, so rssi() there fails fast.
//   ./gradlew :e2e-runner:rssiRun --args "ws://localhost:8080/agent \"Warsha HRM\" <token> 120"
tasks.register<JavaExec>("rssiRun") {
    group = "application"
    description = "Read connected-link RSSI from a peripheral via a live agent (F2)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.RssiMainKt")
}

// Connection-parameters (B / conn.params) live check (ConnParamsMain.kt): connect to a peripheral and
// request each ConnProfile, printing whether the agent honored it. An Android agent advertises
// `conn.params` and accepts all three profiles; iOS/JVM agents don't advertise it and answer
// UNSUPPORTED — the driver prints a PASS/MIXED verdict for whichever it is talking to.
//   ./gradlew :e2e-runner:connParamsRun --args "ws://localhost:8080/agent \"Warsha HRM\" <token>"
tasks.register<JavaExec>("connParamsRun") {
    group = "application"
    description = "Request each connection-parameter profile from a peripheral via a live agent (B)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.ConnParamsMainKt")
}

// Write-without-response throughput baseline (0.8.3 / C, ThroughputMain.kt): drives a serial burst
// of MTU-sized WithoutResponse writes against the TestProfile peripheral and reports bytes/s plus
// the per-write latency distribution — the number the coalescing design in
// ai-context/0.8.3-implementation-plan.md is measured against.
//   ./gradlew :e2e-runner:throughputRun --args "ws://localhost:8080/agent <token> 200"
tasks.register<JavaExec>("throughputRun") {
    group = "application"
    description = "Measure write-without-response throughput/latency baseline against a live agent (0.8.3 / C)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.ThroughputMainKt")
}
