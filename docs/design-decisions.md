# Design Decisions & Rationale

[← back to index](README.md)

This document explains **why** the system is built the way it is — the trade-offs
behind the structure, not just its shape.

## The central bet: program against Kable, swap the implementation

The product requirement is "remote BLE." The design choice is to express it as a
**substitution behind an existing interface** rather than a new API. App code is
written against Kable's `Peripheral`/`Scanner`; `RemotePeripheral`/`RemoteScanner`
are alternative implementations of those same interfaces. The local-vs-remote choice
collapses to a single construction site, [`peripheralFor(...)`](client-sdk.md#peripheralfor-and-remoteperipheralfactory--the-decision-point).

Consequences that ripple through the whole codebase:
- The wire protocol mirrors the GATT/`Peripheral` surface **1:1** (`Op` = connect /
  read / write / observe / discover / mtu / scan). There is no clever RPC layer —
  the ops *are* the BLE operations.
- The client SDK's job is "make a remote thing satisfy a local interface," which is
  why Layer 3 (Kable adapters) is thin and most of the work is in Layers 1–2.
- Anything Kable's interface exposes that the wire doesn't yet model must still
  *type-check*, so it throws `UnsupportedOperationException` rather than not existing.
  (Both early gaps — descriptor I/O and connected RSSI — are now modelled, behind the
  `descriptors` and `rssi` capabilities respectively; either surfaces `UNSUPPORTED`
  against an agent whose backend lacks it.)

### Prior art: ESPHome's Bluetooth Proxy

This "swap the implementation behind the host BLE library" idea is not original to RemoteBLE —
it's the architecture [ESPHome's Bluetooth Proxy](https://esphome.io/components/bluetooth_proxy/)
established for Home Assistant, where a remote proxy is presented to app code as an ordinary
[Bleak](https://github.com/hbldh/bleak) client. RemoteBLE does the same behind Kable, and lands
independently on several of the same refinements (connection slots, batched raw advertisements,
handshake feature negotiation). The designs diverge on target and purpose: ESPHome relays over its
Home Assistant native API (protobuf) from a **bare-metal ESP32** for home automation; RemoteBLE uses
its own CBOR/WebSocket protocol from **OS-class hosts** (macOS/Linux/Android/iOS) for development,
testing, and CI, and can't run on an ESP32. Credit where due — the core substitution move is
theirs. Full feature-by-feature comparison in [prior-art.md](prior-art.md).

## Narrow seams everywhere

The system is a stack of small interfaces, each with a fake. This is deliberate and
is what let the whole thing be built and tested **without hardware** until the very
last step.

| Seam | Hides | Real impl | Fake/alt |
|---|---|---|---|
| `AgentTransport` (client) | the network | `WebSocketAgentTransport` | `InMemoryTransport` |
| `ProtocolCodec` | the encoding | `CborProtocolCodec` | `JsonProtocolCodec` |
| `AgentBackend` (server) | per-connection wiring | `BleAgentBackend` | `FakeAgentBackend`, `BlackholeBackend` |
| `BleBackend` (agent) | the radio | `EngineBleBackend` | `FakeBleBackend`, `StubBleBackend` |

Each seam is byte- or data-level and BLE-agnostic where it can be. The payoff: the
session/adapters are tested over an in-memory pipe *and* a real WebSocket with the
same assertions; the agent's op handler is tested against a fake radio; the whole
client is tested against a fake agent. The interface that finally needs hardware
(`EngineBleBackend` ↔ a real engine) is the **only** place hardware is required.

## The error taxonomy: *where*, not just *what*

[`ErrorKind`](protocol.md#errors--agenterror--errorkind) is split into two groups by a
single question: **did the call reach the radio?**

- "Reached the radio and the radio said no" — `CONNECTION_FAILED`, `WRITE_FAILED`,
  `CHARACTERISTIC_NOT_FOUND`, … (often with a `gattStatus`).
- "Never reached the radio" — `UNKNOWN_DEVICE`, `NO_CONNECTION_SLOT`, `TIMEOUT`,
  `TRANSPORT_LOST`, …

This is the information a caller actually needs to decide what to do: a
`TRANSPORT_LOST` might succeed on retry once the link is back; a `WRITE_FAILED` from
the peer probably won't. Encoding *where* a failure occurred — rather than a flat
"error" — is the difference between a transport that's debuggable in the field and one
that isn't.

That judgment is now **encoded, not left to the caller's memory**: each `ErrorKind`
carries `transient` (could a retry help?), and each `Op` carries `isIdempotent` (is a
retry *safe*?). The client SDK combines the two into a per-op [`RetryPolicy`](client-sdk.md#error-and-retry-policies)
— a stateless `fun interface` resolved per op, overridable per call — so the default is
correct-by-construction (idempotent ops retry transient errors; a `Write` whose reply was
merely lost is never silently repeated) while any op can still be tuned. Keeping retry a
*client* concern (not a wire feature) is deliberate: the agent reports facts, the client
decides strategy.

A corollary: `TIMEOUT` and `TRANSPORT_LOST` are minted **client-side** by the session,
never sent by the agent — because by definition they describe the agent being
unreachable, so the agent couldn't have reported them. Everything else originates at
the agent/backend.

## Two state machines, never conflated

The single subtlest correctness property. There is an **IP transport** state
(`TransportState`) and a **physical BLE** state (`BleConnState`), and they are
independent:

- A momentary IP blip does not mean the BLE link dropped.
- The agent restarting *does* lose BLE state — but the client can't tell that apart
  from a blip at the transport layer.

The design keeps them separate end to end: `TransportState` lives on the session;
`BleConnState` arrives as `ConnectionState` events and drives `RemotePeripheral.state`.
The reconnection policy (below) is built so that the transport layer can recover
transparently *without* fabricating BLE-state changes.

**One refinement, added 0.10.0 after a hardware finding.** Keeping the two separate is right while the
transport might still recover; it stops being right once it definitively cannot. A killed agent
sends no `ConnectionState` event — it cannot — so a peripheral that only ever moves on wire events
sat at `Connected` indefinitely while every operation failed `TRANSPORT_LOST`, and a Kable consumer
gating on `state.collect { … }` waited for a transition that could never arrive. `TransportState`
therefore distinguishes `DISCONNECTED` ("a reconnect episode is running — still a blip") from
`GAVE_UP` ("nothing is going to fix this"), and `RemotePeripheral` moves to `Disconnected` **only**
on the latter. The rule is unchanged for blips, which is the case it was written for; what is new
is that "the transport is never coming back" is not a blip, and continuing to report `Connected`
there is not separation of concerns, it is a false claim about the radio.

### Reconnection

**Policy: auto-replay subscriptions, surface the drop — but never synthesize a BLE
disconnect on an IP blip.**

On a transport reconnect the session re-issues, with their **original stream ids**,
every `Connect` and `ObserveStart`/`ScanStart` it believes is live
([reconcile-on-reconnect](client-sdk.md#defaultagentsession)). Three things make this
the right design:

1. **The observe/scan flows can't replay themselves.** They send their start op once,
   on collect, then park in `awaitClose`. A transport drop doesn't fail a parked
   flow, so without session-level replay a reconnect would leave the agent with no
   subscription while the client still "has" one. The session is the only component
   that sees both the drop and the set of live streams, so replay belongs there.
2. **Idempotency makes it safe both ways.** If the agent survived the blip,
   replayed `Connect`/`ObserveStart` are no-ops (the agent returns `Ok` without
   re-emitting). If the agent restarted, they actually re-establish. The client
   doesn't need to know which happened.
3. **Why *not* synthesize a BLE disconnect on the IP drop?** Because it would be
   actively harmful: a synthetic `DISCONNECTED` would tear down `RemotePeripheral`'s
   connection scope, which cancels the very `observe` flows we're about to replay —
   their `awaitClose` would fire `ObserveStop` and *remove* them from the replay set.
   So the drop is surfaced through `transportState` (and real BLE drops through real
   `ConnectionState` events), and the BLE state is left untouched until the agent
   actually reports a change.

The replay set is maintained from **successful** ops only (a failed connect never
happened on the agent) and a `Disconnect` forgets that device's subscriptions (they
can't outlive the connection).

The *timing* of reconnection is a separate, configurable concern: the transport retries
with exponential [`Backoff`](client-sdk.md#error-and-retry-policies) under a
[`ReconnectPolicy`](client-sdk.md#error-and-retry-policies). By default it retries
forever, but a `maxAttempts` budget can bound an episode and fire `onGaveUp` so a UI can
distinguish "still reconnecting" from "gave up" instead of watching a silent loop. The
same loop also covers the **initial** connect — a client started before its agent is up
self-heals once the agent appears, rather than the first attempt being one-shot.

## Concurrency model

- **Client session.** A single `AtomicLong` mints correlation ids. A `pending` map of
  `cid → CompletableDeferred` (under a `Mutex`) matches replies to requests. One
  decode coroutine, one transport-state coroutine. Reconcile runs on its own launched
  coroutine so it can't block the state watcher. Pending-slot cleanup uses
  `NonCancellable` so a cancelled caller still tidies up.
- **Agent.** One coroutine per command (`scope.launch { handle(cmd) }`) so a slow read
  can't head-of-line-block an `observe.stop`. All per-session maps/sets (connected
  devices, scan jobs, observe jobs) are guarded by one `Mutex`. The slot is taken
  *before* the slow native connect so the cap holds under concurrency, and released on
  failure.
- **Cross-client ownership.** The per-session `BleAgent`s share one `PeripheralRegistry`
  (the radio is shared, so ownership must be too). `connect` acquires the lease there, and
  a peripheral another client owns is rejected with `PERIPHERAL_BUSY` (see *Peripheral
  ownership*).
- **The slot cap is agent-wide, not per session.** It lives in the registry, alongside the
  leases, because the constraint it models is the host controller's: two clients holding
  four peripherals each exhaust the same radio as one client holding eight. Acquiring a
  lease *is* taking the slot — there is no second, per-session capacity rule to disagree
  with it — so a lease inside its grace window keeps occupying capacity, which is the
  honest answer for anyone asking whether the next `connect` will succeed. It was per
  session through 0.10.0, which meant a client's `slots` reading described only itself.
- **Streams.** Both sides express scans/subscriptions as cold `Flow`s opened on
  collect and torn down on cancel (`channelFlow` + `awaitClose`), so lifecycle is tied
  to collection — no manual bookkeeping leaks.
- **Scan ownership.** Guaranteed scan modes are agent-lifetime resources keyed by stable client
  identity plus `scanId`; the connection generation fences delayed stop and grace cleanup. A single
  physical collector fans out only matching merged advertisements, while independent logical
  mailboxes and round-robin connection arbitration prevent one scan from monopolising another.
- **Event flow never backpressures the decode loop.** The session's `events()` shared
  flow is `MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)`.
  Events are emitted from the *same* coroutine that decodes replies, so a suspending
  `emit()` would let a slow event subscriber stall reply delivery and hang every in-flight
  `request()`. Dropping the oldest buffered event keeps the loop live; correctness-critical
  state (connect/observe success) flows through *replies*, not events.
- **Best-effort teardown preserves cancellation.** Fire-and-forget cleanup
  (`scan.stop`/`observe.stop`, `disconnect`, `sendHello`, warm-link release) is wrapped in
  `runCatchingNonCancellation { … }`, which swallows ordinary failures but **rethrows
  `CancellationException`** so structured concurrency isn't broken by a teardown path. The
  Kable `connect()` mirrors this: a non-cancellation failure resets state and best-effort
  disconnects the agent; a cancellation propagates untouched.

## Stream ids are session-global

`subId`/`scanId` come from `AgentSession.nextStreamId()` and are unique across the
*whole session*, not per peripheral. This avoids a class of bug where two peripherals
independently pick `subId = 1` and their notifications collide on the shared event
flow. It also means a replayed `ObserveStart` can reuse the exact id the client's pump
is already filtering on.

## Correlation ids are client-assigned

The client owns `cid` allocation (monotonic `AtomicLong`); the agent only echoes it in
the reply. This keeps matching trivial and stateless on the agent — it never has to
allocate or track ids, just mirror them. Events deliberately carry **no** `cid`
(they're unsolicited) and are routed by the `subId`/`scanId`/`device` baked into the
event body.

## Per-op-class timeouts, tuned for a relay

A single global timeout is wrong for BLE-over-IP: establishing a link (scan → connect
→ bond) and discovering a full GATT table are intrinsically slower and more variable
than one read/write. [`RemoteTimeouts`](client-sdk.md#remotetimeouts) gives `connect`
(30s) and `discover` (20s) more headroom than ordinary ops (15s). The defaults assume
a relayed worst case; an app on a controlled LAN can tighten them. The agent's
`EngineBleBackend` has its *own* shorter internal timeouts for the native polling —
those are about radio responsiveness, deliberately distinct from the client's
end-to-end request budget.

## MTU is learned, not assumed

`RemotePeripheral` requests an MTU on connect (`requestedMtu`, default 247) and caches
what the agent **negotiated**, feeding `maximumWriteValueLengthForType = negotiated −
3`. On platforms that auto-negotiate (iOS), `RequestMtu` simply reports the live value
— so the same code path *learns* the MTU everywhere instead of guessing. It falls back
to the ATT default (23) while disconnected. Throughput coalescing of
write-without-response bursts landed in 0.8.3 (`writeWithoutResponseBurst` pipelines to
fill the in-flight window, no wire change; the agent chains writes per device so
submission order survives to the radio — see the boundaries table below).

On the agent side an `RequestMtu` outside the ATT range **23–517** is an argument error: both
agents reject it as `INVALID_REQUEST` before the backend call, rather than as `UNSUPPORTED` (a
too-large MTU is a bad argument, not a missing capability — the same distinction the other operation
ceilings draw).

## Auth is a hook, not a framework

A single bearer token: the client supplies it via a **suspend provider**
(`authToken: suspend () -> String?`) rather than a fixed string, and sends
`Authorization: Bearer <token>` on the WebSocket upgrade; the server enforces it at the
handshake (`401` before the upgrade completes, so the client never reaches CONNECTED).
Rejecting at the handshake — rather than accepting then closing — avoids a
CONNECTED→DISCONNECTED flap. The provider is invoked **once per connection attempt**
(including every reconnect), so a rotating or short-lived token is refreshed on reconnect
instead of replayed stale, and the SDK never caches it — the embedder owns token
lifecycle. The SDK deliberately owns no identity system beyond the shared token; richer
auth is the embedder's concern.

## CBOR by default, JSON for debugging

Reads/writes/notifications carry raw `ByteArray`s, so a binary wire format is the
natural default (JSON would base64-bloat every payload). CBOR is self-describing and
maps cleanly onto the polymorphic `@SerialName` discriminators. `JsonProtocolCodec`
exists for human-readable debugging; the session/transport are codec-agnostic so
swapping is a one-liner. The CBOR experimental opt-in is kept off the public API so
consumers don't inherit it.

For the full CBOR-vs-Protobuf trade-off — including why the compactness ESPHome gets from
Protobuf on an ESP32 doesn't pay off on RemoteBLE's real-OS hosts, and the `@CborLabel` lever
that would narrow the gap if it ever mattered — see [prior-art.md](prior-art.md#part-b--cbor-vs-protobuf-and-why-cbor-here).

## Content-based equality on the wire types

Every wire type holding a `ByteArray` is a hand-written `class` (not `data class`)
with content-based `equals`/`hashCode` and a size-only `toString`
([details](protocol.md#serialization-rules-for-bytearray-bearing-types)). Without
this, the round-trip suite couldn't assert structural equality, and event
de-duplication/matching would use array identity. `toString` printing sizes (not
bytes) keeps logs readable and avoids leaking payloads.

## Peripheral ownership

BLE allows only one central↔peripheral link, and all clients share the agent's single
radio. So the agent leases each peripheral to **one** client at a time. The shared
`PeripheralRegistry` is the authority. The pre-0.9 implementation exposes a shared-mode switch,
but the 2026-07-15 review found that it does not model participants safely; 0.9.0 disables that
surface and 0.9.1 owns any full participant design.

Transport loss and radio loss are resumable grace cases; an explicit disconnect is not.

- **Acquire on connect.** A second client's `connect` to an owned peripheral is rejected
  with `PERIPHERAL_BUSY` before any radio call. Re-connecting as the owner is idempotent.
- **Explicit disconnect → immediate release.** `Disconnect` disconnects the radio and drops the
  lease at once; a later connect is a new connection.
- **Unsolicited BLE disconnect → `leaseGrace` (10s).** A drop caught by `ConnectionWatcher`
  schedules release, debouncing radio flaps without treating an operator disconnect as resumable.
- **Transport drop → `transportGrace` (120s), link kept warm.** When a client's WebSocket
  drops, `BleAgent`'s job-completion teardown hands off to the registry, which leaves the
  radio link **up** and schedules release. A reconnect within the window **resumes** with no
  re-pair/rediscover; on expiry `onRelease` tears the warm link down. This is why a brief IP
  blip no longer costs the BLE connection. The window is two minutes rather than ten seconds
  because the binding case is not a network blip but a **process-per-command client** — a CLI, a
  script, or a coding agent — whose next command has to resume the same warm link, and the gap
  between two such commands is a human or model thinking. The trade is contention: a peripheral
  stays leased for up to the window after its holder walks away, so a shared rig should lower
  `REMOTE_BLE_TRANSPORT_GRACE_MS`.
- **Resume needs authenticated identity.** Each socket gets a fresh monotonic id; ownership is
  keyed by the verified credential principal plus the stable client id the SDK sends on every
  reconnect (`CLIENT_ID_HEADER`). A stable ID never crosses a principal boundary.
- **0.9 release policy is exclusive-only.** The legacy dashboard/config switch is removed or
  disabled for 0.9.0. Both grace windows remain process config (`REMOTE_BLE_LEASE_GRACE_MS` /
  `REMOTE_BLE_TRANSPORT_GRACE_MS`), surfaced read-only on the dashboard.

The registry is engine-free (pure logic, virtual-time unit tests); the physical disconnect is
an injected `onRelease` callback and `ConnectionWatcher` is the only piece that polls the radio.

## Single agent, multiple clients

v1 is one **agent endpoint** per client session — there is no client-side `AgentRegistry` or
multi-agent fan-out API. Multiple **clients** may share one agent, with authenticated principals and
peripheral ownership keeping them from colliding on the same hardware (above). Transparent
multi-agent aggregation, if a concrete deployment needs it, belongs behind one ordinary endpoint in
the future [AgentProxy](proposals/agent-proxy.md); it remains a non-goal for 0.10.0.

**The claim has a defined scope for scanning, and it did not always.** Peripheral ownership isolates
*connections*; it says nothing about the radio's single scan, and until 0.10.0 the Kotlin agent gave
each `scan.start` its own platform scanner — which on Apple, where one `CBCentralManager` has exactly
one scan, meant two scans silently interfering with no error on either. Concurrent scanning is now a
configured, handshake-advertised agent property rather than whatever the host happens to do, and what
it guarantees is stated rather than implied: agent-side filter correctness and lifecycle isolation,
**not** discovery completeness equal to an isolated Apple scan. See [scanning.md](scanning.md) for the
consumer-facing contract and
[proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) for the record. The
general lesson generalizes past scanning: "multiple clients share one agent" is only true for the
resources the agent actually arbitrates, so a shared resource without an arbiter is a defect waiting
to be found on the platform with the tightest constraint.

## Monorepo: SDK and agent share one repository

The client SDK and the agent(s) live in one repository, even though only `:protocol` and
`:client-sdk` are published to Maven Central and the agent is distributed separately (as
prebuilt binaries — see below). This was reconsidered against splitting the agent into its own
repo and deliberately kept as a monorepo.

**The two are already decoupled at the code level**, so a monorepo costs almost nothing:

- Production `:client-sdk` has **zero** dependency on `:agent`. The only edge is a *test-only*
  one — `client-sdk`'s `jvmTest` wires the client against the agent's `FakeAgent` over an
  in-memory transport (`client-sdk/build.gradle.kts`). Keeping both in one repo makes this
  end-to-end test a trivial `project(":agent")` dependency; across repos it would require
  extracting `FakeAgent` into a published test fixture or moving the test to consume the
  released SDK.
- The real compatibility contract between SDK and agent is **`:protocol`** (the wire format),
  which is *already* an independently published, independently versioned Central artifact. SDK
  and agent are compatible when they agree on the protocol version — they do **not** need to
  share a release version. Version coupling is therefore a release-process choice, not a
  structural requirement.

**Why not split (yet).** Splitting would *add* coordination cost for a solo, pre-1.0 project:
every protocol change would become publish-`:protocol` → bump-in-SDK-repo → bump-in-agent-repo
instead of one atomic commit; it would fork CI, signing secrets, and changelogs; and
`:e2e-runner` straddles both sides. The upside (independent cadence, an SDK-only tree) mostly
pays off once the agent has its own contributors or a distinct release rhythm. The split is
also **cheap to do later** — `:protocol` is the clean seam and `git filter-repo` preserves
history — whereas un-splitting is painful. So the bias is to defer until the pain is real
(revisit post-1.0 or when the agent draws independent contributors).

**The actual consumer pain is distribution, not repo layout.** The complaint "consumers must
clone the whole repo to run an agent" is solved by *shipping the agent as a released artifact*
(JVM fat JAR, `agent-rs` native binaries, on-device APK via GitHub Releases), which removes the
clone-and-build step entirely without touching repo structure. That release workflow and its current
gates are tracked in [`proposals/0.10.0-scope.md`](proposals/0.10.0-scope.md).

## Kable builder options: set only what the platform reads back

`PeripheralBuilder` is a common `expect class`, so every option compiles on every target — but each
platform's factory reads back only the subset it implements and **silently drops the rest**. Setting
an option is therefore not evidence that it applies. Verified against `kable-core:0.43.1` by
disassembling the published artifacts (`javap` on the JVM jar and the Android AAR, `klib
dump-metadata` on the iOS klib) rather than by reading documentation, because this is exactly the
class of assumption that has cost this project real defects.

| Option | Default | Apple | Android | JVM/btleplug |
|---|---|---|---|---|
| `logging` | quiet | ✅ | ✅ | ✅ |
| `onServicesDiscovered` | no-op | ✅ | ✅ | ✅ |
| `observationExceptionHandler` | rethrows | ✅ | ✅ | ❌ replaced by an internal log-only handler |
| `disconnectTimeout` | 5s | ✅ | ✅ | ❌ dropped — no bound at any layer |
| `forceCharacteristicEqualityByUuid` | `false` | ✅ | ❌ hardcoded `false` | ❌ hardcoded `false` |
| `autoConnectIf` / `transport` / `phy` / `threadingStrategy` | direct connect / `Le` / `Le1M` / on-demand | n/a | ✅ | n/a |

What follows from it:

- **`forceCharacteristicEqualityByUuid = true` is set on Apple only**, in
  `agent/…/PeripheralByIdentifier.ios.kt` and `client-sdk/…/KableWorkarounds.ios.kt`. CoreBluetooth
  can hand back a different `CBCharacteristic` instance than the one an operation was issued
  against, so Kable's default reference comparison never matches the completion and the operation
  suspends forever. Android and the JVM ignore the option, and correctly so: their stacks return the
  instances the operation was issued against. Setting it in `commonMain` would read as configuration
  while doing nothing on two of three targets, and would silently change behaviour on both if Kable
  ever wired it up.

  **Scope, measured on hardware (2026-07-30).** This affects reads issued against a
  **`DiscoveredCharacteristic`** taken from `Peripheral.services` — a platform-backed object, which
  is what `EngineBleBackend.findCharacteristic` uses and what the hardware measurement below covers. It
  does **not** affect a lazy `characteristicOf(serviceUuid, characteristicUuid)`, which Kable can
  only resolve by UUID, so reference identity never enters the matching. Both reads were run against
  the same connection with the option off: the discovered read timed out at 15.008 s while the lazy
  read completed 67 ms later. With the option on, both complete in ~60 ms. Earlier notes describing
  this as "every read on iOS timing out" were therefore too broad — an app that only ever reads by
  UUID would never have hit it, which is why the SDK's LOCAL path could carry the defect unnoticed.
- **The agent bounds its own `disconnect()`** (`EngineBleBackend.boundedDisconnect`, at
  `GATT_OP_TIMEOUT`) because the JVM drops `disconnectTimeout` entirely. The bound is deliberately
  looser than Kable's own 5s so that where the platform *does* honour its timeout, its teardown
  still wins.
- **The default `observationExceptionHandler` is kept.** It rethrows, which surfaces at the
  collector of `Peripheral.observe(…)` — and `BleAgent.startObserve` already `.catch`es there,
  ending that one subscription with a logged error rather than the connection. The JVM's log-only
  substitute is the weaker of the two behaviours (the flow stays alive but delivers nothing) and is
  not reachable from the builder, so this is a divergence to know about, not one to configure away.
- **Android's remaining defaults are all the right choice for a relay** and are left alone: direct
  connect rather than auto-connect (a client-driven agent wants a typed failure, not an indefinite
  pending connection), LE transport, 1M PHY, on-demand threading.

`writeWithoutResponseTimeout` **does not exist** in `0.43.1` on any target — a plan item once named
it as an unset default. Re-run the disassembly above on the next Kable bump rather than assuming
this table still holds.

## Known boundaries & extension points

These are deliberate v1 cuts, each a clean extension:

| Boundary | Where | Extension |
|---|---|---|
| Agent emits `DISCONNECTED` only on explicit `Disconnect` (ownership leases *do* track unsolicited drops via `ConnectionWatcher`, but no `AgentEvent` is emitted for them yet) | `BleAgent` | a `BleBackend` connection-state stream feeding spontaneous-drop events |
| ~~Write/notify are best-effort on the real engine~~ — this row was stale: `EngineBleBackend` has used real Kable suspend calls since `d97146f` ("Prepare for open-source release"), not polling. **Read and write-with-response are exact** — they resume on Kable's `onCharacteristicRead`/`onCharacteristicWrite` GATT completion callbacks. **WWR and notify-delivery are best-effort by BLE design, not an implementation gap** — WWR has no ATT-level acknowledgement, and notifications (unlike indications) are unacknowledged by spec, so there's no callback to plumb even in principle. Confirmed by code inspection 0.8.3; live-radio confirmation batched into the next release's hardware round | `EngineBleBackend` | an *acknowledged-notify* (indications) capability would be a real wire feature (not this boundary) — noted as a possible future item |
| ~~`CharNode.properties` populated only on macOS engine~~ — this row was stale: `EngineBleBackend.toNode()` reads Kable's `properties.value` directly in `commonMain` (no `propertiesOf` seam ever existed), and both the JVM/btleplug (`BtleplugCharacteristic`) and Android (`PlatformDiscoveredCharacteristic`) engines already read real native property bits, not a stub — verified 0.8.2 (see `EngineBleBackendJvmTest.toNodePreservesNonZeroPropertyBits`) | `EngineBleBackend` | — |
| ~~Throughput coalescing not implemented~~ — landed in 0.8.3: `writeWithoutResponseBurst` pipelines WWR writes to fill the in-flight window (no wire change), and `BleAgent` chains writes **per device** so a burst reaches the radio's FIFO GATT queue in submission order despite per-command concurrency (a non-reference agent must uphold the same). Guaranteed in code + asserted in CI (`BleAgentTest`); on-radio confirmation batched into the next hardware round (plan §2d/§3) | `RemoteGattClient`, `BleAgent` | a wire batch op (`Op.WriteBatch`) only if the rig shows framing/round-trip still dominates after pipelining |
| `conn.params`/`conn.priority` implemented Android-only; `ConnParamHint` is reserved wire space no engine honors; result is `Ok(null)` (Android reports accept/reject, not the resulting interval) | `ConnParamsSupport.*`, `EngineBleBackend` | iOS/JVM backends with real interval control (none known today — btleplug exposes none); an engine that reports the applied interval could add a `ResultPayload` for it |
| One agent endpoint / one session | `DefaultAgentSession` | a future transparent [AgentProxy](proposals/agent-proxy.md) behind the endpoint, with no Kable/client API change |
| ~~Agent is JVM-only~~ — done: `:agent` now also targets Android/iOS (Kable's native Android BLE / CoreBluetooth backends, Ktor's native-target CIO server); see [agent.md](agent.md#android--ios-a-phone-as-the-agent). Remaining cut: iOS can't run the agent backgrounded (no listening TCP server while backgrounded on iOS) | `agent/build.gradle.kts` | a background-capable iOS transport would need a fundamentally different delivery mechanism (push-triggered wake, not a held-open server socket) |

## Pinned versions (and why pinned)

| | Version | Note |
|---|---|---|
| Kotlin | 2.4.0 | matches the Kable checkout this project builds against |
| kotlinx-coroutines | 1.11.0 | |
| kotlinx-serialization (+cbor) | 1.9.0 | wire codec |
| Ktor | 3.5.0 | client (transport) + server (agent) |
| Gradle | 9.5.1 | |
| AGP | 9.2.1 | matches Kable so consumed klibs line up; compileSdk 37, minSdk 24 |
| JDK toolchain | 17 | |

Kable is a plain Maven Central dependency, `com.juul.kable:kable-core:0.43.1`; see
[build-and-testing.md](build-and-testing.md#kable). It powers both the client SDK and the JVM
agent's radio engine (`btleplug`) — `0.43.1` is the first release to ship that JVM backend
([kable#901](https://github.com/JuulLabs/kable/pull/901)).
