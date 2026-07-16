package dev.warsha.remoteble.client

import dev.warsha.remoteble.log.Logger
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
import dev.warsha.remoteble.protocol.isIdempotent
import dev.warsha.remoteble.protocol.ProtocolCodec
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
     * Session usability, distinct from the raw IP [transportState]. [SessionReadiness.READY]
     * means the agent hello completed and any reconnect replay succeeded; [SessionReadiness.DEGRADED]
     * means the link is usable but one or more previously live peripherals could not be restored.
     */
    val readiness: StateFlow<SessionReadiness>

    /** Most recent reconnect replay outcome; `null` until the first reconnect completes. */
    val reconciliationReport: StateFlow<ReconciliationReport?>

    /**
     * The capabilities the agent advertised on the most recent handshake, or `null`
     * until the first `ServerHello` lands. Negotiation is lenient — `request()` never
     * blocks on this — so callers that must gate a capability-specific op should await
     * a non-null value (or treat `null`/absent as "not supported"). Reset to `null` on
     * each reconnect, then repopulated.
     */
    val capabilities: StateFlow<Set<String>?>

    /**
     * Issues [op] and awaits its reply. [retry] overrides the session's per-op default policy for
     * this one call (`null` = use the default resolved for [op]); [timeout] is applied **per
     * attempt**. See [RetryPolicy].
     */
    suspend fun request(op: Op, timeout: Duration = DEFAULT_TIMEOUT, retry: RetryPolicy? = null): OpResult

    /**
     * Sends [op] and returns immediately with a [Deferred] for its eventual [OpResult], instead of
     * suspending for the full round trip like [request]. The frame is on the wire **before this
     * function returns** — so a caller that calls [dispatch] several times in a row from one
     * coroutine, without awaiting in between, gets those frames sent in that same order. This is
     * the primitive a pipelining caller needs (e.g. a write-without-response burst that wants
     * several requests in flight without paying one round trip per write). It performs the same
     * single send-and-track step that one iteration of [request]'s attempt loop does, minus the
     * blocking await and the retry policy: no retry, single attempt only.
     */
    suspend fun dispatch(op: Op, timeout: Duration = DEFAULT_TIMEOUT): Deferred<OpResult>

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

    /** Permanently retires this session, its transport, pending work, and reconnect activity. */
    suspend fun close()

    companion object {
        val DEFAULT_TIMEOUT: Duration = 15.seconds
    }
}

/** Higher-level session lifecycle; use this when an operation requires completed negotiation/replay. */
enum class SessionReadiness {
    DISCONNECTED,
    CONNECTING,
    NEGOTIATING,
    RECONCILING,
    READY,
    DEGRADED,
    INCOMPATIBLE_PROTOCOL,
    CLOSED,
}

/** Bounded, secret-free summary of one reconnect replay. */
data class ReconciliationReport(
    val connectionsAttempted: Int,
    val connectionsRestored: Int,
    val connectionsFailed: Int,
    val dependentOperationsReplayed: Int,
    val dependentOperationsSkipped: Int,
    val scansReplayed: Int,
)

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

/**
 * The retry decision for a failed op — **behavior, not parameters**. Given the failure so far it
 * answers one question: wait how long before trying again, or stop? Returning `null` stops and
 * surfaces the error. Implementations are **stateless** — the loop passes the state in ([attempt],
 * [elapsed]) — so a single instance is safe to share across concurrent `request()` calls, and there
 * is nothing to reset. Built-ins live in [RetryPolicies]; anything exotic (per-error budgets,
 * deadlines, circuit breakers, jitter) is just another implementation.
 *
 * A policy is chosen **per op**: the session resolves one via its `retryPolicyFor` (default
 * [defaultRetryPolicyFor]), and a caller can override it for a single call with
 * `request(op, retry = …)`. [timeout] on `request` is applied **per attempt**.
 */
