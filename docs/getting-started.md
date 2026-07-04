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

The agent is the process next to the Bluetooth device that owns the real radio. The
reference agent (`:agent`) targets the JVM, Android, and iOS; this tutorial uses the JVM
target, which runs over Kable's native (`btleplug`) backend — on macOS that's
CoreBluetooth under the hood (see [below](#or-a-phone-as-the-agent) for the Android/iOS
apps):

```sh
agent/run-agent.sh 8080
# → RemoteBLE agent listening on ws://0.0.0.0:8080/agent (no auth, shared peripherals, Kable engine on Mac OS X)
# → Status dashboard: http://localhost:8080/
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

That's the whole server side. The agent exposes one endpoint, `ws://<host>:8080/agent`,
and a status dashboard at `http://<host>:8080/`.

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
token-protected**: type an auth token or let it auto-generate one — it's shown next to the address,
and the client passes it as `WebSocketAgentTransport.authToken`. iOS can't keep the agent running
backgrounded (no equivalent of Android's foreground service — see
[agent.md](agent.md#android--ios-a-phone-as-the-agent)), so keep that app open.

> The Android/iOS agent apps are **dev/test tools**, not shipping builds — they serve over
> cleartext `ws://` (the iOS launcher carries a blanket App Transport Security exception). Run
> them on a trusted network.

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
    authToken = "s3cr3t",                    // omit if the agent has no auth
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

**Two different "disconnected" states — don't confuse them:**

| You want to know… | Watch | Type |
|---|---|---|
| Is the link to the agent up? | `session.transportState` | `TransportState` |
| Is the device still connected? | `peripheral.state` | Kable `State` (driven by BLE `ConnectionState` events) |

A brief IP drop does **not** flip `peripheral.state` — the session heals it silently.
A real radio disconnect flips `peripheral.state` to `State.Disconnected` and clears
`peripheral.services`, exactly as local Kable would.

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

---

## Gotchas & current limits

- **Device handles are opaque and agent-scoped.** Always get one from a
  `RemoteAdvertisement` (or by reconnecting an existing peripheral). Don't try to
  construct or parse `DeviceHandle.value`.
- **Not in protocol v1:** descriptor read/write and connected RSSI. Calling
  `peripheral.read(descriptor)`, `write(descriptor)`, or `rssi()` on a remote
  peripheral throws `UnsupportedOperationException`.
- **`peripheral.identifier`** is best-effort on a remote peripheral (the agent handle
  may not parse as your local platform's Kable `Identifier`) — you don't need it to
  operate the device.
- **One agent** in v1 — there's no multi-agent registry yet. Multiple clients *can* share
  one agent, but each peripheral is owned by one client at a time (a second client's connect
  to an owned peripheral fails with `PERIPHERAL_BUSY`; switchable per peripheral, default block).
- The reference **agent runs on macOS/Linux (JVM), Android, and iOS**; the client builds
  for JVM (tests), Android, and iOS. iOS can't run the agent backgrounded — see
  [agent.md](agent.md#android--ios-a-phone-as-the-agent).

---

## Where to go next

- [client-sdk.md](client-sdk.md) — the full client API and how each layer works.
- [flows.md](flows.md) — sequence diagrams of every operation on the wire.
- [agent.md](agent.md) — how the agent and its radio backend work.
- [design-decisions.md](design-decisions.md) — why the system is shaped this way.
