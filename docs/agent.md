# `:agent` — Agent Reference

[← back to index](README.md)

The agent is the process that owns the real Bluetooth radio and serves the protocol
to clients over an IP link. This `:agent` module is the **JVM reference** (the radio
engine is [Kable](https://github.com/JuulLabs/kable)'s JVM/`btleplug` backend — the same
BLE library the client SDK is built on) and depends on `:protocol`, kotlinx-coroutines,
the Ktor server, and Kable. Kable's `btleplug` backend is native Rust, so the engine
itself is cross-platform (macOS / Linux / Raspberry Pi).

> There is also a **native Rust agent**, [`agent-rs`](../agent-rs), that speaks the exact
> same CBOR wire contract (`btleplug` engine; macOS / Linux). It's a lightweight
> v1-baseline alternative — no status dashboard or optional capability extensions yet — and
> its wire format is pinned to this one by cross-language interop tests. See
> [build-and-testing.md](build-and-testing.md#the-native-rust-agent-agent-rs-tests).

### Two distinct agents — and why `btleplug` is not the name of either

These are **two separate implementations**, and it's worth being precise about identity
because both ultimately reach the radio through the same low-level library:

| Agent | Identity (`agentInfo`) | How it drives the radio |
|---|---|---|
| `:agent` (this module, JVM/Kotlin) | `kable/<os>` | **Kable** — its `Peripheral`/`Scanner` API. On the JVM, Kable's backend *happens to be* `btleplug` (native Rust, via `kable-btleplug-ffi`), but that is Kable's internal plumbing; the agent code only ever sees Kable. |
| `agent-rs` (native Rust) | `RemoteBle-Agent-RS <ver>` | **`btleplug` directly** (tokio + tokio-tungstenite + btleplug). |

So `btleplug` is **shared plumbing reached two different ways**, not the name of an agent.
The JVM agent's identity is **Kable**; the agent that genuinely *is* "the btleplug agent" is
the native Rust one. (The startup banner and `agentInfo` say *Kable*, not "Kable/btleplug",
to keep this line crisp.)

It mirrors the client's layering in reverse:

```
   network seam        AgentWebSocketServer   (Ktor CIO; binary WS message ⇄ frame; + dashboard)
        │  hands a byte link to ▼
   backend seam        AgentBackend           (fun interface: serve(incoming, outgoing, scope, connectionId): Job)
        │  the production impl ▼
   protocol handler    BleAgent               (decode Command → drive BleBackend → emit Reply/Event)
        │  radio seam ▼
   radio seam          BleBackend             (portable BLE op surface)
        │  real impl ▼
   real radio          EngineBleBackend       (drives Kable's connection-oriented Peripheral API)
```

Source: [`agent/src/`](../agent/src)

| File | Role |
|---|---|
| [`AgentWebSocketServer.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/AgentWebSocketServer.kt) | Ktor server; `AgentBackend`; auth gate; client tracking; dashboard routes; `FakeAgentBackend`/`BlackholeBackend` |
| [`BleAgentBackend.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/BleAgentBackend.kt) | Wires the real `BleAgent` over a `BleBackend` into the server |
| [`BleAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/BleAgent.kt) | The protocol op handler |
| [`AgentObserver.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/AgentObserver.kt) | Lifecycle hooks `BleAgent` reports (devices/scan/activity); no-op default |
| [`BleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/BleBackend.kt) | The portable radio op surface |
| [`EngineBleBackend.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/EngineBleBackend.kt) | Real backend over Kable's JVM (`btleplug`) `Peripheral`/`Scanner` |
| [`ConnectionWatcher.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/ConnectionWatcher.kt) | Polls `BleBackend.isConnected` every tick, `BleBackend.checkLiveness` (active probe) every `livenessInterval`, to catch unsolicited drops and start the lease release grace |
| [`FakeAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/FakeAgent.kt) | A canned, radio-free agent for client tests |
| [`AgentMonitor.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/AgentMonitor.kt) | Thread-safe live state (clients/hardware/logs) + JSON snapshot for the dashboard |
| [`Dashboard.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/Dashboard.kt) | The status dashboard HTML page + `/` and `/api/state` routes |
| [`Main.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/Main.kt) | The runnable macOS agent entrypoint (launched via `agent/run-agent.sh`) |

---

## The network seam — `AgentWebSocketServer`

[`AgentWebSocketServer.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/AgentWebSocketServer.kt)

Hosts an `AgentBackend` behind a Ktor (CIO) WebSocket endpoint. Each connection
becomes one bidirectional byte link: binary WS messages in/out are exactly the
protocol frames the backend consumes and produces. **The server knows nothing about
BLE** — that lives entirely in the backend.

```kotlin
class AgentWebSocketServer(
    port: Int,
    path: String = "/agent",
    backend: AgentBackend = FakeAgentBackend(),
    authToken: String? = null,
    monitor: AgentMonitor? = null,   // optional: feeds the status dashboard
) {
    fun start()
    fun stop(gracePeriodMillis: Long = 100, timeoutMillis: Long = 500)
}
```

Per connection, the `webSocket(path)` handler assigns a monotonic `connectionId`,
registers the client with the `monitor` (remote address), then builds:
- `outgoing: suspend (ByteArray) -> Unit` = send a binary frame,
- `incomingFrames: Flow<ByteArray>` = the WS binary messages,

then calls `backend.serve(incomingFrames, outgoing, this, connectionId).join()` — keeping the
socket open until the backend's main job finishes (which happens when the client
disconnects and `incoming` closes); a `finally` unregisters the client from the monitor.
When a `monitor` is supplied, `start()` also installs the dashboard routes (`/`,
`/api/state`). (The `Application.monitor` Ktor property would shadow this constructor arg
inside the server lambda, so it's captured in a local first.)

### The backend seam — `AgentBackend`

```kotlin
fun interface AgentBackend {
    fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long): Job
}
```

`connectionId` is the server-assigned id for this client connection; the real backend
threads it through to `BleAgent` so device activity can be attributed to a client in the
dashboard.

This is the same byte-level seam the client's `AgentTransport` mirrors. Three impls:

| Impl | Purpose |
|---|---|
| `BleAgentBackend` | Hosts the **real** `BleAgent` over a `BleBackend`. |
| `FakeAgentBackend` | Hosts the canned `FakeAgent` (no radio) — for client end-to-end tests. |
| `BlackholeBackend` | Accepts the connection and never replies — for exercising client request timeouts. |

```kotlin
class BleAgentBackend(
    backend: BleBackend,
    maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    observer: AgentObserver = AgentObserver.None,   // the AgentMonitor, in the runnable agent
) : AgentBackend
```

### Authentication

When `authToken` is set, an `ApplicationCallPipeline.Plugins` interceptor gates the
endpoint **before the WebSocket handshake completes**: a request whose
`Authorization` header is not exactly `Bearer <token>` gets `401 Unauthorized` and
the upgrade never succeeds — so the client never reaches CONNECTED. This is cleaner
than accepting the socket and then closing it (which would make the client flap
CONNECTED→DISCONNECTED). The matching client credential is `WebSocketAgentTransport.authToken`.

The SDK owns no identity system beyond this shared bearer token; it is a hook, not a
framework.

---

## The protocol handler — `BleAgent`

[`BleAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/BleAgent.kt)

Decodes `Command` frames, drives a `BleBackend`, and emits `Reply`/`Event` frames
over the opaque byte link. It is `commonMain` (no platform deps) so it drops straight
into the WebSocket server *and* into in-memory tests.

```kotlin
class BleAgent(
    incoming: Flow<ByteArray>,
    outgoing: suspend (ByteArray) -> Unit,
    scope: CoroutineScope,
    backend: BleBackend,
    codec: ProtocolCodec = CborProtocolCodec(),
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,   // 4
    clientId: Long = 0L,                             // for monitor attribution
    observer: AgentObserver = AgentObserver.None,    // device/scan/activity hooks
) {
    fun start(): Job
}
```

It reports lifecycle to the `observer` (no-op by default): device connect/disconnect, the
names seen during a scan (so connected hardware can be labelled), and short per-client
activity lines. This is how the status dashboard learns what's happening without `BleAgent`
depending on any UI or platform code.

### Concurrency model

```kotlin
fun start() = scope.launch {
    incoming.collect { bytes ->
        val frame = try { codec.decode(bytes) }      // a malformed frame is skipped,
                    catch (e: Throwable) { return@collect }  // not session-fatal
        val cmd = frame as? Command ?: return@collect
        commandLimiter.acquire()                      // cap in-flight ops (backpressure)
        scope.launch { try { handle(cmd) } finally { commandLimiter.release() } }
    }
}
```

**Each command runs on its own coroutine**, so a slow `read` can't block an
`observe.stop`. A **malformed/undecodable frame is logged and skipped** rather than
failing the collect (which would drop the whole session). A per-connection
`Semaphore` (`maxInFlightCommands`, default 64) caps concurrent commands: once hit,
the decode loop suspends, applying backpressure to the link so a command flood can't
spawn unbounded coroutines. Shared mutable state — the set of connected devices, the
scan jobs, the observe jobs — is guarded by a single `Mutex`:

```kotlin
private val connected   = mutableSetOf<String>()
private val scanJobs    = mutableMapOf<Long, Job>()
private val observeJobs = mutableMapOf<Long, Job>()
```

### Op handling

`handle(cmd)` is a `when` over the op type. Per-op notes:

- **`Connect`** first **acquires the peripheral's ownership lease** from the shared
  `PeripheralRegistry`; if another client owns it (exclusive, the default) it replies
  `Err(PERIPHERAL_BUSY)` before touching the radio. It then reserves a connection slot
  under the mutex *before* the (slow) `backend.connect()` so the cap is honored despite
  concurrency; on success it marks the lease connected and emits a
  `ConnectionState(CONNECTED)` event. Connecting an already-connected device is an
  **idempotent success** (no re-emit). If the slot cap (`maxConnections`, default 4)
  is hit, it replies `Err(NO_CONNECTION_SLOT)`. A failed connect **releases the
  reserved slot and the lease**.
- **`Disconnect`** disconnects via the backend, frees the slot, starts the lease's
  **release grace** (`leaseGrace`, default 10s — a quick reconnect keeps ownership),
  and emits `ConnectionState(DISCONNECTED)`.
- **`Discover` / `Read` / `Write` / `RequestMtu`** call straight through to the
  backend and reply with the appropriate payload (`Services` / `Bytes` / `Ok` /
  `Mtu`).
- **`ScanStart` / `ObserveStart`** launch a backend flow, pump each emission into a
  `ScanResult`/`Notification` event tagged with the request's `scanId`/`subId`, and
  reply `Ok`. A backend stream failure is caught (`.catch {}`, **logged** via the
  observer rather than silently swallowed) so it ends *that* stream, not the agent.
  Starting a stream with an id that's already active cancels
  the previous job — making `ObserveStart`/`ScanStart` **replay-safe** (the client's
  reconcile re-issues them with the same id).
- **`ScanStop` / `ObserveStop`** cancel and forget the job for that id.

### Peripheral ownership

`connected`/`scanJobs`/`observeJobs` are **per session** (one `BleAgent` per WebSocket).
Cross-client ownership of the shared radio lives in one `PeripheralRegistry` injected into
every `BleAgent`:

- A peripheral is **leased to one client** while exclusive (the default; switchable per
  peripheral). `Connect` acquires before the slot reservation, so an owned peripheral is
  rejected with `PERIPHERAL_BUSY`.
- The model is **uniform**: an "owner temporarily gone" event schedules a per-lease release
  timer; an "owner back" event cancels it. The cause only sets the delay and warmth.
- **Transport drop → `transportGrace`, link kept warm.** When the WebSocket closes,
  `BleAgent`'s job-completion teardown hands off to the registry (`onTransportDropped`),
  which leaves the radio link **up** and schedules release. A reconnect within the window
  resumes; on expiry the injected `onRelease` disconnects the warm link.
- **BLE disconnect → `leaseGrace`.** Explicit `Disconnect` and unsolicited drops (the latter
  caught by **`ConnectionWatcher`**, which polls `BleBackend.isConnected` — a cached read of
  Kable's `Peripheral.state` — every tick, and `BleBackend.checkLiveness` — an active GATT
  round-trip, `EngineBleBackend`'s override — every `livenessInterval`) schedule release; the
  lease frees only if the peripheral stays down for the window. The active probe exists because
  a peripheral that vanishes without a clean BLE-level teardown (crashed, force-stopped, walked
  out of range) can leave the cached state reporting "connected" until an LL supervision timeout
  that's tens of seconds or effectively unbounded — the fast per-tick check alone never catches
  that case. Unlike an explicit `Disconnect` (which the client already knows about, having asked
  for it), an unsolicited drop also needs telling: `registry.onUnsolicitedDisconnect` pushes
  `ConnectionState(DISCONNECTED)` to the owning client's live connection via a callback
  `BleAgent.start()` registers (`registerClient`) — see the [error mapping](#error-mapping) note.
- **Resume needs identity.** Sockets get a fresh id each time, so ownership is keyed by the
  **stable client id** from the handshake (`CLIENT_ID_HEADER`); a returning client re-acquires
  its own leases. A client sending none falls back to its connection id and never resumes.
- **The switch** is operator-side: `POST /api/peripheral/exclusive` (dashboard) or
  `REMOTE_BLE_EXCLUSIVE` for the global default; `REMOTE_BLE_LEASE_GRACE_MS` /
  `REMOTE_BLE_TRANSPORT_GRACE_MS` tune the windows (shown read-only on the dashboard).

The registry is engine-free and unit-tested with virtual time; the physical disconnect is the
injected `onRelease` callback and `ConnectionWatcher` is the only piece that polls the radio.

### Error mapping

`handle` wraps the op in a `try/catch`:

```kotlin
catch (e: CancellationException) throw e
catch (e: AgentException)        reply(cid, Err(e.error))                          // backend's chosen ErrorKind
catch (e: Throwable)             reply(cid, Err(AgentError(GATT_ERROR, message=e.message)))  // unexpected
```

So a backend reports a precise failure by throwing `AgentException(AgentError(kind,
…))`; anything unexpected degrades to a `GATT_ERROR` with the message preserved.

> `BleAgent` emits `ConnectionState(DISCONNECTED)` on an explicit `Op.Disconnect` directly, and on
> an *unsolicited* drop (device out of range, crashed) via the registry: `BleAgent.start()`
> registers a callback with `PeripheralRegistry.registerClient`, and `ConnectionWatcher` calls
> `PeripheralRegistry.onUnsolicitedDisconnect` when its cached-state or active-liveness checks
> (see [Peripheral ownership](#peripheral-ownership)) find a drop — that's the only path an
> unsolicited disconnect reaches a specific client's wire connection, since `ConnectionWatcher`
> itself has no live link to any one client. The callback is keyed by `clientKey` and replaced
> wholesale by a reconnect's fresh `BleAgent` (guarded by reference identity against the old
> connection's delayed teardown clobbering it).

---

## The radio seam — `BleBackend`

[`BleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/BleBackend.kt)

The physical BLE operations, decoupled from the wire protocol. `BleAgent` maps ops
onto this; the real impl drives Kable, tests use a deterministic fake.

```kotlin
interface BleBackend {
    fun scan(filters: List<ScanFilter>): Flow<AdvertisementDto>   // streams while collected
    suspend fun connect(device: DeviceHandle)
    suspend fun disconnect(device: DeviceHandle)
    fun isConnected(device: DeviceHandle): Boolean   // polled by ConnectionWatcher; default false
    suspend fun checkLiveness(device: DeviceHandle): Boolean   // active probe; default delegates to isConnected
    suspend fun discover(device: DeviceHandle): List<ServiceNode>
    suspend fun read(device: DeviceHandle, char: CharRef): ByteArray
    suspend fun write(device: DeviceHandle, char: CharRef, value: ByteArray, withResponse: Boolean)
    fun observe(device: DeviceHandle, char: CharRef): Flow<ByteArray>   // streams while collected
    suspend fun requestMtu(device: DeviceHandle, mtu: Int): Int
    // optional, capability-gated: readDescriptor/writeDescriptor, pair/unpair, requestConnectionPriority
}

// convenience for backends to fail an op with a specific ErrorKind:
internal fun bleError(kind: ErrorKind, gattStatus: Int? = null, message: String? = null): Nothing
```

**Failures are reported by throwing `AgentException`** (via `bleError`) with an
appropriate `ErrorKind`; `BleAgent` turns those into `OpResult.Err`. The "reached the
radio vs didn't" distinction (see [the error taxonomy](protocol.md#errors--agenterror--errorkind))
is the backend's responsibility — it knows whether a call actually touched the radio.

---

## The real backend — `EngineBleBackend`

[`EngineBleBackend.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/EngineBleBackend.kt)

The production `BleBackend` over Kable's JVM (`btleplug`) stack — the same
`Peripheral`/`Scanner` API the client SDK is built on, here used **server-side** to drive
the host's real radio.

### Connection-oriented, not fire-and-forget

Kable's `Peripheral` is a long-lived, **connection-oriented** object: every op is a plain
`suspend` call that completes (or throws) when the radio finishes — there is no polling and
no per-op timeout bookkeeping. The peripheral also owns its connection on its **own**
`CoroutineScope` (a `SilentSupervisor`), so the link survives after the op coroutine that
opened it returns. The backend keeps one `Peripheral` per `DeviceHandle` in a
`ConcurrentHashMap` so subsequent ops resolve back to the same live connection:

```kotlin
private val peripherals = ConcurrentHashMap<DeviceHandle, Peripheral>()
private fun resolve(device) = peripherals.getOrPut(device) { Peripheral(device.value.toIdentifier()) }
```

Every Kable call is funnelled through one small helper that maps failures to the protocol's
`ErrorKind` **while letting `CancellationException` propagate** (so structured cancellation is
never swallowed):

```kotlin
private suspend inline fun <T> bleOp(failure: ErrorKind, block: () -> T): T =
    try { block() } catch (c: CancellationException) { throw c } catch (t: Throwable) { bleError(failure, message = t.message) }
```

### Op-by-op

- **`scan`** — a `channelFlow` wrapping Kable's `Scanner`; each `ScanFilter` becomes one
  `match { services = …; name = Filter.Name.Exact(…) }` predicate (AND within a filter, OR
  across them). **The agent mints the `DeviceHandle` here** from the advertisement's
  `identifier` (`DeviceHandle(advertisement.identifier.toString())`). It uses `send` (not
  `trySend`) so advertisements apply backpressure rather than being dropped; cancelling the
  collect stops the scan.
- **`connect`** — `peripheral.connect()`; Kable suspends until connected (discovering
  services along the way) or throws → `CONNECTION_FAILED`.
- **`disconnect`** — `peripheral.disconnect()`, then **always `close()` and evict** the
  peripheral from the map so its native (`btleplug`/UniFFI) handles are released; a reconnect
  recreates a fresh one (Kable's recommended pattern).
- **`isConnected`** — reads `Peripheral.state.value is State.Connected` without creating a
  peripheral. `ConnectionWatcher` polls this every tick to detect unsolicited drops.
- **`checkLiveness`** — an active probe `ConnectionWatcher` runs far less often (every
  `livenessInterval`, since it may do real I/O): if still `Connected`, it forces a real GATT read
  (reads only — never writes, to stay side-effect-free) with a `LIVENESS_PROBE_TIMEOUT` (5s)
  budget. It prefers the first `read`-property characteristic; failing that (a notify/indicate-only
  peripheral) it reads that characteristic's CCCD descriptor (0x2902, always readable per spec), so
  those devices are actively probed too rather than silently trusted. Only a peripheral exposing
  neither — or one whose services aren't discovered yet — falls back to the cached state. This is
  what catches a link the platform still calls "connected" but that's actually dead.
- **`discover`** — reads `peripheral.services.value` (populated by `connect()`) and maps it to
  `ServiceNode`/`CharNode`. No incremental "wait for stable" dance — Kable surfaces a complete
  tree once connected.
- **`read` / `write`** — resolve the `DiscoveredCharacteristic` from the service tree and call
  `peripheral.read(...)` / `peripheral.write(..., WriteType.WithResponse|WithoutResponse)`;
  failures map to `READ_FAILED` / `WRITE_FAILED`.
- **`observe`** — returns `peripheral.observe(characteristic)` directly (Kable manages the CCCD
  subscribe/unsubscribe over the flow's lifecycle).
- **`readDescriptor` / `writeDescriptor`** — same resolution against `DiscoveredDescriptor`
  (capability `descriptors`, advertised on every platform).
- **`requestMtu`** — `btleplug` exposes no MTU-negotiation API, so the agent doesn't advertise
  an MTU capability; this **echoes** the requested value so a probing client degrades cleanly.

Helpers guard connected-only ops (`connectedPeripheral` → `NOT_CONNECTED`) and walk the
discovered-service tree (`findCharacteristic` / `findDescriptor` → `CHARACTERISTIC_NOT_FOUND`).

> **`CharNode.properties`** is the standard GATT property bitmask, read straight from Kable's
> `DiscoveredCharacteristic.properties` on every platform (the old Blue-Falcon caveat — bits
> only on the macOS engine — no longer applies).

> **Capabilities.** `btleplug` offers no bonding or connection-priority control, so the macOS
> reference agent advertises only `descriptors`. The `pair`/`unpair`/`requestConnectionPriority`
> ops fall back to the `BleBackend` defaults (`UNSUPPORTED`).

---

## The canned backend — `FakeAgent`

[`FakeAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/FakeAgent.kt)

A complete agent with **no real radio** — it consumes `Command` frames and emits
`Reply`/`Event` frames directly (it sits at the same `incoming`/`outgoing` seam as
`BleAgent`, so it drops into `AgentWebSocketServer` via `FakeAgentBackend`). It is the
backbone of the client end-to-end tests: it lets the unchanged session + adapters be
exercised over both the in-memory transport and a real WebSocket without hardware.

```kotlin
class FakeAgent(incoming, outgoing, scope, config: Config = Config(), codec = CborProtocolCodec()) {
    class Config(services, readValue, advertisements, notificationValues,
                 emitInterval = 50.ms, replyDelay = 0.s)   // replyDelay holds a request in-flight
    val activeScanCount: Int     // for asserting scan teardown
    val activeNotifyCount: Int   // for asserting subscription teardown
    fun start(): Job
}
```

Scans and subscriptions emit periodically (cycling the configured values) for as long
as they are active — which also sidesteps hot-flow subscription races in tests. The
configurable `replyDelay` is used to hold a request in-flight (e.g. to test
transport-drop-fails-in-flight) and, under virtual time, to test the differentiated
`RemoteTimeouts`.

---

## The runnable agent — `Main`

[`Main.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/Main.kt)

```kotlin
fun main(args: Array<String>) {
    val config = AgentConfig(
        port = args.firstOrNull()?.toIntOrNull() ?: AgentConfig.DEFAULT_PORT,
        authToken = System.getenv("REMOTE_BLE_TOKEN")?.takeIf { it.isNotBlank() }, // optional bearer auth
    )
    val app = startKoin { modules(agentModule(config)) }
    app.koin.get<AgentWebSocketServer>().start()
    Runtime.getRuntime().addShutdownHook(Thread { /* stop server + disconnect peripherals */ })
    CountDownLatch(1).await()   // run until killed (SIGTERM/SIGINT → graceful shutdown)
}
```

```sh
agent/run-agent.sh 8080                          # open endpoint
REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080  # require a bearer token
```

> **Run it with `agent/run-agent.sh`, not `./gradlew :agent:jvmRun`.** A bare JVM process is
> killed with `SIGABRT` the instant it touches CoreBluetooth: macOS **TCC** requires the
> running process's main bundle to declare `NSBluetoothAlwaysUsageDescription`, and the
> request is only honored for apps launched via LaunchServices. The script compiles a tiny
> in-process JNI launcher ([`macos-launcher/launcher.c`](../agent/macos-launcher/launcher.c)),
> wraps it in a signed `RemoteBleAgent.app` carrying that key, and starts it with `open`
> (streaming the agent log). First run prompts once for Bluetooth permission. It uses
> `:agent:printJvmRuntimeClasspath` to assemble the JVM classpath. The launcher's main
> thread runs a menu bar status item ([`macos-launcher/MenuBar.swift`](../agent/macos-launcher/MenuBar.swift),
> 🟢/🟡 dot + recent activity, polling the dashboard below) so it's visible at a glance
> that the agent is running, without needing `ps` or a browser tab.

The endpoint and the **status dashboard** share the port: `ws://<host>:8080/agent` for
clients, `http://<host>:8080/` for the dashboard.

The object graph (`AgentMonitor`, `EngineBleBackend` → `BleAgentBackend` →
`AgentWebSocketServer`, plus `ConnectionWatcher`) is assembled by Koin in
[`di/AgentModule.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/di/AgentModule.kt)
— the single `AgentMonitor` is shared as both the backend's `AgentObserver` and the server's
dashboard source. The agent's classes keep their plain constructors — only this composition
root touches a DI container, mirroring the optional `remoteBleClientModule` on the client side.

---

## The status dashboard — `AgentMonitor` + `Dashboard`

[`AgentMonitor.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/AgentMonitor.kt) ·
[`Dashboard.kt`](../agent/src/jvmMain/kotlin/dev/warsha/ble/remoteble/agent/Dashboard.kt)

The agent serves a live, mobile-friendly status page from the same Ktor server:

- `GET /` — a single self-contained HTML page (no build step, no external assets) that polls
  `/api/state` once a second and renders three panels: **connected clients**, **connected
  hardware**, and a rolling **activity log**.
- `GET /api/state` — a JSON snapshot from `AgentMonitor`.

`AgentMonitor` is a thread-safe, in-memory store updated from two sides:
`AgentWebSocketServer` reports client connect/disconnect (id + remote address); `BleAgent`
reports device lifecycle and scan-seen names through the `AgentObserver` hooks. Hardware is
labelled from scan results and attributed to the owning client; when a client's socket drops,
its hardware is released. It is **read-only** — it never touches the radio. Wiring it is
optional: construct `AgentWebSocketServer` without a `monitor` and the routes simply aren't
installed.

---

## Resilience & stability

The agent is meant to run unattended and stay reachable to remote clients. Both agents
(`agent`, KMP/Kable; `agent-rs`, Rust/btleplug) were hardened so that no single unexpected
event or malformed input can crash them, and so they recover on their own. The behaviors below
hold for **both** unless noted.

**Crash isolation — one bad event never takes the process down.**
- *Transient accept failures (Rust).* The TCP accept loop logs and backs off (capped) on an
  `accept()` error (fd exhaustion, `ECONNABORTED`, a peer reset between SYN and accept) instead
  of propagating it out of `run()` and killing the agent.
- *Malformed frames.* An undecodable CBOR frame is logged and skipped; it never fails the
  read loop or tears down the client session. (KMP `BleAgent.start`, Rust `handle_connection`.)
- *Per-op failures* are caught and returned as `OpResult.Err`; cancellation always propagates.
  Unexpected (unmapped) failures return a generic error — raw internal exception text is logged
  server-side, not sent to the client.

**Recovery — the agent heals without a restart.**
- *Radio event listener (Rust).* `BtleplugBackend`'s adapter-event subscription runs in a
  supervising loop that re-subscribes with capped backoff if `events()` errors or the stream
  ends (adapter reset, Bluetooth toggle, USB re-enumeration). Without this the agent would go
  permanently deaf to scan results and disconnect events while still appearing alive.
- *Unsolicited BLE drops* free the owning lease. Both agents primarily learn about a drop from
  the native stack — `agent-rs` routes btleplug's `DeviceDisconnected` into the registry; KMP's
  `ConnectionWatcher` polls `BleBackend.isConnected` every tick — but that alone only reflects
  what CoreBluetooth/btleplug has *already* reported, which can stay "connected" indefinitely if
  a peripheral vanished without a clean BLE-level teardown (crashed, force-stopped, out of range)
  and the native link sits at an LL supervision timeout nobody's told about yet. Both agents also
  run an **active liveness probe** far less often (real I/O): KMP's `ConnectionWatcher` calls
  `BleBackend.checkLiveness` every `livenessInterval`; Rust's `BtleplugBackend` spawns a second
  background task (`spawn_liveness_prober`) alongside the event listener, re-running
  `discover_services()` on each tracked connection every `liveness_interval`
  (`REMOTE_BLE_LIVENESS_PROBE_MS` on both). Either path — native-reported or actively probed —
  feeds the same disconnect handling.
- *Lease grace timers.* Both registries schedule a per-lease release on "owner gone" and cancel
  it on "owner back". On expiry the lease is freed **and** the warm radio link is torn down via
  an injected teardown (KMP `onRelease`, Rust `set_teardown` → `BleBackend::disconnect`). This is
  what reclaims connections, slots, and pump tasks after a drop — see
  [Peripheral ownership](#peripheral-ownership).

**Availability — dead clients are detected fast.**
- *WebSocket liveness pings.* Each connection is pinged on an interval (default 15s) and closed
  if nothing — including the peer's auto-pong — is heard within a timeout (default 40s). A client
  that vanished without a TCP FIN (Wi-Fi drop, NAT timeout, sleep) is reclaimed in seconds rather
  than waiting on the OS TCP keepalive (minutes); the close also starts the lease grace timer.
  (KMP: Ktor `WebSockets { pingPeriodMillis/timeoutMillis }`; Rust: a ping arm in the send task.)

**Resource bounds — one client can't exhaust the host.**
- *Bounded outbound buffers.* Per-connection outbound is bounded (Rust frame channel = 512).
  Replies apply backpressure; **events are shed on overflow** so a notification flood never
  blocks the radio.
- *Capped in-flight commands.* A per-connection semaphore (default 64) limits concurrent ops;
  once hit, the read loop suspends, backpressuring the link instead of spawning unbounded tasks.

**Lifecycle hygiene.**
- *Graceful shutdown.* `SIGTERM`/`SIGINT` stops accepting and disconnects tracked peripherals
  (Rust `disconnect_all`; KMP shutdown hook over the registry snapshot) so a restart starts from
  a clean radio state.
- *No registration/task leaks.* Rust `start_scan` removes its registration if the radio scan
  fails to start; the per-connection event-pump task is aborted on transport drop.

**Tuning.** Ping period/timeout, in-flight-command cap, lease/transport grace, and slot count
are constructor params (KMP) / CLI+env (Rust, e.g. `REMOTE_BLE_LEASE_GRACE_MS`,
`REMOTE_BLE_TRANSPORT_GRACE_MS`); defaults are conservative.

**Verification boundary.** The *logic* above is covered by unit + integration tests (incl.
virtual-time tests for the Rust grace timers and a malformed-frame test for KMP). The
**live-radio** paths — a real adapter reset triggering re-subscribe, a real peripheral dropping
on grace expiry, `disconnect_all` on shutdown — are deferred to a hardware bring-up round with
the peripheral SDK.
