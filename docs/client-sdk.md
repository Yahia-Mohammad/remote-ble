# `:client-sdk` — Client SDK Reference

[← back to index](README.md)

The client SDK turns "I have a URL to an agent" into "I have a Kable `Peripheral`."
It is multiplatform (JVM for tests, Android, iOS) and depends on `:protocol`,
kotlinx-coroutines, the Ktor client, and Kable.

The module is organized as the four layers described in
[the architecture overview](README.md#the-clients-three-layers). This document walks
them bottom-up.

Source root: [`client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client)

---

## Layer 1 — Transport (`AgentTransport`)

[`AgentTransport.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/AgentTransport.kt)

One bidirectional, message-oriented, **BLE-agnostic** link to one agent at an opaque
endpoint.

```kotlin
enum class TransportState { CONNECTING, CONNECTED, DISCONNECTED }

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

[`WebSocketAgentTransport.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/WebSocketAgentTransport.kt) —
the production Layer-1 impl over a Ktor WebSocket. One protocol frame = one binary
WS message.

```kotlin
class WebSocketAgentTransport(
    url: String,                  // "ws://host:port/path" — opaque above this layer
    scope: CoroutineScope,
    httpClient: HttpClient,
    authToken: String? = null,    // bearer credential, sent as Authorization: Bearer <token>
    autoReconnect: Boolean = true,
    backoff: Backoff = Backoff(),
) : AgentTransport
```

Behavior:

- **`connect()` is idempotent** — guarded by a mutex; returns immediately if already
  CONNECTED; otherwise opens a session (CONNECTING → CONNECTED) and launches a
  receive loop.
- **On an unexpected close** the receive loop falls through to `onDisconnected`,
  which flips state to DISCONNECTED (this is what makes the session fail in-flight
  requests with `TRANSPORT_LOST`) and, if `autoReconnect`, launches
  `reconnectWithBackoff`. Stale receive loops from a session that was already
  replaced are ignored.
- **`reconnectWithBackoff`** retries `openSession()` with exponential `Backoff`
  until CONNECTED or `close()`. **The `incoming` channel survives reconnects** (it is
  created once, `Channel.UNLIMITED`), so the session's decode loop is uninterrupted.
