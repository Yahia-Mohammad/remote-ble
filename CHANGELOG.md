# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The **wire protocol** version is tracked separately from the library version — it
is a distinct compatibility contract for agent/client implementers. Current wire
protocol version: **1**.

## [Unreleased]

## [0.10.0] - 2026-08-04

> **The consolidated Maven Central release.** 0.8.1 through 0.9.1 shipped as GitHub-only agent
> releases to conserve Central file quota, so this single publish carries everything accumulated
> since 0.8.0 — the last version on Central. Upgrade guidance, including the breaking `authToken`
> change and the AGP 9 build requirement, is in
> [`docs/migrate-to-0.10.0.md`](docs/migrate-to-0.10.0.md).
>
> **`:log` is a new required coordinate.** The published SDK closure is `client-sdk` → `protocol` +
> `log`. It is brought in through published metadata; do not substitute a project dependency.
>
> **The hardware gate carried since 0.9.0 is discharged.** All four validation rigs are complete
> (25/25 cases) — real radio, iOS lifecycle, TLS proxy, and a Linux container host. What the
> container image does *not* cover is stated in
> [`docs/proposals/rust-agent-container.md`](docs/proposals/rust-agent-container.md): one amd64 Linux
> host is validated, while arm64, AppArmor, SELinux-enforcing, and rootless Podman are not.
>
> Shared mode remains disabled pending a safe participant model.

### Added

- **Scan-concurrency lifecycle hardening.** Guaranteed scans now use per-admission fencing tokens,
  serialized Kotlin physical-scanner replacement, direct bounded Rust arbiter mailboxes, and raw
  coordinator delivery in the Rust backend. Each logical scan has exactly one bounded reservation on
  both agents, and waiting for a physical collector to unwind is time-bounded so a slow backend
  teardown cannot hold the agent-wide coordinator lock. Paired WebSocket conformance and
  deterministic boundary evidence run locally; real-radio Rig B validation remains a separate
  release gate.

- **Configured scan concurrency modes.** Agents advertise exactly one of `multiplexed` (default),
  `single`, or `uncontrolled`; guaranteed modes use stable-client ownership, replay-safe rebind,
  bounded replay, and fair logical-scan mailboxes. Every client session offers all three capability
  strings automatically, including manually constructed `DefaultAgentSession`s, so the negotiated
  intersection is always exactly the agent's configured mode. `SCAN_UNAVAILABLE` is capability-gated
  so legacy clients retain `AGENT_BUSY`. Final Apple hardware evidence remains release-gated.

- **Clean-consumer gates for the Android and Apple publication variants.** `consumer-tests/android`
  and `consumer-tests/kmp` join the existing JVM fixture, each a standalone Gradle build resolving
  Maven coordinates only. They exist because `jvm`, `android` (`.aar`) and Apple (klib) artifacts are
  selected through different Gradle metadata and can fail independently — a closure that is complete
  for one can be broken for another. Both resolve the full closure
  (`client-sdk-android` → `protocol-android` + `log-android`, and the `iosarm64` equivalents) and
  both were verified to fail on an unpublished version, so neither can pass vacuously. Wired into
  `release-gates.yml`; the Apple gate runs on macOS because Kotlin/Native Apple targets do not
  cross-compile.

  > **Android consumers on AGP 9 must put KGP 2.4+ on their build classpath.** AGP 9.3.0's built-in
  > Kotlin compiler is 2.2.0 and reads metadata only to 2.3.0, while this SDK publishes 2.4.0
  > metadata — so a stock AGP 9 module fails with *"was compiled with an incompatible version of
  > Kotlin"*. Add `id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false` to the
  > consumer's `plugins` block. Note the older `org.jetbrains.kotlin.android` plugin is a hard error
  > under AGP 9, so the obvious remedy is the wrong one. Found by the new Android gate; the JVM gate
  > cannot detect it.

- **Multi-architecture Rust-agent image workflow.** PR/main builds smoke-test the amd64 image; a
  version-tag workflow publishes an amd64/arm64 GHCR manifest with semantic/commit tags, OCI labels,
  Buildx SBOM/provenance, and an archived digest after the shared version guard passes.

- **Local Rust-agent OCI image.** `agent-rs/Dockerfile` produces a multi-stage, non-root Debian
  runtime that uses host BlueZ through system D-Bus and fails closed without credentials on its
  published `0.0.0.0:8080` default. `container-smoke.sh` verifies version and bind policy; actual
  Ubuntu/Pi D-Bus radio evidence remains release-gated hardware validation.

