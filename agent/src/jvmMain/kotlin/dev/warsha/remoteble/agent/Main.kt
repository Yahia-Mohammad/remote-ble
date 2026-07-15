package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.log.PrintlnSink
import dev.warsha.remoteble.protocol.DeviceHandle
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main(args: Array<String>) {
    val logLevel = parseLogLevel(System.getenv("REMOTE_BLE_LOG")?.takeIf { it.isNotBlank() })
    Logger.sink = PrintlnSink
    Logger.level = logLevel

    val requestedExclusive = System.getenv("REMOTE_BLE_EXCLUSIVE")?.toBooleanStrictOrNull()
    require(requestedExclusive != false) {
        "REMOTE_BLE_EXCLUSIVE=false is unsupported in RemoteBLE 0.9.0; shared mode is disabled"
    }
    val config = AgentConfig(
        port = args.firstOrNull()?.toIntOrNull() ?: AgentConfig.DEFAULT_PORT,
        authToken = System.getenv("REMOTE_BLE_TOKEN")?.takeIf { it.isNotBlank() },
        exclusiveByDefault = true,
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
    app.koin.get<ConnectionWatcher>().start()

    val auth = if (config.authToken != null) "bearer-token required" else "no auth"
    val host = System.getProperty("os.name") ?: "jvm"
    Logger.info(LogTags.AGENT) { "RemoteBLE agent listening on ws://0.0.0.0:${config.port}/agent ($auth, exclusive peripherals, Kable engine on $host)" }
    Logger.info(LogTags.AGENT) { "Ownership grace: lease ${config.leaseGrace}, transport ${config.transportGrace}" }
    Logger.info(LogTags.AGENT) { "Liveness probe: every ${config.livenessProbeInterval}" }
    Logger.info(LogTags.AGENT) { "Log level: ${logLevel?.name?.lowercase() ?: "off"}" }
    Logger.info(LogTags.AGENT) { "Status dashboard: http://localhost:${config.port}/" }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            Logger.info(LogTags.AGENT) { "Shutting down RemoteBLE agent..." }
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
    CountDownLatch(1).await()
}

private fun parseLogLevel(raw: String?): LogLevel? = when (raw?.lowercase()) {
    "trace" -> LogLevel.TRACE
    "debug" -> LogLevel.DEBUG
    "info" -> LogLevel.INFO
    "warn" -> LogLevel.WARN
    "error" -> LogLevel.ERROR
    "off" -> null
    else -> LogLevel.INFO
}
