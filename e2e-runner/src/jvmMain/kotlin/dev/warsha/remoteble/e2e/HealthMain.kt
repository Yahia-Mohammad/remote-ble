@file:OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)

package dev.warsha.remoteble.e2e

import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import dev.warsha.remoteble.client.BleMode
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.RemoteScanner
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.client.defaultWebSocketHttpClient
import dev.warsha.remoteble.client.peripheralFor
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// Battery Service / Battery Level and Device Information Service / Manufacturer Name / Model
// Number, standard 16-bit Bluetooth SIG UUIDs (see ../ble-peripheral's HealthProfile.kt).
private const val DEFAULT_URL = "ws://localhost:8080/agent"
private val BATTERY_SERVICE = shortUuid(0x180F)
private val BATTERY_LEVEL = shortUuid(0x2A19)
private val DEVICE_INFO_SERVICE = shortUuid(0x180A)
private val MANUFACTURER_NAME = shortUuid(0x2A29)
private val MODEL_NUMBER = shortUuid(0x2A24)

private fun shortUuid(short: Int): Uuid =
    Uuid.parse("%08x-0000-1000-8000-00805f9b34fb".format(short))

/**
 * Rig A case 7: Battery/Device-Info on a non-iOS peripheral (validation-plan.md). Confirms
 * live (not frozen/cached) standard-service values through a real agent — using an iPhone
 * peripheral for this case would silently shadow 0x180F/0x180A with iOS's own system battery and
 * device-info values instead of the test peripheral's, which is exactly the false pass this
 * driver exists to avoid (see docs/validation-plan.md's note against iOS here).
 *
 *   ./gradlew :e2e-runner:healthRun --args "ws://localhost:8080/agent"
 *
 * args: [ws-url] [token] (token also read from REMOTE_BLE_TOKEN). Needs a running agent and the
 * sibling ../ble-peripheral health-peripheral app advertising as "Warsha HRM".
 */
fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrNull(0) ?: DEFAULT_URL
    val token = args.getOrNull(1)?.ifBlank { null } ?: System.getenv("REMOTE_BLE_TOKEN")
    val name = "Warsha HRM"

    println("== RemoteBle Battery/Device-Info live check (Rig A case 7) ==")
    println("agent : $url")
    println("device: \"$name\"  token=${if (token != null) "set" else "none"}")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val http = defaultWebSocketHttpClient()
    val session = DefaultAgentSession(
        WebSocketAgentTransport(url, scope, http, authToken = { token }),
        CborProtocolCodec(),
        scope,
    )
    var peripheral: Peripheral? = null
    var failed = false
    try {
        withTimeout(15.seconds) { session.transportState.first { it == TransportState.CONNECTED } }
        println("transport connected; scanning for \"$name\"...")
        val adv = withTimeout(30.seconds) { RemoteScanner(session).advertisements.first { it.name == name } }
        val p = peripheralFor(BleMode.REMOTE, adv, session).also { peripheral = it }
        println("connecting...")
        withTimeout(30.seconds) { p.connect() }

        val services = p.services.value ?: error("no services discovered")
        val battery = services.first { it.serviceUuid == BATTERY_SERVICE }
            .characteristics.byUuid(BATTERY_LEVEL)
        val deviceInfo = services.first { it.serviceUuid == DEVICE_INFO_SERVICE }
        val manufacturer = deviceInfo.characteristics.byUuid(MANUFACTURER_NAME)
        val model = deviceInfo.characteristics.byUuid(MODEL_NUMBER)

        val manufacturerName = p.read(manufacturer).decodeToString()
        val modelName = p.read(model).decodeToString()
        println("Device Information: manufacturer=\"$manufacturerName\" model=\"$modelName\"")

        val first = p.read(battery).single().toInt() and 0xFF
        println("Battery level (read 1): $first%")
        println()
        println(">>> Now drag the battery slider on the phone to a DIFFERENT value, then press Enter <<<")
        readlnOrNull()
        val second = p.read(battery).single().toInt() and 0xFF
        println("Battery level (read 2): $second%")

        if (second == first) {
            println("FAILED: battery level did not change — looks frozen/cached, not a live read")
            failed = true
        } else {
            println("PASS: battery level changed ($first% -> $second%), confirming a live (not frozen) read")
        }
    } catch (t: Throwable) {
        println("FAILED: ${t.message}")
        failed = true
    } finally {
        runCatching { peripheral?.disconnect() }
        runCatching { peripheral?.close() }
        http.close()
        scope.cancel()
    }
    exitProcess(if (failed) 1 else 0)
}

private fun List<DiscoveredCharacteristic>.byUuid(uuid: Uuid): DiscoveredCharacteristic =
    first { it.characteristicUuid == uuid }
