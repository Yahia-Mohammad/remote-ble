@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.read
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.CborProtocolCodec
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_URL = "ws://localhost:8080/agent"
private const val SERVICE = "a1b2c3d4-0000-4000-8000-000000000001"
private const val READABLE = "a1b2c3d4-0000-4000-8000-000000000002"

/** How long to keep observing after the stimulus prompt, unless overridden by arg 3. */
private val DEFAULT_OBSERVE_WINDOW = 180.seconds

/** GATT read cadence — fast enough to date the moment the radio link stops answering. */
private val GATT_PROBE_INTERVAL = 2.seconds

/** Fresh-inbound-connection cadence. Slower: each probe can block for its own connect timeout. */
private val INBOUND_PROBE_INTERVAL = 5.seconds

/**
 * Rig B cases 3, 4 and 5 (pr8-validation-plan.md), client half: holds a fully established session —
 * transport connected, peripheral connected, services discovered — and then narrates, on a single
 * timeline, everything an already-connected client can observe while the operator changes the iOS
 * agent app's lifecycle state.
 *
 *   ./gradlew :e2e-runner:agentLifecycleRun --args "ws://192.168.178.85:8080/agent"
 *
 * args: [ws-url] [token] [observe-seconds] [link|nolink] (token also read from `REMOTE_BLE_TOKEN`).
 *
 * **`nolink` is the control, and it is the whole experiment.** `ios-agent`'s `Info.plist` declares
 * `UIBackgroundModes: bluetooth-central`, which keeps the process running in the background *only*
 * while it holds an active CoreBluetooth connection. So "does the backgrounded agent keep serving?"
 * has no single answer — it depends on whether a radio link is open, and running only the default
 * `link` mode would attribute to "backgrounding" what is really "backgrounding without a link".
 * `nolink` holds the transport open and skips the peripheral entirely, changing exactly that one
 * variable.
 *
 * All three cases ask the same question with a different stimulus — background/lock (3), tap Stop
 * (4), or stop mid-operation (5) — so they share one instrument rather than three. The stimulus is
 * the operator's; this side only records.
 *
 * **Why it watches four things at once.** Each answers a different half of the cases, and the
 * interesting results are in how they *disagree*:
 * - **Transport state** — does the client's own WebSocket drop, and does it silently reconnect?
 * - **GATT reads** every [GATT_PROBE_INTERVAL] — is the *radio link* still alive? Case 3 documents
 *   that existing links "may linger briefly", which is only checkable with traffic on them.
 * - **A brand-new inbound connection** every [INBOUND_PROBE_INTERVAL] — case 3's actual assertion
 *   is that *new* connections are not accepted, which an already-connected client cannot detect.
 *   Classified rather than pass/fail, because the distinction carries the finding: a suspended app
 *   can leave the listening socket up at the OS level, so the connect succeeds and then nothing
 *   answers ([InboundProbe.NoResponse]) — which is a different observation from the listener being
 *   gone ([InboundProbe.Refused]) and would be erased by a boolean.
 * - **Wire events** — anything the agent volunteers on its way down.
 *
 * Note the deliberate asymmetry: the inbound probe speaks raw HTTP rather than opening a second
 * client session, because [WebSocketAgentTransport] reconnects on its own and would turn a refusal
 * into a retry — the very thing this needs to see.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val observeWindow = args.getOrNull(2)?.toIntOrNull()?.seconds ?: DEFAULT_OBSERVE_WINDOW
    val holdBleLink = !args.getOrNull(3).equals("nolink", ignoreCase = true)

    println("== RemoteBle agent-lifecycle observer (Rig B cases 3-5) ==")
    println("agent : $url")
    println("device: ${if (holdBleLink) "service $SERVICE" else "(nolink control — transport only, no radio link)"}")
    println("token : ${if (token != null) "set" else "none"}")
    println("window: ${observeWindow.inWholeSeconds}s after the prompt")
    println()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    var readable: DiscoveredCharacteristic? = null
    try {
        print("• transport connects ... ")
        withTimeoutOrNull(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
            ?: error("transport never reached CONNECTED")
        println("ok")

        if (holdBleLink) {
            print("• scan finds the peripheral (by service UUID) ... ")
            val adv = withTimeoutOrNull(30.seconds) {
                RemoteScanner(session).advertisements.first { a ->
                    a.uuids.any { it.toString().equals(SERVICE, ignoreCase = true) }
                }
            } ?: error("scan timed out")
            println("ok [dev=${adv.handle.value}]")

            val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
            print("• connect + discover ... ")
            p.connect()
            readable = p.services.value
                ?.flatMap { it.characteristics }
                ?.firstOrNull { it.characteristicUuid.toString().equals(READABLE, ignoreCase = true) }
                ?: error("readable characteristic not found")
            println("ok")

            print("• baseline read ... ")
            println(p.read(readable).toHexString())
        } else {
            println("• nolink control — no scan, no connection, transport only")
        }

        val timeline = Timeline()

        // Watchers first, so nothing between here and the prompt goes unrecorded.
        scope.launch { session.transportState.onEach { timeline.log("transport -> $it") }.collect {} }
        scope.launch { session.events().onEach { timeline.log("wire event -> $it") }.collect {} }
        peripheral?.let { p ->
            scope.launch { p.state.onEach { timeline.log("peripheral -> $it") }.collect {} }
            val char = readable ?: error("no readable characteristic")
            scope.launch {
                while (true) {
                    delay(GATT_PROBE_INTERVAL)
                    val outcome = runCatching { p.read(char) }
                    timeline.log(
                        "gatt read -> " + outcome.fold(
                            onSuccess = { "OK ${it.toHexString()} (radio link UP)" },
                            onFailure = { t ->
                                val kind = (t as? AgentException)?.error?.kind
                                "FAILED ${kind ?: t::class.simpleName}: ${t.message}"
                            },
                        ),
                    )
                }
            }
        }
        val inbound = InboundProbe(url, token)
        scope.launch {
            while (true) {
                delay(INBOUND_PROBE_INTERVAL)
                timeline.log("new inbound connection -> ${inbound.probe()}")
            }
        }

        println()
        println("=".repeat(78))
        println(">>> PERFORM THE STIMULUS ON THE PHONE NOW — observing for ${observeWindow.inWholeSeconds}s <<<")
        println("=".repeat(78))
        timeline.start()
        delay(observeWindow)

        println()
        println("------------------------------")
        println("observation window closed. Final state:")
        println("  transport      : ${session.transportState.value}")
        println("  new inbound    : ${inbound.probe()}")
        peripheral?.let { p ->
            println("  peripheral     : ${p.state.value}")
            println("  final gatt read: " + runCatching { p.read(readable!!).toHexString() }
                .fold(onSuccess = { "OK $it" }, onFailure = { "FAILED: ${it.message}" }))
        }
    } catch (t: Throwable) {
        println()
        println("SETUP FAILED: ${t.message}")
        runCatching { peripheral?.close() }
        http.close()
        scope.cancel()
        exitProcess(1)
    }
    runCatching { peripheral?.close() }
    http.close()
    scope.cancel()
    exitProcess(0)
}

/** Single monotonic timeline shared by every watcher, so their orderings are comparable. */
private class Timeline {
    private var origin: TimeSource.Monotonic.ValueTimeMark? = null

