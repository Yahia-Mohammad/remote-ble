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
- Anything Kable's interface exposes that the wire doesn't yet model
  (connected RSSI) must still *type-check*, so it throws
  `UnsupportedOperationException` rather than not existing. (Descriptor I/O *was* such a
  gap; it is now modelled behind the `descriptors` capability.)

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

## Concurrency model

- **Client session.** A single `AtomicLong` mints correlation ids. A `pending` map of
  `cid → CompletableDeferred` (under a `Mutex`) matches replies to requests. One
  decode coroutine, one transport-state coroutine. Reconcile runs on its own launched
  coroutine so it can't block the state watcher. Pending-slot cleanup uses
  `NonCancellable` so a cancelled caller still tidies up.
- **Agent.** One coroutine per command (`scope.launch { handle(cmd) }`) so a slow read
  can't head-of-line-block an `observe.stop`. All per-session maps/sets (connected slots,
  scan jobs, observe jobs) are guarded by one `Mutex`. A connection slot is reserved
  *before* the slow native connect so the cap holds under concurrency, and released on
  failure.
- **Cross-client ownership.** The per-session `BleAgent`s share one `PeripheralRegistry`
  (the radio is shared, so ownership must be too). `connect` acquires the lease *before*
  the per-session slot, so a peripheral another client owns is rejected with
  `PERIPHERAL_BUSY` (see *Peripheral ownership*).
- **Streams.** Both sides express scans/subscriptions as cold `Flow`s opened on
  collect and torn down on cancel (`channelFlow` + `awaitClose`), so lifecycle is tied
  to collection — no manual bookkeeping leaks.
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
write-without-response bursts is intentionally deferred to hardware bring-up (it can't
be meaningfully validated without a real radio).

## Auth is a hook, not a framework

A single bearer token: the client sends `Authorization: Bearer <token>` at transport
construction; the server enforces it at the WebSocket handshake (`401` before the
upgrade completes, so the client never reaches CONNECTED). Rejecting at the handshake
— rather than accepting then closing — avoids a CONNECTED→DISCONNECTED flap. The SDK
deliberately owns no identity system beyond the shared token; richer auth is the
embedder's concern.

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
radio. So the agent leases each peripheral to **one** client at a time (the default;
switchable per peripheral). The shared `PeripheralRegistry` is the authority:

The model is **uniform**: anything that means "the owner is temporarily gone" schedules a
per-lease release timer; anything that means "the owner is back" cancels it. The cause only
sets the delay and whether the radio link is kept warm.

- **Acquire on connect.** A second client's `connect` to an owned peripheral is rejected
  with `PERIPHERAL_BUSY` before any radio call. Re-connecting as the owner is idempotent.
- **BLE disconnect → `leaseGrace` (10s).** An explicit `Disconnect` *or* an unsolicited drop
  (caught by `ConnectionWatcher` polling `connectionState`) schedules release; the link is
  already down, so this just frees ownership after the window. Debounces flaps.
- **Transport drop → `transportGrace` (10s), link kept warm.** When a client's WebSocket
  drops, `BleAgent`'s job-completion teardown hands off to the registry, which leaves the
  radio link **up** and schedules release. A reconnect within the window **resumes** with no
  re-pair/rediscover; on expiry `onRelease` tears the warm link down. This is why a brief IP
  blip no longer costs the BLE connection.
- **Resume needs identity.** Each socket gets a fresh monotonic id, so ownership is instead
  keyed by a **stable client id** the SDK generates once and sends on every (re)connect
  (`CLIENT_ID_HEADER`). A returning client matches its own leases; a client that sends none
  falls back to its connection id and simply never resumes. It identifies, not authenticates
  (that is the separate bearer token).
- **The switch is operator-side.** Exclusivity defaults to block and is toggled per
  peripheral from the dashboard (`POST /api/peripheral/exclusive`) or globally via
  `REMOTE_BLE_EXCLUSIVE`. Both grace windows are process config (`REMOTE_BLE_LEASE_GRACE_MS` /
  `REMOTE_BLE_TRANSPORT_GRACE_MS`), surfaced read-only on the dashboard. Clients cannot open a
  peripheral to barge in.

The registry is engine-free (pure logic, virtual-time unit tests); the physical disconnect is
an injected `onRelease` callback and `ConnectionWatcher` is the only piece that polls the radio.

## Single agent, multiple clients

v1 is one **agent** per deployment — there is no `AgentRegistry`, no multi-agent fan-out, and
no *auth* identity beyond the bearer token (the handshake client id is for resume, not access).
Multiple **clients** may share one agent, but peripheral ownership keeps them from colliding on
the same hardware (above). Multiplexing many agents would live above this layer and is a
non-goal for v1.

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
clone-and-build step entirely without touching repo structure. That release workflow is tracked
as remaining work in `ai-context/RELEASE_PLAN.md`.

## Known boundaries & extension points

These are deliberate v1 cuts, each a clean extension:

| Boundary | Where | Extension |
|---|---|---|
| No connected RSSI (descriptor read/write now modelled behind the `descriptors` capability) | `RemotePeripheral.rssi()` throws `UnsupportedOperation` | add `Op.Rssi` + payload |
| Agent emits `DISCONNECTED` only on explicit `Disconnect` (ownership leases *do* track unsolicited drops via `ConnectionWatcher`, but no `AgentEvent` is emitted for them yet) | `BleAgent` | a `BleBackend` connection-state stream feeding spontaneous-drop events |
| Write/notify are best-effort on the real engine | `EngineBleBackend` | engines with completion callbacks can be exact |
| `CharNode.properties` populated only on macOS engine | `EngineBleBackend.propertiesOf` | other engines exposing property bits |
| Throughput coalescing not implemented | — | batch write-without-response bursts (needs hardware) |
| One agent / one session | `DefaultAgentSession` | an `AgentRegistry` above the session |
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