- **Radio-less simulated JVM agent.** A versioned, bounded `schemaVersion: 1` profile now drives a
  production `SimulatedBleBackend`, selected with `--simulate <profile.json>` or
  `REMOTE_BLE_SIMULATE`. The canonical HRM/Battery profile, real-WebSocket SDK E2E test, and CI job
  let scan/connect/discover/read/write/observe run without Bluetooth hardware. Rust intentionally
  does not interpret simulation profiles in this release.

- **Permanent 0.10.0 release gates.** A dedicated cross-agent conformance task/job, merged Kotlin
  JVM Kover report, Rust Tarpaulin coverage report, clean Maven-local JVM consumer fixture,
  Gitleaks, dependency review, Cargo advisory/license policy, Dependabot, and an archived SPDX
  source SBOM now run on pull requests, `main`, manual dispatch, and/or weekly review. The consumer
  gate also ensures the SDK's public `:log` dependency is published with the SDK.

- **Published logging dependency.** `:log` is now published to Maven Central alongside
  `:protocol` and `:client-sdk`, so downstream consumers can resolve the SDK's logging API from
  its generated POM without source-tree or composite-build dependencies.

- **New `:log` module** — a shared, zero-dependency KMP logging facade (`Logger`, `LogLevel`,
  `LogSink`, `PrintlnSink`, `AndroidLogSink`, `AppleLogSink`, `bytesPreview`, `RateLimitedLog`)
  that both `:client-sdk` and `:agent` depend on. `Logger` is a global object: consumers set
  `Logger.level` and `Logger.sink` once at startup (e.g. `Logger.sink = AndroidLogSink;
  Logger.level = LogLevel.INFO`). Defaults silent — no consumer impact unless configured. Lazy
  message lambdas, level check first, zero allocation below the threshold. Never logs a bearer
  token; payload bytes only at `TRACE` (truncated). Targets JVM, Android, iOS. No wire impact,
  no new external dependencies.

- **Client SDK instrumentation.** `WebSocketAgentTransport` (transport lifecycle: connect,
  disconnect, reconnect-backoff, gave-up), `DefaultAgentSession` (request/retry/reply, reconcile
  summary, handshake + capability negotiation, previously-silent error paths),
  `RemotePeripheral` (connect/disconnect/unsolicited-drop lifecycle, MTU best-effort failure,
  WWR burst item failures).

- **Kotlin agent instrumentation.** `BleAgent` (handshake, decode failures, op errors, connect
  failure, unsolicited disconnect, scan/observe lifecycle), `AgentWebSocketServer` (client
  connect/disconnect, 401 rejections), `PeripheralRegistry` (lease acquire/resume/release),
  `ConnectionWatcher` (probe failures), `EngineBleBackend` (Kable state transitions),
  `HandleTranslator` (minted/primed mappings). Read-only log-level status at `GET /api/log-level`
  (the live `POST` toggle was removed by the 0.9.0 addendum's dashboard-mutation hardening — see
  **Fixed**). `REMOTE_BLE_LOG` env var on `Main.kt` (default `info`).

- **`agent-rs` logging polish.** `--log-level` / `REMOTE_BLE_LOG` clap flag (seeds `EnvFilter`
  when `RUST_LOG` is unset — explicit `RUST_LOG` still wins), `--log-format json` (for
  journald/Loki setups), per-connection `tracing` spans propagated into spawned op tasks,
  inbound binary dump demoted to `TRACE`.

- **`agent-rs` CI now runs `cargo fmt --check`, `cargo clippy -- -D warnings`, and `cargo test`**
  in `build.yml`, matching the Kotlin build/test gate.

### Fixed

- **`agent-rs` on Linux could discover a peripheral and then fail every operation on it.** Each GATT
  op resolved its own fresh `Peripheral` through `Adapter::peripherals()`, but on BlueZ only the
  instance that ran `discover_services()` reports a populated characteristic table — so read, write,
  and observe all failed with `CharacteristicNotFound` after a successful connect and discover.
  `connect()` now retains the live handle and every op resolves through it, matching what the Kotlin
  agent has always done. CoreBluetooth tolerates the old pattern, which is why only a Linux host
  surfaced it.

