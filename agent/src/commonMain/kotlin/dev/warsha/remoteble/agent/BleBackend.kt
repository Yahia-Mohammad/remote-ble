package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ConnPriority
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import kotlinx.coroutines.flow.Flow

/**
 * The physical BLE operations, decoupled from the wire protocol. [BleAgent] maps
 * incoming `Op`s onto this; the real implementation ([EngineBleBackend]) drives
 * Kable's native (btleplug) stack, while tests use a deterministic fake.
 *
 * Failures are reported by throwing [AgentException] with an appropriate
 * [ErrorKind]; [BleAgent] turns those into `OpResult.Err`. The "reached the radio
 * vs didn't" distinction (see [ErrorKind]) is the backend's responsibility.
 */
interface BleBackend {
    /**
     * The optional [Capabilities] this backend can actually service, advertised in the
     * handshake. The agent is the source of truth — a backend only names a capability
     * here if its corresponding ops are implemented (the defaults below stay
     * [ErrorKind.UNSUPPORTED]). Defaults to none (the v1 baseline).
     */
    val capabilities: Set<String> get() = emptySet()

    /** Streams advertisements while collected; stops scanning on cancellation. */
    fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto>

    suspend fun connect(device: DeviceHandle)

    suspend fun disconnect(device: DeviceHandle)

    suspend fun discover(device: DeviceHandle): List<ServiceNode>

    suspend fun read(device: DeviceHandle, char: CharRef): ByteArray

    suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean)

    /** Streams notifications while collected; unsubscribes on cancellation. */
    fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray>

    suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int

    /**
     * Reads a descriptor value (capability: `descriptors`). Optional: the default
     * reports [ErrorKind.UNSUPPORTED] so a backend that doesn't implement it — and an
     * agent that doesn't advertise the capability — degrade cleanly. Override to support.
     */
    suspend fun readDescriptor(device: DeviceHandle, desc: DescRef): ByteArray =
        bleError(ErrorKind.UNSUPPORTED, message = "descriptor reads not supported")

    /** Writes a descriptor value (capability: `descriptors`). See [readDescriptor]. */
    suspend fun writeDescriptor(device: DeviceHandle, desc: DescRef, value: ByteArray): Unit =
        bleError(ErrorKind.UNSUPPORTED, message = "descriptor writes not supported")

    /**
     * Creates a bond with the peripheral and returns the resulting state (capability:
     * `pairing`). Optional, like the descriptor ops: the default reports
     * [ErrorKind.UNSUPPORTED]. Platforms where bonding is implicit/uncontrollable (e.g.
     * CoreBluetooth on macOS, and the btleplug stack generally) leave this unimplemented and
     * don't advertise the capability; a backend with real bonding control overrides it.
     */
    suspend fun pair(device: DeviceHandle): BleBondState =
        bleError(ErrorKind.UNSUPPORTED, message = "pairing not supported")

    /** Removes the bond with the peripheral (capability: `pairing`). See [pair]. */
    suspend fun unpair(device: DeviceHandle): Unit =
        bleError(ErrorKind.UNSUPPORTED, message = "unpair not supported")

    /**
     * Requests a link connection priority (capability: `conn.priority`). Optional, like
     * the pairing ops; the default reports [ErrorKind.UNSUPPORTED]. Android-only in
     * practice — Apple platforms ignore it, so the macOS backend doesn't advertise it.
     */
    suspend fun requestConnectionPriority(device: DeviceHandle, priority: ConnPriority): Unit =
        bleError(ErrorKind.UNSUPPORTED, message = "connection priority not supported")

    /** Checks if the peripheral is currently connected. */
    fun isConnected(device: DeviceHandle): Boolean = false

    /**
     * An active liveness probe beyond [isConnected]'s cached state: attempts a real round-trip
     * so a dead link is caught even when the platform hasn't (yet) reported a disconnect — e.g.
     * the peripheral vanished without a clean BLE-level teardown (crashed, force-stopped, walked
     * out of range), which can leave [isConnected] reporting `true` until an LL supervision
     * timeout that's tens of seconds or effectively unbounded. [ConnectionWatcher] calls this far
     * less often than [isConnected] since it may do real I/O. Default: delegates to [isConnected]
     * for backends with nothing extra to probe (fakes, tests, the blackhole backend).
     */
    suspend fun checkLiveness(device: DeviceHandle): Boolean = isConnected(device)
}

/** Convenience for backends to fail an op with a specific [ErrorKind]. */
internal fun bleError(
    kind: ErrorKind,
    gattStatus: Int? = null,
    message: String? = null,
): Nothing = throw AgentException(AgentError(kind, gattStatus, message))
