package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.BleBondState
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.BleRadioState
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.IdentifierFormat
import dev.warsha.remoteble.protocol.LeaseStatusDto
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.mapDevice
import dev.warsha.remoteble.protocol.PROTOCOL_VERSION
import dev.warsha.remoteble.protocol.ProtocolVersionSelection
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServerHello
import dev.warsha.remoteble.protocol.StatusSettingsDto
import dev.warsha.remoteble.protocol.StatusSlotsDto
import dev.warsha.remoteble.protocol.selectProtocolVersion
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

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
    // Streaming resources are independently bounded: their jobs persist after their start command
    // replies, so command concurrency cannot cap them.
    private val maxActiveScans: Int = MAX_ACTIVE_SCANS,
    private val maxActiveObservations: Int = MAX_ACTIVE_OBSERVATIONS,
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
    private val registry: PeripheralRegistry = PeripheralRegistry(scope, maxSlots = maxConnections),
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
    private val agentFormat: IdentifierFormat = agentIdentifierFormat(),
    // Shared by every socket only in the guaranteed modes. Null preserves the standalone/test
    // agent's historical connection-local scan behaviour.
    private val scanCoordinator: ScanCoordinator? = null,
    // Agent-wide observations `agent.status` reports but no single connection owns: uptime, the
    // cross-client connection count, and last-seen advertised names. Null in a standalone/test agent
    // that runs no monitor, which narrows the status reply rather than failing it.
    private val monitor: AgentMonitor? = null,
    // Whether this connection presented a valid operator credential on the upgrade (OPERATOR_HEADER).
    // Widens `agent.status` disclosure to every lease and its holder; nothing else consults it.
    private val operatorScope: Boolean = false,
    // Per-principal write allowlist (U7): the only real control on writes, since CLI-side policy is
    // advisory (it lives in a file the calling agent can edit). Permissive by default so nothing
    // changes for a consumer that never configures one.
    private val writePolicy: WritePolicy = WritePolicy.permissive(),
) {
    init {
        require(maxActiveScans in 1..MAX_ACTIVE_SCANS) { "maxActiveScans must be 1..$MAX_ACTIVE_SCANS" }
        require(maxActiveObservations in 1..MAX_ACTIVE_OBSERVATIONS) {
            "maxActiveObservations must be 1..$MAX_ACTIVE_OBSERVATIONS"
        }
    }

    // The principal half of [clientKey], for write-policy lookups — computed once since clientKey
    // never changes for this connection's lifetime.
    private val principal: String by lazy { ClientCredentials.principalOf(clientKey) }

    private val state = Mutex()
    private val commandLimiter = Semaphore(maxInFlightCommands)
    private val connected = mutableSetOf<String>()
    private data class ManagedScan(
        val job: Job,
        val registration: ScanRegistration?,
        val sink: ScanOutboundArbiter.Sink? = null,
    )
    private val scanJobs = mutableMapOf<Long, ManagedScan>()
    // Steady-state depth only. The replay reservation lives once, in the coordinator's own
    // mailbox; the collector below hands events on with a suspending send, so a slow arbiter
    // backpressures into that single bounded reservation instead of needing a second copy of it.
    private val scanArbiter = ScanOutboundArbiter(scope, ::emit)
    private val observeJobs = mutableMapOf<Long, Job>()

    // Per-device write ordering (0.8.3 / feature C). Every command runs on its own coroutine, so a
    // pipelined write-without-response burst would otherwise race into `backend.write` out of order
    // and defeat the radio's FIFO GATT queue. We chain writes per real device handle: each write
    // awaits the previous write's completion before touching the backend. The chain is *built* in
    // the sequential decode loop ([reserveWriteTurn]), so it links writes in submission order even
    // though the coroutines that drain it run concurrently. Only writes to the *same* device
    // serialize — reads and writes to other devices stay fully concurrent. Guarded by [writeChain].
    private val writeChain = Mutex()
    private val writeChainTails = mutableMapOf<String, CompletableDeferred<Unit>>()

    // Scan lifecycle commands are stateful for one connection-local scan id.  Commands still run
    // concurrently overall, but same-id start/stop turns are reserved on the decode loop so their
    // coordinator admission and local binding publication follow WebSocket receive order.
    private val scanChain = Mutex()
    private val scanChainTails = mutableMapOf<Long, CompletableDeferred<Unit>>()

    /** In-flight scan-lifecycle turns. Test seam for the bound on [scanChainTails]. */
    internal suspend fun pendingScanTurns(): Int = scanChain.withLock { scanChainTails.size }

    /** A write's slot in its device's ordering chain: await [predecessor], then complete [mine]. */
    private class WriteTurn(val predecessor: CompletableDeferred<Unit>?, val mine: CompletableDeferred<Unit>)
    private class ScanTurn(
        val scanId: Long,
        val predecessor: CompletableDeferred<Unit>?,
        val mine: CompletableDeferred<Unit>,
    )

    /**
     * Reserves the next turn in [realDevice]'s write chain, returning the predecessor to await (or
     * null if none is pending) and this write's completion signal. **Must be called from the decode
     * loop, in submission order**, so the chain reflects that order rather than coroutine-launch race.
     */
    private suspend fun reserveWriteTurn(realDevice: String): WriteTurn = writeChain.withLock {
        val predecessor = writeChainTails[realDevice]?.takeUnless { it.isCompleted }
        val mine = CompletableDeferred<Unit>()
        writeChainTails[realDevice] = mine
        WriteTurn(predecessor, mine)
    }

    /** Drops a device's write-chain tail once it can hold no more successors (on disconnect). */
    private suspend fun evictWriteChain(realDevice: String) = writeChain.withLock {
        writeChainTails.remove(realDevice)
    }

    /** Reserves a [WriteTurn] for a `Command` iff it's an [Op.Write]; other ops don't order. */
    private suspend fun reserveWriteTurnFor(frame: Command): WriteTurn? {
        val op = frame.op
        if (op !is Op.Write) return null
        return reserveWriteTurn(translator.toReal(op.device.value))
    }

    private suspend fun reserveScanTurn(scanId: Long): ScanTurn = scanChain.withLock {
        val predecessor = scanChainTails[scanId]?.takeUnless { it.isCompleted }
        val mine = CompletableDeferred<Unit>()
        scanChainTails[scanId] = mine
        ScanTurn(scanId, predecessor, mine)
    }

    /**
     * Drops a scan id's tail once this turn is the last one holding it. `scanId` is client-chosen
     * and a turn is reserved for every `scan.start`/`scan.stop` frame — including ones later
     * refused — so without this the map would retain an entry per distinct id the connection has
     * ever sent. The identity check keeps a successor's tail intact, mirroring `release_scan_tail`
     * in the Rust agent.
     */
    private suspend fun releaseScanTurn(turn: ScanTurn) = scanChain.withLock {
        if (scanChainTails[turn.scanId] === turn.mine) scanChainTails.remove(turn.scanId)
    }

    /** Reserves same-id scan lifecycle order on the sequential decode loop. */
    private suspend fun reserveScanTurnFor(frame: Command): ScanTurn? = when (val op = frame.op) {
        is Op.ScanStart -> reserveScanTurn(op.scanId)
        is Op.ScanStop -> reserveScanTurn(op.scanId)
        else -> null
    }

    // The capabilities negotiated with this client (clientWanted ∩ supported), set once by the
    // first handshake. Gates agent→client features the client must opt into to decode — sending an
    // event a client never negotiated would break its decode loop. @Volatile: written on the
    // collect loop, but read from concurrently launched command coroutines and from the shared
    // ConnectionWatcher's unsolicited-disconnect callback (another thread with no happens-before
    // edge to the hello write). A well-behaved client sends hello before any command; visibility
    // must not depend on that.
    @Volatile
    private var negotiated: Set<String> = emptySet()

    // Per-connection device-handle translator (capability `identifier.translate`). Identity until
    // the first handshake declares the client's format; then it rewrites outgoing handles into that
    // format and reverse-maps incoming ops. Written once (first hello, on the collect loop), read
    // from command/emit coroutines and the watcher callback — @Volatile for the same reason as
    // [negotiated]. Never swapped after that first write: replacing the instance would orphan its
    // reverse map and break routing for handles the client already holds (see [respondHello]).
    @Volatile
    private var translator: HandleTranslator = identityTranslator()

    // Whether a ClientHello has been honored on this connection. Touched only on the sequential
    // collect loop ([respondHello]), so it needs no synchronization of its own.
    private var handshaken = false

    // Whether this connection's transport is still live. Flipped false the instant the main job
    // ends (transport gone) — before the registry schedules lease grace — so a slow connect that
    // completes after retirement is not committed onto an abandoned lease. @Volatile: written on the
    // main job's completion callback, read from concurrently launched command coroutines.
    @Volatile
    private var connectionLive: Boolean = true

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
    private val onUnsolicitedDisconnect: suspend (handle: String, reason: AgentError?) -> Unit = { handle, reason ->
        scope.launch {
            try {
                state.withLock { connected -= handle }
                observer.onDeviceDisconnected(clientId, handle)
                observer.onClientLog(clientId, "unsolicited disconnect: $handle")
                Logger.info(LogTags.ENGINE) { "unsolicited disconnect [c=$clientId dev=$handle reason=${reason?.message}]" }
                emit(AgentEvent.ConnectionState(DeviceHandle(handle), BleConnState.DISCONNECTED, reason = reason))
                translator.evict(handle)
                evictWriteChain(handle)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                observer.onClientLog(clientId, "failed to deliver unsolicited disconnect for $handle: ${t.message}")
            }
        }
    }

    fun start(): Job = scope.launch {
        registry.registerClient(clientKey, onUnsolicitedDisconnect)
        // Command work must retire before this connection is detached from the agent-lifetime
        // coordinator. Otherwise a sibling start coroutine can resurrect a scan after grace has
        // been scheduled for this socket generation.
        val commandSupervisor = SupervisorJob(coroutineContext[Job])
        val commandScope = CoroutineScope(coroutineContext + commandSupervisor)
        try {
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
                Logger.warn(LogTags.SERVER) { "dropped undecodable frame (${bytes.size} bytes) [c=$clientId]: ${e.message}" }
                return@collect
            }
            when (frame) {
                // Per-command coroutine: independent ops proceed concurrently. Acquire a permit
                // first so the collect suspends (backpressuring the link) once too many commands
                // are in flight; the permit is released when the command's coroutine finishes.
                is Command -> {
                    commandLimiter.acquire()
                    // Reserve the write's ordering turn HERE, on the sequential decode loop, so a
                    // burst chains in submission order before the concurrent coroutines pick it up.
                    val writeTurn = reserveWriteTurnFor(frame)
                    val scanTurn = reserveScanTurnFor(frame)
                    commandScope.launch {
                        try {
                            scanTurn?.predecessor?.await()
                            handle(frame, writeTurn)
                        } finally {
                            // Complete first so a waiting successor proceeds, then drop the tail
                            // if no successor took it. NonCancellable because teardown cancels
                            // these coroutines and the release still has to reclaim the entry.
                            //
                            // The write turn is completed here as well as at the backend hand-off
                            // in `handle`: any path that leaves `handle` without reaching the
                            // Write branch (a retired connection, an authorization failure, an
                            // early error reply) must still release the successor rather than
                            // leave the device's chain waiting on a turn that will never signal.
                            // `complete` is idempotent, so the early in-branch release still wins
                            // when it happens.
                            writeTurn?.mine?.complete(Unit)
                            scanTurn?.mine?.complete(Unit)
                            scanTurn?.let { withContext(NonCancellable) { releaseScanTurn(it) } }
                            commandLimiter.release()
                        }
                    }
                }
                is ClientHello -> respondHello(frame)
                else -> Unit // agent never receives Reply/Event/ServerHello
            }
            }
        } finally {
            // Retire this connection before the registry schedules grace, so a connect completing
            // in the teardown window sees a dead connection and leaves its lease to the grace path.
            connectionLive = false
            withContext(NonCancellable) {
                commandSupervisor.cancelAndJoin()
                // The coordinator owns an agent-lifetime scope, so this cleanup survives the
                // retiring connection. Registration fencing makes stale actions harmless.
                scanCoordinator?.detachConnection(clientId)
                val retiringScans = state.withLock {
                    scanJobs.values.toList().also { scanJobs.clear() }
                }
                for (scan in retiringScans) {
                    scan.sink?.let { scanArbiter.unregister(it) }
                }
                retiringScans.forEach { it.job.cancel() }
                retiringScans.map { it.job }.joinAll()
                registry.onTransportDropped(clientKey)
                registry.unregisterClient(clientKey, onUnsolicitedDisconnect)
            }
            scanArbiter.close()
        }
    }

    private suspend fun handle(cmd: Command, writeTurn: WriteTurn? = null) {
        try {
            // The transport is already gone, so there is nobody to reply to. Ordering turns are
            // released by the caller's `finally`, which runs on this path too.
            if (!connectionLive) return
            // Reverse-translate the op's client-facing handle back to the real radio handle up front,
            // so connection tracking, the registry, and the backend all deal only in real handles.
            // (mapDevice is inline, so the suspend toReal call is legal here.)
            val op = cmd.op.mapDevice { DeviceHandle(translator.toReal(it.value)) }
            operationLimitError(op)?.let {
                reply(cmd.cid, OpResult.Err(it))
                return
            }
            radioUnavailableError(op)?.let {
                reply(cmd.cid, OpResult.Err(it))
                return
            }
            when (op) {
                is Op.Connect -> reply(cmd.cid, connect(op.device))
                is Op.Disconnect -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, disconnect(op.device))
                }
                is Op.Discover -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Services(backend.discover(op.device))))
                }
                is Op.Read -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(backend.read(op.device, op.char))))
                }
                is Op.Write -> {
                    authorizeConnected(op.device)
                    // Honor this write's ordering turn: wait for the prior same-device write to reach
                    // the backend, then complete our signal for the next — even on failure/cancel, so
                    // a rejected write never wedges the chain. See [reserveWriteTurn].
                    try {
                        writeTurn?.predecessor?.await()
                        // Inside the turn, not before it: a denial still runs the `finally` below,
                        // so a policy-refused write cannot wedge the next write to this device the
                        // way throwing ahead of this block would.
                        if (!writePolicy.authorizesWrite(principal, op.char.service, op.char.characteristic, op.value.size, op.withResponse)) {
                            throw AgentException(policyDeniedError("write not permitted for this principal"))
                        }
                        backend.write(op.device, op.char, op.value, op.withResponse)
                        reply(cmd.cid, OpResult.Ok())
                    } finally {
                        writeTurn?.mine?.complete(Unit)
                    }
                }
                is Op.RequestMtu -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Mtu(backend.requestMtu(op.device, op.mtu))))
                }
                is Op.ReadRssi -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Rssi(backend.readRssi(op.device))))
                }
                is Op.ReadDescriptor -> {
                    authorizeConnected(op.device)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Bytes(backend.readDescriptor(op.device, op.desc))))
                }
                is Op.WriteDescriptor -> {
                    authorizeConnected(op.device)
                    if (!writePolicy.authorizesDescriptorWrite(
                            principal,
                            op.desc.service,
                            op.desc.characteristic,
                            op.desc.descriptor,
                            op.value.size,
                        )
                    ) {
                        throw AgentException(policyDeniedError("descriptor write not permitted for this principal"))
                    }
                    backend.writeDescriptor(op.device, op.desc, op.value)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.Pair -> {
                    authorizeConnected(op.device)
                    if (!writePolicy.authorizesPairing(principal)) {
                        throw AgentException(policyDeniedError("pairing not permitted for this principal"))
                    }
                    val state = backend.pair(op.device)
                    emitBondStateIfNegotiated(op.device, state)
                    reply(cmd.cid, OpResult.Ok(ResultPayload.Bond(state)))
                }
                is Op.Unpair -> {
                    authorizeConnected(op.device)
                    if (!writePolicy.authorizesPairing(principal)) {
                        throw AgentException(policyDeniedError("pairing not permitted for this principal"))
                    }
                    backend.unpair(op.device)
                    emitBondStateIfNegotiated(op.device, BleBondState.NONE)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.RequestConnectionPriority -> {
                    authorizeConnected(op.device)
                    backend.requestConnectionPriority(op.device, op.priority)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.SetConnParams -> {
                    authorizeConnected(op.device)
                    backend.setConnParams(op.device, op.profile, op.hint)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ScanStart -> replyThenDeliver(cmd.cid) { startScan(op.scanId, op) }
                is Op.ScanStop -> {
                    stopScan(op.scanId)
                    reply(cmd.cid, OpResult.Ok())
                }
                is Op.ObserveStart -> {
                    authorizeConnected(op.device)
                    replyThenDeliver(cmd.cid) { startObserve(op.subId, op.device, op) }
                }
                is Op.ObserveStop -> {
                    stopObserve(op.subId)
                    reply(cmd.cid, OpResult.Ok())
                }
                // Names no device, so there is nothing to authorize against a lease: what a caller
                // may see is decided inside, by who it is.
                Op.AgentStatus -> reply(cmd.cid, OpResult.Ok(ResultPayload.Status(agentStatus())))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentException) {
            // A deliberate, mapped backend failure: its message is domain-level, safe to return.
            reply(cmd.cid, OpResult.Err(e.error))
        } catch (e: Throwable) {
            observer.onClientLog(clientId, "op ${cmd.op::class.simpleName} failed unexpectedly: ${e.message}")
            Logger.error(LogTags.ENGINE, e) { "op ${cmd.op::class.simpleName} failed unexpectedly [c=$clientId cid=${cmd.cid}]" }
            reply(cmd.cid, OpResult.Err(AgentError(ErrorKind.GATT_ERROR, message = "internal error")))
        }
    }

    /**
     * Builds this caller's view of the agent for [Op.AgentStatus].
     *
     * Disclosure is decided here rather than at the client, because only the agent knows who is
     * asking: an ordinary caller sees the leases its own session key holds, and everything else
     * becomes `otherLeases` plus the aggregate slot count — enough to answer "can I connect?"
     * without naming another tenant. A caller that presented operator scope sees every lease and its
     * holder, the same plane the dashboard serves over HTTP.
     *
     * Lease handles go out through [translator], so a handle read here is routable in the caller's
     * next op without a scan first — the whole point of U6.
     */
    private suspend fun agentStatus(): AgentStatusDto {
        val settings = registry.settings()
        val leases = registry.snapshot()
        val visible = if (operatorScope) leases else leases.filter { it.owner == clientKey }
        return AgentStatusDto(
            agentInfo = agentInfo,
            uptimeMs = monitor?.uptimeMs() ?: 0L,
            settings = StatusSettingsDto(
                leaseGraceMs = settings.leaseGraceMs,
                transportGraceMs = settings.transportGraceMs,
                exclusiveByDefault = settings.defaultExclusive,
                // The mode is already pinned by the single `scan.concurrency.*` capability this
                // agent advertises, so it is read back from there rather than threading the config
                // through a second path that could disagree with what the handshake said.
                scanConcurrency = ScanConcurrencyMode.entries
                    .firstOrNull { it.capability in capabilities }
                    ?.name?.lowercase()
                    ?: ScanConcurrencyMode.UNCONTROLLED.name.lowercase(),
                strictIdentifiers = strictMode.enabled,
                writePolicyEnforced = writePolicy.enforced,
            ),
            slots = StatusSlotsDto(
                free = (registry.totalSlots - registry.occupiedSlots.value).coerceAtLeast(0),
                total = registry.totalSlots,
            ),
            // With no monitor there is no cross-client view; this connection is the one client the
            // agent can account for, and reporting it is honest where reporting zero would not be.
            connectedClients = monitor?.connectedClients() ?: 1,
            leases = visible.map { lease ->
                LeaseStatusDto(
                    handle = translator.toClient(lease.handle),
                    name = monitor?.nameOf(lease.handle),
                    holder = LeaseDisclosure.holderLabel(lease.owner, clientKey, operatorScope),
                    mine = lease.owner == clientKey,
                    connected = lease.connected,
                    inGrace = lease.inGrace,
                    remainingGraceMs = lease.remainingGraceMs,
                )
            },
            otherLeases = leases.size - visible.size,
            operatorScope = operatorScope,
        )
    }

    /** Rejects every device-bearing operation unless this connection owns a live lease. */
    private suspend fun authorizeConnected(device: DeviceHandle) {
        when (val authorization = registry.authorizeConnected(device.value, clientKey)) {
            PeripheralRegistry.Authorization.Granted -> Unit
            is PeripheralRegistry.Authorization.PeripheralBusy ->
                throw AgentException(
                    AgentError(
                        ErrorKind.PERIPHERAL_BUSY,
                        message = LeaseDisclosure.busyMessage(authorization.owner, clientKey),
                    ),
                )
            PeripheralRegistry.Authorization.NotConnected ->
                throw AgentException(AgentError(ErrorKind.NOT_CONNECTED, message = "peripheral is not connected"))
        }
    }

    /**
     * The error for a write the policy refused. Never enumerates the policy — a refused caller
     * learns that it was refused, not the shape of the allowlist.
     *
     * Gated on [Capabilities.WRITE_POLICY] for the same reason [radioUnavailableError] gates
     * [ErrorKind.RADIO_OFF]: [ErrorKind] serializes by name, and an unknown name would fail a v1
     * client's decode. A client that has not negotiated this receives [ErrorKind.INVALID_REQUEST]
     * instead — the same kind an over-limit write already returns.
     */
    private fun policyDeniedError(message: String): AgentError = AgentError(
        if (Capabilities.WRITE_POLICY in negotiated) ErrorKind.POLICY_DENIED else ErrorKind.INVALID_REQUEST,
        message = message,
    )

    private fun operationLimitError(op: Op): AgentError? = when (op) {
        is Op.ScanStart -> op.filters.takeIf { it.size > MAX_SCAN_FILTERS }?.let {
            AgentError(ErrorKind.INVALID_REQUEST, message = "at most $MAX_SCAN_FILTERS scan filters are allowed")
        }
        is Op.Write -> op.value.takeIf { it.size > MAX_WRITE_BYTES }?.let {
            AgentError(ErrorKind.INVALID_REQUEST, message = "write payload exceeds $MAX_WRITE_BYTES bytes")
        }
        is Op.WriteDescriptor -> op.value.takeIf { it.size > MAX_WRITE_BYTES }?.let {
            AgentError(ErrorKind.INVALID_REQUEST, message = "descriptor payload exceeds $MAX_WRITE_BYTES bytes")
        }
        is Op.RequestMtu -> op.mtu.takeIf { it < MIN_MTU || it > MAX_MTU }?.let {
            AgentError(ErrorKind.INVALID_REQUEST, message = "requested MTU must be between $MIN_MTU and $MAX_MTU")
        }
        else -> null
    }

    /**
     * Fails the two ops that *need the radio to start something* when the host's radio is not
     * usable, instead of letting them fail silently — a scan with the radio off completes normally
     * and yields nothing, which is exactly the "empty room" ambiguity gap 17 is about.
     *
     * Scoped to `ScanStart`/`Connect` on purpose. Ops against an already-established link are left
     * to fail on their own terms: the radio going off drops those links, and the backend reports
     * that as a disconnect with its own error, which is more precise than a blanket radio error.
     *
     * Gated on the capability because [ErrorKind.RADIO_OFF] is a name a v1 client's decoder has
     * never seen. [BleRadioState.UNAUTHORIZED] is reported with the same kind as
     * [BleRadioState.OFF] — both are unusable-but-user-fixable, so both are `transient` — with the
     * message carrying which one it is. [BleRadioState.UNSUPPORTED] is deliberately *not* gated
     * here: it is not transient, so reporting it as `RADIO_OFF` would tell a client to retry
     * something that can never succeed.
     */
    private fun radioUnavailableError(op: Op): AgentError? {
        if (Capabilities.RADIO_STATE !in negotiated) return null
        if (op !is Op.ScanStart && op !is Op.Connect) return null
        return when (backend.radioState?.value) {
            BleRadioState.OFF ->
                AgentError(ErrorKind.RADIO_OFF, message = "the agent host's Bluetooth radio is off")
            BleRadioState.UNAUTHORIZED ->
                AgentError(ErrorKind.RADIO_OFF, message = "the agent host has not granted Bluetooth permission")
            else -> null
        }
    }

    private suspend fun connect(device: DeviceHandle): OpResult {
        // Already connected by this client: idempotent success, no cross-client check.
        if (state.withLock { device.value in connected }) return OpResult.Ok()

        // Cross-client ownership gate: another client holds an exclusive peripheral.
        val resumedWarmLink = when (val acquisition = registry.acquire(device.value, clientKey)) {
            is PeripheralRegistry.Acquisition.Denied ->
                return OpResult.Err(
                    AgentError(
                        ErrorKind.PERIPHERAL_BUSY,
                        message = LeaseDisclosure.busyMessage(acquisition.owner, clientKey),
                    ),
                )
            PeripheralRegistry.Acquisition.NoSlot ->
                return OpResult.Err(AgentError(ErrorKind.NO_CONNECTION_SLOT))
            is PeripheralRegistry.Acquisition.Granted -> acquisition.linkAlreadyLive
        }

        // The slot was taken by the acquisition above — the cap is the host radio's, so it is the
        // registry's to enforce. This set stays for *this* connection's bookkeeping (idempotent
        // connect, teardown), not as a second, per-session capacity rule.
        state.withLock { connected += device.value } // reserve before the (slow) connect

        // Resuming a lease whose radio link never dropped: the peripheral is already connected, so
        // calling the backend again would be a second physical connect for a link that is up.
        // `connected` above is per *connection*, and a resuming client is by definition a new one,
        // so it cannot answer this — only the registry, which outlives the transport, can. This is
        // the path every invocation of a process-per-command client takes after its first.
        if (resumedWarmLink) {
            observer.onClientLog(clientId, "resumed warm link ${device.value}")
            Logger.info(LogTags.ENGINE) { "warm lease resumed, skipping connect [c=$clientId dev=${device.value}]" }
            emit(AgentEvent.ConnectionState(device, BleConnState.CONNECTED))
            return OpResult.Ok()
        }

        try {
            backend.connect(device)
        } catch (e: Throwable) {
            state.withLock { connected -= device.value } // release on failure
            registry.releaseNow(device.value, clientKey)
            observer.onClientLog(clientId, "connect failed: ${device.value} (${e.message})")
            Logger.warn(LogTags.ENGINE) { "connect failed [c=$clientId dev=${device.value}]: ${e.message}" }
            throw e
        }
        if (!registry.onConnected(device.value, clientKey) { connectionLive }) {
            // The transport was retired while this slow connect completed. Don't commit the lease;
            // leave it to the transport-grace path, whose onRelease disconnects the radio on the
            // registry scope (surviving this command coroutine's own cancellation).
            state.withLock { connected -= device.value }
            observer.onClientLog(clientId, "connect completed after transport loss for ${device.value}")
            Logger.warn(LogTags.ENGINE) { "connect completed after transport retirement [c=$clientId dev=${device.value}]" }
            return OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST, message = "connection closed before connect completed"))
        }
        observer.onDeviceConnected(clientId, device.value)
        observer.onClientLog(clientId, "connected ${device.value}")
        Logger.info(LogTags.ENGINE) { "device connected [c=$clientId dev=${device.value}]" }
        emit(AgentEvent.ConnectionState(device, BleConnState.CONNECTED))
        return OpResult.Ok()
    }

    private suspend fun disconnect(device: DeviceHandle): OpResult {
        val result = runCatching { backend.disconnect(device) }
        state.withLock { connected -= device.value }
        // Explicit disconnect is terminal ownership intent: it is never eligible for warm resume.
        registry.releaseNow(device.value, clientKey)
        observer.onDeviceDisconnected(clientId, device.value)
        observer.onClientLog(clientId, "disconnected ${device.value}")
        Logger.info(LogTags.ENGINE) { "device disconnected [c=$clientId dev=${device.value}]" }
        emit(AgentEvent.ConnectionState(device, BleConnState.DISCONNECTED))
        translator.evict(device.value)
        evictWriteChain(device.value)
        return result.fold(onSuccess = { OpResult.Ok() }, onFailure = { throw it })
    }

    /**
     * Admits the scan and returns its **unstarted** delivery collector.
     *
     * The caller must start the returned job only after `scan.start`'s reply has been written — see
     * [replyThenDeliver]. Admission has to happen inside the command (it carries the ordering and
     * fencing guarantees the coordinator depends on) but *delivery* must not: a guaranteed-mode
     * admission enqueues the replay cache synchronously, so a collector started here can put a
     * `ScanResult` on the socket before the handler returns and the reply is written.
     */
    private suspend fun startScan(scanId: Long, op: Op.ScanStart): Job {
        val coordinator = scanCoordinator
        if (coordinator != null && coordinator.mode != ScanConcurrencyMode.UNCONTROLLED) {
            // Once physical replacement begins, this command must commit its local binding before
            // connection teardown can detach the generation. Otherwise cancellation between the
            // old collector's stop and the new collector's launch strands surviving logical scans.
            val deliver = withContext(NonCancellable) {
                when (val admission = try {
                    coordinator.startOrReplace(LogicalScanKey(clientKey, scanId), clientId, op.filters)
                } catch (limit: ScanLimitExceeded) {
                    throw AgentException(AgentError(ErrorKind.INVALID_REQUEST, message = limit.message))
                }) {
                    ScanAdmission.SingleOccupied -> {
                        val kind = if (Capabilities.SCAN_CONCURRENCY_SINGLE in negotiated) {
                            ErrorKind.SCAN_UNAVAILABLE
                        } else {
                            ErrorKind.AGENT_BUSY
                        }
                        throw AgentException(AgentError(kind, message = "the agent-wide scan slot is held"))
                    }
                    is ScanAdmission.Accepted -> {
                        val batched = Capabilities.SCAN_BATCH in negotiated
                        val sink = scanArbiter.register(scanId)
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                if (batched) {
                                    runCoordinatorBatchedScan(scanId, admission.advertisements, sink)
                                } else {
                                    runCoordinatorPerResultScan(scanId, admission.advertisements, sink)
                                }
                            } finally {
                                scanArbiter.unregister(sink)
                            }
                        }
                        val previous = state.withLock {
                            scanJobs.put(scanId, ManagedScan(job, admission.registration, sink))
                        }
                        previous?.job?.cancel()
                        job
                    }
                }
            }
            val batchedLog = Capabilities.SCAN_BATCH in negotiated
            observer.onClientLog(
                clientId,
                "scan started (#$scanId, ${coordinator.mode.name.lowercase()}${if (batchedLog) ", batched" else ""})",
            )
            Logger.debug(LogTags.ENGINE) {
                "scan started [c=$clientId scanId=$scanId batched=$batchedLog mode=${coordinator.mode.name.lowercase()}]"
            }
            return deliver
        }
        val batched = Capabilities.SCAN_BATCH in negotiated
        val coalesce = advertisementCoalescer()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (batched) runBatchedScan(scanId, op, coalesce) else runPerResultScan(scanId, op, coalesce)
        }
        val previous = state.withLock {
            if (scanId !in scanJobs && scanJobs.size >= maxActiveScans) {
                throw AgentException(
                    AgentError(ErrorKind.INVALID_REQUEST, message = "at most $maxActiveScans active scans are allowed"),
                )
            }
            scanJobs.put(scanId, ManagedScan(job, registration = null))
        }
        previous?.job?.cancel()
        observer.onClientLog(clientId, "scan started (#$scanId${if (batched) ", batched" else ""})")
        Logger.debug(LogTags.ENGINE) { "scan started [c=$clientId scanId=$scanId batched=$batched]" }
        return job
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
            .catch {
                observer.onClientLog(clientId, "scan #$scanId ended on error: ${it.message}")
                Logger.warn(LogTags.ENGINE) { "scan ended on error [c=$clientId scanId=$scanId]: ${it.message}" }
            }
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
                .catch {
                    observer.onClientLog(clientId, "scan #$scanId ended on error: ${it.message}")
                    Logger.warn(LogTags.ENGINE) { "scan ended on error [c=$clientId scanId=$scanId]: ${it.message}" }
                }
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
        val managed = state.withLock { scanJobs.remove(scanId) }
        managed?.sink?.let { scanArbiter.unregister(it) }
        managed?.job?.cancelAndJoin()
        managed?.registration?.let { scanCoordinator?.stop(it) }
        observer.onClientLog(clientId, "scan stopped (#$scanId)")
        Logger.debug(LogTags.ENGINE) { "scan stopped [c=$clientId scanId=$scanId]" }
    }

    private suspend fun runCoordinatorPerResultScan(
        scanId: Long,
        source: Flow<AdvertisementDto>,
        sink: ScanOutboundArbiter.Sink,
    ) {
        // Brackets the collector so a scan that delivers nothing can be placed: "collector started"
        // with no following handoff means the coordinator mailbox never produced, while a handoff
        // with nothing on the wire puts it downstream in the arbiter.
        Logger.debug(LogTags.ENGINE) { "coordinator scan collector started [c=$clientId scanId=$scanId]" }
        try {
            source.collect { ad ->
                observer.onDeviceSeen(ad.device.value, ad.name)
                deliverScanEvent(sink, AgentEvent.ScanResult(scanId, ad))
            }
        } finally {
            Logger.debug(LogTags.ENGINE) { "coordinator scan collector ended [c=$clientId scanId=$scanId]" }
        }
    }

    /**
     * Hands one scan event to the arbiter, suspending when its steady-state mailbox is full so
     * backpressure lands on the coordinator's single bounded reservation (which drops newest)
     * rather than on a second copy of it here.
     *
     * [stopScan] closes this sink out from under a collector that may still be mid-flight, and an
     * unguarded suspending send would throw [ClosedSendChannelException] and tear down the whole
     * connection instead of just ending this scan's delivery — so closure is caught and reported
     * as "not delivered". The caller keeps collecting; the coordinator closes its mailbox on stop,
     * which is what actually ends the collect.
     */
    private suspend fun deliverScanEvent(sink: ScanOutboundArbiter.Sink, event: AgentEvent) {
        try {
            sink.events.send(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ClosedSendChannelException) {
            return
        }
        scanArbiter.signal()
    }

    private suspend fun runCoordinatorBatchedScan(
        scanId: Long,
        source: Flow<AdvertisementDto>,
        sink: ScanOutboundArbiter.Sink,
    ): Unit = coroutineScope {
        val buffer = mutableListOf<AdvertisementDto>()
        val bufLock = Mutex()
        suspend fun flush() {
            val batch = bufLock.withLock {
                if (buffer.isEmpty()) null else buffer.toList().also { buffer.clear() }
            }
            if (batch != null) {
                deliverScanEvent(sink, AgentEvent.ScanResultBatch(scanId, batch))
            }
        }
        val flusher = launch {
            while (isActive) {
                delay(scanBatchWindow)
                flush()
            }
        }
        try {
            source.collect { ad ->
                observer.onDeviceSeen(ad.device.value, ad.name)
                val full = bufLock.withLock { buffer.add(ad); buffer.size >= scanBatchMaxSize }
                if (full) flush()
            }
        } finally {
            flusher.cancel()
            flush()
        }
    }

    /**
     * Registers the subscription and returns its **unstarted** notification collector.
     *
     * Started only after `observe.start`'s reply — see [replyThenDeliver]. A characteristic that
     * notifies immediately on subscribe would otherwise land a `Notification` on the socket before
     * the client has been told the subscription exists.
     */
    private suspend fun startObserve(subId: Long, device: DeviceHandle, op: Op.ObserveStart): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            backend.observe(device, op.char)
                .onEach { value ->
                    // Notifications are ordered payloads, not safely coalescible. If the
                    // transport remains blocked, retire this affected stream rather than holding
                    // an unbounded producer chain or silently discarding an arbitrary suffix.
                    val delivered = withTimeoutOrNull(NOTIFICATION_DELIVERY_TIMEOUT) {
                        emit(AgentEvent.Notification(subId, value))
                        true
                    } ?: false
                    if (!delivered) throw NotificationOverflowException()
                }
                // A subscription failure ends this stream, not the agent. Log it rather than
                // swallowing silently; the client sees notifications stop and can re-subscribe.
                .catch {
                    if (it is NotificationOverflowException) {
                        observer.onClientLog(clientId, "observe #$subId terminated: client too slow")
                        Logger.warn(LogTags.ENGINE) { "observe terminated on output overflow [c=$clientId subId=$subId]" }
                    } else {
                        observer.onClientLog(clientId, "observe #$subId ended on error: ${it.message}")
                        Logger.warn(LogTags.ENGINE) { "observe ended on error [c=$clientId subId=$subId]: ${it.message}" }
                    }
                }
                .collect()
        }
        val previous = state.withLock {
            if (subId !in observeJobs && observeJobs.size >= maxActiveObservations) {
                throw AgentException(
                    AgentError(
                        ErrorKind.INVALID_REQUEST,
                        message = "at most $maxActiveObservations active observations are allowed",
                    ),
                )
            }
            observeJobs.put(subId, job)
        }
        previous?.cancel()
        return job
    }

    private suspend fun stopObserve(subId: Long) {
        state.withLock { observeJobs.remove(subId) }?.cancel()
    }

    /**
     * Answer the client's handshake with the negotiated capability set (intersection).
     *
     * **First hello wins.** Negotiation and the translator are fixed by the first `ClientHello`
     * on this connection; a repeated hello is answered idempotently (the same `ServerHello`) but
     * changes nothing. Renegotiating mid-session could un-gate event types the client's decode
     * loop no longer expects, and swapping the translator would drop the reverse map that routes
     * handles the client already holds. A reconnect is a new socket — and thus a fresh [BleAgent] —
     * so it negotiates from scratch as before.
     */
    private suspend fun respondHello(hello: ClientHello) {
        val first = !handshaken
        if (first) {
            handshaken = true
            negotiated = hello.capabilities intersect capabilities
            translator = HandleTranslator(
                clientFormat = hello.identifierFormat,
                agentFormat = agentFormat,
                capabilityNegotiated = Capabilities.IDENTIFIER_TRANSLATION in negotiated,
                strict = { strictMode.enabled },
            )
            // A reconnecting client (same clientKey, within transportGrace) still holds handles
            // minted by its previous connection's translator — and will replay ops with them
            // (reconcile-on-reconnect). Synthesis is deterministic, so re-mint the mapping for
            // every lease the registry kept warm for this client; without this the fresh reverse
            // map couldn't route a replayed translated handle until a new scan re-emitted it.
            translator.prime(registry.heldBy(clientKey))
        }
        outgoing(
            codec.encode(
                ServerHello(
                    version = (selectProtocolVersion(hello.minVersion, hello.maxVersion) as? ProtocolVersionSelection.Selected)
                        ?.version ?: PROTOCOL_VERSION,
                    capabilities = negotiated,
                    agentInfo = agentInfo,
                ),
            ),
        )
        // Log after the send so the dashboard never records a handshake the client didn't receive.
        // The repeated-hello line keeps what the client asked for — the divergence first-hello-wins
        // silently ignores is exactly what an operator needs to see when debugging that client.
        if (first) {
            observer.onClientLog(clientId, "handshake: v${hello.maxVersion}, capabilities=$negotiated")
            Logger.info(LogTags.SERVER) { "handshake [c=$clientId]: v${hello.maxVersion}, caps=$negotiated, translate=${Capabilities.IDENTIFIER_TRANSLATION in negotiated}, fmt=${hello.identifierFormat}" }
        } else {
            observer.onClientLog(
                clientId,
                "repeated hello ignored (negotiation fixed): client asked " +
                    "v${hello.minVersion}..${hello.maxVersion}, capabilities=${hello.capabilities}, " +
                    "identifierFormat=${hello.identifierFormat}",
            )
            Logger.warn(LogTags.SERVER) { "repeated hello ignored [c=$clientId]" }
        }
        if (first) {
            startRadioStateFeed()
            startSlotStateFeed()
        }
    }

    /**
     * Streams this host's radio state to a client that negotiated `radio.state`, starting with the
     * current value.
     *
     * Started from the handshake rather than from [start] because [negotiated] is only known once
     * the hello lands, and an event sent before that would be exactly the un-negotiated event the
     * gating exists to prevent. `StateFlow` replays its current value to a new collector, so the
     * client's first event is the state at handshake time — a client should never have to wait for
     * the user to toggle Bluetooth before it learns the radio was off all along.
     */
    private fun startRadioStateFeed() {
        if (Capabilities.RADIO_STATE !in negotiated) return
        val source = backend.radioState ?: return
        scope.launch {
            source.collect { state ->
                // Contained like [onUnsolicitedDisconnect]: emit() writes to this client's socket,
                // and a send that throws on an already-closing socket must not tear down the
                // session's scope on the way out.
                try {
                    emit(AgentEvent.RadioState(state))
                    observer.onClientLog(clientId, "radio state: $state")
                    Logger.info(LogTags.ENGINE) { "radio state [c=$clientId]: $state" }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    observer.onClientLog(clientId, "failed to deliver radio state $state: ${t.message}")
                }
            }
        }
    }

    /**
     * Emits a bond-state change — but only to a client that negotiated `pairing` (the same
     * decode-loop rationale as [startSlotStateFeed]: an unnegotiated event type could break
     * the client's decode loop). The solicited reply payload carries the state either way.
     */
    private suspend fun emitBondStateIfNegotiated(device: DeviceHandle, state: BleBondState) {
        if (Capabilities.PAIRING !in negotiated) return
        emit(AgentEvent.BondState(device, state))
    }

    /**
     * Streams free/total connection slots to a client that negotiated `slots`, starting with the
     * current value.
     *
     * Two things this deliberately is not. It is not per session: the count comes from the
     * registry, so it reflects every client's leases against the host's capacity, and a client
     * learns that a peripheral is unavailable because *someone* holds it — including itself,
     * between two invocations of a process-per-command tool. And it is not change-triggered from
     * the connect/disconnect paths: a `StateFlow` replays its current value to a new collector, so
     * a client that negotiates `slots` and asks nothing else still gets an answer instead of
     * waiting for a connection count to move.
     *
     * Started from the handshake for the same reason as [startRadioStateFeed] — [negotiated] is
     * only known once the hello lands.
     */
    private fun startSlotStateFeed() {
        if (Capabilities.CONNECTION_SLOTS !in negotiated) return
        scope.launch {
            registry.occupiedSlots.collect { occupied ->
                // Contained like [startRadioStateFeed]: a send that throws on an already-closing
                // socket must not tear down the session scope on its way out.
                try {
                    emit(
                        AgentEvent.SlotState(
                            free = (registry.totalSlots - occupied).coerceAtLeast(0),
                            total = registry.totalSlots,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    observer.onClientLog(clientId, "failed to deliver slot state: ${t.message}")
                }
            }
        }
    }

    private suspend fun reply(cid: Long, result: OpResult) = outgoing(codec.encode(Reply(cid, result)))

    /**
     * Enforces the wire guarantee that a stream's `Ok` precedes any event it produces.
     *
     * [admit] does the registration and returns an **unstarted** collector; the reply goes out, and
     * only then does delivery begin. Without this a client can be handed results for a stream it has
     * not yet been told was accepted — and a client that reads the reply before switching to event
     * handling loses the first result outright, which is precisely how the `scanConc03` flake
     * presented: a replayed advertisement overtook its own `scan.start` reply on a loaded runner.
     *
     * The start is in a `finally` so a failed reply write cannot strand the collector unstarted,
     * holding its arbiter sink and coordinator mailbox until the connection scope unwinds.
     */
    private suspend fun replyThenDeliver(cid: Long, admit: suspend () -> Job) {
        val deliver = admit()
        try {
            reply(cid, OpResult.Ok())
        } finally {
            deliver.start()
        }
    }

    // Forward-translate any real handle the event carries into the client's declared format before
    // it goes on the wire (identity when translation isn't active).
    private suspend fun emit(event: AgentEvent) = outgoing(codec.encode(Event(translator.outgoing(event))))

    private class NotificationOverflowException : Exception()

    companion object {
        /**
         * Peripherals this agent host will hold at once, across **every** client. Enforced by
         * `PeripheralRegistry`, because the constraint it models is the host controller's.
         *
         * Eight, matching `agent-rs`. Neither Kable nor btleplug exposes the controller's real
         * limit, so any fixed number is a guess, and the useful property of a guess here is that it
         * not be the binding constraint on a healthy host: a cap below the radio's own ceiling
         * silently wastes capacity, while one above it merely means a refusal arrives from the
         * radio instead of from us. An operator running a shared rig sets a real policy explicitly.
         *
         * It was 4, and per session rather than agent-wide, which made it *effectively* 4×clients.
         * Making it agent-wide without raising it would have tightened every existing multi-client
         * deployment; a client that needs a hard reservation should be leasing, not counting slots.
         */
        const val DEFAULT_MAX_CONNECTIONS: Int = 8
        const val DEFAULT_MAX_INFLIGHT_COMMANDS: Int = 64
        const val MAX_SCAN_FILTERS: Int = 64
        const val MAX_ACTIVE_SCANS: Int = 16
        const val MAX_ACTIVE_OBSERVATIONS: Int = 128
        const val MAX_WRITE_BYTES: Int = 512
        const val MIN_MTU: Int = 23
        const val MAX_MTU: Int = 517
        val NOTIFICATION_DELIVERY_TIMEOUT: Duration = 5.seconds
        val DEFAULT_SCAN_BATCH_WINDOW: Duration = 100.milliseconds
        const val DEFAULT_SCAN_BATCH_MAX_SIZE: Int = 16

        /**
         * Capabilities the agent implements itself, independent of the radio backend
         * (the backend contributes its own — descriptors, pairing). Unioned into the
         * advertised set by [BleAgentBackend].
         */
        val AGENT_CAPABILITIES: Set<String> = setOf(
            Capabilities.CONNECTION_SLOTS,
            Capabilities.SCAN_BATCH,
            Capabilities.IDENTIFIER_TRANSLATION,
            Capabilities.AGENT_STATUS,
            Capabilities.WRITE_POLICY,
        )
    }
}
