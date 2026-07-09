# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The **wire protocol** version is tracked separately from the library version — it
is a distinct compatibility contract for agent/client implementers. Current wire
protocol version: **1**.

## [0.8.1] - 2026-07-09

### Added

- **Connected RSSI over the wire** (capability `rssi`). `RemotePeripheral.rssi()` now issues a new
  `Op.ReadRssi` and returns the live connected link RSSI instead of throwing. Capability-gated: the
  agent advertises `rssi` only where its Kable backend does a real connected read — **Android**
  (`readRemoteRssi`) and **Apple** (`readRSSI`). The JVM/btleplug agent and `agent-rs` have no
  connected-RSSI read (btleplug exposes only advertisement RSSI), so they answer `UNSUPPORTED`. Wire
  protocol stays **1** (additive). `agent-rs` mirrors the codec (`Op.ReadRssi` / `ResultPayload.Rssi`)
  for byte parity.
- **Prebuilt `agent-rs` binaries for more platforms** — the release now attaches `agent-rs` for
  **Linux aarch64** (Raspberry Pi / ARM SBCs) and **Windows x86_64** alongside Linux x86_64. The JVM
  agent fat JAR already runs on all of these (it bundles kable-btleplug natives for linux/darwin/win ×
  x86_64+aarch64).

- **Configurable, well-defined error handling.** `ErrorKind` now classifies each kind as
  `transient` (a retry could plausibly succeed) or permanent, and `Op.isIdempotent` marks which ops
  are safe to repeat (writes and pairing are not). Two policies build on this:
  - **`ReconnectPolicy`** on `WebSocketAgentTransport` (replaces the `autoReconnect`/`backoff`
    constructor args, and `RemoteBleClientConfig.autoReconnect`/`backoff`): adds a bounded
    `maxAttempts` and an `onGaveUp` callback, so the reconnect loop can stop and signal instead of
    retrying forever silently.
  - **`RetryPolicy`**, a stateless `fun interface` — `retryDelay(attempt, error, elapsed): Duration?`
    (return the delay, or `null` to stop) — so arbitrary logic (per-error budgets, deadlines, circuit
    breakers, jitter) is expressible, and one instance is safe to share across concurrent requests.
    Built-ins in `RetryPolicies` (`None`, `maxAttempts(…)`, `untilElapsed(…)`). A policy is chosen
    **per operation**: `DefaultAgentSession` resolves one via `retryPolicyFor` (default
    `defaultRetryPolicyFor`), overridable per call with `request(op, retry = …)`. Defaults derive
    from safety: non-idempotent ops (writes, pairing) default to `None`, `Connect` retries 3×, other
    idempotent ops 2× — only on transient errors. Retrying a write is an explicit per-call opt-in.
- **Prompt spontaneous-disconnect events with a cause.** The agent now surfaces *unsolicited* BLE
  drops (peripheral powered off, out of range, crashed) immediately and with a reason, driven by the
  backend's native connection-state signal — Kable's `Peripheral.state` on the Kotlin agent,
  `CentralEvent::DeviceDisconnected` on `agent-rs` — instead of only via the up-to-15s liveness poll
  (kept as a fallback). `AgentEvent.ConnectionState(DISCONNECTED)` now carries the disconnect
  `reason`. No wire change (the field already existed).

### Changed

- **`WebSocketAgentTransport` auth token is now a suspend provider.** The `authToken` constructor
  parameter changed from `String?` to `suspend () -> String? = { null }` (and likewise
  `RemoteBleClientConfig.authToken`). The provider is invoked once per connection attempt — including
  every reconnect retry — so a rotating/expiring token is refreshed on reconnect instead of replayed
  stale; the SDK never caches the value. Static tokens become `authToken = { "secret" }`. **Breaking**
  for callers that passed a bare string (wrap it in a lambda).
- **Initial connect now self-heals when reconnect is enabled.** Previously only a drop *after* a
  successful connection armed the reconnect/backoff loop; the very first `connect()` was one-shot,
  so a client that started before its agent was reachable would silently never connect. The initial
  attempt now folds into the same backoff loop (when `ReconnectPolicy.enabled`), so it keeps trying
  until the agent appears. With reconnect disabled the initial attempt stays one-shot and `connect()`
  still throws on failure.
