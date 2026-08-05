# `:agent` — Agent Reference

[← back to index](README.md)

The agent is the process that owns the real Bluetooth radio and serves the protocol
to clients over an IP link. This `:agent` module is the **Kotlin reference**, and
targets **JVM** (macOS / Linux / Raspberry Pi, via Kable's `btleplug` backend),
**Android**, and **iOS** (via Kable's own native Android BLE / CoreBluetooth backends —
no `btleplug` involved on those two) — depending on `:protocol`, kotlinx-coroutines, the
Ktor server, and Kable. Almost all of the agent's logic (radio ops, the WebSocket
server, the status dashboard, the Koin composition module) is `commonMain`: only the
process **entry point** differs per platform — a blocking CLI `main()` on the JVM, a
foreground `Service` + Compose UI on Android, and a Compose UI (with no background
support — see [below](#android--ios-a-phone-as-the-agent)) on iOS.

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
| `:agent` (this module, Kotlin, targets JVM/Android/iOS) | `kable/<platform>` | **Kable** — its `Peripheral`/`Scanner` API. On the JVM, Kable's backend *happens to be* `btleplug` (native Rust, via `kable-btleplug-ffi`); on Android/iOS it's Kable's own native Android BLE / CoreBluetooth backends. Either way that's Kable's internal plumbing — the agent code (`EngineBleBackend`) only ever sees Kable's common `Peripheral`/`Scanner` API, unchanged across all three targets. |
| `agent-rs` (native Rust) | `RemoteBle-Agent-RS <ver>` | **`btleplug` directly** (tokio + tokio-tungstenite + btleplug). |

So `btleplug` is **shared plumbing reached two different ways**, not the name of an agent.
The JVM agent's identity is **Kable**; the agent that genuinely *is* "the btleplug agent" is
the native Rust one. (The startup banner and `agentInfo` say *Kable*, not "Kable/btleplug",
to keep this line crisp.)

It mirrors the client's layering in reverse:

```
   network seam        AgentWebSocketServer   (Ktor CIO; binary WS message ⇄ frame; + dashboard)
        │  hands a byte link to ▼
   backend seam        AgentBackend           (fun interface: serve(incoming, outgoing, scope, connectionId, clientKey): Job)
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
| [`AgentWebSocketServer.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentWebSocketServer.kt) | Ktor server; `AgentBackend`; auth gate; client tracking; dashboard routes; `FakeAgentBackend`/`BlackholeBackend` |
| [`BleAgentBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgentBackend.kt) | Wires the real `BleAgent` over a `BleBackend` into the server |
| [`BleAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt) | The protocol op handler |
| [`AgentObserver.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentObserver.kt) | Lifecycle hooks `BleAgent` reports (devices/scan/activity); no-op default |
| [`BleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleBackend.kt) | The portable radio op surface |
| [`EngineBleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/EngineBleBackend.kt) | Real backend over Kable's common `Peripheral`/`Scanner` API — one implementation for all three targets |
| [`ScanConcurrencyMode.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanConcurrencyMode.kt) | The configured mode (`multiplexed`/`single`/`uncontrolled`) and its `REMOTE_BLE_SCAN_CONCURRENCY` parsing — see [below](#scan-concurrency-policy) |
| [`ScanCoordinator.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanCoordinator.kt) | Agent-lifetime owner of the single physical scan in the guaranteed modes: admission, fencing, identity merge, per-subscriber filtered fan-out |
| [`ScanOutboundArbiter.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanOutboundArbiter.kt) | Per-connection round-robin fairness across that connection's logical scans |
| [`SimulationProfile.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulationProfile.kt) | Strict, bounded `schemaVersion: 1` JSON profile decoded before simulated startup |
| [`SimulatedBleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulatedBleBackend.kt) | Deterministic `BleBackend` implementation for the JVM agent's radio-less profile mode |
| [`PeripheralByIdentifier.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/PeripheralByIdentifier.kt) | `expect`/`actual` bridge for reconstructing a `Peripheral` from a bare identifier — see [below](#the-real-backend--engineblebackend) |
| [`ConnectionWatcher.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ConnectionWatcher.kt) | Polls `BleBackend.isConnected` every tick, `BleBackend.checkLiveness` (active probe) every `livenessInterval`, to catch unsolicited drops and start the lease release grace |
| [`FakeAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/FakeAgent.kt) | A canned, radio-free agent for client tests |
| [`AgentMonitor.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentMonitor.kt) | Thread-safe live state (clients/hardware/logs) + a `Snapshot` served as JSON (HTML dashboard) or read directly (Compose UI) |
| [`Dashboard.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/Dashboard.kt) | The status dashboard HTML page + `/` and `/api/state` routes |
| [`Main.kt`](../agent/src/jvmMain/kotlin/dev/warsha/remoteble/agent/Main.kt) | The runnable JVM (macOS/Linux) agent entrypoint (launched via `agent/run-agent.sh`) |
| [`AgentRunner.kt`](../agent/src/mobileMain/kotlin/dev/warsha/remoteble/agent/AgentRunner.kt) | The Android/iOS composition root — serialized `STOPPED → STARTING → RUNNING → STOPPING` Koin-graph lifecycle, observable state, and structured stop result (see [below](#android--ios-a-phone-as-the-agent)) |
| [`ui/AgentApp.kt`](../agent/src/mobileMain/kotlin/dev/warsha/remoteble/agent/ui/AgentApp.kt) | The Compose Multiplatform mirror of the HTML dashboard |
| [`AgentService.kt`](../agent/src/androidMain/kotlin/dev/warsha/remoteble/agent/AgentService.kt) | Android foreground service keeping the process alive while backgrounded; observes `AgentRunner.running` and self-stops |
| [`IosAgentEntry.kt`](../agent/src/iosMain/kotlin/dev/warsha/remoteble/agent/IosAgentEntry.kt) | iOS entry point (`IosAgentSession`): owns the runner + idle-timer observer; `dispose()` on view teardown |
| `PlatformName` / `LanAddress` / `TokenStore` (`expect`/`actual`) | Per-platform bits the mobile UI needs: the host label (`agentInfo`), the LAN IPv4 for the `ws://` address, and development-only auth-token persistence (Android DataStore / iOS `NSUserDefaults`; not a protected production credential store) |
| [`AndroidAgentContext.kt`](../agent/src/androidMain/kotlin/dev/warsha/remoteble/agent/AndroidAgentContext.kt) | Holds the application `Context` the Android `actual`s (LAN address, token store) need |

---

## Radio-less JVM mode — `SimulatedBleBackend`

`Main.kt` normally builds `EngineBleBackend`. Passing `--simulate <profile.json>` (or setting
`REMOTE_BLE_SIMULATE`) instead parses `SimulationProfile` and wires `SimulatedBleBackend` through
the same `BleAgentBackend` and `AgentWebSocketServer` path. The client, protocol handler, lease
logic, authentication, and WebSocket server are therefore unchanged; only the radio seam moves.

This is the deterministic CI/demo path, not a generic scripting engine and not a Rust-agent feature.
Start with `agent/simulation/sim-hrm.json`, then see [simulation.md](simulation.md) for profile
semantics.

---

## The network seam — `AgentWebSocketServer`

[`AgentWebSocketServer.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentWebSocketServer.kt)

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
    monitor: AgentMonitor? = null,          // optional: feeds the status dashboard
    registry: PeripheralRegistry? = null,   // ownership/grace state; legacy shared toggle disabled for 0.9
    pingPeriod: Duration = 15.seconds,      // WebSocket keepalive: ping idle clients this often…
    pongTimeout: Duration = 40.seconds,     // …and close the session if no pong arrives within this
) {
    fun start()
    fun stop(gracePeriodMillis: Long = 100, timeoutMillis: Long = 500)
}
```

Per connection, the `webSocket(path)` handler assigns a monotonic `connectionId`,
derives a **stable `clientKey`** (the `CLIENT_ID_HEADER` the client sent, so ownership
survives reconnects — falling back to the connection id, in which case that client never
resumes), registers the client with the `monitor` (remote address), then builds:
- `outgoing: suspend (ByteArray) -> Unit` = send a binary frame,
- `incomingFrames: Flow<ByteArray>` = the WS binary messages,

then calls `backend.serve(incomingFrames, outgoing, this, connectionId, clientKey).join()` — keeping
the socket open until the backend's main job finishes (which happens when the client
disconnects and `incoming` closes); a `finally` unregisters the client from the monitor.
When a `monitor` is supplied, `start()` also installs the dashboard routes (`/`,
`/api/state`). (The `Application.monitor` Ktor property would shadow this constructor arg
inside the server lambda, so it's captured in a local first.)

### The backend seam — `AgentBackend`

```kotlin
fun interface AgentBackend {
    fun serve(incoming: Flow<ByteArray>, outgoing: suspend (ByteArray) -> Unit, scope: CoroutineScope, connectionId: Long, clientKey: String): Job
}
```

`connectionId` is the server-assigned id for this client connection; the real backend
threads it through to `BleAgent` so device activity can be attributed to a client in the
dashboard. `clientKey` is the client's **stable identity** (survives reconnects) — it keys
peripheral ownership in the `PeripheralRegistry` so a returning client re-acquires its own
leases (see [Peripheral ownership](#peripheral-ownership)).

This is the same byte-level seam the client's `AgentTransport` mirrors. Three impls:

| Impl | Purpose |
|---|---|
| `BleAgentBackend` | Hosts the **real** `BleAgent` over a `BleBackend`. |
| `FakeAgentBackend` | Hosts the canned `FakeAgent` (no radio) — for client end-to-end tests. |
| `BlackholeBackend` | Accepts the connection and never replies — for exercising client request timeouts. |

```kotlin
class BleAgentBackend(
    backend: BleBackend,
    lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    registry: PeripheralRegistry =                  // shared cross-client ownership; warm-link
        PeripheralRegistry(lifecycleScope, onRelease = { backend.disconnect(DeviceHandle(it)) }),  // teardown disconnects via the backend
    maxConnections: Int = BleAgent.DEFAULT_MAX_CONNECTIONS,
    observer: AgentObserver = AgentObserver.None,   // the AgentMonitor, in the runnable agent
    capabilities: Set<String> = backend.capabilities + BleAgent.AGENT_CAPABILITIES,
    agentInfo: String? = null,                      // identity in the handshake, e.g. "kable/<platform>"
) : AgentBackend
```

### Authentication

Credentials are **named principals** — a set of `name=secret` bearer pairs (`ClientCredentials`).
`REMOTE_BLE_TOKEN` remains the legacy alias for a `default` principal; `REMOTE_BLE_TOKENS` supplies
`name=secret,other=secret` pairs. When any credential is configured, an
`ApplicationCallPipeline.Plugins` interceptor gates the endpoint **before the WebSocket handshake
completes**: a request whose `Authorization` header is not exactly `Bearer <secret>` for some
credential gets `401 Unauthorized` and the upgrade never succeeds — so the client never reaches
CONNECTED (cleaner than accepting the socket and then closing it, which would make the client flap
CONNECTED→DISCONNECTED). Secrets are compared in constant time, and the verified credential's name
becomes the connection's **principal**, which scopes peripheral ownership: `X-RemoteBle-Client` is
only a reconnect key *within* a principal, never across principals. The matching client credential
is `WebSocketAgentTransport.authToken`.

Repeated failed upgrades are bounded by a fixed-memory `FailedAuthLimiter` (per-peer and global
ceilings with least-recently-seen eviction, so spoofed source addresses can't grow an unbounded
map); a peer past its ceiling gets `429 Too Many Requests`, with rate-limited denial logs. Only one
live WebSocket generation is permitted per `(principal, stable client id)` — a duplicate is refused
(conformance `LEASE-DUPLICATE-01`).

Desktop/headless agents **bind loopback by default**; a non-loopback bind requires at least one
credential (or the explicit `REMOTE_BLE_ALLOW_INSECURE_LAN` development override, which logs a
prominent unencrypted-service warning). The supported encrypted deployment is a TLS-terminating
reverse proxy or VPN with a local-only upstream — see
[tls-proxy-recipe.md](tls-proxy-recipe.md) for the verified `wss://` recipe (Caddy config, CA
handling, and the checks that prove the proxy forwards the bearer header and fails closed on an
untrusted certificate). The SDK owns no identity system beyond these bearer credentials; it is a
hook, not a framework.

---

## The protocol handler — `BleAgent`

[`BleAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt)

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
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,   // 8, agent-wide
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
  **idempotent success** (no re-emit). If the slot cap (`maxConnections`, default 8)
  is hit, it replies `Err(NO_CONNECTION_SLOT)`. A failed connect **releases the
  reserved slot and the lease**. The cap is enforced by the registry and is therefore
  **agent-wide**: the constraint is the host controller's, so two clients holding four
  peripherals each exhaust the same radio as one client holding eight.
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

> **0.9.0 release note:** the implementation described below still contains a legacy shared-mode
> switch, but its one-owner-plus-untracked-guests model is not release-safe. The 0.9.0 addendum
> disables that switch; a participant-based replacement is deferred to 0.9.1.

- A peripheral is **leased to one client** while exclusive (the supported release mode).
  `Connect` acquires before the slot reservation, so an owned peripheral is
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
- The pre-0.9 implementation's operator switch is not part of the 0.9.0 release surface.
  `REMOTE_BLE_LEASE_GRACE_MS` / `REMOTE_BLE_TRANSPORT_GRACE_MS` tune the windows (shown read-only
  on the dashboard).

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

[`BleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleBackend.kt)

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

[`EngineBleBackend.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/EngineBleBackend.kt)

The production `BleBackend` over Kable's **common** `Peripheral`/`Scanner` API — the same
API the client SDK is built on, here used **server-side** to drive the host's real radio.
It's `commonMain` and unchanged across targets: on the JVM Kable's backend is `btleplug`;
on Android/iOS it's Kable's own native Android BLE / CoreBluetooth backends. Nothing in
this file knows or cares which.

### Connection-oriented, not fire-and-forget

Kable's `Peripheral` is a long-lived, **connection-oriented** object: every op is a plain
`suspend` call that completes (or throws) when the radio finishes — there is no polling and
no per-op timeout bookkeeping. The peripheral also owns its connection on its **own**
`CoroutineScope` (a `SilentSupervisor`), so the link survives after the op coroutine that
opened it returns. The backend keeps one `Peripheral` per `DeviceHandle` in a plain map
guarded by an [atomicfu](https://github.com/Kotlin/kotlinx-atomicfu) lock (`java.util.
concurrent.ConcurrentHashMap` has no Kotlin/Native equivalent) so subsequent ops resolve
back to the same live connection:

```kotlin
private val lock = SynchronizedObject()
private val peripherals = mutableMapOf<DeviceHandle, Peripheral>()
private fun resolve(device) = synchronized(lock) {
    peripherals.getOrPut(device) { peripheralByIdentifier(device.value.toIdentifier()) }
}
```

`peripheralByIdentifier` is this module's own tiny `expect`/`actual`
([`PeripheralByIdentifier.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/PeripheralByIdentifier.kt)):
Kable's only truly common (`expect`) factory is `Peripheral(advertisement: Advertisement, …)` —
reconstructing one from nothing but a bare identifier (no live `Advertisement` in hand,
since a `DeviceHandle` is just an opaque string round-tripped over the wire) is a
plain, non-`expect` convenience each platform happens to add on its own: JVM reconnects by
address via `btleplug`, Android via `BluetoothAdapter.getRemoteDevice`, iOS via
`CentralManager.retrievePeripheral`. The three `actual`s just call each platform's own
convenience, preserving the exact reconnect-by-handle-alone behavior the JVM agent always
had. `resolve` also wraps `toIdentifier()` in a `try/catch`, mapping a **malformed handle**
(hostile input over the wire — every op path funnels through here, including the non-`bleOp`
`observe`) to a typed `UNKNOWN_DEVICE` error rather than letting a raw throwable escape.

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
  collect stops the scan. In the guaranteed modes this is driven by `ScanCoordinator` rather than
  called per client — see [Scan concurrency policy](#scan-concurrency-policy) below.
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
  an MTU capability; rather than **echo** the request (which would let a client believe a large
  MTU was negotiated and then oversize its writes), it returns the **ATT default minimum, 23** —
  the only value a client can safely size payloads against when nothing was actually negotiated.

Helpers guard connected-only ops (`connectedPeripheral` → `NOT_CONNECTED`) and walk the
discovered-service tree (`findCharacteristic` / `findDescriptor` → `CHARACTERISTIC_NOT_FOUND`).

> **`CharNode.properties`** is the standard GATT property bitmask, read straight from Kable's
> `DiscoveredCharacteristic.properties` on every platform (the old Blue-Falcon caveat — bits
> only on the macOS engine — no longer applies).

> **Capabilities.** `btleplug` offers no bonding or connection-priority control, so the macOS
> reference agent advertises only `descriptors`. The `pair`/`unpair`/`requestConnectionPriority`
> ops fall back to the `BleBackend` defaults (`UNSUPPORTED`).

### Scan concurrency policy

Two logical scans through one agent are not isolated by the radio on Apple hosts — a
`CBCentralManager` has exactly one scan, so a second `Scanner` can stop or silently re-parameterize
the first. This is reachable by a single ordinary client holding two `RemoteScanner`s, so the agent
mediates it rather than passing it through. The design record is
[proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md); the consumer-facing
contract is [scanning.md](scanning.md).

| Type | Role |
|---|---|
| [`ScanConcurrencyMode.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanConcurrencyMode.kt) | The mode enum — `MULTIPLEXED` (default), `SINGLE`, `UNCONTROLLED` — plus parsing for `REMOTE_BLE_SCAN_CONCURRENCY` |
| [`ScanCoordinator.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanCoordinator.kt) | Agent-lifetime, one instance per process. Owns the single physical scan, admits/retires logical scans, fences admissions, merges identity, and fans out per-subscriber filtered results |
| [`ScanOutboundArbiter.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/ScanOutboundArbiter.kt) | Per-connection round-robin fairness across that connection's logical scans, feeding the existing best-effort outbound path |

`AgentConfig.scanConcurrency` selects the mode for the process lifetime; the JVM launcher reads
`REMOTE_BLE_SCAN_CONCURRENCY`. The guaranteed modes (`multiplexed`, `single`) key the coordinator by
stable client key plus `scanId`, retain ownership through transport grace, merge identity before
filter matching, and fan out through 320-item drop-newest logical mailboxes (256 retained replay
entries plus 64 steady-state entries).

Each logical scan has exactly **one** bounded reservation of that size on both agents, and drop-newest
is applied once, where the physical fan-out writes. `agent-rs` reaches that by handing the arbiter's
mailbox to the coordinator as the delivery sink. The Kotlin agent keeps a collector between the two
(it is where `scan.batch` coalescing happens, which `agent-rs` does not implement), so its arbiter
sink carries only the 64-entry steady-state depth and the collector hands events on with a suspending
send — backpressure lands on the coordinator's single reservation instead of a second copy of it.
Physical fan-out itself never suspends on either agent, so a stalled connection cannot slow the radio.

`multiplexed` guarantees filter and lifecycle isolation, not Apple discovery completeness; operator
choice of `uncontrolled` retains the direct backend path and makes no isolation claim. The Rust
counterpart is [`agent-rs/src/transport/scan_coordinator.rs`](../agent-rs/src/transport/scan_coordinator.rs),
which must produce the same observable behaviour — that parity is what
[agent-parity-verification.md](agent-parity-verification.md) tracks.

---

## The canned backend — `FakeAgent`

[`FakeAgent.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/FakeAgent.kt)

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

## The runnable agent (JVM) — `Main`

[`Main.kt`](../agent/src/jvmMain/kotlin/dev/warsha/remoteble/agent/Main.kt)

This is the JVM composition root only — a blocking CLI `main()` (env vars, a shutdown
hook, `CountDownLatch(1).await()`) that doesn't map to Android/iOS lifecycles. Those two
platforms have their own composition roots; see
[Android / iOS: a phone as the agent](#android--ios-a-phone-as-the-agent).

```kotlin
fun main(args: Array<String>) {
    val config = AgentConfig(
        port = args.firstOrNull()?.toIntOrNull() ?: AgentConfig.DEFAULT_PORT,
        authToken = System.getenv("REMOTE_BLE_TOKEN")?.takeIf { it.isNotBlank() }, // optional bearer auth
        operatorToken = System.getenv("REMOTE_BLE_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() },
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
REMOTE_BLE_TOKEN=client REMOTE_BLE_OPERATOR_TOKEN=operator agent/run-agent.sh 8080
```

### Per-principal write policy

Set `REMOTE_BLE_POLICY_FILE` to a JSON file to enforce mutation permissions at the agent, keyed to
the bearer credential principal. The file is read once before Koin, the BLE backend, or the
WebSocket listener starts. An absent variable preserves the historical allow-all behavior. A blank
or whitespace-only value is also treated as unconfigured, but logs a warning; once a nonblank file
is configured, unlisted principals and empty rule lists deny all mutations.

The parser is strict: malformed JSON, unknown fields, unknown principals, unsupported versions,
and negative or out-of-range signed-32-bit `maximumBytes` values stop startup. Rules match the
full wire-form UUIDs case-insensitively; `"*"` is the only wildcard. Descriptor rules must name a
`descriptor` UUID as well as service and characteristic, so allowing one descriptor cannot permit
another descriptor on the same characteristic. `maximumBytes: null` is unlimited, and `0` permits
only an empty payload (the ordinary 512-byte operation limit still applies).

Use unique JSON member names throughout a policy file. Duplicate names are invalid and unsupported:
the Kotlin agent can retain a last value while Rust rejects duplicate DTO fields, so neither outcome
is portable until duplicate-member rejection is hardened.

See [`proposals/agent-write-policy.md`](proposals/agent-write-policy.md) for the complete schema,
pairing behavior, and a full JSON example. `agent.status` reports whether a policy is currently
configured through `settings.writePolicyEnforced`.

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

The endpoint and optional **status dashboard** share the port: `ws://<host>:8080/agent` for
clients and `http://<host>:8080/` for the dashboard. The dashboard is disabled unless the
separate `REMOTE_BLE_OPERATOR_TOKEN` is configured; it must not reuse any client credential.

That same operator credential has a second, transport-independent use: a client may present it on
the WebSocket upgrade as `X-RemoteBle-Operator: Bearer <secret>` to widen what an `agent.status`
reply discloses — every lease and its holder, rather than only its own. It grants nothing else, and
an absent or wrong value is not a connection failure: the session proceeds at normal scope and says
so in `operatorScope`. `agent-rs` accepts the same header (`--operator-token` /
`REMOTE_BLE_OPERATOR_TOKEN`), which is what lets one status command work against every reference
agent rather than only the one that serves HTTP. See §6.2 of the
[conformance spec](agent-conformance-spec.md).

The object graph (`AgentMonitor`, `EngineBleBackend` → `BleAgentBackend` →
`AgentWebSocketServer`, plus `ConnectionWatcher`) is assembled by Koin in
[`di/AgentModule.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/di/AgentModule.kt)
— the single `AgentMonitor` is shared as both the backend's `AgentObserver` and the server's
dashboard source. The agent's classes keep their plain constructors — only this composition
root touches a DI container, mirroring the optional `remoteBleClientModule` on the client side.

---

## The status dashboard — `AgentMonitor` + `Dashboard`

[`AgentMonitor.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentMonitor.kt) ·
[`Dashboard.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/Dashboard.kt)

When `REMOTE_BLE_OPERATOR_TOKEN` (or `AgentConfig.operatorToken`) is configured, the agent serves
a live, mobile-friendly status page from the same Ktor server. Without that separate credential,
the HTTP dashboard routes are not mounted.

- `GET /` — a single self-contained HTML page (no build step, no external assets) that polls
  `/api/state` once a second and renders three panels: **connected clients**, **connected
  hardware**, and a rolling **activity log**.
- `GET /api/state` — a JSON snapshot from `AgentMonitor`.

Every dashboard route requires the operator credential, never a client credential. A browser uses
the standard Basic-auth prompt with username `operator` and the operator token as its password;
scripts may send `Authorization: Bearer <operator-token>`. This protects client addresses, device
names/handles, ownership, and activity from LAN readers. Wrong-credential attempts (not the normal
first-leg challenge) are throttled by a dedicated `FailedAuthLimiter`, so the management plane is
brute-force-bounded independently of the client-upgrade plane.

`AgentMonitor` is a thread-safe (atomicfu-locked, not JVM `synchronized`), in-memory store
updated from two sides: `AgentWebSocketServer` reports client connect/disconnect (id +
remote address); `BleAgent` reports device lifecycle and scan-seen names through the
`AgentObserver` hooks. Hardware is labelled from scan results and attributed to the owning
client; when a client's socket drops, its hardware is released. The monitor itself is
**read-only** and never touches the radio. Dashboard mutation routes are absent. Wiring the HTML
dashboard is optional: construct `AgentWebSocketServer` without a `monitor`, or without a separate
operator credential, and the routes simply aren't installed. Its
`snapshot(leases, settings): Snapshot` is the same call both consumers use — `snapshotJson`
just serializes it for `/api/state`; the Android/iOS Compose UI (below) calls it directly,
no HTTP round-trip needed since it shares the process with the server.

---

## Android / iOS: a phone as the agent

Everything above — `EngineBleBackend`, `AgentWebSocketServer`, `Dashboard`, `AgentMonitor`,
`di/AgentModule` — is `commonMain` and identical on Android/iOS. What differs is the
**composition root**, since neither platform has a JVM-style blocking `main()`:

- [`AgentRunner`](../agent/src/mobileMain/kotlin/dev/warsha/remoteble/agent/AgentRunner.kt)
  (in `mobileMain`, a source set shared by `androidMain`/`iosMain` but not `jvmMain` — so the
  desktop CLI's dependency graph never pulls in Compose Multiplatform) wraps the Koin graph in a
  restartable `start(config)`/`stop()` pair over a **private** `KoinApplication` (not the
  process-global `startKoin`, so it can't collide with any other Koin usage in the host app), and
  exposes `monitor`/`registry`/`config`/a `running: StateFlow<Boolean>` for the UI. Because
  `start`/`stop` and the UI's polling run on different threads (and `stop` can be driven from a
  best-effort teardown on `Dispatchers.Default`), its mutable fields are guarded by the same
  atomicfu lock `EngineBleBackend` uses, and `stop()` captures-and-clears the graph atomically so
  two concurrent teardowns can't double-close it.
- [`ui/AgentApp.kt`](../agent/src/mobileMain/kotlin/dev/warsha/remoteble/agent/ui/AgentApp.kt)
  is a Compose Multiplatform mirror of the HTML dashboard — the same clients/ownership/log panels
  (one `LazyColumn`, `safeDrawingPadding` so it clears the status bar/notch under edge-to-edge),
  a legacy exclusive/shared toggle calling `PeripheralRegistry.setExclusive` directly (disabled for
  the 0.9.0 release), and a start/stop control — polling `AgentMonitor.snapshot(...)` every second,
  same cadence as the HTML page. Two
  things the terminal agent gets for free but a phone must surface itself:
  - **Reachable address.** The UI shows `ws://<lan-ip>:<port>/agent` (or a "no Wi-Fi" notice),
    resolved per platform by the `lanIPv4Address()` `expect`/`actual` (Android: active-network
    `LinkProperties`, falling back to `WifiManager`; iOS: a `getifaddrs` walk for `en0`; JVM:
    `NetworkInterface`).
  - **Auth token.** An editable field, enabled only while stopped, persisted across launches via
    the `TokenStore` `expect`/`actual` (Android DataStore, iOS `NSUserDefaults`). If it's left
    blank, a random token is generated on Start and shown next to the address — **the mobile agent
    never runs token-free**, because unlike the CLI (typically firewalled to localhost, or given a
    token via `REMOTE_BLE_TOKEN`) it listens on `0.0.0.0` over cleartext on a shared Wi-Fi.
- **Android**: [`AgentService.kt`](../agent/src/androidMain/kotlin/dev/warsha/remoteble/agent/AgentService.kt)
  is a foreground service (`connectedDevice` type) whose only job is the persistent notification
  Android requires to keep the process alive backgrounded — it owns no BLE/server logic. Rather
  than trust the Activity/composition to keep it in lockstep (fragile — the composition can be torn
  down, e.g. on task removal, without running a "stop" branch), the service **observes
  `AgentRunner.running` itself** and calls `stopSelf()` the moment it flips false, so it can never
  outlive the agent it represents; it returns `START_NOT_STICKY` (it can't meaningfully resume
  after a process kill) and adds `onTaskRemoved` as an independent backstop. `MainActivity`
  requests the runtime `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` permissions (API 31+;
  `ACCESS_FINE_LOCATION` below it) and **gates Start on the grant** — a denial disables Start and
  shows an inline warning with a shortcut to app settings — since a `connectedDevice` foreground
  service can't legally start without a qualifying Bluetooth permission on API 34+.
- **iOS**: [`IosAgentEntry.kt`](../agent/src/iosMain/kotlin/dev/warsha/remoteble/agent/IosAgentEntry.kt)
  has no foreground-service equivalent to reach for — iOS does not support a backgrounded,
  listening TCP server at all (new inbound connections can't be accepted once the app backgrounds
  or the screen locks, though the `bluetooth-central` background mode can keep *already-connected*
  radio links alive briefly). Rather than leave that as a silent trap, the returned `IosAgentSession`
  sets `UIApplication.sharedApplication.isIdleTimerDisabled` while the agent runs (so the screen
  can't auto-lock and kill it), and `AgentApp` shows a matching on-screen reminder. The session
  owns its runner and observing scope for exactly one hosting view controller; `ios-agent`'s
  `ComposeView` disposes it from the SwiftUI `Coordinator`'s `deinit`, so repeated view creation
  can't leak scopes/runners. See [`ios-agent/README.md`](../ios-agent/README.md).

`:android-agent` and `ios-agent/` are thin app shells with no logic of their own — the
same split as `:client-ui` → `:android-client`/`ios-client/`, just for the agent role.
`:agent` itself holds the UI and orchestration (per your call to keep it in the one KMP
module rather than mirroring that split a second time); the shells exist only because
Android/iOS require an actual runnable app/Xcode project (AGP 9 forbids a
`com.android.application` module from also declaring `androidTarget()`, and iOS has no
Gradle-native app packaging at all).

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
- *Degraded-write fail-fast* (`REMOTE_BLE_WRITE_FAIL_FAST`, default `true`, on both reference
  agents; `agent-rs` also accepts `--write-fail-fast <true|false>`). Confirmed
  on hardware: once btleplug has had one write-with-response answered by an ATT error, it stops
  delivering write completions for that peripheral for the rest of the connection — later writes
  reach the peripheral and are accepted, but nothing ever comes back. Reads are unaffected, and only
  re-establishing the connection recovers it. With this on, the first write to expire
  `EngineBleBackend.GATT_OP_TIMEOUT` marks the connection and subsequent writes are rejected
  immediately with the *same* `TIMEOUT` error they would have got by waiting — the change is
  latency, not semantics. Set it to `false` to run without the workaround (the state is still
  tracked and logged, writes just go to the radio and wait as before). It is stated in the startup
  log so the running behaviour is visible, and it becomes moot once the backend delivers ATT errors
  properly, since the degraded state can then never be entered.
- *Lease grace timers.* Both registries schedule a per-lease release on "owner gone" and cancel
  it on "owner back". On expiry the lease is freed **and** the warm radio link is torn down via
  an injected teardown (KMP `onRelease`, Rust `set_teardown` → `BleBackend::disconnect`). This is
  what reclaims connections, slots, and pump tasks after a drop — see
  [Peripheral ownership](#peripheral-ownership). A `connect` that completes *after* its transport
  was retired is generation-bound: it will not resurrect the abandoned lease (KMP re-checks a
  `connectionLive` flag when committing; Rust checks `connection_live`), so the grace path still
  releases it instead of leaking a peripheral held by a dead connection.

**Availability — dead clients are detected fast.**
- *WebSocket liveness pings.* Each connection is pinged on an interval (default 15s) and closed
  if nothing — including the peer's auto-pong — is heard within a timeout (default 40s). A client
  that vanished without a TCP FIN (Wi-Fi drop, NAT timeout, sleep) is reclaimed in seconds rather
  than waiting on the OS TCP keepalive (minutes); the close also starts the lease grace timer.
  (KMP: Ktor `WebSockets { pingPeriodMillis/timeoutMillis }`; Rust: a ping arm in the send task.)

**Resource bounds — one client can't exhaust the host.**
- *Bounded inbound frames.* An encoded WebSocket frame above **1 MiB** is rejected before decoding
  (Ktor `maxFrameSize` / tungstenite `WebSocketConfig` at the framing layer, plus an app-level
  guard), closing the connection with a stable reason rather than buffering unbounded input.
- *Bounded outbound buffers.* Per-connection outbound is bounded (Rust frame channel = 512).
  Replies apply backpressure; **events are shed on overflow** so a notification flood never
  blocks the radio. Notifications are ordered payloads, not coalescible: an observation whose
  delivery stays blocked past a timeout terminates *that stream* with a stable outcome rather than
  holding an unbounded producer chain.
- *Capped in-flight commands.* A per-connection semaphore (default 64) limits concurrent ops;
  once hit, the read loop suspends, backpressuring the link instead of spawning unbounded tasks.
- *Argument ceilings.* Oversized arguments are rejected as `INVALID_REQUEST` before reaching the
  radio — at most 64 scan filters, 512-byte write/descriptor payloads, and an MTU within the ATT
  range 23–517. Streaming resources are independently capped per connection (16 active scans, 128
  observations); reusing a live stream id replaces it without consuming a slot.
- *Bounded failed-auth accounting.* Rejected upgrades are tracked in the fixed-memory
  `FailedAuthLimiter` (per-peer + global ceilings, LRU eviction), shared in spirit by both agents;
  the Kotlin agent additionally rate-limits the operator dashboard's auth with its own limiter.

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

---

## Logging

The agent depends on the shared [`:log`](../log) module — the same `Logger` object the
client SDK uses. The agent process initializes it at startup:

```kotlin
// Main.kt (JVM)
Logger.sink = PrintlnSink
Logger.level = parseLogLevel(System.getenv("REMOTE_BLE_LOG"))  // default: INFO
```

### What's logged at each level

| Level | Tag | What |
|---|---|---|
| **ERROR** | `agent/engine` | Unexpected op failure (internal error) |
| **WARN** | `agent/server` | Undecodable frame dropped; 401 rejection; repeated hello ignored |
| **WARN** | `agent/engine` | Connect failed; scan/observe ended on error |
| **WARN** | `agent/watcher` | Liveness probe failed (unsolicited disconnect declared); probe tick exception |
| **INFO** | `agent/server` | Client connected/disconnected; handshake negotiated |
| **INFO** | `agent/engine` | Device connected/disconnected; unsolicited disconnect |
| **INFO** | `agent/registry` | Lease acquired/resumed; lease grace expired → released |
| **DEBUG** | `agent/engine` | Scan started/stopped; Kable connect/disconnect ok; Kable state → Disconnected (unsolicited drop) |
| **DEBUG** | `agent/engine` | Translator primed N handle(s) |

### Relationship to `AgentObserver`

`AgentObserver` (the bounded, human-facing dashboard activity feed) and the `Logger`
(the unbounded operational stream) are different products. The ~6 dashboard-relevant
sites keep their `observer.onClientLog()` call *alongside* a `Logger` call. The logger
is the primary operational record; the observer feeds `AgentMonitor`'s bounded
`ArrayDeque<LogEntry>` (capped at 500) for the dashboard UI.

### `agent-rs` logging

The Rust agent uses `tracing`/`tracing-subscriber` (not the `:log` module — it's a
separate codebase with its own ecosystem). It adds:
- `--log-level` / `REMOTE_BLE_LOG` clap flag (seeds `EnvFilter` when `RUST_LOG` is unset)
- `--log-format json` for journald/Loki
- Per-connection `info_span!("conn", client, peer)` propagated into spawned op tasks

See [agent-parity-verification.md](agent-parity-verification.md) for the full
comparison.