fun interface RetryPolicy {
    /**
     * @param attempt 1-based number of the attempt that just failed
     * @param error the failure — inspect [AgentError.kind], or [AgentError.gattStatus] for raw status
     * @param elapsed wall-clock time since the first attempt began
     * @return the delay before the next attempt, or `null` to stop retrying
     */
    fun retryDelay(attempt: Int, error: AgentError, elapsed: Duration): Duration?
}

/** Built-in [RetryPolicy] implementations. */
object RetryPolicies {
    /** Attempt once, never retry. */
    val None: RetryPolicy = RetryPolicy { _, _, _ -> null }

    /** Retry up to [maxAttempts] total attempts, on [retryOn] errors, paced by [backoff]. */
    fun maxAttempts(
        maxAttempts: Int,
        backoff: Backoff = Backoff(),
        retryOn: Set<ErrorKind> = ErrorKind.transientKinds,
    ): RetryPolicy {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        return RetryPolicy { attempt, error, _ ->
            if (attempt < maxAttempts && error.kind in retryOn) backoff.delayFor(attempt) else null
        }
    }

    /** Keep retrying [retryOn] errors, paced by [backoff], until [budget] wall-clock has elapsed. */
    fun untilElapsed(
        budget: Duration,
        backoff: Backoff = Backoff(),
        retryOn: Set<ErrorKind> = ErrorKind.transientKinds,
    ): RetryPolicy = RetryPolicy { attempt, error, elapsed ->
        if (elapsed < budget && error.kind in retryOn) backoff.delayFor(attempt) else null
    }
}

/**
 * The built-in per-op default policy, derived from safety: a non-idempotent op (write, pairing —
 * see [Op.isIdempotent]) is never auto-retried, [Op.Connect] gets a little more patience, and every
 * other idempotent op retries a couple of times. Only [ErrorKind.transient] errors are retried.
 * Override for a whole session via `DefaultAgentSession(retryPolicyFor = …)`, or per call via
 * `request(op, retry = …)`.
 */
fun defaultRetryPolicyFor(op: Op): RetryPolicy = when {
    !op.isIdempotent -> RetryPolicies.None
    op is Op.Connect -> RetryPolicies.maxAttempts(3)
    else -> RetryPolicies.maxAttempts(2)
}

