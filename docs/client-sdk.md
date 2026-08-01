# `:client-sdk` — Client SDK Reference

[← back to index](README.md)

The client SDK turns "I have a URL to an agent" into "I have a Kable `Peripheral`."
It is multiplatform (JVM for tests, Android, iOS) and depends on `:protocol`,
kotlinx-coroutines, the Ktor client, and Kable.

The module is organized as the four layers described in
[the architecture overview](README.md#the-clients-three-layers). This document walks
them bottom-up.

`transportState == CONNECTED` only means the WebSocket link is open. For work that requires the
server hello and reconnect restoration, observe `AgentSession.readiness`: it moves through
`NEGOTIATING` and, after a reconnect, `RECONCILING`, then reaches `READY` or `DEGRADED` when one or
more previously connected peripherals could not be restored. `INCOMPATIBLE_PROTOCOL` and `CLOSED`
are terminal for the session instance.

After a reconnect, `AgentSession.reconciliationReport` records the completed replay without exposing
device handles or credentials: attempted/restored/failed connections, replayed/skipped dependent
operations, and independent scans replayed. It is `null` before the first replay.

Source root: [`client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client)

---

## Layer 1 — Transport (`AgentTransport`)

[`AgentTransport.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/AgentTransport.kt)

One bidirectional, message-oriented, **BLE-agnostic** link to one agent at an opaque
endpoint.

```kotlin
// GAVE_UP = dropped and not retrying (reconnect exhausted, or disabled): distinct from
// DISCONNECTED, which means a recovery attempt is still in progress.
enum class TransportState { CONNECTING, CONNECTED, DISCONNECTED, GAVE_UP, INCOMPATIBLE_PROTOCOL }

interface AgentTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<ByteArray>          // reassembled frames arriving from the agent
    suspend fun connect()                  // idempotent: safe to (re)establish after a drop
    suspend fun send(frame: ByteArray)
    suspend fun close()
}

class TransportClosedException(message: String? = null) : Exception(message)
```

`incoming` delivers already-reassembled frames (one `ByteArray` per protocol frame).
The endpoint (URL, host:port, …) is supplied at construction of a concrete impl and
is none of this interface's concern — that is what makes a cloud-relay or raw-TCP
transport a drop-in replacement.

### `WebSocketAgentTransport`

[`WebSocketAgentTransport.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/WebSocketAgentTransport.kt) —
the production Layer-1 impl over a Ktor WebSocket. One protocol frame = one binary
WS message.

```kotlin
class WebSocketAgentTransport(
    url: String,                  // "ws://host:port/path" — opaque above this layer
    scope: CoroutineScope,
    httpClient: HttpClient,
    authToken: suspend () -> String? = { null }, // bearer provider, sent as Authorization: Bearer <token>
    reconnect: ReconnectPolicy = ReconnectPolicy(), // how the link recovers (below)
) : AgentTransport
```

Behavior:

- **`connect()` is idempotent** — guarded by a mutex; returns immediately if already
  CONNECTED; otherwise opens a session (CONNECTING → CONNECTED) and launches a
  receive loop. If the *initial* attempt fails and `reconnect` is enabled, it arms the
  same backoff loop instead of giving up, so a client that starts before its agent still
  connects once the agent appears (with `enabled = false` the first attempt is one-shot
  and `connect()` throws).
- **On an unexpected close** the receive loop falls through to `onDisconnected`,
  which flips state to DISCONNECTED (this is what makes the session fail in-flight
  requests with `TRANSPORT_LOST`) and, if enabled, launches
  `reconnectWithBackoff`. Stale receive loops from a session that was already
  replaced are ignored.
- **`reconnectWithBackoff`** retries `openSession()` with the policy's `Backoff` until
  CONNECTED, `close()`, or — when `maxAttempts` is set — the attempt budget is spent, at
  which point it rests at DISCONNECTED and fires `onGaveUp` once. **The `incoming` channel
  survives reconnects** (created once, `Channel.UNLIMITED`), so the decode loop is uninterrupted.
- **`authToken`** is a suspend provider invoked once per connection attempt (including
  every reconnect retry); a non-null return is sent as an `Authorization: Bearer <token>`
  header on the WS upgrade. Because it's called per attempt, a token that rotates or
  expires is refreshed on reconnect — the SDK never caches the value. For a static token
  just return it: `authToken = { "secret" }`. If the provider throws, the attempt fails
  like any connect error and folds into the backoff/reconnect path. (Server-side
  enforcement lives in the agent — see [agent.md](agent.md#authentication).)

```kotlin
class Backoff(base: Duration = 50.ms, max: Duration = 2000.ms) {
    fun delayFor(attempt: Int): Duration   // base * 2^attempt, capped at max
}
```

The Ktor engine is **not** baked in — it is passed as `httpClient`. Each platform
provides a default via an `expect`/`actual` (next section).

### Error and retry policies

Two independent policies control recovery. Both default to **today's behavior**, so they
are pure opt-ins.

**`ReconnectPolicy`** — how the *transport link* recovers (constructor arg above):

```kotlin
data class ReconnectPolicy(
    val enabled: Boolean = true,            // false = one-shot; connect() throws on failure
    val backoff: Backoff = Backoff(),
    val maxAttempts: Int? = null,           // null = retry forever; else give up after N
    val onGaveUp: (() -> Unit)? = null,     // fired once when a bounded episode is exhausted
)
```

`maxAttempts` bounds a single recovery *episode* (a later success resets the count), and
`onGaveUp` lets a UI distinguish "still reconnecting" from "gave up — surface an error"
rather than watching an unbounded, silent loop.

**`RetryPolicy`** — whether the *session* retries a failed **operation**. It's an interface, not a
bag of parameters: given the failure so far, it answers one question — wait how long, or stop?

```kotlin
fun interface RetryPolicy {
    // 1-based attempt that just failed, the AgentError, and time since the first try.
    // Return the delay before the next attempt, or null to stop.
    fun retryDelay(attempt: Int, error: AgentError, elapsed: Duration): Duration?
}
```

It is **stateless** — the loop passes the attempt count and elapsed time *in*, so one instance is
safe to share across concurrent `request()` calls and there is nothing to reset. Arbitrary logic
(per-error budgets, deadlines, circuit breakers, jitter) is just another implementation; the common
cases are built into `RetryPolicies`:

```kotlin
RetryPolicies.None                                   // attempt once, never retry
RetryPolicies.maxAttempts(3)                         // up to 3, transient errors, backoff
RetryPolicies.untilElapsed(10.seconds)               // retry transient errors until a deadline

// The built-in per-op default resolver, derived from safety (Op.isIdempotent):
fun defaultRetryPolicyFor(op: Op): RetryPolicy = when {
    !op.isIdempotent -> RetryPolicies.None            // Write / WriteDescriptor / Pair
    op is Op.Connect -> RetryPolicies.maxAttempts(3)
    else             -> RetryPolicies.maxAttempts(2)
}
```

Which policy an op gets is resolved most-specific first:

1. **Per call** — `request(op, retry = RetryPolicies.None)` overrides everything for that one call.
   This is the deliberate "I know this write *is* safe to repeat" opt-in (or the reverse — force a
   single attempt).
2. **Per session** — `DefaultAgentSession(retryPolicyFor = …)` swaps the whole default table;
   otherwise `defaultRetryPolicyFor` applies.

Write-safety therefore lives in *which policy an op gets by default* — **writes and pairing default
to `None`**, because re-issuing a `Write` whose reply was merely lost would apply it twice — not in
a runtime gate. `timeout` on `request()` is **per attempt**; a `TRANSPORT_LOST` retry waits (up to
the policy's delay) for the link to reconnect rather than busy-failing.

### The completion contract: exact on success, ambiguous on lost-reply

The agent's `EngineBleBackend` is exact on the radio — reads and with-response writes resume on the
real GATT completion callback, not a poll (see [design-decisions.md](design-decisions.md#known-boundaries--extension-points)).
But the client never observes that callback directly; it only learns the outcome via a `Reply(cid)`
over the WebSocket, and the agent sends that Reply **only after `backend.write`/`backend.read`
returns**. Two consequences follow directly from that ordering:

- **A delivered `Ok` is exact, not probabilistic.** It cannot precede the agent's real completion, so
  `write()` returning ⟺ (for with-response) the peripheral acknowledged at the ATT layer; `read()`
  returning ⟺ the actual `onCharacteristicRead` bytes. There is no false positive to guard against.
- **A lost Reply is irreducibly ambiguous, not a bug to fix.** If the Reply is dropped, the request
  times out, or the transport dies in the window between the agent completing and the Reply landing,
  the client sees `TIMEOUT` / `TRANSPORT_LOST` — **even though the write may have already succeeded on
  the radio.** No amount of agent-side exactness removes this; it's the at-least-once/at-most-once
  split any request/reply protocol has over an unreliable transport.

This is why **writes, descriptor-writes, and `Pair` are non-idempotent and default to
`RetryPolicies.None`** (above) and are **not** in the reconcile-on-reconnect replay set (`Connect` /
`ObserveStart` / `Scan` / `SetConnParams` are — see `trackForReplay`): auto-retrying or replaying a
mutating op whose Reply was merely lost risks a silent double-apply (e.g. double-dispensing,
double-incrementing) — the exact hazard [`Op.isIdempotent`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Op.kt)'s
KDoc warns about. A `TIMEOUT`/`TRANSPORT_LOST` on a write is the SDK deliberately refusing to guess;
the app owns the reconcile (e.g. a read-back, or an idempotent retry it constructs itself). Idempotent
ops (read, connect…) *do* auto-retry on transient errors, because repeating them is harmless.

**Write-without-response (`withResponse = false`) has one more layer of ambiguity, inherent to BLE
itself:** an `Ok` there means "handed to the local radio controller," not "the peripheral received
it" — WWR has no ATT-level acknowledgement, so nothing (client, agent, or engine) can ever report
peripheral-side rejection for it. Design idempotent writes or a read-back confirmation for WWR paths
where that distinction matters.

### `defaultWebSocketHttpClient()` — per-platform engine

[`WebSocketClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/WebSocketClient.kt)
(expect) with one `actual` per target:

```kotlin
expect fun defaultWebSocketHttpClient(): HttpClient   // commonMain
```

| Target | Engine | File |
|---|---|---|
| JVM | CIO | [`WebSocketClient.jvm.kt`](../client-sdk/src/jvmMain/kotlin/dev/warsha/remoteble/client/WebSocketClient.jvm.kt) |
| Android | OkHttp | [`WebSocketClient.android.kt`](../client-sdk/src/androidMain/kotlin/dev/warsha/remoteble/client/WebSocketClient.android.kt) |
| iOS | Darwin (NSURLSession) | [`WebSocketClient.ios.kt`](../client-sdk/src/iosMain/kotlin/dev/warsha/remoteble/client/WebSocketClient.ios.kt) |

This is a convenience only — the transport accepts any `HttpClient { install(WebSockets) }`,
so an app needing proxy/TLS-pinning/timeout config builds its own and hands it in.

---

## Layer 2 — Session (`AgentSession`)

[`AgentSession.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/AgentSession.kt)

Turns the byte pipe into a request/response + event API.

```kotlin
interface AgentSession {
    val transportState: StateFlow<TransportState>
    suspend fun request(op: Op, timeout: Duration = DEFAULT_TIMEOUT): OpResult
    suspend fun dispatch(op: Op, timeout: Duration = DEFAULT_TIMEOUT): Deferred<OpResult>
    fun events(): Flow<AgentEvent>     // hot, shared; consumers filter by subId/scanId
    fun nextStreamId(): Long           // session-global id for tagging streams
    fun fireAndForget(op: Op)          // best-effort teardown (scan.stop / observe.stop)
    companion object { val DEFAULT_TIMEOUT = 15.seconds }
}
```

**`dispatch`** (0.8.3) sends [op]'s frame and returns a `Deferred<OpResult>` immediately, instead of
suspending for the whole round trip like `request`. The send happens *before* `dispatch` returns, so
a caller invoking it several times in a row from one coroutine — the
[`writeWithoutResponseBurst`](#writewithoutresponseburst--pipelining-not-a-wire-change-083--feature-c)
use case below — gets its frames on the wire in that same order without waiting on any Reply in
between. It's the same single-attempt send-and-track logic `request`'s per-attempt path uses
internally, just exposed without the blocking await; ordinary callers never need `dispatch` directly.

### `DefaultAgentSession`

```kotlin
class DefaultAgentSession(
    transport: AgentTransport,
    codec: ProtocolCodec,
    scope: CoroutineScope,
) : AgentSession
```

The session owns four responsibilities. Each maps to a piece of state and a small
amount of concurrency-safe code.

**1. Correlation-id matching.** `request()` allocates a monotonic `cid` (an
`AtomicLong`), parks a `CompletableDeferred<OpResult>` in a `pending` map (guarded by
a `Mutex`), encodes and sends the `Command`, then awaits the deferred under a
timeout. A background loop collects `transport.incoming`, decodes each frame, and for
a `Reply` completes the matching deferred by `cid`. Events are emitted to the shared
event flow; a client never receives `Command`s.

```kotlin
suspend fun request(op, timeout): OpResult {
    if (transport.state.value != CONNECTED) return Err(TRANSPORT_LOST)   // fail fast
    val cid = ids.incrementAndFetch()
    pending[cid] = CompletableDeferred()
    try { transport.send(codec.encode(Command(cid, op))) }
        catch (CancellationException) { removePending(cid); throw }
        catch (Throwable) { removePending(cid); return Err(TRANSPORT_LOST) }
    val result = withTimeoutOrNull(timeout) { deferred.await() } ?: Err(TIMEOUT)
    trackForReplay(op, result)     // (4) below
    return result
}
```

**2. Per-request timeout.** `withTimeoutOrNull(timeout)` yields `Err(TIMEOUT)` rather
than hanging or throwing. Cleanup of the `pending` entry runs under
`NonCancellable` so a cancelled caller still removes its slot.

**3. Event fan-out.** `events()` exposes a `MutableSharedFlow<AgentEvent>`
(`extraBufferCapacity = 256`, `onBufferOverflow = DROP_OLDEST`) as a read-only shared flow.
`nextStreamId()` hands out **session-global** ids so a `subId`/`scanId` is unique across all
peripherals on the session (this avoids cross-peripheral collisions). Consumers —
`RemoteGattClient.observe` and `RemoteScanSource.advertisements` — filter the shared flow by
their id. `DROP_OLDEST` matters: events are emitted from the *same* decode loop that dispatches
replies, so `emit()` must never suspend — otherwise a slow event subscriber would stall reply
delivery and hang every in-flight `request()`. Under sustained backpressure the oldest buffered
events are shed instead.

Two suspend helpers gate capability-specific work on the negotiated handshake set:
`AgentSession.awaitCapabilities()` suspends until the first `ServerHello` lands and returns the
set; `supportsCapability(cap)` is the boolean shorthand. (`connectionSlots()` similarly filters
the event stream to `SlotState`.)

**4. Reconcile-on-reconnect.** This is the subtlest piece. The session keeps a
*replay set* of what it believes is live on the agent:

```kotlin
private val activeConnections   = mutableSetOf<DeviceHandle>()
private val activeSubscriptions = mutableMapOf<Long, Op.ObserveStart>()  // by subId
private val activeScans         = mutableMapOf<Long, Op.ScanStart>()      // by scanId
```

`trackForReplay(op, result)` updates this set **only on `Ok` results** — a failed
connect/observe never happened on the agent, so there is nothing to re-establish. A
successful `Disconnect` also forgets that device's subscriptions (they cannot outlive
the connection):

```kotlin
private suspend fun trackForReplay(op, result) {
    if (result !is Ok) return
    when (op) {
        is Connect      -> activeConnections += op.device
        is Disconnect   -> { activeConnections -= op.device
                             activeSubscriptions.values.removeAll { it.device == op.device } }
        is ObserveStart -> activeSubscriptions[op.subId] = op
        is ObserveStop  -> activeSubscriptions.remove(op.subId)
        is ScanStart    -> activeScans[op.scanId] = op
        is ScanStop     -> activeScans.remove(op.scanId)
        else            -> {}
    }
}
```

A single coroutine watches `transport.state`. The **first** CONNECTED is the initial
connect (nothing to replay — guarded by an `everConnected` flag). A CONNECTED *after*
a prior connection triggers `reconcileOnReconnect`, launched on its own coroutine so
a slow replay can't delay reacting to a subsequent drop. A DISCONNECTED fails all
in-flight requests:

```kotlin
transport.state.collect { state -> when (state) {
    CONNECTED    -> { if (everConnected) scope.launch { reconcileOnReconnect() }; everConnected = true }
    DISCONNECTED -> failAllPending()      // complete every pending deferred with Err(TRANSPORT_LOST)
    CONNECTING   -> {}
}}
```

`reconcileOnReconnect` snapshots the replay set under a lock, then **re-issues
`Connect` for each device, then `ObserveStart`/`ScanStart` with their original stream
ids**. Because `observe()`/`advertisements()` flows are still collecting the shared
event flow by those same ids, events resume into them with **no app involvement**.
`Connect`/`ObserveStart` are idempotent on the agent, so a still-live link reconciles
harmlessly.

Why no synthetic BLE-disconnect on an IP blip? See
[design-decisions.md](design-decisions.md#reconnection).

---

## Layer 2½ — Protocol-typed op surface

These classes express GATT/scan in protocol types. They are usable directly by
non-Kable callers and by tests; the Kable adapters are thin wrappers over them.

### `RemoteGattClient`

[`RemoteGattClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteGattClient.kt) —
drives one device's ops over a session.

```kotlin
class RemoteGattClient(
    val handle: DeviceHandle,
    session: AgentSession,
    timeouts: RemoteTimeouts = RemoteTimeouts(),
) {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun discover(): List<ServiceNode>
    suspend fun read(char: CharRef): ByteArray
    suspend fun write(char: CharRef, value: ByteArray, withResponse: Boolean)
    suspend fun writeWithoutResponseBurst(char: CharRef, values: List<ByteArray>, window: Int = 8): List<OpResult>
    suspend fun requestMtu(mtu: Int): Int
    fun observe(char: CharRef, onSubscription: suspend () -> Unit = {}): Flow<ByteArray>
}
```

Each request-style method is a one-liner: `session.request(op, timeout).orThrow()` (or
`.payloadAs<T>()`). **`writeWithoutResponseBurst`** is the exception — see below — and
**`observe`** bridges the request side (observe.start/stop) and the event side (notifications by
`subId`) inside a `channelFlow`:

```kotlin
fun observe(char, onSubscription) = channelFlow {
    val subId = session.nextStreamId()
    val pump = session.events()
        .filterIsInstance<Notification>().filter { it.subId == subId }
        .onEach { send(it.value) }.launchIn(this)        // event side
    session.request(ObserveStart(subId, handle, char), timeouts.op).orThrow()  // request side
    onSubscription()                                      // mirrors Kable's contract
    awaitClose { pump.cancel(); session.fireAndForget(ObserveStop(subId)) }     // teardown on cancel
}
```

Key properties:
- The `ObserveStart` request runs **once on collect**; the flow then parks in
  `awaitClose` collecting events. This is precisely why the *session* must replay the
  subscription on reconnect — the flow won't re-issue it.
- The pump is not tied to transport state, so it survives an IP blip and resumes
  delivering once the session replays `ObserveStart`.
- Cancellation issues a best-effort `ObserveStop` via `fireAndForget` (a fire-and-
  forget request with a short timeout).

### `writeWithoutResponseBurst` — pipelining, not a wire change (0.8.3 / feature C)

Tracing a serial WWR loop end-to-end shows the dominant cost isn't the radio — it's **N sequential
WebSocket round trips**, one per `write()` call, because `session.request()` suspends until its
Reply lands before the next write is even sent. `writeWithoutResponseBurst` fixes that without
touching the wire: it uses [`AgentSession.dispatch`](#agentsession) to send up to `window` frames
before awaiting any of their Replies, instead of one-at-a-time.

```kotlin
suspend fun writeWithoutResponseBurst(
    char: CharRef,
    values: List<ByteArray>,
    window: Int = DEFAULT_BURST_WINDOW,   // 8
): List<OpResult> {
    val inFlight = ArrayDeque<Deferred<OpResult>>()
    for (value in values) {
        if (inFlight.size >= window) results += inFlight.removeFirst().await()
        inFlight += session.dispatch(Op.Write(handle, char, value, withResponse = false), timeouts.op)
    }
    while (inFlight.isNotEmpty()) results += inFlight.removeFirst().await()
    return results
}
```

- **Submission order is preserved end-to-end.** `dispatch` sends its frame synchronously before
  returning, and this function calls it from a single coroutine in a plain loop — so frames *land on
  the wire* in submission order. The reference agent then upholds it on its side: although it runs
  each `Command` on its own coroutine, it **chains writes per device** (`BleAgent` awaits the prior
  same-device write before calling `backend.write`), so a pipelined burst reaches the radio's FIFO
  GATT queue in submission order rather than coroutine-launch race order. Writes to *different*
  devices and non-write ops stay fully concurrent. (This per-device write ordering is now part of the
  agent contract — a third-party agent must uphold it too.) The ordering is guaranteed in code and
  asserted in CI (`BleAgentTest`); end-to-end on-radio confirmation is batched into the next
  hardware-rig round (plan §2d/§3). WWR *delivery* is still best-effort by BLE design, independent
  of order.
- **Bounded, not silent buffering.** At most `window` requests are ever in flight; a `window` of 1
  degenerates to today's serial-await behavior.
- **Independent failures.** Each value gets its own `OpResult` in submission order; one write
  failing (e.g. a local queue-full) doesn't cancel the rest of the burst.
- **WWR only.** With-response writes keep their per-write ack via plain `write()` — coalescing
  isn't offered there because it would erode the one completion signal WithResponse gives you.

`RemotePeripheral.writeWithoutResponseBurst(characteristic, values, window)` is the Kable-facing
wrapper — see [`RemotePeripheral`](#remoteperipheral) — returning `List<Result<Unit>>` instead of
`List<OpResult>`.

### `RemoteScanSource`

[`RemoteScanSource.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteScanSource.kt) —
the same channel-flow pattern for scanning.

```kotlin
class RemoteScanSource(session: AgentSession) {
    fun advertisements(filters: List<ScanFilter> = emptyList()): Flow<AdvertisementDto>
}
```

Opens a scan with a session-global `scanId` on collect, streams `ScanResult` events
filtered to that id, and issues a best-effort `ScanStop` on cancel.

Filter semantics, concurrent-scanner behaviour, replay for late joiners, the agent's
scan-concurrency modes and the errors they produce are in **[scanning.md](scanning.md)**; this
section documents only the class.

### `ScanConcurrencyMode`

[`ScanConcurrencyMode.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/ScanConcurrencyMode.kt)

```kotlin
enum class ScanConcurrencyMode { MULTIPLEXED, SINGLE, UNCONTROLLED, LEGACY_OR_UNKNOWN }

suspend fun AgentSession.awaitScanConcurrencyMode(): ScanConcurrencyMode
```

The agent's scan-isolation policy, derived from the negotiated capability set. Exactly one of
`scan.concurrency.multiplexed` / `.single` / `.uncontrolled` is advertised; a missing **or
contradictory** set is `LEGACY_OR_UNKNOWN`, never an inferred `UNCONTROLLED` — a client must not
read a safety property from an agent that did not state one.

Every session offers all three strings automatically, whether constructed directly or through Koin,
so the intersection returns exactly the agent's configured mode. That is also what gates the typed
`SCAN_UNAVAILABLE` error: an agent only sends it to a client that negotiated
`scan.concurrency.single`, and falls back to `AGENT_BUSY` otherwise.

### `RemoteTimeouts`

[`RemoteGattClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteGattClient.kt) —
per-op-class deadlines, tuned for the *relayed* worst case rather than localhost.

```kotlin
data class RemoteTimeouts(
    val connect: Duration = 30.seconds,    // link-up: scan→connect→bond is slow + variable
    val discover: Duration = 20.seconds,   // full GATT table
    val op: Duration = AgentSession.DEFAULT_TIMEOUT,   // 15s — single read/write/observe-start
)
```

Connect and discover get more headroom than a single read/write because over a relay
they are slower and more variable. Tighten these when you control the network path.

---

## Layer 3 — Kable adapters (the public face)

These implement Kable's interfaces so app code is identical local vs remote.

### `RemotePeripheral`

[`RemotePeripheral.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemotePeripheral.kt) —
a Kable `Peripheral` backed by an agent. App code written against `Peripheral` cannot
tell it from a local one.

```kotlin
class RemotePeripheral(
    handle: DeviceHandle,
    session: AgentSession,
    name: String? = null,
    requestedMtu: Int = DEFAULT_REQUESTED_MTU,    // 247
    timeouts: RemoteTimeouts = RemoteTimeouts(),
    dispatchers: DispatcherProvider = DefaultDispatcherProvider,   // injectable for tests
) : Peripheral
```

It delegates ops to an internal `RemoteGattClient` and maintains the Kable-facing
state:

- **`state: StateFlow<State>`** — Kable's `State` machine. `connect()` walks it
  `Disconnected → Connecting.Bluetooth → Connecting.Services → Connecting.Observes →
  Connected` (decomposed into `initializeGattConnection` + `establishConnectionScope`).
  A **non-cancellation** failure mid-connect resets to `Disconnected` and sends a
  best-effort `disconnect()` to the agent so a half-open link isn't left behind;
  `CancellationException` is rethrown untouched. An `init` block subscribes to
  `ConnectionState` events for this handle and, on a BLE `DISCONNECTED`, tears the
  connection down and flips state to `Disconnected` — this is how a *physical* drop surfaces.
- **`services: StateFlow<List<DiscoveredService>?>`** — populated from `discover()`
  on connect (mapped via `ServiceNode.toDiscoveredService()`), cleared on teardown.
- **MTU** — on connect, after discovery, it calls `requestMtu(requestedMtu)` and
  caches the **negotiated** value. `maximumWriteValueLengthForType` returns
  `negotiated − 3` (ATT header). It resets to the ATT default (23) while
  disconnected. On platforms that auto-negotiate (iOS), `RequestMtu` simply reports
  the live value.
- **`observe(characteristic, onSubscriptionAction)`** delegates straight to
  `RemoteGattClient.observe`, mapping the Kable `Characteristic` to a `CharRef`.

- **`scope`** is built on `dispatchers.default` — production uses
  `DefaultDispatcherProvider` (`Dispatchers.Default`); a test can inject a deterministic
  dispatcher through `RemotePeripheralFactory` (see below).

For deliberate retirement, use the additive `RemotePeripheral.shutdown()` API instead of Kable's
non-suspending `close()`:

```kotlin
when (peripheral.shutdown()) {
    RemoteShutdownResult.Completed -> Unit             // agent released the lease
    RemoteShutdownResult.TimedOut -> /* remote result unknown; local cleanup still finished */
    RemoteShutdownResult.TransportLost -> /* reconnect or rescan as appropriate */
    is RemoteShutdownResult.Failed -> /* inspect errorKind */
}
```

`shutdown(timeout)` stops local observations and child connection work in `finally`, even if the
disconnect reply is lost or times out. It returns the remote outcome rather than throwing ordinary
cleanup failures. `close()` remains Kable-compatible best-effort local cancellation; it does not
wait for remote release.

`read(Descriptor)`/`write(Descriptor)` and `pair()/unpair()/bondState` are supported when the
agent advertises the `descriptors`/`pairing` capabilities (otherwise the op is answered with
`UNSUPPORTED`). Two further ops sit *beyond* Kable's `Peripheral` surface (RemoteBLE extensions on
the concrete `RemotePeripheral`, outside the local/remote parity guarantee): `rssi()` (wire op
`Op.ReadRssi`, capability `rssi` — a live connected read on Android/Apple agents) and
`setConnParams(profile, hint)` (wire op `Op.SetConnParams`, capability `conn.params` — Android
agents today). Both answer `UNSUPPORTED` where the agent lacks the capability rather than throwing.
`identifier` is `lazy` and derived from the opaque agent handle via `deviceHandleToIdentifier`
(see below) — it isn't needed to operate the peripheral.

### `RemoteScanner` and `RemoteAdvertisement`

[`RemoteScanner.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteScanner.kt) /
[`RemoteAdvertisement.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteAdvertisement.kt)

```kotlin
class RemoteScanner(session: AgentSession, filters: List<ScanFilter> = emptyList())
    : Scanner<RemoteAdvertisement>

class RemoteAdvertisement internal constructor(dto: AdvertisementDto) : Advertisement {
    val handle: DeviceHandle    // the agent-minted handle for RemotePeripheral / the factory
    // name, rssi, uuids (parsed via parseBleUuid), manufacturerData(code) … from the DTO
}
```

`RemoteScanner.advertisements` is `RemoteScanSource(session).advertisements(filters)`
mapped into `RemoteAdvertisement`. The crucial field is `handle` — it carries the
agent-scoped token forward so the scanned device can be connected. Fields the wire
doesn't model (`txPower`, `isConnectable`, aggregate `manufacturerData`) are `null`.

#### `identifier` across platforms — `deviceHandleToIdentifier`

Both `RemoteAdvertisement.identifier` and `RemotePeripheral.identifier` go through an
internal `expect fun deviceHandleToIdentifier(value: String): Identifier`
([`RemoteIdentifier.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteIdentifier.kt)).
Kable's `Identifier` is a **local-platform** type, so the actuals differ: **Android** returns the
`String` as-is (its `Identifier` is a `String` typealias — this also skips the MAC-format check
Kable's Android `toIdentifier()` applies, which used to crash on a UUID handle); **Apple** does
`Uuid.parse`; **JVM** goes through the host radio's native parser (UUID on macOS, MAC on Windows,
a bluez id on Linux).

**Cross-platform translation (0.8.0).** The handle's format is set by the *agent's* platform (a
macOS agent mints UUIDs). Since 0.8.0 the client declares its local `IdentifierFormat` in the
handshake and requests the `identifier.translate` capability; a supporting agent then mints handles
already in the client's native format and reverse-maps ops back to the real radio device (see
[proposals/agent-side-identifier-translation.md](proposals/agent-side-identifier-translation.md)).
So `.identifier` normally succeeds on every client platform, and `deviceHandleToIdentifier` only
throws [`RemoteIdentifierUnavailableException`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteIdentifier.kt)
when translation is off: a **pre-0.8.0 agent**, the agent's **strict mode** (dashboard toggle), or
the still-stubbed **Linux-host-JVM `BLUEZ_JSON`** format. The value is identity/display only —
**remote ops key off `DeviceHandle`, so `.handle` remains the portable cross-platform identity.**
Covered by `RemoteAdvertisementIdentifierTest` (portable `.handle`), `RemoteIdentifierJvmTest`
(host-specific `.identifier`), and the agent's `HandleTranslatorTest` / `BleAgentTest` translation
e2e.

### `peripheralFor` and `RemotePeripheralFactory` — the decision point

[`RemotePeripheralFactory.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemotePeripheralFactory.kt) —
**the one place the local-vs-remote choice lives.**

```kotlin
enum class BleMode { LOCAL, REMOTE }

fun peripheralFor(mode: BleMode, advertisement: Advertisement, session: AgentSession? = null): Peripheral =
    when (mode) {
        LOCAL  -> Peripheral(advertisement)                     // Kable's platform builder (local radio)
        REMOTE -> RemotePeripheral((advertisement as RemoteAdvertisement).handle, session!!, advertisement.name)
    }

class RemotePeripheralFactory(
    session: AgentSession,
    dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {
    fun create(advertisement: RemoteAdvertisement): Peripheral
    fun create(handle: DeviceHandle, name: String? = null): Peripheral
}
```

Because both branches return the *same* `Peripheral` type, every line of app code
downstream is identical. Switching an app from local to remote is a one-line change
at this factory. The factory threads its `dispatchers` into every `RemotePeripheral`
it mints, so injecting a test `DispatcherProvider` here makes the produced peripherals'
scopes deterministic (guarded by `KableAdapterTest.factoryThreadsInjectedDispatcherIntoPeripheralScope`).

### `DispatcherProvider` — the dispatcher seam

[`DispatcherProvider.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/DispatcherProvider.kt) —
a one-member seam (`val default: CoroutineDispatcher`) so the SDK's own scopes can run on an
injected dispatcher in tests instead of `Dispatchers.Default`. Only `default` is modelled: the
library has no UI and does no blocking I/O of its own, and `Dispatchers.IO` doesn't exist in
`commonMain`. `DefaultDispatcherProvider` is the production binding; the Koin module and
`RemotePeripheralFactory` both default to it.

### UUID + discovery mapping helpers

- [`KableUuid.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/KableUuid.kt) —
  `parseBleUuid(value)` expands 16-/32-bit Bluetooth SIG short forms (e.g. `"180d"`)
  to their 128-bit canonical form using the Bluetooth base UUID, then parses to a
  Kotlin `Uuid`.
- [`RemoteDiscovered.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteDiscovered.kt) —
  builds Kable's `DiscoveredService`/`DiscoveredCharacteristic`/`DiscoveredDescriptor`
  tree from the agent's `List<ServiceNode>`, mapping `CharNode.properties` into a
  Kable `Characteristic.Properties` bitmask, so `peripheral.services` navigates
  exactly as for a local peripheral.

---

## Putting it together

The smallest remote client:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val transport = WebSocketAgentTransport("ws://agent-host:8080/agent", scope,
                                        defaultWebSocketHttpClient(), authToken = { "secret" })
val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)

// scan → pick a device → get a Kable Peripheral that happens to be remote
val advertisement = RemoteScanner(session).advertisements.first()
val peripheral = peripheralFor(BleMode.REMOTE, advertisement, session)

// from here, identical to local Kable:
peripheral.connect()
val service = peripheral.services.first { it != null }!!.first()
val characteristic = service.characteristics.first()
val value = peripheral.read(characteristic)
peripheral.observe(characteristic).collect { /* notifications */ }
```

See [flows.md](flows.md) for the frame-by-frame view of what each call sends and
receives.

## Dependency injection (Koin) — optional

Every class above takes its collaborators as constructor parameters, so manual wiring
(as shown) always works and is what the tests use. For apps that already run Koin,
[`client/di/ClientModule.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/di/ClientModule.kt)
offers `remoteBleClientModule(config)` — a `commonMain` module (so it compiles for every
target) that binds the same constructors:

```kotlin
startKoin {
    modules(remoteBleClientModule(RemoteBleClientConfig(url = "ws://agent-host:8080/agent",
                                                        authToken = { "secret" })))
}
val session = koin.get<AgentSession>()
val factory = koin.get<RemotePeripheralFactory>()
val scanner = koin.get<RemoteScanner> { parametersOf(emptyList<ScanFilter>()) }
```

The SDK never references Koin internally — the module is a convenience at the composition
root, not a dependency of the library. Override the bound `CoroutineScope` if your app owns
a lifecycle scope (e.g. a ViewModel scope) so the session/transport coroutines die with it;
the default is a process-lifetime supervisor scope.

---

## Logging

The client SDK depends on the shared [`:log`](../log) module, which provides a global
`Logger` object — a mutable minimum level gating a pluggable `LogSink`. The SDK
**defaults silent** (`Logger.level = null`); consumers opt in with two lines:

```kotlin
import dev.warsha.remoteble.log.Logger
import dev.warsha.remoteble.log.LogLevel
import dev.warsha.remoteble.log.PrintlnSink   // or AndroidLogSink / AppleLogSink

Logger.sink = PrintlnSink
Logger.level = LogLevel.DEBUG
```

### What's logged at each level

| Level | What | Where |
|---|---|---|
| **ERROR** | Reconnect gave up; initial connect failed (reconnect disabled) | `WebSocketAgentTransport` |
| **WARN** | Reconnect attempt N failed (backing off); sendCommand transport error | `WebSocketAgentTransport`, `DefaultAgentSession` |
| **INFO** | CONNECTED / DISCONNECTED; transport lost; reconciled N connections/subs/scans in Xms; hello sent; negotiated caps | `WebSocketAgentTransport`, `DefaultAgentSession`, `RemotePeripheral` |
| **DEBUG** | Request ok / failed / retry; sendCommand not connected; fireAndForget failure; sendHello failure; MTU failure; cleanup/teardown; WWR burst item failure | `DefaultAgentSession`, `RemotePeripheral` |
| **TRACE** | Payload bytes (truncated); per-event traffic — not used by current instrumentation (reserved for future) | — |

### Properties

- **Zero cost when off:** `Logger.at()` returns immediately if `level == null`; the
  message lambda is never invoked, no allocation occurs.
- **Runtime-switchable:** `Logger.level` is a regular `var`; changing it mid-session
  takes effect on the next log call.
- **Never logs secrets:** bearer tokens and `Authorization` headers are never
  interpolated into any log message. Payload bytes are only rendered at `TRACE` and
  truncated via `bytesPreview()`.
- **No per-advertisement/per-notification logging at INFO+:** hot BLE paths use `TRACE`
  only, so a busy scan adds zero sink calls at `INFO`.

### Kable radio-level logging

`RemoteBleClientConfig.kableLogging: (PeripheralBuilder.() -> Unit)?` is applied to a
*local* `Peripheral` created via `peripheralFor(BleMode.LOCAL, …)`. Default `null`
(Kable stays quiet). RemoteBLE logging = the relay; Kable logging = the radio.
