package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharNode
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalApi
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.read
import com.juul.kable.toIdentifier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [BleBackend], built over Kable's native `Peripheral`/`Scanner` API — Kable's
 * JVM (btleplug) backend on macOS/Linux, Android's own platform BLE stack on Android, and
 * CoreBluetooth on iOS. This class only ever calls Kable's common, platform-agnostic API,
 * so one implementation drives all three.
 *
 * Kable is connection-oriented: a [Peripheral] is a long-lived object that owns its radio
 * connection (on its own [Peripheral.scope]) until [Peripheral.disconnect]/[Peripheral.close].
 * One is cached per [DeviceHandle] so ops resolve back to the same connection; it is closed
 * and evicted on [disconnect] to release its native handles (Kable recommends discarding a
 * peripheral's references after disconnect — a fresh one is created on the next [connect]).
 *
 * Failures from Kable are mapped to [ErrorKind]s and thrown as `AgentException` (via
 * [bleError]); [CancellationException] is always allowed to propagate so structured
 * cancellation is preserved.
 */
@OptIn(ExperimentalUuidApi::class)
class EngineBleBackend(
    // Agent-lifetime scope for the per-connection state watchers (see [connect]). Defaults to a
    // standalone scope so plain `EngineBleBackend()` (tests) works; the DI graph injects the shared
    // agent scope so the watchers are torn down with the process, not leaked.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Whether to short-circuit writes on a connection whose writes have stopped completing.
     *
     * This is a workaround for a backend defect, so it is a deliberate, operator-visible switch
     * rather than silent special-casing: see [markWriteDegraded] for the defect and
     * `REMOTE_BLE_WRITE_FAIL_FAST` for the control. Turn it off to get the unmodified behaviour
     * back — in particular once the underlying backend delivers ATT errors properly, at which point
     * the degraded state can no longer be entered anyway.
     */
    private val failFastOnDegradedWrites: Boolean = true,
) : BleBackend {

    override val capabilities: Set<String> = buildSet {
        add(Capabilities.DESCRIPTORS) // Kable exposes descriptor read/write on every platform.
        // Connected RSSI is a live read only on Kable's Android/Apple backends; the JVM/btleplug
        // backend returns cached advertisement RSSI (or Int.MIN_VALUE), so advertise it only where
        // readRssi() below is genuinely a connected read.
        if (agentRssiSupported()) add(Capabilities.RSSI)
        // conn.params and its coarse conn.priority alias are both driven by the same platform
        // binding (Android's requestConnectionPriority) — advertise both from one predicate.
        if (agentConnParamsSupported()) {
            add(Capabilities.CONN_PARAMS)
            add(Capabilities.CONN_PRIORITY)
        }
    }

    // Plain map guarded by a multiplatform lock (kotlinx-atomicfu — java.util.concurrent has no
    // Kotlin/Native equivalent). resolve()/isConnected() are called from non-suspend contexts, so
    // this stays a synchronous lock rather than a suspend-only Mutex.
    private val lock = SynchronizedObject()
    private val peripherals = mutableMapOf<DeviceHandle, Peripheral>()

    /**
     * Devices whose writes have stopped completing — see [markWriteDegraded].
     *
     * Keyed by handle and cleared whenever the [Peripheral] is discarded ([disconnect]) or a fresh
     * connection is established ([connect]), because a new connection is the only thing observed to
     * clear the underlying condition.
     */
    private val writeDegraded = mutableSetOf<DeviceHandle>()

    // Native unsolicited-drop stream (see [connectionDrops]). DROP_OLDEST + a small buffer so a slow
    // or absent collector can never suspend the per-connection watcher that emits here; the polling
    // ConnectionWatcher is the backstop if a rare overflow drops a signal.
    private val drops = MutableSharedFlow<ConnectionDrop>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun connectionDrops(): Flow<ConnectionDrop> = drops

    /**
     * Resolves [device] to a cached (or freshly created) [Peripheral]. `toIdentifier()` throws
     * on a malformed handle — reachable with hostile input from a client — so that failure is
     * mapped to a typed [ErrorKind] here
     * rather than surfacing as a raw throwable on the `observe()` path, which isn't wrapped in
     * [bleOp]. [ErrorKind.UNKNOWN_DEVICE] ("never reached the radio") is the accurate fit: a handle
     * that won't even parse identifies no device the agent could reach.
     */
    private fun resolve(device: DeviceHandle): Peripheral = synchronized(lock) {
        peripherals.getOrPut(device) {
            val identifier = try {
                device.value.toIdentifier()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                bleError(ErrorKind.UNKNOWN_DEVICE, message = "malformed device handle: ${t.message}")
            }
            peripheralByIdentifier(identifier)
        }
    }

    override fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto> = channelFlow {
        val scanner = Scanner {
            filters {
                for (filter in filters) {
                    match {
                        filter.service?.let { services = listOf(Uuid.parse(it)) }
                        filter.name?.let { name = Filter.Name.Exact(it) }
                    }
                }
            }
        }
        // `send` (not `trySend`) applies backpressure so advertisements aren't dropped; the
        // collect ends when this channelFlow's scope is cancelled, which stops the scan.
        scanner.advertisements.collect { advertisement ->
            send(
                AdvertisementDto(
                    device = DeviceHandle(advertisement.identifier.toString()),
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                ),
            )
        }
    }

    override suspend fun connect(device: DeviceHandle) {
        val peripheral = resolve(device)
        bleOp(ErrorKind.CONNECTION_FAILED) { peripheral.connect() }
        // A newly established connection is the one thing observed to clear the degraded-write
        // condition, so this is where the flag is dropped (see [markWriteDegraded]).
        synchronized(lock) { writeDegraded.remove(device) }
        Logger.debug(LogTags.ENGINE) { "Kable connect ok [dev=${device.value}]" }
        scope.launch { watchForUnsolicitedDrop(device, peripheral) }
    }

    /**
     * Suspends until [peripheral] transitions to [State.Disconnected], then emits a
     * [ConnectionDrop] — UNLESS this was an explicit [disconnect], which removes and closes the
     * peripheral first, so it's no longer the active instance for [device]. Only a drop the radio
     * initiated on its own leaves the peripheral still mapped here, which is exactly the unsolicited
     * case [connectionDrops] promises.
     */
    private suspend fun watchForUnsolicitedDrop(device: DeviceHandle, peripheral: Peripheral) {
        val disconnected = peripheral.state.first { it is State.Disconnected } as State.Disconnected
        val stillActive = synchronized(lock) { peripherals[device] === peripheral }
        if (stillActive) {
            Logger.debug(LogTags.ENGINE) { "Kable state → Disconnected [dev=${device.value} reason=${disconnected.status}]: unsolicited drop" }
            drops.tryEmit(ConnectionDrop(device, disconnected.reason()))
        }
    }

    /** Maps Kable's disconnect [State.Disconnected.Status] to a wire [AgentError] cause. */
    private fun State.Disconnected.reason(): AgentError =
        AgentError(ErrorKind.DISCONNECTED, message = status?.let { it::class.simpleName } ?: "peer disconnected")

    override suspend fun disconnect(device: DeviceHandle) {
        val peripheral = synchronized(lock) {
            writeDegraded.remove(device)
            peripherals.remove(device)
        } ?: return
        try {
            peripheral.disconnect()
            Logger.debug(LogTags.ENGINE) { "Kable disconnect ok [dev=${device.value}]" }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logger.warn(LogTags.ENGINE) { "Kable disconnect error (best-effort) [dev=${device.value}]: ${t.message}" }
        } finally {
            runCatchingNonCancellation { peripheral.close() }
        }
    }

    override fun isConnected(device: DeviceHandle): Boolean =
        synchronized(lock) { peripherals[device] }?.state?.value is State.Connected

    override suspend fun checkLiveness(device: DeviceHandle): Boolean {
        val peripheral = synchronized(lock) { peripherals[device] } ?: return false
        if (peripheral.state.value !is State.Connected) return false
        // Not discovered yet (client connected but hasn't discovered): nothing to probe with; the
        // cached Connected state is the best we have.
        val characteristics = peripheral.services.value?.flatMap { it.characteristics } ?: return true

        // Force a real GATT round-trip so a peripheral that vanished without a clean BLE-level
        // teardown is caught even while Kable/the native stack still reports it Connected (see the
        // kdoc on BleBackend.checkLiveness). Reads only, never writes: side-effect-free by GATT
        // convention, unlike a write. Prefer a readable characteristic; failing that (a
        // notify/indicate-only peripheral) read its CCCD descriptor — always readable per spec and
        // present on every notify/indicate characteristic — so those devices are actively probed
        // too instead of being silently trusted. Only a peripheral exposing neither is un-probable.
        val probe: (suspend () -> ByteArray)? =
            characteristics.firstOrNull { it.properties.read }?.let { c -> suspend { peripheral.read(c) } }
                ?: characteristics.firstNotNullOfOrNull { it.cccd() }?.let { d -> suspend { peripheral.read(d) } }
        probe ?: return true // nothing safe to probe with; trust the cached state

        return try {
            withTimeoutOrNull(LIVENESS_PROBE_TIMEOUT) { probe() } != null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        }
    }

    /** The Client Characteristic Configuration descriptor (0x2902), if this characteristic has one. */
    private fun DiscoveredCharacteristic.cccd(): DiscoveredDescriptor? =
        descriptors.firstOrNull { it.descriptorUuid == CCCD_UUID }

    override suspend fun discover(device: DeviceHandle): List<ServiceNode> {
        val peripheral = resolve(device)
        requireConnected(peripheral)
        // Kable discovers services automatically during connect(), so they're present once
        // the peripheral reaches Connected.
        val services = peripheral.services.value
            ?: bleError(ErrorKind.GATT_ERROR, message = "services not discovered")
        return services.map { it.toNode() }
    }

    override suspend fun read(device: DeviceHandle, char: CharRef): ByteArray {
        val peripheral = connectedPeripheral(device)
        val characteristic = peripheral.findCharacteristic(char)
        return gattOp(ErrorKind.READ_FAILED, "read") { peripheral.read(characteristic) }
    }

    override suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean) {
        val peripheral = connectedPeripheral(device)
        val characteristic = peripheral.findCharacteristic(char)
        val writeType = if (withResponse) WriteType.WithResponse else WriteType.WithoutResponse

        degradedWriteRejection(device, withResponse)?.let { throw AgentException(it) }

        try {
            gattOp(ErrorKind.WRITE_FAILED, "write") { peripheral.write(characteristic, value, writeType) }
        } catch (e: AgentException) {
            if (withResponse && e.error.kind == ErrorKind.TIMEOUT) markWriteDegraded(device)
            throw e
        }
    }

    /**
     * Records that this device's writes have stopped completing.
     *
     * Confirmed on hardware (Rig A, 2026-07-28): once btleplug has one write-with-response answered
     * by an ATT error, it stops delivering write completions for that peripheral entirely — later
     * writes reach the peripheral and are accepted, but no completion ever arrives. Reads are
     * unaffected, and a *fresh* connection writes normally (measured at 66ms), so tearing the
     * connection down is the only observed recovery.
     *
     * Without [failFastOnDegradedWrites] every subsequent write costs a full [GATT_OP_TIMEOUT]
     * before failing, which is a poor failure mode for a connection that cannot succeed. Recording
     * the state lets [write] answer immediately with the identical error instead.
     *
     * Note this only becomes reachable *because* [gattOp] bounds the operation — an unbounded hung
     * write never returns to mark anything.
     */
    // internal (not private): EngineBleBackendJvmTest drives the degraded-write gate directly.
    internal fun markWriteDegraded(device: DeviceHandle) {
        val newlyDegraded = synchronized(lock) { writeDegraded.add(device) }
        if (newlyDegraded) {
            Logger.warn(LogTags.ENGINE) {
                "write did not complete [dev=${device.value}]; treating this connection's writes as " +
                    "degraded until it is re-established" +
                    if (failFastOnDegradedWrites) "" else " (fail-fast disabled; later writes will still wait)"
            }
        }
    }

    /**
     * The rejection [write] should raise before touching the radio, or `null` to proceed normally.
     *
     * Split out from [write] so the gate — including [failFastOnDegradedWrites] — is testable
     * without a radio; [write] itself needs a live connection.
     *
     * Reports [ErrorKind.TIMEOUT], the *same* kind and therefore the same client-visible outcome as
     * letting [gattOp] expire on this write. That is the point: this changes how long the failure
     * takes, not what the failure means.
     *
     * Only applies to [withResponse] writes. The degraded state protects against a
     * write-with-response completion that never arrives ([markWriteDegraded]'s doc);
     * WriteWithoutResponse has no ATT response to await in the first place (resumes on local
     * hand-off, see docs/phase7-bringup.md), so it can't be affected by that wedge and must not be
     * short-circuited by it — doing so broke the documented "WWR still returns Ok" guarantee on
     * real hardware (Rig A, 2026-07-28).
     */
    // internal (not private): EngineBleBackendJvmTest drives this directly.
    internal fun degradedWriteRejection(device: DeviceHandle, withResponse: Boolean): AgentError? {
        if (!withResponse || !failFastOnDegradedWrites || !isWriteDegraded(device)) return null
        return AgentError(
            ErrorKind.TIMEOUT,
            message = "writes on this connection are not completing; reconnect the device " +
                "(set REMOTE_BLE_WRITE_FAIL_FAST=false to wait $GATT_OP_TIMEOUT per write instead)",
        )
    }

    // internal (not private): EngineBleBackendJvmTest drives the degraded-write gate directly.
    internal fun isWriteDegraded(device: DeviceHandle): Boolean =
        synchronized(lock) { device in writeDegraded }

    override fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray> {
        val peripheral = connectedPeripheral(device)
        return peripheral.observe(peripheral.findCharacteristic(char))
    }

    override suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int {
        // btleplug exposes no MTU-negotiation API, so the agent doesn't advertise an MTU
        // capability. Rather than echo the request (which would let a client believe a large
        // MTU was negotiated and oversize its writes), report the ATT default minimum — the
        // only value guaranteed safe for a client sizing payloads against the result.
        return DEFAULT_ATT_MTU
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun readRssi(device: DeviceHandle): Int {
        val peripheral = connectedPeripheral(device)
        val rssi = gattOp(ErrorKind.READ_FAILED, "rssi") { peripheral.rssi() }
        // Defensive: Kable's btleplug backend returns Int.MIN_VALUE when it has no cached value. This
        // backend only advertises the `rssi` capability where rssi() is a real connected read
        // (agentRssiSupported()), so this guards a stub/edge case rather than a normal path — surface
        // it as UNSUPPORTED so the sentinel never reaches the client as a real reading.
        if (rssi == Int.MIN_VALUE) bleError(ErrorKind.UNSUPPORTED, message = "connected RSSI unavailable")
        return rssi
    }

    override suspend fun requestConnectionPriority(device: DeviceHandle, priority: ConnPriority) {
        val peripheral = connectedPeripheral(device)
        val accepted = applyConnParams(peripheral, priority.toConnProfile(), hint = null)
        if (!accepted) bleError(ErrorKind.WRITE_FAILED, message = "connection priority request rejected")
    }

    override suspend fun setConnParams(device: DeviceHandle, profile: ConnProfile, hint: ConnParamHint?) {
        val peripheral = connectedPeripheral(device)
        val accepted = applyConnParams(peripheral, profile, hint)
        if (!accepted) bleError(ErrorKind.WRITE_FAILED, message = "conn-params request rejected")
    }

    private fun ConnPriority.toConnProfile(): ConnProfile = when (this) {
        ConnPriority.LOW_POWER -> ConnProfile.LOW_POWER
        ConnPriority.BALANCED -> ConnProfile.BALANCED
        ConnPriority.HIGH -> ConnProfile.LOW_LATENCY
    }

    override suspend fun readDescriptor(device: DeviceHandle, desc: DescRef): ByteArray {
        val peripheral = connectedPeripheral(device)
        val descriptor = peripheral.findDescriptor(desc)
        return gattOp(ErrorKind.READ_FAILED, "readDescriptor") { peripheral.read(descriptor) }
    }

    override suspend fun writeDescriptor(device: DeviceHandle, desc: DescRef, value: ByteArray) {
        val peripheral = connectedPeripheral(device)
        val descriptor = peripheral.findDescriptor(desc)
        gattOp(ErrorKind.WRITE_FAILED, "writeDescriptor") { peripheral.write(descriptor, value) }
    }

    // --- helpers ---

    /** Runs a Kable op, mapping its failure to [failure] while letting cancellation propagate. */
    private suspend inline fun <T> bleOp(failure: ErrorKind, block: () -> T): T =
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            bleError(failure, message = t.message)
        }

    /**
     * [bleOp] for a single ATT transaction (read/write of a characteristic or descriptor, RSSI),
     * bounded by [GATT_OP_TIMEOUT].
     *
     * The bound exists because a native stack can fail to *complete* a transaction at all rather
     * than completing it with an error: on btleplug/macOS a write-with-response that the peripheral
     * answers with an ATT error never returns and never throws, so an unbounded [bleOp] parks its
     * caller forever. That is not merely slow reporting — [BleAgent] chains same-device writes, and
     * a write that never returns never completes its turn, so *every* later write to that device
     * blocks behind it until the peripheral is disconnected. Nothing else unwedges it: the client's
     * own timeout is client-side only, and the protocol has no cancel op, so the agent-side coroutine
     * is never cancelled. Bounding here lets the `finally` that completes the write turn actually run.
     *
     * Deliberately NOT applied to [connect], which is legitimately long-running and has its own
     * connection-level semantics.
     *
     * Expiry is reported as [ErrorKind.TIMEOUT], not as [failure]: when a transaction never
     * completes, the operation's fate is *unknown* — the peripheral may well have received and
     * applied it. Reporting e.g. WRITE_FAILED would assert "the radio said no", a stronger claim
     * than the evidence supports (see the two ErrorKind groupings in `Errors.kt`). TIMEOUT is the
     * honest "no answer" kind, and it keeps the retry decision safe: it is transient, but
     * `Op.Write.isIdempotent` is false, so a policy still won't blind-retry a possibly-applied write.
     */
    // internal (not private): EngineBleBackendJvmTest exercises the timeout and the mapping
    // directly, which needs no radio — a real hung write reproduces only on hardware.
    internal suspend fun <T> gattOp(failure: ErrorKind, op: String, block: suspend () -> T): T {
        val result = withTimeoutOrNull(GATT_OP_TIMEOUT) {
            runCatching { block() }
        } ?: run {
            Logger.warn(LogTags.ENGINE) { "$op did not complete within $GATT_OP_TIMEOUT; reporting TIMEOUT" }
            bleError(ErrorKind.TIMEOUT, message = "$op did not complete within $GATT_OP_TIMEOUT")
        }
        return result.getOrElse { t ->
            if (t is CancellationException) throw t
            bleError(failure, message = t.message)
        }
    }

    /** Resolves [device] and fails with [ErrorKind.NOT_CONNECTED] unless it is connected. */
    private fun connectedPeripheral(device: DeviceHandle): Peripheral =
        resolve(device).also(::requireConnected)

    private fun requireConnected(peripheral: Peripheral) {
        if (peripheral.state.value !is State.Connected) bleError(ErrorKind.NOT_CONNECTED)
    }

    private fun Peripheral.discoveredService(uuid: Uuid): DiscoveredService {
        val services = services.value ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
        return services.find { it.serviceUuid == uuid } ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
    }

    private fun Peripheral.findCharacteristic(char: CharRef): DiscoveredCharacteristic {
        val characteristicUuid = Uuid.parse(char.characteristic)
        return discoveredService(Uuid.parse(char.service))
            .characteristics
            .filter { it.characteristicUuid == characteristicUuid }
            .getOrNull(char.instance)
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND)
    }

    private fun Peripheral.findDescriptor(desc: DescRef): DiscoveredDescriptor {
        val descriptorUuid = Uuid.parse(desc.descriptor)
        return findCharacteristic(CharRef(desc.service, desc.characteristic))
            .descriptors
            .filter { it.descriptorUuid == descriptorUuid }
            .getOrNull(desc.instance)
            ?: bleError(ErrorKind.CHARACTERISTIC_NOT_FOUND, message = "descriptor not found")
    }

    private fun DiscoveredService.toNode(): ServiceNode = ServiceNode(
        uuid = serviceUuid.toString(),
        characteristics = characteristics.map { it.toNode() },
    )

    // internal (not private): EngineBleBackendJvmTest exercises this directly against a fake
    // DiscoveredCharacteristic to regression-test that property bits survive the mapping on every
    // engine, without needing a real radio connection.
    internal fun DiscoveredCharacteristic.toNode(): CharNode = CharNode(
        uuid = characteristicUuid.toString(),
        properties = properties.value,
        descriptors = descriptors.map { it.descriptorUuid.toString() },
    )

    private companion object {
        /** ATT default minimum MTU (bytes); always safe to advertise when none was negotiated. */
        const val DEFAULT_ATT_MTU = 23

        /** How long [checkLiveness] waits for its probe read before treating the link as dead. */
        val LIVENESS_PROBE_TIMEOUT = 5.seconds

        /**
         * How long a single ATT transaction may run before [gattOp] reports [ErrorKind.TIMEOUT].
         *
         * Chosen to sit below `AgentSession.DEFAULT_TIMEOUT` (15s) so the client receives a real,
         * explained error from the agent instead of expiring on its own with no diagnosis, while
         * still leaving far more headroom than a healthy GATT round-trip needs (milliseconds to a
         * few hundred ms, even on a slow link with a long connection interval).
         */
        val GATT_OP_TIMEOUT = 10.seconds

        /** Client Characteristic Configuration Descriptor UUID — the [checkLiveness] probe of last resort. */
        val CCCD_UUID = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
    }
}
