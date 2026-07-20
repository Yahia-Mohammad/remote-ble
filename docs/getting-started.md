# Getting Started (for app developers)

[← back to index](README.md)

This is a hands-on tutorial: stand up an agent, write a client that talks to it, and
see the **one-line local↔remote swap** that is the whole point of the system. It
assumes you can build the repo (`./gradlew build`) and have read nothing else.

> **Mental model.** You write your app against Kable's `Peripheral` / `Scanner`
> interfaces, exactly as you would for local Bluetooth. At *construction* time you
> choose whether a given `Peripheral` is driven by the local radio or by a remote
> **agent** over a WebSocket. Everything downstream is identical. That's it.

---

## Part 1 — Run an agent

### Fastest path: a simulated agent (no Bluetooth hardware)

For a deterministic local walkthrough or CI-style test, start the JVM agent with the checked-in
Heart Rate profile. This bypasses the real radio, so it does not need the macOS signed-app launcher:

```sh
./gradlew :agent:jvmRun --args="--simulate agent/simulation/sim-hrm.json"
# → RemoteBLE agent listening on ws://127.0.0.1:8080/agent (simulated backend)
```

Use `ws://127.0.0.1:8080/agent` in Part 2 and continue through the client walkthrough unchanged.
The simulated backend is intentionally an agent configuration, not a separate client API. See
[simulation.md](simulation.md) for its profile contract.

### Real radio: JVM agent

