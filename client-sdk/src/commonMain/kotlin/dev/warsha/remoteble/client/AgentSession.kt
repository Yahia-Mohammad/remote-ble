package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Event
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LAYER 2 — the session. Turns a byte pipe into a request/response + event API.
 *
 * Responsibilities:
 *  - assign correlation ids and match `Reply` -> awaiting `request()`
 *  - enforce a pessimistic timeout per request
 *  - demux events to subscribers by id
 *  - on transport drop: fail every in-flight `request()` with `Err(TRANSPORT_LOST)`
 */
interface AgentSession {
    val transportState: StateFlow<TransportState>

    /**
     * The capabilities the agent advertised on the most recent handshake, or `null`
     * until the first `ServerHello` lands. Negotiation is lenient — `request()` never
     * blocks on this — so callers that must gate a capability-specific op should await
     * a non-null value (or treat `null`/absent as "not supported"). Reset to `null` on
     * each reconnect, then repopulated.
     */
    val capabilities: StateFlow<Set<String>?>

    suspend fun request(op: Op, timeout: Duration = DEFAULT_TIMEOUT): OpResult

    /**
     * Hot, shared stream of all events; consumers filter by subId/scanId. Returned as a
     * [SharedFlow] so stream openers can use `onSubscription` to issue their `scan.start` /
     * `observe.start` only once they're registered as a collector — otherwise the agent's first
     * event could be emitted before the collector subscribes and be missed.
     */
    fun events(): SharedFlow<AgentEvent>

    /** Session-global id for tagging event streams (scanId/subId). Unique per session. */
    fun nextStreamId(): Long

    /** Best-effort, fire-and-forget op for teardown (scan.stop / observe.stop). */
    fun fireAndForget(op: Op)

    companion object {
        val DEFAULT_TIMEOUT: Duration = 15.seconds
    }
}

/**
 * Free/total connection-slot updates from the agent (requires the agent's `slots`
 * capability — otherwise this flow simply never emits). Lets a caller wait for a slot
 * instead of retry-storming on [ErrorKind.NO_CONNECTION_SLOT].
 */
fun AgentSession.connectionSlots(): Flow<AgentEvent.SlotState> =
    events().filterIsInstance<AgentEvent.SlotState>()

/**
 * Suspends until the agent populates its negotiated capability set via ServerHello handshake.
 */
suspend fun AgentSession.awaitCapabilities(): Set<String> =
    capabilities.filterNotNull().first()

/**
 * Checks whether the agent supports the specified protocol capability after handshake completion.
 */
suspend fun AgentSession.supportsCapability(capability: String): Boolean =
    awaitCapabilities().contains(capability)