- Dropped the `iosX64` (Intel-Mac iOS *simulator*) target from the published `:protocol` and
  `:client-sdk` modules — consistent with the rest of the repo; `iosArm64` (device) and
  `iosSimulatorArm64` (Apple Silicon simulator) remain, so real iOS consumers are unaffected.

### Fixed

- **The mobile agent can now run token-free.** Emptying the auth-token field no longer silently
  mints a random token on Start. A blank field now prompts a confirmation dialog (*"Start without an
  auth token?"*) and, on confirm, runs the agent with no `Authorization` gate; the running header
  reads *"No auth token — any client can connect"* and the blank choice is persisted. The field label
  is now *"Auth token (blank = none)"*, matching the client. A non-blank token still starts
  immediately and persists across restarts.
- **Android on-device agent keeps the BLE radio alive.** Declared `neverForLocation` on
  `BLUETOOTH_SCAN` and hold the screen on while the agent runs, so scanning/connections don't stall
  when the device would otherwise idle.

## [0.8.0] - 2026-07-05

### Added

- **Cross-platform device `Identifier` via agent-side handle translation** (capability
  `identifier.translate`). The client declares its local `IdentifierFormat` in the handshake; a
  supporting agent mints device handles already in that format and reverse-maps ops back to the real
  radio device, so a remote peripheral's Kable `Identifier` now works on every client platform
  regardless of the agent's platform. Same-platform pairings are unaffected (identity translation).
  Implemented in **both** the Kotlin agent and `agent-rs`. See
  [docs/proposals/agent-side-identifier-translation.md](docs/proposals/agent-side-identifier-translation.md).
- **Identifier strict mode** — an agent-side switch that passes handles through untranslated to
  surface cross-platform format mismatches loudly (dev/CI). Live-toggleable from the Kotlin agent's
  dashboard (`POST /api/strict`); a `--strict-identifiers` flag on `agent-rs`.

### Fixed

- Scan and observe streams now issue their `scan.start` / `observe.start` from a flow
  `onSubscription` hook, so the collector is guaranteed registered on the shared event stream before
  the agent can emit — closing a rare race where the first advertisement/notification could be
  dropped under load. `AgentSession.events()` now returns a `SharedFlow<AgentEvent>` (was `Flow`).

### Notes

- Wire protocol version stays **1**: the new `identifier.translate` capability and the optional
  `ClientHello.identifierFormat` field are additive and backward-compatible with 0.7.0 peers.

## [0.7.0] - 2026-07-05

### Added

- Initial public release of RemoteBLE — a Kotlin Multiplatform "remote mode" for
  [Kable](https://github.com/JuulLabs/kable). App code written against Kable's
  `Peripheral`/`Scanner` runs unchanged whether the radio is local or driven by a
  remote **agent** over a WebSocket (CBOR-framed protocol).
- `:client-sdk` — transport → session → GATT/scan ops → Kable adapters. Targets
  JVM (tests), Android, iOS. Published to Maven Central as
  `dev.warsha.remoteble:client-sdk`.
- `:protocol` — the wire contract (`Frame`/`Op`/`OpResult`/`AgentEvent`) + CBOR/JSON
  codec. Published as `dev.warsha.remoteble:protocol`.
- `:agent` — the remote Bluetooth agent (WebSocket server, op handler, Kable radio
  engine, live status dashboard) on JVM/Android/iOS.
- `agent-rs` — a second, independent agent implementation in Rust (tokio,
  `btleplug`), interop-verified byte-for-byte against the Kotlin codec.
- Full GATT surface over the wire: scan, connect, discover, read, write,
  observe/notify, MTU, plus capability-gated descriptors, pairing, connection
  priority, batched scan, and connection-slot telemetry.
- Peripheral ownership/leasing, reconcile-on-reconnect, and independent IP-vs-BLE
  connection state machines.
- A normative, language-agnostic conformance spec
  ([docs/agent-proxy-spec.md](docs/agent-proxy-spec.md)).

[0.8.1]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.1
[0.8.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.0
[0.7.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.7.0