The agent is the process next to the Bluetooth device that owns the real radio. The
reference agent (`:agent`) targets the JVM, Android, and iOS; this tutorial uses the JVM
target, which runs over Kable's native (`btleplug`) backend — on macOS that's
CoreBluetooth under the hood (see [below](#or-a-phone-as-the-agent) for the Android/iOS
apps):

```sh
agent/run-agent.sh 8080
# → RemoteBLE agent listening on ws://127.0.0.1:8080/agent (no auth, exclusive peripherals, Kable engine on Mac OS X)
# → Dashboard disabled (set a separate REMOTE_BLE_OPERATOR_TOKEN to enable it)
```

> Use `agent/run-agent.sh`, **not** `./gradlew :agent:jvmRun` — a bare JVM is killed by macOS
> TCC the instant it touches CoreBluetooth (it has no `NSBluetoothAlwaysUsageDescription`). The
> script wraps the agent in a signed `.app` and launches it via LaunchServices. See
> [agent.md](agent.md#the-runnable-agent-jvm--main).

macOS will prompt for Bluetooth permission on the first run — grant it.

To require a bearer token, set `REMOTE_BLE_TOKEN`; clients must then present the same
value:

```sh
REMOTE_BLE_TOKEN=s3cr3t agent/run-agent.sh 8080
# → … (bearer-token required, …)
```

To enable the HTTP status dashboard, configure a **different** operator token as well. Browsers
prompt for username `operator` and that token; client tokens are rejected by dashboard routes.

```sh
REMOTE_BLE_TOKEN=client-secret REMOTE_BLE_OPERATOR_TOKEN=operator-secret agent/run-agent.sh 8080
```

The desktop agent binds `127.0.0.1` by default. For LAN use, set an explicit bind address and
credentials (for example `REMOTE_BLE_BIND=192.168.1.20 REMOTE_BLE_TOKENS='phone=…'`); use a
TLS-terminating reverse proxy or VPN for encrypted deployments. Direct `ws://` is only for
trusted-network/development use. A named credential (`REMOTE_BLE_TOKENS='name=secret,…'`) maps
the verified bearer secret to a principal; `REMOTE_BLE_TOKEN` remains the `default` alias.

### Or: the native Rust agent (`agent-rs`)

For a lightweight, cross-platform agent (macOS / Linux) speaking the same wire contract, use
`agent-rs` instead of the JVM agent:

```sh
# Self-bootstrapping: installs Rust + (on Linux) the BlueZ/D-Bus build prerequisites if
# they're missing, then builds and runs. On macOS it also wraps the binary in a signed
# RemoteBleAgentRs.app (a bare cargo run hits the same CoreBluetooth-TCC SIGABRT as the
# JVM agent) — approve the one-time Bluetooth prompt.
agent-rs/run-agent-rs.sh 8080
```

It serves the same `ws://<host>:8080/agent` endpoint, so the client side below is
identical. It's a v1-baseline agent (scan/connect/read/write/notify/MTU); it doesn't
yet serve the status dashboard or the optional capability extensions. Its wire format is
pinned to the Kotlin contract by cross-language interop tests (see
[build-and-testing.md](build-and-testing.md#the-native-rust-agent-agent-rs-tests)).

### Or: a phone as the agent

`:agent` also targets Android and iOS directly — same server, same wire contract, a
Compose Multiplatform status UI instead of a terminal. `./gradlew :android-agent:installDebug`
(or the `ios-agent/` Xcode project on a physical iPhone) installs an app that hosts the
agent on the phone's own radio. Grant the Bluetooth permission it requests, tap **Start**, and
point a client at the `ws://<phone-ip>:8080/agent` the screen shows (the app resolves the LAN IP
for you). Because the phone listens on the open Wi-Fi in cleartext, the mobile agent is **always
token-protected**: type an auth token, then configure that value in the client through
`WebSocketAgentTransport.authToken` (a suspend provider). The app masks the field and never
renders the bearer value after startup. iOS can't keep the agent running
backgrounded (no equivalent of Android's foreground service — see
[agent.md](agent.md#android--ios-a-phone-as-the-agent)), so keep that app open.

> The Android/iOS agent apps are **dev/test tools**, not shipping builds — they serve over
> cleartext `ws://` (the iOS launcher carries a blanket App Transport Security exception) and
> retain the configured credential in platform development storage (Android DataStore; iOS
> `NSUserDefaults`), not a protected production credential store. Run them on a trusted network;
> do not use the mobile launcher for production credentials.

### Credential rotation and revocation

Desktop and Rust agent credentials are read at process startup. Rotate or revoke a credential by
updating the environment/configuration and restarting the agent. Restart clears all in-memory
leases, so an old credential cannot resume a warm lease; existing WebSocket sessions also end with
the process. There is intentionally no live credential-reload API in 0.10.0 — changing an
environment variable without restarting has no security effect. For a zero-downtime rotation, run
a separately configured replacement agent and migrate clients before retiring the old one.

---

## Part 2 — Connect a client

A client needs three things: a **transport** (the link to the agent), a **codec**
(CBOR), and a **session** (the request/response + event layer) built on them.

```kotlin
import dev.warsha.remoteble.client.*
import dev.warsha.remoteble.protocol.CborProtocolCodec
import kotlinx.coroutines.*

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val transport = WebSocketAgentTransport(
    url = "ws://192.168.1.50:8080/agent",   // the agent host
    scope = scope,
    httpClient = defaultWebSocketHttpClient(),  // platform engine (JVM CIO / Android OkHttp / iOS Darwin)
    authToken = { "s3cr3t" },                // suspend provider; omit if the agent has no auth
)

val session = DefaultAgentSession(transport, CborProtocolCodec(), scope)
```

The session connects in the background. You can wait for the link if you want a clear
failure point:

```kotlin
import kotlinx.coroutines.flow.first
withTimeout(10_000) { session.transportState.first { it == TransportState.CONNECTED } }
```

You don't *have* to wait — any `request()` made before CONNECTED simply returns
`Err(TRANSPORT_LOST)`, and the transport keeps retrying with backoff.

---

## Part 3 — Scan, connect, and use a device

From here the code is ordinary Kable. Scan for a device, turn the advertisement into
a `Peripheral`, and use it:

```kotlin
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.flow.first

// 1. Scan — RemoteScanner is a Kable Scanner.
val advertisement = RemoteScanner(session).advertisements.first { it.name == "My Sensor" }

// 2. Turn it into a Peripheral. THIS is the local-vs-remote decision point.
val peripheral: Peripheral = peripheralFor(BleMode.REMOTE, advertisement, session)

// 3. …everything below is identical to local Kable.
peripheral.connect()

val service = peripheral.services.first { it != null }!!.first()
val characteristic = service.characteristics.first()

val value: ByteArray = peripheral.read(characteristic)
peripheral.write(characteristic, byteArrayOf(0x01), WriteType.WithResponse)

peripheral.observe(characteristic).collect { notification ->
    println("got ${notification.size} bytes")
}

peripheral.disconnect()
```

`RemoteScanner.advertisements` emits `RemoteAdvertisement`s; each carries the
agent-minted `handle` that `peripheralFor` needs. You never construct or parse a
device handle yourself.

**RemoteBLE extensions beyond Kable.** A few ops (pairing, RSSI, connection parameters) aren't
on Kable's portable `Peripheral` surface, so they live as extension methods on the concrete
`RemotePeripheral` returned for `BleMode.REMOTE` — capability-gated, like everything else:

```kotlin
val remote = peripheral as RemotePeripheral // the concrete type peripheralFor(REMOTE, …) returns

// Requests a connection profile (capability: conn.params — Android agents today).
remote.setConnParams(ConnProfile.LOW_LATENCY)
```

---

## Part 4 — The payoff: write code that doesn't care

Because `peripheralFor` returns the same `Peripheral` type either way, your real
logic takes a `Peripheral` and is **identical** for local and remote:

```kotlin
// Pure Kable — no idea whether it's local or remote.
suspend fun readBatteryLevel(peripheral: Peripheral): Int {
    peripheral.connect()
    val services = peripheral.services.first { it != null }!!
    val battery = services
        .first { it.serviceUuid == BATTERY_SERVICE }
        .characteristics.first { it.characteristicUuid == BATTERY_LEVEL }
    return peripheral.read(battery).first().toInt()
}
```

Switching this function between local and remote is a one-line change at the call
site:

```kotlin
val peripheral = peripheralFor(BleMode.LOCAL,  advertisement)              // local radio
val peripheral = peripheralFor(BleMode.REMOTE, advertisement, session)    // remote agent
```

(For `BleMode.LOCAL` the advertisement comes from Kable's own `Scanner`; for
`BleMode.REMOTE` it comes from a `RemoteScanner` and you pass the `session`.)

---

## Part 5 — Lifecycle, reconnection, and errors

**Reconnection is automatic and transparent.** If the network blips or the agent
restarts, the transport reconnects with backoff and the session **replays** your
connections and subscriptions — your `observe { … }` flow keeps delivering with no
code on your part. You can watch the IP link if you want to show a "reconnecting…"
indicator:

```kotlin
session.transportState.collect { state -> /* CONNECTING / CONNECTED / DISCONNECTED */ }
```

When an operation must wait for the initial hello or for recovery replay to finish, observe the
additive `session.readiness` flow and proceed only at `SessionReadiness.READY`. A
`SessionReadiness.DEGRADED` link is usable, but one or more remembered peripheral connections did
not recover and should be handled by the app (usually rescan/reconnect).

**Two different "disconnected" states — don't confuse them:**

| You want to know… | Watch | Type |
|---|---|---|
| Is the link to the agent up? | `session.transportState` | `TransportState` |
| Is the device still connected? | `peripheral.state` | Kable `State` (driven by BLE `ConnectionState` events) |

A brief IP drop does **not** flip `peripheral.state` — the session heals it silently.
A real radio disconnect flips `peripheral.state` to `State.Disconnected` and clears
`peripheral.services`, exactly as local Kable would.

When replacing a remote peripheral or leaving a screen, prefer the concrete
`RemotePeripheral.shutdown()` API over Kable's non-suspending `close()`. It requests remote
disconnect/release within a timeout, always cancels local observation/connection work, and reports
whether cleanup completed, timed out, or lost transport.

**Errors.** A failed op throws `AgentException`, whose `error.kind` tells you *where*
it failed:

```kotlin
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.ErrorKind

try {
    peripheral.write(characteristic, payload, WriteType.WithResponse)
} catch (e: AgentException) {
    when (e.error.kind) {
        ErrorKind.WRITE_FAILED   -> /* the peer rejected it — retrying probably won't help */
        ErrorKind.TRANSPORT_LOST -> /* the link dropped — a retry may succeed once reconnected */
        ErrorKind.TIMEOUT        -> /* no reply in time */
        else                     -> throw e
    }
}
```

The split between "reached the radio and it said no" and "never reached the radio" is
deliberate — see the [error taxonomy](protocol.md#errors--agenterror--errorkind). A
failed op never poisons the session; the next request proceeds normally.

**A returned success is exact; a timeout/transport-lost on a write is not "it failed."** The agent
replies only after the real GATT completion, so `write()` returning means the peripheral actually
acknowledged it (with-response) — never a false positive. But if the *Reply* is lost in transit, you
see `TIMEOUT`/`TRANSPORT_LOST` even though the write may already have taken effect on the radio. That's
why writes/`Pair` don't auto-retry and aren't replayed on reconnect (see
[client-sdk.md](client-sdk.md#the-completion-contract-exact-on-success-ambiguous-on-lost-reply)) — a
silent retry could double-apply. Design a read-back or an app-level idempotent retry for writes where
that ambiguity matters, especially `WriteType.WithoutResponse` writes (an `Ok` there only ever means
"handed to the radio," since WWR has no ATT acknowledgement at all).

---

## Part 6 — Tuning (optional)

`peripheralFor` uses sensible defaults. When you need to tune timeouts (e.g. a slow
relay) or the requested MTU, construct a `RemotePeripheral` directly:

```kotlin
import kotlin.time.Duration.Companion.seconds

val peripheral = RemotePeripheral(
    handle = advertisement.handle,
    session = session,
    name = advertisement.name,
    requestedMtu = 185,                                   // default 247
    timeouts = RemoteTimeouts(connect = 45.seconds),      // defaults: connect 30s / discover 20s / op 15s
)
```

**Custom HTTP engine.** `defaultWebSocketHttpClient()` is a convenience. For proxies,
TLS pinning, or custom timeouts, build your own and pass it to the transport:

```kotlin
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*

val httpClient = HttpClient(/* your engine */) { install(WebSockets) }
val transport = WebSocketAgentTransport(url, scope, httpClient)
```

**Bursting write-without-response writes.** A serial loop of `peripheral.write(char, data,
WriteType.WithoutResponse)` pays one full client↔agent round trip per write — fine for occasional
writes, a real ceiling if you're streaming many small WWR payloads back-to-back.
`RemotePeripheral.writeWithoutResponseBurst` pipelines them instead, keeping several in flight:

```kotlin
val results: List<Result<Unit>> = peripheral.writeWithoutResponseBurst(
    characteristic = writable,
    values = chunks,               // List<ByteArray>
    window = 8,                    // default; how many writes may be in flight at once
)
```

Frames are sent one per write, and **submission order is preserved end-to-end** — the client sends in
order and the agent chains same-device writes so they reach the radio in order (writes to other
devices stay concurrent). This is a client-side change plus an agent ordering guarantee, not a wire
change. The one inherent caveat is WWR itself: `WriteType.WithoutResponse` has no ATT acknowledgement
(see above), so a `Result.success(Unit)` means "handed to the agent/radio," not "the peripheral
received it" — order is guaranteed, per-write *delivery* is still best-effort.

---

## Gotchas & current limits

- **Device handles are opaque and agent-scoped.** Always get one from a
  `RemoteAdvertisement` (or by reconnecting an existing peripheral). Don't try to
  construct or parse `DeviceHandle.value`.
- **Capability-gated ops:** descriptor read/write (`descriptors`) and connected RSSI
  (`rssi`) are supported, but only when the agent advertises the capability — it enables
  each one only on backends that implement it (connected RSSI is live on the Android
  `readRemoteRssi()` / Apple `readRSSI()` Kable backends, but **not** the JVM/btleplug
  or `agent-rs` agents). Calling `peripheral.read(descriptor)`, `write(descriptor)`, or
  `rssi()` against an agent that lacks the capability fails with a clear `UNSUPPORTED`
  error, never a stale value.
- **`peripheral.identifier`** is best-effort on a remote peripheral (the agent handle
  may not parse as your local platform's Kable `Identifier`) — you don't need it to
  operate the device.
- **One agent** in v1 — there's no multi-agent registry yet. Multiple clients *can* use one agent,
  but each peripheral is owned exclusively by one client at a time (a second client's connect to an
  owned peripheral fails with `PERIPHERAL_BUSY`). Shared mode is disabled for 0.9.0 pending a
  participant model.
- The reference **agent runs on macOS/Linux (JVM), Android, and iOS**; the client builds
  for JVM (tests), Android, and iOS. iOS can't run the agent backgrounded — see
  [agent.md](agent.md#android--ios-a-phone-as-the-agent).
- **Diagnostics:** The SDK defaults silent. To turn on logging, set `Logger.level` and
  `Logger.sink` before creating the session (see [client-sdk.md → Logging](client-sdk.md#logging)).
  The agents default to `INFO` (`REMOTE_BLE_LOG=debug` to override).

---

## Where to go next

- [client-sdk.md](client-sdk.md) — the full client API and how each layer works.
- [flows.md](flows.md) — sequence diagrams of every operation on the wire.
- [agent.md](agent.md) — how the agent and its radio backend work.
- [design-decisions.md](design-decisions.md) — why the system is shaped this way.
