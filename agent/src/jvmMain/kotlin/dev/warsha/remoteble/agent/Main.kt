package dev.warsha.remoteble.agent

import dev.warsha.remoteble.agent.di.AgentConfig
import dev.warsha.remoteble.agent.di.agentModule
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.log.PrintlnSink
import dev.warsha.remoteble.protocol.DeviceHandle
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main(args: Array<String>) {
    val logLevel = parseLogLevel(System.getenv("REMOTE_BLE_LOG")?.takeIf { it.isNotBlank() })
    Logger.configure(level = logLevel, sink = PrintlnSink)

    val requestedExclusive = System.getenv("REMOTE_BLE_EXCLUSIVE")?.toBooleanStrictOrNull()
    require(requestedExclusive != false) {
        "REMOTE_BLE_EXCLUSIVE=false is unsupported in RemoteBLE 0.9.0; shared mode is disabled"
    }
    val cli = parseCli(args)
    val simulationPath = cli.simulationPath ?: System.getenv("REMOTE_BLE_SIMULATE")?.takeIf { it.isNotBlank() }
    val simulationProfile = simulationPath?.let(::readSimulationProfile)
    val token = System.getenv("REMOTE_BLE_TOKEN")?.takeIf { it.isNotBlank() }
    val namedCredentials = parseNamedCredentials(System.getenv("REMOTE_BLE_TOKENS"))
    val operatorToken = System.getenv("REMOTE_BLE_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() }
    val bindHost = validateBind(
        requested = cli.bindHost ?: System.getenv("REMOTE_BLE_BIND") ?: AgentConfig.DEFAULT_BIND_HOST,
        hasCredential = token != null || namedCredentials.isNotEmpty(),
        allowInsecureLan = System.getenv("REMOTE_BLE_ALLOW_INSECURE_LAN")?.toBooleanStrictOrNull() == true,
    )
    val config = AgentConfig(
        bindHost = bindHost,
        port = cli.port,
        authToken = token,
        namedCredentials = namedCredentials,
        operatorToken = operatorToken,
        exclusiveByDefault = true,
        leaseGrace = System.getenv("REMOTE_BLE_LEASE_GRACE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().leaseGrace,
        transportGrace = System.getenv("REMOTE_BLE_TRANSPORT_GRACE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().transportGrace,
        livenessProbeInterval = System.getenv("REMOTE_BLE_LIVENESS_PROBE_MS")?.toLongOrNull()?.milliseconds
            ?: AgentConfig().livenessProbeInterval,
        scanConcurrency = System.getenv("REMOTE_BLE_SCAN_CONCURRENCY")
            ?.takeIf { it.isNotBlank() }
            ?.let(ScanConcurrencyMode::parse)
            ?: AgentConfig().scanConcurrency,
        // Strict parse: a typo'd value fails startup rather than silently taking the default,
        // because this switch changes an operator-visible failure mode.
        failFastOnDegradedWrites = System.getenv("REMOTE_BLE_WRITE_FAIL_FAST")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                it.toBooleanStrictOrNull()
                    ?: error("REMOTE_BLE_WRITE_FAIL_FAST must be 'true' or 'false', got '$it'")
            }
            ?: AgentConfig().failFastOnDegradedWrites,
        simulationProfile = simulationProfile,
    )
    val app = startKoin { modules(agentModule(config)) }
    val server = app.koin.get<AgentWebSocketServer>()
    val registry = app.koin.get<PeripheralRegistry>()
    val backend = app.koin.get<BleBackend>()
    // `start()` suspends until the socket is genuinely bound (see AgentWebSocketServer.start), so
    // the desktop agent now fails here with a usable message instead of logging "listening on …"
    // and only then discovering, on a CIO worker, that it never bound.
    try {
        runBlocking { server.start() }
    } catch (bind: AgentBindException) {
        Logger.error(LogTags.AGENT) { "Cannot start: ${bind.message}. Is another agent already running?" }
        exitProcess(1)
    }
    app.koin.get<ConnectionWatcher>().start()

    val auth = if (config.authToken != null || config.namedCredentials.isNotEmpty()) "bearer-token required" else "no auth"
    val host = System.getProperty("os.name") ?: "jvm"
    val radio = if (simulationProfile == null) "Kable engine on $host" else "simulation profile (${simulationProfile.peripherals.size} peripherals)"
    Logger.info(LogTags.AGENT) { "RemoteBLE agent listening on ws://${config.bindHost}:${config.port}/agent ($auth, exclusive peripherals, $radio)" }
    Logger.info(LogTags.AGENT) { "Ownership grace: lease ${config.leaseGrace}, transport ${config.transportGrace}" }
    Logger.info(LogTags.AGENT) {
        "Scan concurrency: ${config.scanConcurrency.name.lowercase()} (REMOTE_BLE_SCAN_CONCURRENCY)"
    }
    Logger.info(LogTags.AGENT) { "Liveness probe: every ${config.livenessProbeInterval}" }
    // Stated at startup because it is a workaround for a backend defect, not a neutral default:
    // an operator should be able to see from the log which behaviour this process has.
    if (simulationProfile == null) {
        Logger.info(LogTags.AGENT) {
            "Degraded-write fail-fast: ${if (config.failFastOnDegradedWrites) "on" else "off"} " +
                "(REMOTE_BLE_WRITE_FAIL_FAST)"
        }
    }
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

internal data class Cli(val bindHost: String?, val port: Int, val simulationPath: String?)

internal fun parseCli(args: Array<String>): Cli {
    var bind: String? = null
    var port = AgentConfig.DEFAULT_PORT
    var simulation: String? = null
    var index = 0
    if (args.firstOrNull()?.toIntOrNull() != null) {
        port = args[0].toInt()
        index = 1
    }
    while (index < args.size) {
        when (args[index]) {
            "--bind" -> bind = args.getOrNull(++index) ?: error("--bind requires an address")
            "--port" -> port = args.getOrNull(++index)?.toIntOrNull() ?: error("--port requires a valid port")
            "--simulate" -> simulation = args.getOrNull(++index)?.takeIf { it.isNotBlank() }
                ?: error("--simulate requires a profile path")
            else -> error("unknown argument ${args[index]}; supported: [port], --port, --bind, --simulate")
        }
        index++
    }
    require(port in 1..65535) { "port must be between 1 and 65535" }
    return Cli(bind, port, simulation)
}

/** Resolves a simulation profile before Koin/server startup, so malformed input never opens a port. */
internal fun readSimulationProfile(path: String): SimulationProfile = try {
    SimulationProfile.decode(Files.readString(Path.of(path)))
} catch (error: IllegalArgumentException) {
    throw error
} catch (error: Throwable) {
    throw IllegalArgumentException("cannot load simulation profile '$path': ${error.message}", error)
}

// BIND-SECURITY-01: internal (not private) so MainTest.kt can exercise the policy directly.
internal fun validateBind(requested: String, hasCredential: Boolean, allowInsecureLan: Boolean): String {
    val address = runCatching { InetAddress.getByName(requested) }
        .getOrElse { error("invalid bind address $requested: ${it.message}") }
    require(!address.isMulticastAddress) { "refusing multicast bind address $requested" }
    if (!address.isLoopbackAddress && !hasCredential && !allowInsecureLan) {
        error("non-loopback bind requires REMOTE_BLE_TOKEN; set REMOTE_BLE_ALLOW_INSECURE_LAN=true only for local development")
    }
    if (!address.isLoopbackAddress && !hasCredential) {
        Logger.warn(LogTags.AGENT) { "starting unauthenticated non-loopback listener because insecure development override is enabled" }
    }
    return address.hostAddress
}

/** Parses `principal=secret,other=secret`; names are never written to the log. */
private fun parseNamedCredentials(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',').associate { item ->
        val separator = item.indexOf('=')
        require(separator > 0 && separator < item.length - 1) {
            "REMOTE_BLE_TOKENS entries must use principal=secret"
        }
        item.substring(0, separator) to item.substring(separator + 1)
    }.also { parsed ->
        require(parsed.size == raw.split(',').size) { "REMOTE_BLE_TOKENS contains duplicate principals" }
    }
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