@OptIn(ExperimentalAtomicApi::class)
class DefaultAgentSession(
    private val transport: AgentTransport,
    private val codec: ProtocolCodec,
    private val scope: CoroutineScope,
    private val clientCapabilities: Set<String> = emptySet(),
    private val retryPolicyFor: (Op) -> RetryPolicy = ::defaultRetryPolicyFor,
) : AgentSession {

    // Do not own or cancel the caller's scope: a session gets its own child so endpoint/token
    // replacement can await retirement before a replacement session starts reconnecting.
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val closeLock = Mutex()
    private var closed = false

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
    private val _readiness = MutableStateFlow(SessionReadiness.DISCONNECTED)
    private val _reconciliationReport = MutableStateFlow<ReconciliationReport?>(null)

    // Replay set for reconcile-on-reconnect. The IP transport reconnecting does NOT
    // mean the agent's BLE state survived (the agent may have restarted), so on every
    // reconnect we re-establish what the client believes is live: connections, then
    // their subscriptions and any active scans. Guarded by [replayLock].
    private val replayLock = Mutex()
    private val activeConnections = mutableSetOf<DeviceHandle>()
    private val activeSubscriptions = mutableMapOf<Long, Op.ObserveStart>()
    private val activeScans = mutableMapOf<Long, Op.ScanStart>()
    private val lastConnParams = mutableMapOf<DeviceHandle, Op.SetConnParams>()

    override val transportState: StateFlow<TransportState> get() = transport.state

    override val capabilities: StateFlow<Set<String>?> = _capabilities.asStateFlow()
    override val readiness: StateFlow<SessionReadiness> = _readiness.asStateFlow()
    override val reconciliationReport: StateFlow<ReconciliationReport?> = _reconciliationReport.asStateFlow()

    init {
        // Decode incoming frames: complete matching requests, fan out events, record
        // the negotiated capability set from the agent's handshake reply.
        sessionScope.launch {
            transport.incoming.collect { bytes ->
                when (val frame = codec.decode(bytes)) {
                    is Reply -> complete(frame.cid, frame.result)
                    is Event -> _events.emit(frame.event)
                    is ServerHello -> {
                        _capabilities.value = frame.capabilities
                        if (_readiness.value == SessionReadiness.NEGOTIATING) {
                            _readiness.value = SessionReadiness.READY
                        }
                        Logger.info(LogTags.SESSION) { "agent hello — negotiated caps: ${frame.capabilities}" }
                    }
                    is Command, is ClientHello -> Unit
                }
            }
        }
        // Track IP-link transitions. A drop fails every in-flight request (never hang);
        // a reconnect AFTER a prior connection triggers reconcile (the first connect does
        // not — there is nothing to replay yet). Reconcile runs off the collector so a
        // slow replay can't delay reacting to a subsequent drop.
        sessionScope.launch {
            var everConnected = false
            transport.state.collect { state ->
                when (state) {
                    TransportState.CONNECTED -> {
                        _capabilities.value = null
                        _readiness.value = SessionReadiness.NEGOTIATING
                        val replay = everConnected
                        sessionScope.launch {
                            sendHello()
                            if (replay) {
                                _readiness.value = SessionReadiness.RECONCILING
                                val report = reconcileOnReconnect()
                                _reconciliationReport.value = report
                                _readiness.value = if (report.connectionsFailed > 0) {
                                    SessionReadiness.DEGRADED
                                } else {
                                    SessionReadiness.READY
                                }
                            }
                        }
                        everConnected = true
                    }
                    TransportState.DISCONNECTED -> {
                        _readiness.value = SessionReadiness.DISCONNECTED
                        Logger.info(LogTags.SESSION) { "transport lost — failing in-flight requests" }
                        failAllPending()
                    }
                    TransportState.INCOMPATIBLE_PROTOCOL -> {
                        _readiness.value = SessionReadiness.INCOMPATIBLE_PROTOCOL
                        Logger.warn(LogTags.SESSION) { "protocol incompatible — failing in-flight requests" }
                        failAllPending(ErrorKind.INCOMPATIBLE_PROTOCOL)
                    }
                    TransportState.CONNECTING -> _readiness.value = SessionReadiness.CONNECTING
                }
            }
        }
        // Establish the link once; the transport owns reconnect-with-backoff thereafter.
        sessionScope.launch { runCatching { transport.connect() } }
    }

    override fun nextStreamId(): Long = ids.incrementAndFetch()

    override suspend fun request(op: Op, timeout: Duration, retry: RetryPolicy?): OpResult {
        val policy = retry ?: retryPolicyFor(op)
        val started = TimeSource.Monotonic.markNow()
        var attempt = 0
        while (true) {
            attempt++
            val result = attemptRequest(op, timeout)
            if (result is OpResult.Ok) {
                Logger.debug(LogTags.SESSION) { "request ok: ${op::class.simpleName} cid=${ids.load()}" }
                return result
            }
            val error = (result as OpResult.Err).error
            val pause = policy.retryDelay(attempt, error, started.elapsedNow())
            if (pause == null) {
                Logger.debug(LogTags.SESSION) {
                    "request failed (stop): ${op::class.simpleName} kind=${error.kind} message=${error.message}"
                }
                return result
            }
            Logger.debug(LogTags.SESSION) {
                "request retry ${attempt}: ${op::class.simpleName} kind=${error.kind} delay=${pause}"
            }
            if (error.kind == ErrorKind.TRANSPORT_LOST) {
                withTimeoutOrNull(pause) { transport.state.first { it == TransportState.CONNECTED } }
            } else {
                delay(pause)
            }
        }
    }

    /**
     * The shared send-and-register step behind both [attemptRequest] and [dispatch]: assigns a
     * [cid], registers a pending slot, and puts [op]'s `Command` on the wire — **synchronously**,
     * so successive calls from one coroutine send in call order. Returns the [cid] and the
     * [CompletableDeferred] the reply will land in, already pre-completed with a `TRANSPORT_LOST`
     * `Err` when the link is down or the send throws (so callers await it uniformly). Rethrows on
     * cancellation. The caller owns the await, the per-attempt timeout, `removePending`, and
     * [trackForReplay] — those differ between the blocking and pipelined paths.
     */
    private suspend fun sendCommand(op: Op): Pair<Long, CompletableDeferred<OpResult>> {
        val cid = ids.incrementAndFetch()
        val deferred = CompletableDeferred<OpResult>()
        if (closeLock.withLock { closed }) {
            deferred.complete(OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST, message = "session closed")))
            return cid to deferred
        }
        if (transport.state.value != TransportState.CONNECTED) {
            val kind = if (transport.state.value == TransportState.INCOMPATIBLE_PROTOCOL) {
                ErrorKind.INCOMPATIBLE_PROTOCOL
            } else {
                ErrorKind.TRANSPORT_LOST
            }
            deferred.complete(OpResult.Err(AgentError(kind, message = "transport not connected")))
            Logger.debug(LogTags.SESSION) { "sendCommand failed: transport not connected cid=$cid" }
            return cid to deferred
        }
        pendingLock.withLock { pending[cid] = deferred }
        try {
            transport.send(codec.encode(Command(cid, op)))
        } catch (e: CancellationException) {
            removePending(cid)
            throw e
        } catch (e: Throwable) {
            removePending(cid)
            Logger.warn(LogTags.SESSION) { "sendCommand failed: ${e.message} cid=$cid" }
            deferred.complete(OpResult.Err(AgentError(ErrorKind.TRANSPORT_LOST, message = e.message)))
        }
        return cid to deferred
    }

    /** Awaits [deferred] within [timeout], cleans up its pending slot, and records it for replay. */
    private suspend fun awaitReply(op: Op, cid: Long, deferred: CompletableDeferred<OpResult>, timeout: Duration): OpResult {
        val result = try {
            withTimeoutOrNull(timeout) { deferred.await() }
                ?: OpResult.Err(AgentError(ErrorKind.TIMEOUT))
        } finally {
            removePending(cid)
        }
        trackForReplay(op, result)
        return result
    }

    /** One attempt at [op]: send, await the reply within [timeout], and record it for replay. */
    private suspend fun attemptRequest(op: Op, timeout: Duration): OpResult {
        val (cid, deferred) = sendCommand(op)
        return awaitReply(op, cid, deferred, timeout)
    }

    override suspend fun dispatch(op: Op, timeout: Duration): Deferred<OpResult> {
        // sendCommand puts the frame on the wire before returning; the await/timeout/cleanup then
        // runs off the caller's coroutine (on the session scope) so dispatch() returns immediately,
        // letting the caller send its next frame without waiting on this one's Reply.
        val (cid, deferred) = sendCommand(op)
        return sessionScope.async { awaitReply(op, cid, deferred, timeout) }
    }

    override fun events(): SharedFlow<AgentEvent> = _events.asSharedFlow()

    override fun fireAndForget(op: Op) {
        sessionScope.launch {
            runCatchingNonCancellation { request(op, FIRE_AND_FORGET_TIMEOUT) }
                .onFailure { Logger.debug(LogTags.SESSION) { "fireAndForget op=${op::class.simpleName} failed: ${it.message}" } }
        }
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
                    lastConnParams -= op.device
                }
                is Op.ObserveStart -> activeSubscriptions[op.subId] = op
                is Op.ObserveStop -> activeSubscriptions.remove(op.subId)
                is Op.ScanStart -> activeScans[op.scanId] = op
                is Op.ScanStop -> activeScans.remove(op.scanId)
                is Op.SetConnParams -> lastConnParams[op.device] = op
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
    private suspend fun reconcileOnReconnect(): ReconciliationReport {
        val connections: List<DeviceHandle>
        val subscriptions: List<Op.ObserveStart>
        val scans: List<Op.ScanStart>
        val connParams: List<Op.SetConnParams>
        replayLock.withLock {
            connections = activeConnections.toList()
            subscriptions = activeSubscriptions.values.toList()
            scans = activeScans.values.toList()
            connParams = lastConnParams.values.toList()
        }
        val started = TimeSource.Monotonic.markNow()
        val unavailable = mutableSetOf<DeviceHandle>()
        connections.forEach { device ->
            if (request(Op.Connect(device)) !is OpResult.Ok) unavailable += device
        }
        // A failed prerequisite must not produce noisy dependent replays against a device that is
        // known unavailable after this reconnect. Scans do not depend on a connection and still run.
        var dependentReplayed = 0
        connParams.filterNot { it.device in unavailable }.forEach { request(it); dependentReplayed++ }
        subscriptions.filterNot { it.device in unavailable }.forEach { request(it); dependentReplayed++ }
        scans.forEach { request(it) }
        val elapsed = started.elapsedNow()
        val summary = "reconciled ${connections.size - unavailable.size}/${connections.size} conn(s), " +
            "${connParams.size} param(s), ${subscriptions.size} sub(s), ${scans.size} scan(s) in ${elapsed.inWholeMilliseconds}ms"
        if (unavailable.isEmpty()) {
            Logger.info(LogTags.SESSION) { summary }
        } else {
            Logger.warn(LogTags.SESSION) {
                "$summary; skipped dependent replay for ${unavailable.size} unavailable device(s)"
            }
        }
        return ReconciliationReport(
            connectionsAttempted = connections.size,
            connectionsRestored = connections.size - unavailable.size,
            connectionsFailed = unavailable.size,
            dependentOperationsReplayed = dependentReplayed,
            dependentOperationsSkipped = (connParams.count { it.device in unavailable } +
                subscriptions.count { it.device in unavailable }),
            scansReplayed = scans.size,
        )
    }

    /**
     * Best-effort handshake: announce our version range + capabilities. Not a [Command]
     * (no cid/reply matching) — the agent answers with a `ServerHello` that lands in the
     * decode loop and populates [capabilities]. A send failure is ignored; the transport
     * drop path will fire and a reconnect will re-handshake.
     */
    private suspend fun sendHello() {
        val caps = clientCapabilities + Capabilities.IDENTIFIER_TRANSLATION
        val fmt = currentIdentifierFormat()
        runCatchingNonCancellation {
            transport.send(
                codec.encode(
                    ClientHello(capabilities = caps, identifierFormat = fmt),
                ),
            )
        }.onFailure {
            Logger.debug(LogTags.SESSION) { "sendHello failed: ${it.message}" }
        }
        Logger.info(LogTags.SESSION) { "hello sent (caps=${caps.size}, fmt=$fmt)" }
    }

    private suspend fun failAllPending(kind: ErrorKind = ErrorKind.TRANSPORT_LOST) {
        val drained = pendingLock.withLock {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        val failure = OpResult.Err(AgentError(kind))
        drained.forEach { it.complete(failure) }
    }

    override suspend fun close() {
        val shouldClose = closeLock.withLock {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return

        _capabilities.value = null
        _readiness.value = SessionReadiness.CLOSED
        _reconciliationReport.value = null
        failAllPending()
        replayLock.withLock {
            activeConnections.clear()
            activeSubscriptions.clear()
            activeScans.clear()
            lastConnParams.clear()
        }
        transport.close()
        sessionJob.cancelAndJoin()
    }

    companion object {
        private val FIRE_AND_FORGET_TIMEOUT: Duration = 5.seconds
    }
}