- **A non-owner can no longer learn anything about a leased device from `agent-rs`.** Operations the
  Rust agent does not implement (`rssi`, `conn.params`) fell to a catch-all arm that answered
  `UNSUPPORTED` without consulting the lease registry, so a client that did not own the peripheral
  got a capability answer where the Kotlin agent gives `PERIPHERAL_BUSY`. Both agents now authorize
  first. Found by the new two-client rig case on real radio, and confirmed fixed there.

- **A single stalled liveness probe no longer tears down a healthy connection.** Both agents now
  require two consecutive failed deep probes before declaring an unsolicited disconnect. A probe is a
  real GATT round trip, so one failure cannot distinguish "the peripheral is gone" from "this round
  trip did not return in time" — and the second case is not hypothetical: on real hardware a probe
  read of an encrypted characteristic blocked on a host pairing dialog until the probe timeout, and
  the watchdog dropped a link that was never in trouble. The probe cannot dodge this by choosing a
  safer characteristic, because encryption is a GATT security *permission* and is not visible in the
  discovered table. The cost is one extra probe interval before a genuine silent drop is declared;
  the native disconnect stream reports real drops in ~145 ms, so this loop is the backstop rather
  than the primary detector.

- **A stalled ATT transaction no longer wedges an agent's write chain.** Both reference agents now
  bound a single characteristic read/write at 10s (below the client SDK's 15s default op timeout)
  and report `TIMEOUT` when it expires, instead of awaiting the backend indefinitely. Confirmed on
  hardware: btleplug on macOS never delivers the completion for a write-with-response that the
  peripheral answers with an ATT error — the call neither returns nor throws. Because both agents
  serialize same-device writes, that stalled write never released its ordering turn, so every
  later write to that device blocked behind it, and in the Rust agent it also held one of the 64
  in-flight op permits permanently; nothing recovered it, since the client's timeout is
  client-side only and the protocol has no cancel op. Expiry is reported as `TIMEOUT` rather than
  `WRITE_FAILED` because a transaction that never completed has an unknown outcome — the
  peripheral may have applied it — and `Op.Write` is non-idempotent, so auto-retry stays off
  either way. `connect` is deliberately left unbounded (legitimately long-running).

  The live E2E step "Write-with-response error surfaces WRITE_FAILED (F)" is consequently recorded
  as a known failure (XFAIL) on btleplug-backed agents rather than being relaxed to accept
  `TIMEOUT` — the expectation is still enforced on backends that can satisfy it, and an XPASS now
  signals the gate is stale. Set `REMOTE_BLE_E2E_BTLEPLUG=false` when running against the Android
  or Apple agent, whose native Kable backends are expected to deliver ATT errors correctly but are
  unverified on hardware.

- **Writes on a connection that has stopped completing them now fail immediately**
  (`REMOTE_BLE_WRITE_FAIL_FAST`, default `true`, both reference agents; `agent-rs` also takes
  `--write-fail-fast`). Follow-up hardware verification of
  the bound above found the gap has a second symptom: after one write-with-response is answered by
  an ATT error, btleplug delivers no further write completions for that peripheral *at all* — the
  peripheral's own log shows later writes arriving and being accepted with error injection already
  off, yet nothing returns. Reads keep working and a fresh connection writes normally (measured at
  66ms), so re-establishing the connection is the only recovery. Without this, every later write
  costs a full 10s before failing. The short-circuit reports the same `TIMEOUT` error the wait
  would have produced — it changes latency, not semantics — and the switch exists so the workaround
  can be turned off deliberately rather than the agent silently special-casing a backend defect.
  The state is scoped to a single connection generation, so a write that stalls, drops, and only
  times out after the client has already reconnected cannot degrade the fresh connection; with
  fail-fast off, a write that completes again clears it. The setting is printed at startup. The E2E step "a failed write never poisons the session" is
  gated as XFAIL on btleplug-backed agents for the same reason as the step above it: no agent-side
  handling can make it pass while the backend behaves this way.

- **Cross-client device authorization and release-surface hardening.** Both reference agents now
  require the lease-owning client to hold a live connection before any device-bearing operation can
  reach the backend; a scanned handle is no longer sufficient to read, write, observe, configure,
  or disconnect another client's peripheral. The 0.9.0 source surface is now exclusive-only:
  shared-mode configuration is rejected and its dashboard/mobile controls are removed. Dashboard
  mutation endpoints have also been removed pending an authenticated operator plane; the remaining
  dashboard is read-only. Covered by a two-client table-driven Kotlin suite and a Rust
  fake-backend suite (`transport::server::tests`).

- **`agent-rs` connection-scoped streams and task ownership.** Scan and observation state was
  previously keyed process-wide by the client-generated `i64` id alone, so two clients' first scan
  or subscription (both `1`) collided and either could stop the other's stream. IDs are now
  internally composited with a per-connection generation (`StreamKey { connection, local_id }`);
  the wire ID is unchanged. Command tasks are now tracked in a per-connection `JoinSet` and
  cancelled/joined before the connection's lease-release grace is scheduled, so a slow `connect`
  can no longer complete — and commit a lease — after its socket has already closed; that path now
  unwinds the radio connection and returns `TRANSPORT_LOST` instead.

- **`agent-rs` write ordering, observation teardown, MTU, and scan filters.** Writes to the same
  device now reserve their position in a per-device chain on the sequential receive loop (matching
  the Kotlin agent's existing guarantee), so a pipelined burst can no longer reach the backend out
  of submission order. `observe.stop` now actually cancels the notification task and unsubscribes
  (reference-counted per characteristic) instead of returning a fabricated `Ok`. `request_mtu`
  returns `UNSUPPORTED` rather than echoing the client's requested value as if it had been
  negotiated. Scan filters (name/service) are now evaluated per subscriber instead of ignored.

- **Client SDK session/transport lifecycle.** `AgentSession` gains an explicit `close()` that
  retires its child scope, fails pending requests, clears replay state, and closes the transport
  before the demo `AgentConnection` closes its `HttpClient` — replacing a connection no longer
  leaves the old transport's reconnect loop running underneath the new one.

- **One release version, enforced in CI.** `scripts/check-release-version.sh` cross-checks
  `gradle.properties`, `agent-rs/Cargo.toml`, the macOS `Info.plist`, the README dependency
  snippet, and the Kotlin agent's `ServerHello.agentInfo` string, and now gates `build.yml`,
  `agent-artifacts.yml`, and `release.yml` (passed the tag as the expected version) — a tag can no
  longer publish while any component reports a different release.

- **Reconcile-on-reconnect now works under identifier translation.** Previously a transport blip
  permanently broke resume for cross-platform clients: the client replays the *translated* handles
  it was issued, but the fresh connection's reverse map was empty and synthesis is one-way, so the
  replayed `connect` reached the backend as an unknown device. Both agents now **re-seed** the
  translator on the handshake by deterministically re-minting mappings for the leases the registry
  still holds for that client (`transportGrace` warm leases), and the client now sends its hello
  and its reconcile replay from one coroutine, hello first. No wire change. Remaining limitation
  (documented): after an *agent restart* there is nothing to re-seed from — a translated client
  must rescan. See docs/proposals/agent-side-identifier-translation.md §"Reconnect & reconcile".
- **Handshake hardening — first hello wins.** `negotiated`/`translator` are published safely
  (@Volatile) in the Kotlin agent; both agents fix negotiation at the first `ClientHello` on a
  connection and answer repeated hellos idempotently (previously a repeated hello renegotiated —
  and in `agent-rs` wiped the translator's reverse map, breaking op routing). `BondState` is now
  capability-gated on emit like `SlotState`. The conformance spec gained the missing
  `hello`/`server_hello` frame definitions and a normative §5.3 (lenient negotiation, v1 baseline
  for pre-hello ops, first-hello-wins), with overclaims about op gating and version selection
  corrected to match both reference implementations.

## [0.8.3] - 2026-07-10

> **GitHub-only agent release** — `agent-artifacts.yml` runs on the `v0.8.3` tag (agent binaries),
> `release.yml`/Maven Central publish stays skipped. This SDK version does not appear on Central;
> Central consumers get these changes in 0.10.0's consolidated publish.
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
  next — the fix indicated by tracing a serial WWR burst end-to-end: the dominant cost is N
  sequential WebSocket round-trips, not the radio. **No wire change** — frames are still sent one per write, in
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
> Central consumers get these changes in 0.10.0's consolidated publish.
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

- **0.10.0 Central-consumer migration guidance.** The new
  [`docs/migrate-to-0.10.0.md`](docs/migrate-to-0.10.0.md) collects the cumulative dependency and
  platform guidance, prominently including the earlier breaking `authToken` provider change.

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
  ([docs/agent-conformance-spec.md](docs/agent-conformance-spec.md)).

[0.10.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.10.0
[0.8.3]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.3
[0.8.2]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.2
[0.8.1]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.1
[0.8.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.0
[0.7.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.7.0
