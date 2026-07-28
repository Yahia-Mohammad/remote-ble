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

// The generated jvmRun task doesn't forward the launching process's stdin by default, but
// Main.kt's operator prompts (readlnOrNull) need it — wire it explicitly so the live E2E runner
// is drivable from any invocation, not only an interactive terminal Gradle happens to inherit
// stdin from.
tasks.withType<JavaExec>().configureEach {
    if (name == "jvmRun") standardInput = System.`in`
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

// TLS reverse-proxy rig (TLS-PROXY-01 cases 4 + 5, TlsProxyMain.kt): drives a sustained notification
// stream and a lease-resuming reconnect through a TLS-terminating proxy, reporting per-notification
// inter-arrival gaps (a proxy that buffers or stalls long-lived frames shows up as a gap spike).
// `-PtrustStore` points the JVM at a truststore holding the proxy's CA, so certificate validation
// stays ON — never disable it, or case 3 of the same scenario passes without proving anything.
// Full recipe: docs/tls-proxy-recipe.md
//   ./gradlew :e2e-runner:tlsProxyRun -PtrustStore=/path/truststore.p12 -PtrustStorePassword=changeit \
//     --args "wss://localhost:8443/agent \"Warsha HRM (sim)\" 30"
tasks.register<JavaExec>("tlsProxyRun") {
    group = "application"
    description = "Drive notifications + reconnect through a TLS reverse proxy (TLS-PROXY-01 cases 4/5)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.TlsProxyMainKt")
    providers.gradleProperty("trustStore").orNull?.let {
        systemProperty("javax.net.ssl.trustStore", it)
        systemProperty("javax.net.ssl.trustStoreType", providers.gradleProperty("trustStoreType").getOrElse("PKCS12"))
    }
    providers.gradleProperty("trustStorePassword").orNull?.let {
        systemProperty("javax.net.ssl.trustStorePassword", it)
    }
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

// Rig A case 7 (HealthMain.kt, pr8-validation-plan.md): reads Battery Level (0x180F/0x2A19) and
// Device Information (0x180A/0x2A29+0x2A24) through a live agent against the health-peripheral app
// (../ble-peripheral), confirming a live (not frozen/cached) read by prompting a value change
// mid-run. Needs stdin, same as jvmRun above.
//   ./gradlew :e2e-runner:healthRun --args "ws://localhost:8080/agent"
tasks.register<JavaExec>("healthRun") {
    group = "application"
    description = "Read Battery/Device-Info from the health peripheral via a live agent (Rig A case 7)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.HealthMainKt")
    standardInput = System.`in`
}

// Rig A case 6, burst/ordering half (WwrBurstMain.kt, pr8-validation-plan.md): compares a serial
// write loop against RemotePeripheral.writeWithoutResponseBurst (window > 1) against the
// TestProfile peripheral, confirming a measured improvement and (via strictly-incrementing
// single-byte payloads) that submission order survives pipelining.
//   ./gradlew :e2e-runner:wwrBurstRun --args "ws://localhost:8080/agent <token> 40 8"
tasks.register<JavaExec>("wwrBurstRun") {
    group = "application"
    description = "Compare serial vs. pipelined WriteWithoutResponse and confirm submission order (Rig A case 6)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.WwrBurstMainKt")
}

// Rig A case 2, client half (PeripheralStateMain.kt, pr8-validation-plan.md): watches
// Peripheral.state across an unsolicited BLE-level drop ("Force disconnect all" on the phone),
// confirming the client reaches State.Disconnected — the agent-side half of this case was already
// confirmed, but no runner before this one watched peripheral (as opposed to transport) state.
//   ./gradlew :e2e-runner:peripheralStateRun --args "ws://localhost:8080/agent"
tasks.register<JavaExec>("peripheralStateRun") {
    group = "application"
    description = "Watch Peripheral.state across an unsolicited BLE disconnect (Rig A case 2)."
    val jvmJar = tasks.named("jvmJar")
    dependsOn(jvmJar)
    classpath = files(jvmJar.map { it.outputs.files }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.warsha.remoteble.e2e.PeripheralStateMainKt")
}