    fun start() {
        origin = TimeSource.Monotonic.markNow()
    }

    @Synchronized
    fun log(message: String) {
        val at = origin?.elapsedNow() ?: Duration.ZERO
        val stamp = if (origin == null) "  pre  " else "+%6.1fs".format(at.inWholeMilliseconds / 1000.0)
        println("[$stamp] $message")
    }
}

/**
 * Opens a genuinely new TCP connection to the agent's listener and reports how far it gets.
 *
 * Speaks HTTP by hand instead of using the client SDK: the point is to classify *where* a new
 * connection dies, and a WebSocket upgrade that fails for its own reasons would blur that. A
 * healthy agent answers `400` here (the bearer is accepted, the request just isn't an upgrade) —
 * exactly what a browser hitting `/agent` sees, and proof the accept loop is running.
 */
private class InboundProbe(wsUrl: String, private val token: String?) {
    private val uri = URI(wsUrl)
    private val host: String = uri.host
    private val port: Int = if (uri.port != -1) uri.port else 80
    private val path: String = uri.path.ifBlank { "/" }

    suspend fun probe(): String = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val request = buildString {
                append("GET $path HTTP/1.1\r\nHost: $host:$port\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val status = socket.getInputStream().bufferedReader().readLine()
            when {
                status == null -> "$NO_RESPONSE (connected, then closed with no reply)"
                else -> "ACCEPTED — $status"
            }
        } catch (_: ConnectException) {
            "$REFUSED (nothing listening)"
        } catch (_: SocketTimeoutException) {
            "$NO_RESPONSE (TCP established, no HTTP reply in ${READ_TIMEOUT_MS}ms — listener up, app not serving)"
        } catch (e: IOException) {
            "ERROR ${e::class.simpleName}: ${e.message}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 3_000
        const val REFUSED = "REFUSED"
        const val NO_RESPONSE = "NO_RESPONSE"
    }
}

private fun ByteArray.toHexString(): String =
    if (isEmpty()) "(empty)" else joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
