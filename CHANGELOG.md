# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The **wire protocol** version is tracked separately from the library version — it
is a distinct compatibility contract for agent/client implementers. Current wire
protocol version: **1**.

## [0.8.3] - 2026-07-10

> **GitHub-only agent release** — `agent-artifacts.yml` runs on the `v0.8.3` tag (agent binaries),
> `release.yml`/Maven Central publish stays skipped. This SDK version does not appear on Central;
> Central consumers get these changes in 0.9.0's consolidated publish.
>
> **Shipping CI-validated; on-radio verification batched into the next release.** Both features are
> correct by construction and covered in CI — feature F's exactness is a property of `EngineBleBackend`'s
> Kable suspend calls (which resume on GATT completion callbacks, and have since `d97146f` — not
> polling), and feature C's write ordering is asserted by `BleAgentTest`
> (`concurrentWritesToOneDeviceReachBackendInSubmissionOrder`). What CI *cannot* produce is the live
> radio: the F read/write/notify assertions (`:e2e-runner:jvmRun`) and the C throughput number
> (`:e2e-runner:throughputRun`, before/after the burst API) need a peripheral. Rather than hold the
> release on limited hardware, that live pass is **deferred to the next release's batched hardware
> round** (alongside 0.8.1/0.8.2's pending checks) — see `docs/phase7-bringup.md`. No behavior here is
> unverifiable in principle; it just hasn't been exercised on a physical link yet.

### Added

- **Client-side write-without-response pipelining** — `RemotePeripheral.writeWithoutResponseBurst` /
  `RemoteGattClient.writeWithoutResponseBurst`. Keeps up to `window` (default 8) WithoutResponse
  writes in flight instead of paying one full client↔agent round trip per write before sending the
  next — the fix indicated by tracing a serial WWR burst end-to-end
  (`ai-context/0.8.3-implementation-plan.md` §2b): the dominant cost is N sequential WebSocket
  round-trips, not the radio. **No wire change** — frames are still sent one per write, in
  submission order; only the client's await discipline changes. Backed by a new
  `AgentSession.dispatch(op, timeout)` primitive (send now, await the reply later) that shares its
  send-and-track core with `request()`. Submission order is preserved **end-to-end** (see the
  agent-side write ordering below); WWR *delivery* remains best-effort by BLE design.
- **Agent-side per-device write ordering.** `BleAgent` runs each command on its own coroutine, so a
  pipelined write burst could previously race into `backend.write` out of submission order (harmless
  under the old serial-await client, exposed by the new burst API). The agent now **chains writes per
  device** — each write awaits the prior same-device write before reaching the backend, so writes hit
  the radio's FIFO GATT queue in submission order — while writes to other devices and non-write ops
  stay fully concurrent. This per-device write ordering is now part of the agent conformance contract.
- **`:e2e-runner:throughputRun` (`ThroughputMain.kt`)** — the WWR throughput/latency baseline driver
  the burst API's design is measured against (§2a): bursts N MTU-sized WithoutResponse writes
  serially against the `TestProfile` peripheral and reports bytes/s plus per-write latency
  percentiles (min/p50/mean/p90/p99/max).
- **`:e2e-runner:jvmRun` gained exact-completion assertions (feature F)**: read-exactness
  (bump-then-reread must differ), with-response write surfaces `WRITE_FAILED` on a forced
  peripheral error, WWR still returns `Ok` on that same forced error (documents the inherent
  no-ATT-ack limit rather than treating it as a bug), and the notify stream is checked for
  no-miss/no-duplicate delivery. Interactive — pauses for phone-side debug-control toggles; see
  `docs/phase7-bringup.md`.

### Fixed

- **`design-decisions.md`'s "write/notify are best-effort on the real engine" boundary row was
  stale, not a real gap.** `EngineBleBackend` has used real Kable suspend calls since `d97146f`
  ("Prepare for open-source release"), not polling: read and write-with-response are exact (they
  resume on Kable's GATT completion callbacks); WWR and notify-delivery are best-effort **by BLE
  design** (neither has an ATT-level acknowledgement to plumb), not an implementation gap. Row
  closed; `phase7-bringup.md`'s matching "reads poll / writes have no write-complete callback" prose
  corrected to match, and its live-run transcript updated for the new assertions above.
- **Client-side completion contract documented** (`client-sdk.md`, `getting-started.md`): a
  delivered `Ok` is exact — the agent replies only after the real GATT completion — but a
  `TIMEOUT`/`TRANSPORT_LOST` on a write is ambiguous, since the write may have already succeeded on
  the radio before the Reply was lost. This is why writes/`WriteDescriptor`/`Pair` default to no
  auto-retry and are not replayed on reconnect — a deliberate safety property, not a gap. Added a CI
  regression test
  (`SessionEndToEndTest.writeDropBeforeReplySurfacesTransportLostAndIsNotRetried`) asserting it.

## [0.8.2] - 2026-07-09

> **GitHub-only agent release** — `agent-artifacts.yml` runs on the `v0.8.2` tag (agent binaries),
> `release.yml`/Maven Central publish stays skipped. This SDK version does not appear on Central;
> Central consumers get these changes in 0.9.0's consolidated publish.
>
> **⚠️ Hardware validation pending.** `conn.params`/`conn.priority` and the `CharNode.properties`
> on-device check have been verified against fakes + CI, **not yet on a real Android radio**. Deferred
> to a batched hardware-rig round (alongside 0.8.3's radio-gated work). Drive it with
> `:e2e-runner:connParamsRun` (`setConnParams` on all three profiles → `Ok` on Android; `UNSUPPORTED`
> on iOS/JVM). Until then, treat the Android engine binding as unproven on-device.

### Added

- **Connection parameters over the wire** (capability `conn.params`). A new `Op.SetConnParams`
  requests a coarse `ConnProfile` (`LOW_LATENCY` / `BALANCED` / `LOW_POWER`) — an optional,
  currently-unused `ConnParamHint` is reserved wire space for a future fine-grained engine.
  `RemotePeripheral.setConnParams(profile, hint)` is a RemoteBLE-specific extension beyond Kable's
  `Peripheral` surface. Participates in reconcile-on-reconnect: the last `setConnParams` per device
  replays after a transport reconnect so a blip can't silently revert a peripheral to a
  battery-hungry interval. `agent-rs` mirrors the codec for byte parity; btleplug exposes no
  interval/priority control at all, so agent-rs never advertises the capability.
- **`conn.priority` (0.8.1) now has a real backend.** It shipped wire-only in 0.8.1 — no engine
  implemented it, so every real agent answered `UNSUPPORTED`. Android now implements both
  `conn.priority` and `conn.params` from the same `AndroidPeripheral.requestConnectionPriority`
  binding (`Priority.Low/Balanced/High`); iOS and JVM/btleplug still answer `UNSUPPORTED` and don't
  advertise either capability (no portable or platform API for either exists there).
- **`RemotePeripheral.rssi()`'s capability is now discoverable.** The client's requested-capability
  set never included `Capabilities.RSSI` (a 0.8.1 regression), so `session.supportsCapability(RSSI)`
  was always `false` even against an Android/Apple agent that supports it — `rssi()` itself still
  worked since it doesn't gate on the negotiated set. Fixed by adding `RSSI` to the requested set
  alongside `CONN_PARAMS`.

### Fixed

- **`CharNode.properties` design-decisions row was stale, not a real gap.** The docs claimed
  characteristic property bits were populated only on the macOS engine via a `propertiesOf` seam
  that never existed; `EngineBleBackend.toNode()` has always read Kable's `properties.value`
  directly in `commonMain`, and both the JVM/btleplug and Android engines already carry real native
  property bits. Corrected the doc and added a regression test
  (`EngineBleBackendJvmTest.toNodePreservesNonZeroPropertyBits`) rather than changing any behavior.

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
