package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.IdentifierFormat
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.mapDevice
import dev.warsha.remoteble.protocol.PROTOCOL_VERSION
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * The real agent op handler. Decodes `Command`s, drives a [BleBackend], and emits
 * `Reply`/`Event` frames over an opaque byte link (the same seam [FakeAgent] uses,
 * so it drops straight into [AgentWebSocketServer]).
 *
 * Each command runs on its own coroutine so a slow read can't block an
 * `observe.stop`. Shared state (connection slots, subscription jobs) is guarded by
 * a mutex. `DeviceHandle`s are minted by the backend from scan results — the agent
 * never constructs one.
 */
class BleAgent(
    private val incoming: Flow<ByteArray>,
    private val outgoing: suspend (ByteArray) -> Unit,
    private val scope: CoroutineScope,
    private val backend: BleBackend,
    private val codec: ProtocolCodec = CborProtocolCodec(),
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    // Max commands executing concurrently for this client. Caps spawned coroutines so a command
    // flood can't grow memory without bound; the read loop suspends (stops decoding) once hit.
    private val maxInFlightCommands: Int = DEFAULT_MAX_INFLIGHT_COMMANDS,
    // Per-connection id for monitoring/logs (a fresh value each socket).
    private val clientId: Long = 0L,
    private val observer: AgentObserver = AgentObserver.None,
    // Capabilities this agent's backend actually supports. The handshake returns
    // `clientWanted ∩ capabilities`; empty is the v1 baseline (no optional features).
    private val capabilities: Set<String> = emptySet(),
    // Optional human-readable agent identity (engine/platform) surfaced in ServerHello.
    private val agentInfo: String? = null,
    // Cross-client peripheral ownership. Defaults to a private per-instance registry so a
    // standalone agent (and single-client tests) is unconstrained; the shared agent injects
    // the one [PeripheralRegistry] every connection's BleAgent must agree on.
    private val registry: PeripheralRegistry = PeripheralRegistry(scope),
    // Stable client identity (survives reconnects) — the ownership key. Defaults to this
    // connection's id, so a client that sends no handshake id simply never resumes.
    private val clientKey: String = clientId.toString(),
    // Scan-result batching (capability `scan.batch`): flush coalesced advertisements at
    // least this often, or sooner once a burst reaches [scanBatchMaxSize].
    private val scanBatchWindow: Duration = DEFAULT_SCAN_BATCH_WINDOW,
    private val scanBatchMaxSize: Int = DEFAULT_SCAN_BATCH_MAX_SIZE,
    // Agent-wide identifier strict-mode switch (capability `identifier.translate`). Shared across
    // connections and flipped live from the dashboard; read on each forward handle translation.
    private val strictMode: StrictModeState = StrictModeState(),
    // The handle format this agent's radio mints, so translation is skipped for a same-platform peer.
    private val agentFormat: IdentifierFormat = agentIdentifierFormat(),
) {
    private val state = Mutex()
    private val commandLimiter = Semaphore(maxInFlightCommands)
    private val connected = mutableSetOf<String>()
    private val scanJobs = mutableMapOf<Long, Job>()
    private val observeJobs = mutableMapOf<Long, Job>()

    // The capabilities negotiated with this client (clientWanted ∩ supported), set on the
    // handshake. Gates agent→client features the client must opt into to decode — sending an
    // event a client never negotiated would break its decode loop.
    private var negotiated: Set<String> = emptySet()

    // Per-connection device-handle translator (capability `identifier.translate`). Identity until a
    // handshake declares the client's format; then it rewrites outgoing handles into that format and
    // reverse-maps incoming ops. Set on the collect loop (respondHello), read from command/emit
    // coroutines — same benign single-writer pattern as `negotiated` (the client sends hello first).
    private var translator: HandleTranslator = identityTranslator()

    private fun identityTranslator(): HandleTranslator =
        HandleTranslator(clientFormat = null, agentFormat = agentFormat, capabilityNegotiated = false, strict = { false })

    // How the registry reaches this connection to report an unsolicited BLE drop it detected
    // out-of-band (ConnectionWatcher) — an explicit Disconnect op already emits its own event via
    // the disconnect() path below, so this only ever fires for the other case. Declared once so
    // both registerClient (below) and unregisterClient (in invokeOnCompletion) share one instance.
    //
    // The caller is the *shared, agent-wide* ConnectionWatcher loop, so the work runs
    // fire-and-forget on this connection's own scope, wrapped so nothing it does can propagate
    // back: emit() ultimately writes to this client's WebSocket, and a send that throws (socket
    // already closing) or suspends (slow consumer) must not stall or tear down the watchdog that
    // monitors every other client's peripherals. Any failure here is contained to this client.
    private val onUnsolicitedDisconnect: suspend (handle: String) -> Unit = { handle ->
        scope.launch {
            try {
                state.withLock { connected -= handle }
                observer.onDeviceDisconnected(clientId, handle)
                observer.onClientLog(clientId, "unsolicited disconnect: $handle")
                emit(AgentEvent.ConnectionState(DeviceHandle(handle), BleConnState.DISCONNECTED))
                translator.evict(handle)
                emitSlotsIfNegotiated()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                observer.onClientLog(clientId, "failed to deliver unsolicited disconnect for $handle: ${t.message}")
            }
        }
    }

    fun start(): Job = scope.launch {
        registry.registerClient(clientKey, onUnsolicitedDisconnect)
        incoming.collect { bytes ->
            // A single malformed/truncated frame must not fail the collect and tear down
            // this client's whole session. Decode defensively: log the bad frame and skip
            // it, staying ready for the next valid one.
            val frame = try {
                codec.decode(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                observer.onClientLog(clientId, "dropped undecodable frame (${bytes.size} bytes): ${e.message}")
                return@collect
            }
            when (frame) {
                // Per-command coroutine: independent ops proceed concurrently. Acquire a permit
                // first so the collect suspends (backpressuring the link) once too many commands
                // are in flight; the permit is released when the command's coroutine finishes.
                is Command -> {
                    commandLimiter.acquire()
                    scope.launch {
                        try {
                            handle(frame)
                        } finally {
                            commandLimiter.release()
                        }
                    }
                }
                is ClientHello -> respondHello(frame)
                else -> Unit // agent never receives Reply/Event/ServerHello
            }
        }
    }.also { main ->
        // When the main job ends, the transport (WebSocket) is gone. Don't tear the radio down
        // yet: hand off to the registry, which keeps this client's links warm for the transport
        // grace so a brief blip can resume, then releases (and disconnects) them on expiry.
        main.invokeOnCompletion {
            registry.onTransportDropped(clientKey)
            registry.unregisterClient(clientKey, onUnsolicitedDisconnect)
        }
    }

    private suspend fun handle(cmd: Command) {
        try {
            // Reverse-translate the op's client-facing handle back to the real radio handle up front,
            // so connection tracking, the registry, and the backend all deal only in real handles.
            // (mapDevice is inline, so the suspend toReal call is legal here.)
            when (val op = cmd.op.mapDevice { DeviceHandle(translator.toReal(it.value)) }) {
                is Op.Connect -> reply(cmd.cid, connect(op.device))
                is Op.Disconnect -> reply(cmd.cid, disconnect(op.device))
                is Op.Discover -> reply(cmd.cid, OpResult.Ok(ResultPayload.Services(backend.discover(op.device))))
                is Op.Read -> reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(backend.read(op.device, op.char))))
                is Op.Write -> {
                    backend.write(op.device, op.char, op.value, op.withResponse)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.RequestMtu -> reply(cmd.cid, OpResult.Ok(ResultPayload.Mtu(backend.requestMtu(op.device, op.mtu))))
                is Op.ReadDescriptor ->
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(backend.readDescriptor(op.device, op.desc))))
                is Op.WriteDescriptor -> {
                    backend.writeDescriptor(op.device, op.desc, op.value)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.Pair -> {
                    val state = backend.pair(op.device)
                    emit(AgentEvent.BondState(op.device, state))
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Bond(state)))
                }
                is Op.Unpair -> {
                    backend.unpair(op.device)
                    emit(AgentEvent.BondState(op.device, BleBondState.NONE))
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.RequestConnectionPriority -> {
                    backend.requestConnectionPriority(op.device, op.priority)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ScanStart -> {
                    startScan(op.scanId, op)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ScanStop -> {
                    stopScan(op.scanId)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ObserveStart -> {
                    startObserve(op.subId, op.device, op)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ObserveStop -> {
                    stopObserve(op.subId)
                    reply(cmd.cid, OpResult.Ok())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentException) {
            // A deliberate, mapped backend failure: its message is domain-level, safe to return.
            reply(cmd.cid, OpResult.Err(e.error))
        } catch (e: Throwable) {
            // An *unexpected* failure: log the detail server-side and return a generic GATT error
            // rather than leaking raw internal exception text to the client.
            observer.onClientLog(clientId, "op ${cmd.op::class.simpleName} failed unexpectedly: ${e.message}")
            reply(cmd.cid, OpResult.Err(AgentError(ErrorKind.GATT_ERROR, message = "internal error")))
        }
    }

    private suspend fun connect(device: DeviceHandle): OpResult {
        // Already connected by this client: idempotent success, no cross-client check.
        if (state.withLock { device.value in connected }) return OpResult.Ok()

        // Cross-client ownership gate: another client holds an exclusive peripheral.
        when (registry.acquire(device.value, clientKey)) {
            is PeripheralRegistry.Acquisition.Denied ->
                return OpResult.Err(AgentError(ErrorKind.PERIPHERAL_BUSY, message = "peripheral in use"))
            PeripheralRegistry.Acquisition.Granted -> Unit
        }

        // Per-client slot reservation (the cap is per session, not the lease).
        val reserved = state.withLock {
            when {
                connected.size >= maxConnections -> false
                else -> {
                    connected += device.value // reserve before the (slow) connect
                    true
                }
            }
        }
        if (!reserved) {
            registry.releaseNow(device.value, clientKey) // give the lease back; never connected
            return OpResult.Err(AgentError(ErrorKind.NO_CONNECTION_SLOT))
        }

        try {
            backend.connect(device)
        } catch (e: Throwable) {
            state.withLock { connected -= device.value } // release on failure
            registry.releaseNow(device.value, clientKey)
            observer.onClientLog(clientId, "connect failed: ${device.value} (${e.message})")
            emitSlotsIfNegotiated() // slot restored
            throw e
        }
        registry.onConnected(device.value, clientKey)
        observer.onDeviceConnected(clientId, device.value)
        observer.onClientLog(clientId, "connected ${device.value}")
        emit(AgentEvent.ConnectionState(device, BleConnState.CONNECTED))
        emitSlotsIfNegotiated()
        return OpResult.Ok()
    }

    private suspend fun disconnect(device: DeviceHandle): OpResult {
        backend.disconnect(device)
        state.withLock { connected -= device.value }
        // Start the grace timer rather than releasing the lease now: a quick reconnect by this
        // client keeps the peripheral; only a sustained disconnect frees it for others.
        registry.onDisconnected(device.value, clientKey)
        observer.onDeviceDisconnected(clientId, device.value)
        observer.onClientLog(clientId, "disconnected ${device.value}")
        emit(AgentEvent.ConnectionState(device, BleConnState.DISCONNECTED))
        translator.evict(device.value)
        emitSlotsIfNegotiated()
        return OpResult.Ok()
    }

    private suspend fun startScan(scanId: Long, op: Op.ScanStart) {
        val batched = Capabilities.SCAN_BATCH in negotiated
        val coalesce = advertisementCoalescer()
        val job = scope.launch {
            if (batched) runBatchedScan(scanId, op, coalesce) else runPerResultScan(scanId, op, coalesce)
        }
        state.withLock { scanJobs.put(scanId, job) }?.cancel()
        observer.onClientLog(clientId, "scan started (#$scanId${if (batched) ", batched" else ""})")
    }

    private suspend fun runPerResultScan(
        scanId: Long,
        op: Op.ScanStart,
        coalesce: (AdvertisementDto) -> AdvertisementDto,
    ) {
        backend.scan(op.filters)
            // A backend scan failure ends this stream, not the agent. Log it (don't swallow
            // silently) so the operator can see a radio that stopped scanning; the client
            // observes the absence of results and can re-issue scan.start.
            .catch { observer.onClientLog(clientId, "scan #$scanId ended on error: ${it.message}") }
            .collect { raw ->
                val ad = coalesce(raw)
                observer.onDeviceSeen(ad.device.value, ad.name)
                emit(AgentEvent.ScanResult(scanId, ad))
            }
    }

    /**
     * Per-scan name/service-UUID coalescing. Advertisements for one device arrive repeatedly and
     * individual packets routinely omit the local name or service UUIDs (a bare RSSI refresh is
     * normal); forwarding such a sparse packet verbatim makes the device flicker to "unnamed" on
     * the client. This fills the missing fields from the last packet that carried them, so every
     * result the client sees has the best-known identity. RSSI is passed through unchanged — it
     * legitimately varies per packet. The maps are touched only from the single scan collector.
     */
    private fun advertisementCoalescer(): (AdvertisementDto) -> AdvertisementDto {
        val lastName = HashMap<String, String>()
        val lastUuids = HashMap<String, List<String>>()
        return { ad ->
            val key = ad.device.value
            ad.name?.let { lastName[key] = it }
            if (ad.serviceUuids.isNotEmpty()) lastUuids[key] = ad.serviceUuids
            AdvertisementDto(
                device = ad.device,
                name = ad.name ?: lastName[key],
                rssi = ad.rssi,
                serviceUuids = ad.serviceUuids.ifEmpty { lastUuids[key].orEmpty() },
                manufacturerData = ad.manufacturerData,
            )
        }
    }

    /**
     * Coalesces advertisements into [AgentEvent.ScanResultBatch]es: a buffer flushed every
     * [scanBatchWindow] (so results are never held longer than that) or immediately once a
     * burst reaches [scanBatchMaxSize] (so a flood can't grow unbounded).
     */
    private suspend fun runBatchedScan(
        scanId: Long,
        op: Op.ScanStart,
        coalesce: (AdvertisementDto) -> AdvertisementDto,
    ): Unit = coroutineScope {
        val buffer = mutableListOf<AdvertisementDto>()
        val bufLock = Mutex()
        suspend fun flush() {
            val batch = bufLock.withLock {
                if (buffer.isEmpty()) null else buffer.toList().also { buffer.clear() }
            }
            if (batch != null) emit(AgentEvent.ScanResultBatch(scanId, batch))
        }
        val flusher = launch {
            while (isActive) {
                delay(scanBatchWindow)
                flush()
            }
        }
        try {
            backend.scan(op.filters)
                .catch { observer.onClientLog(clientId, "scan #$scanId ended on error: ${it.message}") }
                .collect { raw ->
                    val ad = coalesce(raw)
                    observer.onDeviceSeen(ad.device.value, ad.name)
                    val full = bufLock.withLock { buffer.add(ad); buffer.size >= scanBatchMaxSize }
                    if (full) flush()
                }
        } finally {
            flusher.cancel()
        }
    }

    private suspend fun stopScan(scanId: Long) {
        state.withLock { scanJobs.remove(scanId) }?.cancel()
        observer.onClientLog(clientId, "scan stopped (#$scanId)")
    }

    private suspend fun startObserve(subId: Long, device: DeviceHandle, op: Op.ObserveStart) {
        val job = backend.observe(device, op.char)
            .onEach { emit(AgentEvent.Notification(subId, it)) }
            // A subscription failure ends this stream, not the agent. Log it rather than
            // swallowing silently; the client sees notifications stop and can re-subscribe.
            .catch { observer.onClientLog(clientId, "observe #$subId ended on error: ${it.message}") }
            .launchIn(scope)
        state.withLock { observeJobs.put(subId, job) }?.cancel()
    }

    private suspend fun stopObserve(subId: Long) {
        state.withLock { observeJobs.remove(subId) }?.cancel()
    }

    /** Answer the client's handshake with the negotiated capability set (intersection). */
    private suspend fun respondHello(hello: ClientHello) {
        negotiated = hello.capabilities intersect capabilities
        translator = HandleTranslator(
            clientFormat = hello.identifierFormat,
            agentFormat = agentFormat,
            capabilityNegotiated = Capabilities.IDENTIFIER_TRANSLATION in negotiated,
            strict = { strictMode.enabled },
        )
        outgoing(
            codec.encode(
                ServerHello(version = PROTOCOL_VERSION, capabilities = negotiated, agentInfo = agentInfo),
            ),
        )
        observer.onClientLog(clientId, "handshake: v${hello.maxVersion}, capabilities=$negotiated")
    }

    /** Reports free/total connection slots — but only to a client that negotiated `slots`. */
    private suspend fun emitSlotsIfNegotiated() {
        if (Capabilities.CONNECTION_SLOTS !in negotiated) return
        val free = state.withLock { maxConnections - connected.size }
        emit(AgentEvent.SlotState(free = free, total = maxConnections))
    }

    private suspend fun reply(cid: Long, result: OpResult) = outgoing(codec.encode(Reply(cid, result)))

    // Forward-translate any real handle the event carries into the client's declared format before
    // it goes on the wire (identity when translation isn't active).
    private suspend fun emit(event: AgentEvent) = outgoing(codec.encode(Event(translator.outgoing(event))))

    companion object {
        const val DEFAULT_MAX_CONNECTIONS: Int = 4
        const val DEFAULT_MAX_INFLIGHT_COMMANDS: Int = 64
        val DEFAULT_SCAN_BATCH_WINDOW: Duration = 100.milliseconds
        const val DEFAULT_SCAN_BATCH_MAX_SIZE: Int = 16

        /**
         * Capabilities the agent implements itself, independent of the radio backend
         * (the backend contributes its own — descriptors, pairing). Unioned into the
         * advertised set by [BleAgentBackend].
         */
        val AGENT_CAPABILITIES: Set<String> =
            setOf(Capabilities.CONNECTION_SLOTS, Capabilities.SCAN_BATCH, Capabilities.IDENTIFIER_TRANSLATION)
    }
}