@OptIn(ExperimentalAtomicApi::class)
class DefaultAgentSession(
    private val transport: AgentTransport,
    private val codec: ProtocolCodec,
    private val scope: CoroutineScope,
    // The capabilities this client understands, sent in every ClientHello. Defaults to
    // empty (the v1 baseline); grows as the SDK gains descriptor/pairing support.
    private val clientCapabilities: Set<String> = emptySet(),
) : AgentSession {

    private val ids = AtomicLong(0)
    private val pending = mutableMapOf<Long, CompletableDeferred<OpResult>>()
    private val pendingLock = Mutex()
    // Events are emitted from the single decode loop that also dispatches replies, so
    // emit() must never suspend: a slow event subscriber would otherwise stall reply
    // delivery and hang every in-flight request(). DROP_OLDEST keeps the loop moving,
    // shedding the oldest buffered events under sustained backpressure (256 deep).
    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _capabilities = MutableStateFlow<Set<String>?>(null)

    // Replay set for reconcile-on-reconnect. The IP transport reconnecting does NOT
    // mean the agent's BLE state survived (the agent may have restarted), so on every
    // reconnect we re-establish what the client believes is live: connections, then
    // their subscriptions and any active scans. Guarded by [replayLock].
    private val replayLock = Mutex()
    private val activeConnections = mutableSetOf<DeviceHandle>()
    private val activeSubscriptions = mutableMapOf<Long, Op.ObserveStart>()
    private val activeScans = mutableMapOf<Long, Op.ScanStart>()

    override val transportState: StateFlow<TransportState> get() = transport.state

    override val capabilities: StateFlow<Set<String>?> = _capabilities.asStateFlow()

    init {
        // Decode incoming frames: complete matching requests, fan out events, record
        // the negotiated capability set from the agent's handshake reply.
        scope.launch {
            transport.incoming.collect { bytes ->
                when (val frame = codec.decode(bytes)) {
                    is Reply -> complete(frame.cid, frame.result)
                    is Event -> _events.emit(frame.event)
                    is ServerHello -> _capabilities.value = frame.capabilities
                    is Command, is ClientHello -> Unit // a client never receives these
                }
            }
        }
        // Track IP-link transitions. A drop fails every in-flight request (never hang);
        // a reconnect AFTER a prior connection triggers reconcile (the first connect does
        // not — there is nothing to replay yet). Reconcile runs off the collector so a
        // slow replay can't delay reacting to a subsequent drop.
        scope.launch {
            var everConnected = false
            transport.state.collect { state ->
                when (state) {
                    TransportState.CONNECTED -> {
                        // Re-handshake on every (re)connection: the agent may have restarted
                        // or upgraded, so the negotiated set is not assumed to survive a drop.
                        _capabilities.value = null
                        scope.launch { sendHello() }
                        if (everConnected) scope.launch { reconcileOnReconnect() }
                        everConnected = true
                    }
                    TransportState.DISCONNECTED -> failAllPending()
                    TransportState.CONNECTING -> Unit
                }
            }
        }
        // Establish the link once; the transport owns reconnect-with-backoff thereafter.
        scope.launch { runCatching { transport.connect() } }
    }

    override fun nextStreamId(): Long = ids.incrementAndFetch()

    override suspend fun request(op: Op, timeout: Duration): OpResult {
        if (transport.state.value != TransportState.CONNECTED) {
            return OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST, message = "transport not connected"))
        }
        val cid = ids.incrementAndFetch()
        val deferred = CompletableDeferred<OpResult>()
        pendingLock.withLock { pending[cid] = deferred }
        try {
            transport.send(codec.encode(Command(cid, op)))
        } catch (e: CancellationException) {
            removePending(cid)
            throw e
        } catch (e: Throwable) {
            removePending(cid)
            return OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST, message = e.message))
        }
        val result = try {
            withTimeoutOrNull(timeout) { deferred.await() }
                ?: OpResult.Err(AgentError(ErrorKind.TIMEOUT))
        } finally {
            removePending(cid)
        }
        trackForReplay(op, result)
        return result
    }

    override fun events(): SharedFlow<AgentEvent> = _events.asSharedFlow()

    override fun fireAndForget(op: Op) {
        scope.launch { runCatchingNonCancellation { request(op, FIRE_AND_FORGET_TIMEOUT) } }
    }

    private suspend fun complete(cid: Long, result: OpResult) {
        pendingLock.withLock { pending.remove(cid) }?.complete(result)
    }

    private suspend fun removePending(cid: Long) {
        withContext(NonCancellable) { pendingLock.withLock { pending.remove(cid) } }
    }

    /**
     * Maintains the replay set from successful ops. Only successes mutate it: a failed
     * connect/observe never happened on the agent, so there is nothing to re-establish.
     * Disconnecting a device also forgets its subscriptions (they cannot outlive it).
     */
    private suspend fun trackForReplay(op: Op, result: OpResult) {
        if (result !is OpResult.Ok) return
        replayLock.withLock {
            when (op) {
                is Op.Connect -> activeConnections += op.device
                is Op.Disconnect -> {
                    activeConnections -= op.device
                    activeSubscriptions.values.removeAll { it.device == op.device }
                }
                is Op.ObserveStart -> activeSubscriptions[op.subId] = op
                is Op.ObserveStop -> activeSubscriptions.remove(op.subId)
                is Op.ScanStart -> activeScans[op.scanId] = op
                is Op.ScanStop -> activeScans.remove(op.scanId)
                else -> Unit
            }
        }
    }

    /**
     * Re-establishes BLE state after the IP link comes back. The agent may have lost it
     * (restart, or BLE actually dropped during the outage), so we replay rather than
     * assume: reconnect each device first, then resume its subscriptions and scans using
     * their original stream ids — the `observe()`/`advertisements()` flows are still
     * collecting by those ids, so events resume into them with no app involvement.
     * `Op.Connect`/`Op.ObserveStart` are idempotent on the agent, so a still-live link is
     * reconciled harmlessly. Runs on its own coroutine; the same drop-fail path applies if
     * the link drops again mid-replay.
     */
    private suspend fun reconcileOnReconnect() {
        val connections: List<DeviceHandle>
        val subscriptions: List<Op.ObserveStart>
        val scans: List<Op.ScanStart>
        replayLock.withLock {
            connections = activeConnections.toList()
            subscriptions = activeSubscriptions.values.toList()
            scans = activeScans.values.toList()
        }
        connections.forEach { request(Op.Connect(it)) }
        subscriptions.forEach { request(it) }
        scans.forEach { request(it) }
    }

    /**
     * Best-effort handshake: announce our version range + capabilities. Not a [Command]
     * (no cid/reply matching) — the agent answers with a `ServerHello` that lands in the
     * decode loop and populates [capabilities]. A send failure is ignored; the transport
     * drop path will fire and a reconnect will re-handshake.
     */
    private suspend fun sendHello() {
        runCatchingNonCancellation {
            transport.send(
                codec.encode(
                    ClientHello(
                        // Always request handle translation and declare our local Identifier format:
                        // it's purely additive (an agent that doesn't support it won't negotiate it,
                        // leaving handles untranslated) and lets a supporting agent hand us handles
                        // this platform can turn into a native Kable Identifier.
                        capabilities = clientCapabilities + Capabilities.IDENTIFIER_TRANSLATION,
                        identifierFormat = currentIdentifierFormat(),
                    ),
                ),
            )
        }
    }

    private suspend fun failAllPending() {
        val drained = pendingLock.withLock {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        val lost = OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST))
        drained.forEach { it.complete(lost) }
    }

    companion object {
        private val FIRE_AND_FORGET_TIMEOUT: Duration = 5.seconds
    }
}
