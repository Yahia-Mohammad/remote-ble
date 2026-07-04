package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.protocol.DeviceHandle
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

/**
 * Runnable BLE agent: a Ktor WebSocket server driving the native radio via Kable's JVM
 * (btleplug) stack — works on macOS and Linux (incl. Raspberry Pi). Run from the host
 * that is near the BLE peripheral.
 *
 *   ./gradlew :agent:jvmRun --args "8080"
 *
 * To require a bearer token, set REMOTE_BLE_TOKEN; clients must then present the same
 * token (see WebSocketAgentTransport.authToken). Without it the endpoint is open.
 *
 * macOS will prompt for Bluetooth permission for the JVM process on first scan.
 *
 * The object graph is assembled by Koin (see di/AgentModule.kt). The agent's classes
 * keep their plain constructors; only this composition root touches a DI container.
 */
fun main(args: Array<String>) {
    val config = AgentConfig(
        port = args.firstOrNull()?.toIntOrNull() ?: AgentConfig.DEFAULT_PORT,
        authToken = System.getenv("REMOTE_BLE_TOKEN")?.takeIf { it.isNotBlank() },
        // Default: peripherals are exclusive to their client. Set REMOTE_BLE_EXCLUSIVE=false to
        // open them by default. REMOTE_BLE_LEASE_GRACE_MS tunes the BLE-disconnect release window;
        // REMOTE_BLE_TRANSPORT_GRACE_MS the warm window after a client's link drops;
        // REMOTE_BLE_LIVENESS_PROBE_MS how often the active (real GATT read) liveness probe runs.
        exclusiveByDefault = System.getenv("REMOTE_BLE_EXCLUSIVE")?.toBooleanStrictOrNull() ?: true,
        leaseGrace = System.getenv("REMOTE_BLE_LEASE_GRACE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().leaseGrace,
        transportGrace = System.getenv("REMOTE_BLE_TRANSPORT_GRACE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().transportGrace,
        livenessProbeInterval = System.getenv("REMOTE_BLE_LIVENESS_PROBE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().livenessProbeInterval,
    )
    val app = startKoin { modules(agentModule(config)) }
    val server = app.koin.get<AgentWebSocketServer>()
    val registry = app.koin.get<PeripheralRegistry>()
    val backend = app.koin.get<BleBackend>()
    server.start()
    // Watch for unsolicited BLE drops so ownership leases release after the grace window.
    app.koin.get<ConnectionWatcher>().start()

    // Graceful shutdown: on SIGTERM/SIGINT, stop accepting and disconnect connected peripherals
    // so a restart starts from a clean radio state (the JVM exit would drop them regardless).
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Shutting down RemoteBLE agent...")
            runCatchingNonCancellation {
                runBlocking {
                    registry.snapshot().filter { it.connected }.forEach { lease ->
                        runCatchingNonCancellation { backend.disconnect(DeviceHandle(lease.handle)) }
                    }
                }
            }
            server.stop()
        },
    )
    val auth = if (config.authToken != null) "bearer-token required" else "no auth"
    val excl = if (config.exclusiveByDefault) "exclusive" else "shared"
    val host = System.getProperty("os.name") ?: "jvm"
    println("RemoteBLE agent listening on ws://0.0.0.0:${config.port}/agent ($auth, $excl peripherals, Kable engine on $host)")
    println("Ownership grace: lease ${config.leaseGrace}, transport ${config.transportGrace}")
    println("Liveness probe: every ${config.livenessProbeInterval}")
    println("Status dashboard: http://localhost:${config.port}/")
    CountDownLatch(1).await() // run until the process is killed
}
