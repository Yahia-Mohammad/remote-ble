package dev.warsha.remoteble.androidclient.ble

import dev.warsha.remoteble.androidclient.model.DeviceState
import dev.warsha.remoteble.client.RemotePeripheral
import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.notify
import com.juul.kable.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Drives a single connected peripheral and projects it as one [DeviceState] flow.
 *
 * The peripheral's own [RemotePeripheral.state] and [RemotePeripheral.services] flows are
 * the source of truth for the link; the only state this class adds is the accumulated
 * characteristic [values] and the set of [subscribed] UUIDs. Everything the UI shows is a
 * pure [combine] of those — there is no hand-maintained mirror to fall out of sync.
 *
 * The session auto-connects on construction and auto-provisions the well-known profiles
 * (reads device info / battery / sensor location, subscribes to heart rate). It confines
 * its work to a child scope so [close] cancels just this device's work.
 */
@OptIn(ExperimentalUuidApi::class)
class PeripheralSession(
    private val peripheral: RemotePeripheral,
    handle: DeviceHandle,
    name: String?,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )

    private val values = MutableStateFlow<Map<Uuid, ByteArray>>(emptyMap())
    private val subscribed = MutableStateFlow<Set<Uuid>>(emptySet())
    private val everConnected = MutableStateFlow(false)
    private val observeJobs = mutableMapOf<Uuid, Job>()

    val state: StateFlow<DeviceState> = combine(
        peripheral.state,
        peripheral.services,
        values,
        subscribed,
        everConnected,
    ) { connectionState, services, values, subscribed, everConnected ->
        DeviceState(handle, name, connectionState, services, values, subscribed, everConnected)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        DeviceState(handle, name, State.Connecting.Bluetooth),
    )

    init {
        peripheral.state
            .onEach { if (it is State.Connected) everConnected.value = true }
            .launchIn(scope)

        // Auto-provision once services land. RemotePeripheral.services emits non-null after a
        // successful discovery (and null again on disconnect), so this naturally re-runs on
        // reconnect without us tracking connection epochs.
        peripheral.services
            .filterNotNull()
            .onEach(::autoProvision)
            .launchIn(scope)

        scope.launch {
            try {
                peripheral.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // RemotePeripheral.connect already drove state back to Disconnected; the UI
                // reads that. Nothing actionable to add here.
            }
        }
    }

    /** Reads a readable characteristic on demand (explorer "Read value"). */
    fun read(characteristic: DiscoveredCharacteristic) {
        scope.launch { runCatchingConnection { store(characteristic, peripheral.read(characteristic)) } }
    }

    /** Writes [data], choosing the response mode the characteristic actually supports. */
    suspend fun write(characteristic: DiscoveredCharacteristic, data: ByteArray): Result<Unit> {
        val writeType = if (characteristic.properties.write) WriteType.WithResponse else WriteType.WithoutResponse
        return runCatching { peripheral.write(characteristic, data, writeType) }
    }

    /** Subscribes if not already subscribed, unsubscribes otherwise. */
    fun toggleSubscription(characteristic: DiscoveredCharacteristic) {
        if (characteristic.characteristicUuid in subscribed.value) {
            unsubscribe(characteristic.characteristicUuid)
        } else {
            subscribe(characteristic)
        }
    }

    /** Tears down the link; safe to call repeatedly. */
    suspend fun disconnect() {
        runCatchingConnection { peripheral.disconnect() }
    }

    /** Cancels this session's work and releases the underlying peripheral. */
    fun close() {
        scope.cancel()
        peripheral.close()
    }

    private fun autoProvision(services: List<DiscoveredService>) {
        for (characteristic in services.flatMap { it.characteristics }) {
            when (characteristic.characteristicUuid) {
                BleUuids.MANUFACTURER_NAME,
                BleUuids.MODEL_NUMBER,
                BleUuids.BODY_SENSOR_LOCATION,
                BleUuids.BATTERY_LEVEL,
                -> read(characteristic)
            }
            val notifies = characteristic.properties.notify
            if (characteristic.characteristicUuid == BleUuids.HEART_RATE_MEASUREMENT ||
                (characteristic.characteristicUuid == BleUuids.BATTERY_LEVEL && notifies)
            ) {
                subscribe(characteristic)
            }
        }
    }

    private fun subscribe(characteristic: DiscoveredCharacteristic) {
        val uuid = characteristic.characteristicUuid
        observeJobs[uuid]?.cancel()
        subscribed.update { it + uuid }
        observeJobs[uuid] = peripheral.observe(characteristic)
            .onEach { store(characteristic, it) }
            .launchIn(scope)
    }

    private fun unsubscribe(uuid: Uuid) {
        observeJobs.remove(uuid)?.cancel()
        subscribed.update { it - uuid }
    }

    private fun store(characteristic: DiscoveredCharacteristic, bytes: ByteArray) {
        values.update { it + (characteristic.characteristicUuid to bytes) }
    }

    private inline fun runCatchingConnection(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Reads/disconnects can race a dropping link; the state flow already reflects it.
        }
    }
}