- **`authToken`**, when set, is sent as an `Authorization: Bearer <token>` header on
  the WS upgrade. (Server-side enforcement lives in the agent — see
  [agent.md](agent.md#authentication).)

```kotlin
class Backoff(base: Duration = 50.ms, max: Duration = 2000.ms) {
    fun delayFor(attempt: Int): Duration   // base * 2^attempt, capped at max
}
```

The Ktor engine is **not** baked in — it is passed as `httpClient`. Each platform
provides a default via an `expect`/`actual` (next section).

### `defaultWebSocketHttpClient()` — per-platform engine

[`WebSocketClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/WebSocketClient.kt)
(expect) with one `actual` per target:

```kotlin
expect fun defaultWebSocketHttpClient(): HttpClient   // commonMain
```

| Target | Engine | File |
|---|---|---|
| JVM | CIO | [`WebSocketClient.jvm.kt`](../client-sdk/src/jvmMain/kotlin/dev/warsha/ble/remoteble/client/WebSocketClient.jvm.kt) |
| Android | OkHttp | [`WebSocketClient.android.kt`](../client-sdk/src/androidMain/kotlin/dev/warsha/ble/remoteble/client/WebSocketClient.android.kt) |
| iOS | Darwin (NSURLSession) | [`WebSocketClient.ios.kt`](../client-sdk/src/iosMain/kotlin/dev/warsha/ble/remoteble/client/WebSocketClient.ios.kt) |

This is a convenience only — the transport accepts any `HttpClient { install(WebSockets) }`,
so an app needing proxy/TLS-pinning/timeout config builds its own and hands it in.

---

## Layer 2 — Session (`AgentSession`)

[`AgentSession.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/AgentSession.kt)

Turns the byte pipe into a request/response + event API.

```kotlin
interface AgentSession {
    val transportState: StateFlow<TransportState>
    suspend fun request(op: Op, timeout: Duration = DEFAULT_TIMEOUT): OpResult
    fun events(): Flow<AgentEvent>     // hot, shared; consumers filter by subId/scanId
    fun nextStreamId(): Long           // session-global id for tagging streams
    fun fireAndForget(op: Op)          // best-effort teardown (scan.stop / observe.stop)
    companion object { val DEFAULT_TIMEOUT = 15.seconds }
}
```

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

[`RemoteGattClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteGattClient.kt) —
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
    suspend fun requestMtu(mtu: Int): Int
    fun observe(char: CharRef, onSubscription: suspend () -> Unit = {}): Flow<ByteArray>
}
```

Each request-style method is a one-liner: `session.request(op, timeout).orThrow()` (or
`.payloadAs<T>()`). The interesting part is **`observe`**, which bridges the
request side (observe.start/stop) and the event side (notifications by `subId`) inside
a `channelFlow`:

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

### `RemoteScanSource`

[`RemoteScanSource.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteScanSource.kt) —
the same channel-flow pattern for scanning.

```kotlin
class RemoteScanSource(session: AgentSession) {
    fun advertisements(filters: List<ScanFilter> = emptyList()): Flow<AdvertisementDto>
}
```

Opens a scan with a session-global `scanId` on collect, streams `ScanResult` events
filtered to that id, and issues a best-effort `ScanStop` on cancel.

### `RemoteTimeouts`

[`RemoteGattClient.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteGattClient.kt) —
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

[`RemotePeripheral.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemotePeripheral.kt) —
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

`read(Descriptor)`/`write(Descriptor)` and `pair()/unpair()/bondState` are supported when the
agent advertises the `descriptors`/`pairing` capabilities (otherwise the op is answered with
`UNSUPPORTED`). The one remaining boundary is `rssi()`, which has no wire op and throws
`UnsupportedOperationException`. `identifier` is `lazy` and derived from the opaque agent handle
via `deviceHandleToIdentifier` (see below) — it isn't needed to operate the peripheral.

### `RemoteScanner` and `RemoteAdvertisement`

[`RemoteScanner.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteScanner.kt) /
[`RemoteAdvertisement.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteAdvertisement.kt)

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
([`RemoteIdentifier.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteIdentifier.kt)),
**not** Kable's `String.toIdentifier()`. The agent mints handles as macOS CoreBluetooth
**UUID** strings, but Kable's Android `Identifier` is a MAC address and its `toIdentifier()`
throws `"MAC Address has invalid format"` for a UUID — which crashed Android clients the
moment they read `identifier`. The actuals: **JVM** wraps it in an opaque `PeripheralId`
(no validation); **Android** returns the `String` as-is (its `Identifier` is a `String`
typealias); **Apple** does `Uuid.parse` (handles are UUIDs). The value is identity/display
only — remote ops key off `DeviceHandle`. Guarded by `RemoteAdvertisementIdentifierTest`.

### `peripheralFor` and `RemotePeripheralFactory` — the decision point

[`RemotePeripheralFactory.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemotePeripheralFactory.kt) —
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

[`DispatcherProvider.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/DispatcherProvider.kt) —
a one-member seam (`val default: CoroutineDispatcher`) so the SDK's own scopes can run on an
injected dispatcher in tests instead of `Dispatchers.Default`. Only `default` is modelled: the
library has no UI and does no blocking I/O of its own, and `Dispatchers.IO` doesn't exist in
`commonMain`. `DefaultDispatcherProvider` is the production binding; the Koin module and
`RemotePeripheralFactory` both default to it.

### UUID + discovery mapping helpers

- [`KableUuid.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/KableUuid.kt) —
  `parseBleUuid(value)` expands 16-/32-bit Bluetooth SIG short forms (e.g. `"180d"`)
  to their 128-bit canonical form using the Bluetooth base UUID, then parses to a
  Kotlin `Uuid`.
- [`RemoteDiscovered.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/RemoteDiscovered.kt) —
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
                                        defaultWebSocketHttpClient(), authToken = "secret")
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
[`client/di/ClientModule.kt`](../client-sdk/src/commonMain/kotlin/dev/warsha/ble/remoteble/client/di/ClientModule.kt)
offers `remoteBleClientModule(config)` — a `commonMain` module (so it compiles for every
target) that binds the same constructors:

```kotlin
startKoin {
    modules(remoteBleClientModule(RemoteBleClientConfig(url = "ws://agent-host:8080/agent",
                                                        authToken = "secret")))
}
val session = koin.get<AgentSession>()
val factory = koin.get<RemotePeripheralFactory>()
val scanner = koin.get<RemoteScanner> { parametersOf(emptyList<ScanFilter>()) }
```

The SDK never references Koin internally — the module is a convenience at the composition
root, not a dependency of the library. Override the bound `CoroutineScope` if your app owns
a lifecycle scope (e.g. a ViewModel scope) so the session/transport coroutines die with it;
the default is a process-lifetime supervisor scope.
