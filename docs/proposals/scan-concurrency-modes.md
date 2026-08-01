# Enhancement: scan concurrency modes (`multiplexed` / `single` / `uncontrolled`)

[← back to index](../README.md)

- **Status:** Production implementation, deterministic boundaries, and paired WebSocket adapters
  are complete locally on `codex/scan-concurrency-modes`; Rig B evidence pending — **release
  blocker for 0.10.0 until hardware validation completes**
- **Type:** Agent configuration + capability-gated protocol extension
- **Fixes:** gap 21 in [0.10.0-progress-status.md](0.10.0-progress-status.md) — concurrent scans
  through one agent interfere on Apple hosts
- **Relates to:** [agent-conformance-spec.md](../agent-conformance-spec.md) §5.3, §7, §9;
  [agent-tunable-configuration.md](agent-tunable-configuration.md) (category A);
  [pr8-validation-plan.md](../pr8-validation-plan.md) Rig A case 3, Rig B

---

## Summary

Two concurrent scans through one agent are not isolated on Apple hosts, and the failure is silent.
This proposal makes scan concurrency an **explicit, configured, wire-visible property of the agent**
rather than whatever the host platform happens to do, with three modes:

| Mode | Behaviour | Isolation guaranteed |
|---|---|---|
| `multiplexed` (**default**) | One physical scan serves every logical scan; the agent filters per subscriber on the way out | filter correctness + lifecycle, **not** discovery completeness on Apple |
| `single` | One logical scan agent-wide; a second key is refused with a typed, transient error | yes (by refusal) |
| `uncontrolled` | Today's Kotlin behaviour — each scan goes straight to the platform | **no** — host-dependent |

The client learns the mode from the handshake, so "which host is this agent running on" never becomes
something an app has to know.

**This is also a parity fix, not only a defect fix.** `agent-rs` already shares one physical scan
and filters agent-side; the Kotlin agent is `uncontrolled`. The complete contract still requires
work on both agents. See [the parity finding](#the-parity-finding-agent-rs-already-multiplexes).

## The defect this fixes

Full write-up in gap 21; the short form:

`MAX_ACTIVE_SCANS = 16` is enforced **per client**
([`BleAgent.kt:500`](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt:500)),
so this is reachable by **one ordinary app** holding two `RemoteScanner`s — one filtered for
heart-rate monitors, one for thermometers. Both drive Kable `Scanner`s inside the agent, and on
Apple every Kable `Scanner` shares the process-wide `CentralManager.Default`. A `CBCentralManager`
has exactly one scan.

Two directions of breakage:

- **stop** — `CentralManager.stopScan()` takes no arguments, so B's stop plausibly ends A's scan.
  Inferred from the API shape; not yet measured.
- **start** — Apple **documents** that when a central is already scanning and receives another set
  of scan parameters, the new parameters override the previous set
  ([`scanForPeripherals(withServices:options:)`](https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/scanforperipherals(withservices:options:))).
  So B starting a filtered scan silently narrows what A sees. This is **documented platform
  behaviour**, not inference.

Neither direction produces an error on either scan. A consumer debugs their own code first.

**Our own layer is not the problem.** `BleAgent` is per-connection, `scanJobs` is per-instance,
`stopScan` only cancels that client's own entry, and `EngineBleBackend.scan` builds a fresh Kable
`Scanner` per call. This is purely a question of what sits between the agent and the radio.

**macOS does not escape it.** macOS is materially more permissive than iOS about background
execution, unfiltered scanning and duplicate delivery — but none of those differences touch the
one-scan-per-`CBCentralManager` constraint, so a macOS-hosted agent is exposed to the same defect and
is not a mitigation.

### The parity finding: `agent-rs` already multiplexes

The pre-existing Rust backend already reference-counted backend subscriptions in an agent-wide
`active_scans: HashMap<StreamKey, ScanSubscription>` and ran one unfiltered adapter scan. In the
guaranteed modes, its reserved coordinator subscription now receives **raw** advertisements; bounded
identity merge, replay, matching, logical mailboxes, and fair connection arbitration are owned by
`ScanCoordinator`. The legacy direct path retains its independently bounded backend coalescer for
`uncontrolled` mode.

The physical-scan sharing was therefore already shipping on one reference agent. The guaranteed-mode
coordinator completes the stable-key replay, bounded merge/cache, per-logical-scan mailboxes, and fair
outbound arbitration that the old implementation lacked. What follows from it:

- **The reference agents diverge today.** Neither the conformance suite nor
  [agent-parity-verification.md](../agent-parity-verification.md) caught it — a gap this work closes.
- **Rust-side effort is smaller than a from-scratch multiplexer**, but remains material: mode
  configuration, capability advertisement, `single`, stable-key replay, the replay cache,
  per-logical-scan mailboxes, and fair outbound arbitration.
- **Whether btleplug tolerates two adapter scans is moot** — `agent-rs` never asks for two. That
  question is closed by inspection, not by a rig.
- **It settles unfiltered-vs-union empirically**: the shipping Rust agent scans unfiltered and filters
  agent-side. Narrowing to a service union stays a permitted optimization (`MAY`), not the reference
  behaviour.

## Decisions taken (2026-07-30)

1. **Scan concurrency is a configured agent mode**, one of the three above, defaulting to
   `multiplexed` on every host.
2. **The iOS agent supports the foreground only.** Backgrounded behaviour is documented as observed,
   never as contract, and no design accommodates it.
3. **This is normative.** It goes in the conformance spec, and the Kotlin and Rust reference agents
   must both implement all three modes.

### Why the default is uniform rather than per-platform

Per-host defaults would fix the radio bug and reintroduce the actual problem: an Android client would
still have to know that its agent is an iPhone. Where a host does not need multiplexing, the
coordinator is a cheap pass-through.

### Why `uncontrolled` exists at all

It is the escape hatch for an operator who knows their deployment has exactly one scanning client and
wants the platform's own path (controller-offloaded filtering, no agent-side matching). It is
**explicitly non-conformant** with the isolation guarantee, and is advertised explicitly so a client
can distinguish it from an old agent.

## Filter semantics (normative — previously mis-filed as an open question)

Already documented at [agent.md:433](../agent.md:433) and implemented by `agent-rs`
([`scan_matches`](../../agent-rs/src/ble/btleplug_impl.rs:1003)). Making the agent the filtering
authority requires it in the spec:

```text
matches(filters, advertisement) =
    filters is empty
    OR any filter where
        (filter.name is null OR filter.name == advertisement.name)
        AND
        (filter.service is null OR canonical(filter.service) ∈ canonical(advertisement.serviceUuids))
```

So: OR across list entries, AND within one entry, an empty list and an empty `ScanFilter()` both
match all, exact name comparison, canonical UUID comparison.

**Two defects to fix before lifting a shared matcher**, both in `SimulatedBleBackend`:

1. [`matches`](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulatedBleBackend.kt:178)
   uses `filters.all { … }` — AND across the list, contradicting the documented and Rust-implemented
   OR.
2. [`scan`](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulatedBleBackend.kt:52)
   filters on profile service UUIDs but emits an `AdvertisementDto` carrying only
   `device`/`name`/`rssi`. Any agent-side re-filter by service rejects everything it emits.

Multi-predicate tests are required: two logical scans with one predicate each do not exercise list
semantics.

## The mode contract (proposed spec text)

An agent MUST run in exactly one scan-concurrency mode for its lifetime and MUST advertise it per
[§5.3](../agent-conformance-spec.md).

Throughout: a **logical scan** is one client's `scan.start`, keyed by `(clientKey, scanId)`; a
**physical scan** is the platform scan the agent runs underneath. `clientKey` is the registry's
stable ownership identity when the client supplies one. When it defaults to the socket id, reconnect
continuity degrades to connection lifetime exactly as it already does for leases; scans do not
invent a second identity model.

In `multiplexed` and `single`, `MAX_ACTIVE_SCANS` is enforced per `clientKey` by agent-lifetime
state. Active and detached/grace-held logical scan keys count toward it; replacing or rebinding the
same key does not.

### `multiplexed`

**The guarantee is filter correctness and lifecycle isolation.** It is deliberately *not* discovery
completeness equal to an isolated radio scan — see the Apple limitation below.

- Every advertisement delivered to a logical scan MUST satisfy that scan's filters.
- Starting or stopping one logical scan MUST NOT cancel, complete, or alter the filter definition of
  another logical scan.
- Reissuing an active key MUST atomically replace that logical scan (§7's replay-safe requirement),
  without releasing its admission between definitions.
- A logical scan joining a running physical scan MUST receive the merged advertisements observed
  within the replay window (below), without waiting for a device to re-advertise.
- Delivery is **best-effort**: the contract does not promise lossless radio delivery, nor an
  identical temporal event sequence across physical reconfiguration. Isolation is about logical
  eligibility and lifecycle, not about reproducing an independent radio scan packet-for-packet.

An agent MAY narrow the physical scan to the union of service UUIDs when every active predicate is
**service-coverable** (has a non-null service constraint — a predicate carrying both a service and a
name is service-coverable, the service being a valid native prefilter with the name applied
agent-side). It MUST fall back to an unfiltered physical scan otherwise, and MAY keep a superset of
service UUIDs until the last logical scan stops rather than renarrowing on each stop.

> **Why the union is optional rather than required.** Writing "always scan unfiltered" into the spec
> would import restrictions from one platform to fix another's — Android blocks unfiltered scans while
> the screen is off (8.1+) and loses controller-offloaded filtering. Writing "always narrow" would
> make `agent-rs`, which scans unfiltered today, non-conformant for no behavioural gain. The spec
> fixes the observable contract; narrowing is an implementation freedom.

**Accepted residual limitation on Apple hosts (pending Phase 0 verification).** A peripheral
advertising service UUIDs in Apple's overflow area is discoverable only by a scan naming that UUID.
When a logical scan requiring an unfiltered physical scan (name-only or empty filters) coexists with
a service-filtered one, the unfiltered physical scan can therefore return *fewer* devices to the
service-filtered subscriber than an isolated scan would.

This is accepted and documented rather than refused — see
[rejected alternatives](#rejected-alternatives). Two consequences must be stated plainly rather than
glossed:

- **Gap 21's start direction is not completely eliminated by `multiplexed` on Apple.** It is reduced
  from "B replaces A's filter, so A sees only B's service" to "A may miss overflow-advertised
  peripherals while a broad scan is live." Do not describe the defect as fully fixed.
- **Discovery completeness is therefore host-dependent**, while filter correctness and lifecycle
  isolation are not. Only the latter two are contract.

Phase 0's overflow-area result gates the final scope statement, and this case needs its **own
hardware check with an iOS-background peripheral** — the ordinary staggered Rig B run will not
exercise it and would pass without touching the residual case.

A future strict fourth mode, refusing a join that mixes filter classes rather than accepting
incomplete discovery, is a reasonable deployment option for operators who prefer refusal. It is
deliberately *not* the default, and it is out of scope for 0.10.0.

### `single`

- At most one logical scan **key** is admitted agent-wide, across all clients.
- A `scan.start` for a **different** key MUST be refused without disturbing the incumbent.
- A `scan.start` for the **incumbent** key MUST atomically replace it — this is the §7 exception, and
  omitting it would break session reconciliation. Because the key is owned by the stable `clientKey`
  (above), a client replaying its scan after a reconnect replaces its own scan rather than contending
  with it.
- The slot is released by explicit `scan.stop`, or by grace expiry after transport loss.
- `MAX_ACTIVE_SCANS` remains a per-client cap underneath the agent-wide limit, but "client" means
  stable `clientKey`, not the current transport connection. Active and detached/grace-held keys both
  count; a same-key replacement or rebind does not consume a new slot.

Note this makes scanning a **contended global resource**: one client can hold the scanner
indefinitely and deny every other client. That is the honest cost of the mode, and the reason the
error is transient rather than permanent. It also follows that the slot stays held for the transport
grace after a drop — the same trade leases already make, chosen for consistency: a brief blip must not
let another client steal the scanner out from under a reconnecting owner.

### `uncontrolled`

Each logical scan follows the backend's existing independent path, including its connection-local
cap admission. The agent makes no cross-scan physical isolation guarantee, MUST NOT default to this
mode, and MUST advertise `scan.concurrency.uncontrolled` so a client can distinguish a deliberate
choice from an old agent.

## Wire surface

### Capability strings (in `Capabilities.kt` and the Rust vocabulary)

```
scan.concurrency.multiplexed
scan.concurrency.single
scan.concurrency.uncontrolled
```

An agent's supported set contains **exactly one**. A client SHOULD offer all three, so the
intersection returns exactly the configured mode; the reference client offers all three
automatically, including manually constructed `DefaultAgentSession`s. Absence of all three means
**legacy or unknown**, and a client MUST NOT read it as `uncontrolled`.

**Why capability strings and not a `server_hello` field.** [§5.1](../agent-conformance-spec.md)
requires that producers not emit fields outside the spec on a `1.x` link, and permits a decoder to
reject a frame it cannot understand — a new field would be a breaking change for existing clients.
Unknown *capability strings* are documented as harmless and are the spec's designated
forward-compatibility mechanism.

### Error kind

```kotlin
SCAN_UNAVAILABLE(transient = true)   // the agent-wide single-mode scan slot is held by another key
```

**`single` is its only source.** An earlier draft also had `multiplexed` return it for an
"incompatible physical plan", but the only incompatibility that ever existed was the mixed
filter-class case, which is accepted rather than refused — so nothing could reach that branch. A
backend or radio failure is not an admission decision either: those keep their existing `RADIO_OFF` /
`GATT_ERROR` mappings. If the future strict fourth mode lands, it becomes this kind's second source.

**It must be capability-gated.** `ErrorKind` is a plain `@Serializable` enum that serializes by name,
and [`Capabilities.kt:74`](../../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Capabilities.kt:74)
records the consequence: an unknown enum name fails a v1 client's decode. This is why `RADIO_OFF` is
gated behind `radio.state`, and the same applies here — a client that negotiated no scan-concurrency
capability receives the existing `AGENT_BUSY` instead.

### Client-side interpretation

```kotlin
enum class ScanConcurrencyMode { MULTIPLEXED, SINGLE, UNCONTROLLED, LEGACY_OR_UNKNOWN }
```

Derived from `AgentSession.capabilities`, alongside the existing `supportsCapability` shorthand.

### Compatibility matrix

| Client | Agent | Negotiated | Contention error |
|---|---|---|---|
| new (offers all three) | new `multiplexed` | `multiplexed` | none from this layer |
| new (offers all three) | new `single` | `single` | `SCAN_UNAVAILABLE` |
| new (offers all three) | new `uncontrolled` | `uncontrolled` | none from this layer |
| new | old | none → `LEGACY_OR_UNKNOWN` | legacy behaviour |
| old | new `single`/`multiplexed` | none | `AGENT_BUSY` |

## Implementation shape

### `ScanCoordinator`, not a backend decorator

Admission must be **synchronous, inside the `scan.start` command path, before `Ok` is replied**. A
decorator around `BleBackend.scan(): Flow` cannot do that: the flow is cold, `BleAgent.startScan`
collects it in a child job, and a failure raised during collection is logged as an ended scan rather
than becoming the initiating command's `OpResult.Err`. A shared backend decorator also has no access
to the connection's negotiated capability set, so it cannot choose between `SCAN_UNAVAILABLE` and
`AGENT_BUSY`.

The seam already exists:
[`BleAgent.startScan`](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt:493)
performs mutex-guarded admission for today's connection-local cap, starts the job `LAZY`, and
implements same-key replacement via `previous?.cancel()`. That location remains the command-path
seam, but cap ownership moves into the agent-lifetime coordinator so reconnecting cannot acquire a
fresh allowance while its detached scans remain in grace. The coordinator is injected into every
per-connection `BleAgent` and called from that same place:

```kotlin
// Ownership is the STABLE client identity — the same one the registry keys leases, transport-drop
// grace and resume on (BleAgent's `clientKey`). A replayed scanId after a reconnect is therefore the
// same logical scan, not a contender.
data class LogicalScanKey(val clientKey: String, val scanId: Long)

// The generation is a FENCING TOKEN, not part of identity: it decides whether a late-arriving
// mutation still owns the resource it is trying to mutate.
data class ScanRegistration(val key: LogicalScanKey, val connectionGeneration: Long)

sealed interface ScanAdmission {
    data class Accepted(val registration: ScanRegistration, val handle: ScanHandle) : ScanAdmission
    data class Refused(val reason: RefusalReason) : ScanAdmission
}

interface ScanCoordinator {
    suspend fun startOrReplace(
        key: LogicalScanKey,
        generation: Long,
        filters: List<ScanFilter>,
    ): ScanAdmission

    /** No-ops unless [registration] is still the current one for its key. */
    suspend fun stop(registration: ScanRegistration)
    suspend fun detachConnection(generation: Long)
}
```

The coordinator returns a domain refusal and knows nothing about wire negotiation; `BleAgent`, which
owns the negotiated set, maps it to `SCAN_UNAVAILABLE` or `AGENT_BUSY`.

**Why both a stable key and a generation.** The key makes reconnect-replay a same-key replacement
instead of self-contention. It does **not** fence delayed asynchronous work: a stop, a grace-expiry
timer, or an in-flight command from the *previous* generation can still arrive after the resumed
scan has rebound, and would otherwise tear down its replacement. `LEASE-DUPLICATE-01` prevents two
live sockets for one client key; it does not order these. The registry already solves the analogous
problem the same way — `Lease(owner, connected, graceJob)` with cancellable grace jobs and
lease-object identity
([`PeripheralRegistry.kt:64`](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/PeripheralRegistry.kt:64)) —
so scans should follow it rather than invent a second scheme.

Lifecycle rules, all generation-guarded:

- a start for an existing key **atomically rebinds** it to the new generation and filter set;
- a stop or teardown whose registration is no longer current is a **no-op**;
- transport drop **detaches the old delivery sink** and starts grace; the logical scan survives;
- a replay **cancels grace** and rebinds the stream to the new sink;
- grace expiry removes the scan **only if** its registration token is still current.

The coordinator also owns `MAX_ACTIVE_SCANS` admission. It counts distinct live
`LogicalScanKey`s per `clientKey`, including registrations detached during transport grace.
`startOrReplace` for an existing key is cap-neutral; a new key is refused when that stable client's
count is already at the cap. This prevents a client from dropping and reconnecting repeatedly to
accumulate more than the limit. When no ownership id was supplied and `clientKey` is the socket id,
the behaviour intentionally inherits the same reconnect limitation as lease ownership.

Admission, replacement, stop, and teardown serialize through one mutex or actor:

1. validate and canonicalise filters;
2. classify the key as new, or a rebind of an existing one;
3. compute the candidate physical plan;
4. reconfigure the physical scan if required;
5. commit the logical registration and return the stream handle.

**Commit is on launch, not on confirmed physical start.** An earlier draft promised commit-only-after-
successful-reconfiguration with rollback, which Kotlin cannot honour: `BleBackend.scan()` is a cold
flow whose failures surface during collection, so there is no synchronous start-success signal to
commit against. `agent-rs` *can* — btleplug's `adapter.start_scan()` returns a `Result` and the Rust
agent already removes its registration when it fails — so the seam stays `Result`-shaped for backends
that can answer it:

```kotlin
interface PhysicalScanController {
    suspend fun replace(filters: List<ScanFilter>): Result<PhysicalScan>
    suspend fun stop()
}
```

An agent MAY report synchronous physical-start failure where its backend exposes one, and MUST
otherwise treat physical failure as **asynchronous stream failure**. Either way it MUST NOT leak
logical registrations. A synchronous failure does not commit the candidate and leaves pre-existing
registrations intact. An asynchronous failure after commit retains the owned logical definitions in
a restartable coordinator state; it never leaves registrations pointing at an orphaned collector.

A failed rebind leaves the previous definition running.

### Identity aggregation moves ahead of matching

The Kotlin agent coalesces name and service UUIDs *after* the backend, per scan
([BleAgent.kt:534](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt:534)),
because repeated packets from one device routinely carry only an RSSI refresh. Filtering below that
point would discard a sparse update for a service-filtered subscriber even after an earlier packet
established that device's service UUID. The pipeline must be:

```text
physical advertisement
  → canonicalise and merge known name/service UUIDs per device
  → update the bounded replay cache
  → evaluate each logical scan's filters
  → fan out through independent bounded mailboxes
```

`agent-rs` already does exactly this (`coalesce_identity` before fan-out), so this is convergence,
not invention. The per-`BleAgent` coalescer moves into the coordinator so there is one authority.

### Bounded replay

A late joiner cannot rely on a device re-advertising, so the coordinator replays the latest merged
advertisement per device — but the requirement must be bounded, or it implies unbounded memory and
replaying devices that are long gone:

**Define the retained set first, then require replay of all of it** — otherwise a MUST over "every
device seen in the window" contradicts the cap the moment `cap + 1` devices appear:

> The cache retains at most `SCAN_REPLAY_CACHE_CAP` devices whose latest observation falls within
> `SCAN_REPLAY_WINDOW`, keeping the most recently observed and evicting oldest-observation-first when
> the cap is exceeded. A joining logical scan MUST receive every **retained** entry its filters match.

Reference agents use a 30 s window and retain at most 256 devices. Every pre-arbitration logical
mailbox has 320 entries: the complete 256-entry replay reservation plus 64 entries of steady-state
headroom. Where the platform permits it, enable duplicate discovery so entries refresh; where it does
not, document that the cache reflects recent platform callbacks rather than asserting current presence.

### Fan-out and backpressure

Each **logical scan** gets its own bounded mailbox: a slow or high-volume scan must not suspend the
physical scanner or consume another logical scan's mailbox capacity. A per-connection arbiter MUST
drain non-empty scan mailboxes fairly (round-robin is sufficient) into the connection's shared
outbound event queue, so one logical scan cannot monopolize scan admission to that queue.

This isolation ends at that shared outbound queue. Scan events still share the final best-effort
transport with observations, connection events, and other protocol traffic; the proposal does not
promise a separate end-to-end transport budget for every scan. Backpressure or transport loss may
drop events after fair arbitration without violating filter or lifecycle correctness.

**This is new work on both agents, and an earlier draft of this document got `agent-rs` wrong.** The
Rust agent creates **one** `event_tx` per WebSocket connection
([server.rs:674](../../agent-rs/src/transport/server.rs:674)) and clones it into every op, so all
scans, observations and connection events on a connection share a single `EVENT_CHANNEL_CAP` budget.
Its `try_send` also drops the **event being sent** when the channel is full, not the oldest queued
one — so neither "per-subscriber mailbox" nor "drop-oldest" described the shipping behaviour.

Two defensible resolutions; this proposal takes the first:

1. **Per-logical-scan mailboxes with drop-newest**, matching tokio's `try_send` semantics, plus a
   fair per-connection arbiter in both agents. Preserves mailbox isolation and prevents one scan
   from monopolizing scan admission to the shared outbound queue.
2. Keep per-connection budgets and weaken the claim to "one *connection* cannot starve another".
   Cheaper, but a client's own two scanners still starve each other.

Either way, "exactly those advertisements" means filter correctness, not lossless delivery. An
overload test must prove one high-volume logical scan cannot consume another's mailbox capacity or
monopolize the arbiter while both mailboxes are non-empty.

### Radio-state interaction

The radio-off check stays a per-connection, capability-gated check *before* coordinator admission. If
the radio dies after a physical scan starts, the physical collector may end, but logical registrations
MUST NOT leak, and a later start or replacement must have a defined path to re-establish the physical
scan.

### Configuration

Following the `REMOTE_BLE_WRITE_FAIL_FAST` precedent in
[agent-tunable-configuration.md](agent-tunable-configuration.md): an `AgentConfig.scanConcurrency`
field, `REMOTE_BLE_SCAN_CONCURRENCY=multiplexed|single|uncontrolled`, `--scan-concurrency` on
`agent-rs`, strict parsing that fails startup on an unknown value, and a startup log line naming the
running mode. Phone agents read neither CLI nor environment, so they get the default — which is the
safe one, which is the point of choosing it.

## Open questions (settle in Phase 0)

1. **Apple overflow-area advertisements** — confirm that a peripheral advertising from the background
   is discoverable only by a scan naming its service UUID. Does not gate the *design* (the response is
   documentation, not a contract change), but it **gates the final scope statement**: how much of gap
   21's start direction `multiplexed` actually closes on Apple. Needs its own hardware case with an
   iOS-background peripheral.
2. **Does Kable expose duplicate delivery on Apple?** Determines whether replay entries refresh, and
   therefore the wording of the cache's meaning — not whether the cache is needed.
3. **Replay window, cache cap, mailbox depth** — settled: 30 s, 256 retained devices, and a 320-entry
   replay-capable mailbox (256 replay + 64 steady-state headroom).
4. **Mailbox policy** — settled: per-logical-scan mailboxes with drop-newest and fair round-robin
   arbitration (option 1 above), on both agents, rather than `agent-rs`'s former per-connection
   budget.

*Closed since the first draft:* filter AND/OR semantics (documented and Rust-implemented; see above),
and whether btleplug multiplexes (moot — `agent-rs` never opens a second adapter scan).

## Plan

**Phase 0 — facts and spec amendment (no production code).** Two staggered `:e2e-runner:scanRun`
clients against the iOS agent, then the JVM agent, per gap 21's procedure — this is the reproduction
the conformance scenarios cite. Settle the four open questions. Amend the spec text before
implementing against it.

**Phase 1 — protocol and client surface.** Three capability strings and `SCAN_UNAVAILABLE` in both
Kotlin and Rust; codec/interop fixtures; the reference client offers all three strings; the
`ScanConcurrencyMode` interpretation API; tests for negotiated-vs-legacy error gating. No agent
advertises a mode until its coordinator implements it.

**Phase 2 — coordinator and `single` (Kotlin).** `LogicalScanKey` + `ScanRegistration` fencing,
atomic rebind, stable-client `MAX_ACTIVE_SCANS` admission including grace-held keys,
grace-guarded teardown, `AgentConfig.scanConcurrency` + env var + startup log. Also the
`NonCancellable` cleanup around the connection's collect — resource hygiene, since fencing is what
makes rebinding correct. `single` first: it proves admission, rebind, and teardown without any
data-path change. Deterministic tests, no radio.

**Phase 3 — `multiplexed` data path (Kotlin).** Physical plan computation and monotonic service
unions; identity aggregation moved ahead of matching; bounded replay; per-logical-scan mailboxes and
fair per-connection arbitration; the shared matcher, with `SimulatedBleBackend`'s two defects fixed
first. Advertise `multiplexed` only once the whole path is live.

**Phase 4 — `agent-rs`.** Mode configuration, explicit capability advertisement, `single` admission,
the replay cache, and — larger than an earlier draft of this document claimed — **per-logical-scan
mailboxes plus fair outbound arbitration**, since the shipping agent shares one per-connection event
channel. Re-key admission from `StreamKey { connection, local_id }` to the stable-key + generation
model, and enforce the per-stable-client cap in that agent-lifetime state, so replay-after-reconnect
behaves identically on both agents. Align canonicalisation, window, cap, and rebind semantics with
Kotlin.

**Phase 5 — conformance and hardware.** Paired adapters for the parity scenarios; re-run Phase 0's
procedure against the multiplexed agent on Rig B; update
[design-decisions.md](../design-decisions.md) (the "single agent, multiple clients" claim now has a
defined scope), [agent.md](../agent.md), [protocol.md](../protocol.md),
[agent-parity-verification.md](../agent-parity-verification.md), and the CHANGELOG.

### Conformance scenarios

Paired Kotlin/Rust adapters — these are the parity-bearing cases:

| ID | Mode | Scenario | Required outcome |
|---|---|---|---|
| `SCAN-CONC-01` | multiplexed | Two service-coverable scans, different services | Each receives only its own matches. |
| `SCAN-CONC-02` | multiplexed | One of two scans stops | Survivor keeps receiving; the physical scan is neither stopped nor narrowed. |
| `SCAN-CONC-03` | multiplexed | Late join within the replay window | Matching merged devices arrive immediately. |
| `SCAN-CONC-04` | multiplexed | Sparse identity then RSSI-only update | Matching uses merged identity; the update reaches the right subscribers. |
| `SCAN-CONC-05` | single | Distinct second key | Typed refusal; incumbent unaffected. |
| `SCAN-CONC-06` | single | Reissue of the incumbent key | Atomic replacement, no false contention. |
| `SCAN-CONC-07` | all | Client offers all three strings | `server_hello` returns exactly the configured mode. |
| `SCAN-CONC-08` | all | Legacy client offers none | No new enum on the wire; `AGENT_BUSY` where applicable. |
| `SCAN-CONC-09` | multiplexed/single | Connection drops with active scans | Scans are detached and enter grace; other clients survive; grace expiry releases them. |
| `SCAN-CONC-10` | single | Drop with an active scan, immediate reconnect, replay the same `scanId` | **First-attempt success** — a rebind, not contention, with no transient-retry round trip. |
| `SCAN-CONC-11` | multiplexed/single | Stale cleanup after rebind: old generation's stop/grace-expiry fires after the resumed scan rebound | The stale registration is a no-op; the resumed scan survives. |
| `SCAN-CONC-12` | multiplexed/single | Two `scan.start`s for one `scanId` pipelined without awaiting the first reply | Both reply `Ok`; the **second** definition is the live one. Same-`scanId` lifecycle follows receive order. |

Agent-local tests (simulated backend or unit, not paired adapters — the behaviour is not
parity-bearing and doubling the adapter cost buys nothing):

- late join **after** replay expiry — no stale replay required;
- replay cache overflow — observe `cap + 1` devices, verify deterministic oldest-first eviction and
  that every retained matching entry is replayed;
- multi-entry filter lists — OR across entries, AND within one;
- empty list and empty `ScanFilter()` — both match all;
- physical-start failure — no logical registration is leaked or orphaned;
- stable-client cap across reconnect — fill the cap, detach into grace, reconnect with the same
  ownership id, and verify new keys are refused while same-key rebind succeeds;
- mailbox/fairness isolation — one high-volume logical scan cannot consume another's mailbox
  capacity or monopolize the outbound arbiter while both have queued events;
- `uncontrolled` — two scans take the existing backend path, capability advertised, no isolation
  asserted;
- a race harness starting two scans simultaneously, repeatedly, proving `single` admission is
  linearizable and never admits two winners.

## Implementation checkpoint and handoff (2026-07-30)

Work is on `codex/scan-concurrency-modes`, based on `main` at `b1ffe6a`. The implementation
landed in the branch history through `aa887d5` (`docs: record scan concurrency implementation`).
There is also an **uncommitted review-hardening worktree** at this checkpoint; do not discard it
when resuming.

Completed in that worktree:

- Kotlin and Rust guaranteed-mode coordinators now use stable logical keys and per-admission fencing,
  so a same-key replacement cannot be stopped by an earlier generation.
- Kotlin waits for a physical collector to finish before replacing it; Rust retains a working
  collector if a replacement scan cannot start. Rust coordinator traffic now enters the bounded
  coordinator path before identity merging rather than an unbounded backend cache.
- Both agents advertise exactly the configured scan-concurrency capability. Rust delivery uses
  direct per-logical bounded mailboxes and a round-robin arbiter; Kotlin teardown cancels command
  work before detaching coordinator delivery.
- Deterministic regression coverage was added for replacement fencing, collector ownership/failure,
  arbiter fairness, bounded legacy identity state, replay expiry/capacity, stable-client caps,
  linearizable `single` admission, and exactly-one capability advertisement.
- Paired WebSocket adapters now execute `SCAN-CONC-01` through `SCAN-CONC-12` against both agents,
  plus an explicit `uncontrolled` legacy-path boundary case, using radio-less scripted backends.
- The documentation now describes `multiplexed` only as filter and lifecycle isolation. It does not
  claim Apple discovery completeness or a resolved release blocker.

### Review pass on the committed branch (2026-08-01)

A read of the committed implementation against this document found, and the branch now fixes:

- **The reference client did not offer the capabilities on the documented construction path.**
  `sendHello` added only `identifier.translate`; the trio came from the Koin module alone, so every
  manually constructed `DefaultAgentSession` — the form used by `getting-started.md`, `flows.md` and
  `client-sdk.md` — read a new agent as `LEGACY_OR_UNKNOWN` and was downgraded to `AGENT_BUSY`
  against a `single`-mode agent. They now live in `ALWAYS_OFFERED_CAPABILITIES`, alongside
  `identifier.translate`, and are no longer restated in the module.
- **`BtleplugBackend::start_scan` had lost its atomic first-scan check.** Emptiness was read, the
  lock dropped, and the registration published only after the await, so two concurrent starts could
  both drive `adapter.start_scan()` (reachable in `uncontrolled`, where starts are only serialized
  per `scanId`) and advertisements arriving in the window were dropped. Both are decided under one
  lock again, with `is_first` taken *before* the insert so a reconfigure of the sole active scan is
  correctly not first — which is the clobber case the branch was right to fix.
- **Waiting for a physical collector to unwind was unbounded** while holding the agent-wide
  coordinator lock inside `NonCancellable`, so one uncooperative backend teardown could wedge scan
  admission for every client and stall connection teardown with it. Bounded by
  `PHYSICAL_SCAN_TEARDOWN_TIMEOUT`; a straggler is already fenced by `physicalGeneration`.
- **Kotlin reserved the 320-entry replay budget twice** (coordinator mailbox *and* arbiter sink)
  where Rust reserves it once. The arbiter sink is now steady-state depth and suspends, so
  backpressure lands on the single reservation and drop-newest is applied only where the physical
  fan-out writes.
- Smaller: the write-ordering turn is now released by the command's own `finally` rather than only
  by the `Op.Write` branch; the guaranteed path regained its `scan started` debug line; the
  `accept_connection` test shim documents that its per-call coordinator defeats the agent-wide
  guarantee; and the never-narrows half of the physical-plan invariant is asserted.

The completed local validation at this checkpoint is:

```text
./gradlew --no-daemon build conformanceTest       PASS
cargo fmt --check                                PASS
cargo clippy --all-targets -- -D warnings        PASS
cargo test --locked                              PASS (113 tests)
git diff --check                                 PASS
```

Still required before this proposal or gap 21 can be closed:

1. Commit the review-hardening worktree intentionally, preserving the existing preparatory commits.
2. Perform the mandatory Rig B hardware run against the available default-multiplexed Kotlin JVM,
   iOS, and Rust reference-agent paths: staggered broad and service-filtered scans, stopping either
   participant while the other continues, and a dedicated iOS-background-peripheral overflow-area
   case. Record date, branch SHA, host/agent, configured and negotiated mode, filters, timing,
   peripheral state, and result.
3. Amend only the Apple discovery-completeness wording from that hardware evidence. Keep the
   release blocker open if the evidence cannot be obtained.

## Review disposition

Findings raised across three review passes, and what each revision did with them. **The final review
is converged**: its required cap-ownership correction and remaining consistency clarifications are
incorporated below.

### Final review

| Finding | Disposition |
|---|---|
| A connection-local `MAX_ACTIVE_SCANS` can be bypassed by reconnecting while old scans remain in grace | **Accepted.** The agent-lifetime coordinator now enforces the cap per stable `clientKey`, counting active and grace-held keys; same-key rebind is cap-neutral. A reconnect cap-bypass test is required. |
| The early Rust parity description still claims per-subscriber mailboxes | **Accepted.** It now distinguishes the already-shipping physical multiplexer/coalescer/matcher from the new mailbox, arbitration, stable-key, and replay work. |
| Per-logical mailboxes alone do not isolate the shared outbound queue | **Accepted.** The contract now requires fair per-connection arbitration and explicitly limits the guarantee at the final shared best-effort transport. |
| Phase 0 says three questions although four are listed | **Accepted.** Corrected. |

### Round 2 (re-review of the revised proposal)

| Finding | Disposition |
|---|---|
| Default mode retains a residual interference class; don't claim the start direction is fully fixed | **Accepted in full.** The guarantee is now stated as filter correctness + lifecycle isolation only, the residual is called out explicitly as accepted rather than closed, the iOS-background hardware case is required, and a strict fourth mode is recorded as a future option. The re-review's correction to the original's phrasing is also right: the rule refuses the *joining* scan, not the incumbent. |
| "Incompatible physical plan" has no normative definition | **Accepted.** The concept is deleted. `SCAN_UNAVAILABLE` is `single`-only; backend/radio failure keeps its existing mappings. Nothing could reach that branch once mixed classes were accepted. |
| Transactional reconfiguration lacks an API seam | **Accepted.** Commit-on-launch with asynchronous physical failure and a MUST-NOT-leak rule; the `Result`-shaped seam is kept for backends (btleplug) that can answer synchronously. The previous rollback promise was not implementable over a cold flow. |
| Teardown must complete before reconnect replay | **Accepted with the reviewer's amendment.** Keyed on stable `clientKey` so replay is a rebind rather than contention, **plus** `ScanRegistration`'s generation as a fencing token — stable identity alone does not fence delayed stops or grace expiry. `NonCancellable` cleanup stays, as resource hygiene rather than as the correctness mechanism. |
| Replay MUST conflicts with cap eviction | **Accepted.** Retained set defined first; replay covers every retained matching entry; cap-overflow test added. |
| Rust mailbox and overflow claims are inaccurate | **Accepted — the claim was wrong.** Verified: one `event_tx` per connection, and tokio `try_send` drops the event being sent. Corrected, with per-logical-scan mailboxes and fair arbitration chosen and Phase 4 resized accordingly. |

### Round 1

| Finding | Disposition |
|---|---|
| P1 — mixed filter classes break the Apple contract | **Partly accepted; settled in round 2.** The service-coverable / unfiltered-required classification is adopted (better than the original "service-only" test). The MUST-refuse remedy is **rejected**: it rests on an unverified premise, and refusing a joining scan because another scan — often the same app's own other scanner — chose a different filter class breaks the broad-plus-targeted use case that motivated multiplexing. Applying it uniformly instead would regress `agent-rs`, which serves mixed classes correctly on Linux today. |
| P1 — decorator cannot do synchronous admission | **Accepted.** `ScanCoordinator` injected into `BleAgent.startScan`. Smaller than the review implies: that method already does mutex-guarded admission, `LAZY` job start, and same-key replacement. |
| P1 — `single` must preserve same-key replacement | **Accepted.** The original wording broke §7 replay-safety. |
| P1 — short-TTL replay contradicts the unbounded MUST | **Accepted.** Bounded window + published cap, both sides of the boundary tested. |
| P1 — reference client absent from the plan | **Accepted.** Verified: `clientCapabilities` defaults empty, only `identifier.translate` is added automatically, and the Koin set lists neither new string. Phase 1 now covers the client. |
| P2 — filter semantics are already answerable | **Accepted, and the original was wrong to file it as open.** Now normative here; two `SimulatedBleBackend` defects recorded. |
| P2 — identity aggregation must precede filtering | **Accepted.** The strongest finding in the review; `agent-rs` already does it. |
| P2 — continuity wording stronger than a radio can guarantee | **Accepted.** Isolation is now defined over logical eligibility and lifecycle, with explicitly best-effort delivery. |
| Advertise `uncontrolled`; `LEGACY_OR_UNKNOWN` on the client | **Accepted** — better than the original's "absence means uncontrolled". |
| 15 paired conformance scenarios | **Partly accepted.** Substance kept; six cases moved to agent-local tests, since each paired scenario costs two adapters. |
| `(connection, scanId)` as the coordinator key | **Superseded in round 2** by `LogicalScanKey(clientKey, scanId)` + a generation fencing token. The generation survives as a fence, not as identity. |
| Fallback: ship `single` as the temporary default | **Rejected.** See below. |
| — | **Added in round 1:** `agent-rs` already implements the physical multiplexer, identity coalescer, and matcher at the core of the mode, which makes this a live parity defect without implying the complete contract already exists. |

## Rejected alternatives

**Refuse logical scans that mix filter classes, as the default.** The rule refuses the *joining*
scan, so an app holding a service-filtered scanner and a broad discovery scanner has its second
scanner fail — the broad-plus-targeted case that motivated multiplexing in the first place. Scoping
the refusal to hosts that need it reintroduces host-dependent semantics; applying it uniformly
instead regresses `agent-rs`, which serves mixed classes correctly on Linux today, where the
overflow-area constraint does not exist. Both branches cost more than the residual they prevent, so
the narrowing is accepted and documented. Kept as a candidate **fourth mode** for operators who
prefer refusal over incomplete discovery — out of scope for 0.10.0.

**Ship `single` as a temporary uniform default if `multiplexed` misses the release.** This publishes a
Maven Central version in which the ordinary two-`RemoteScanner` app fails **by design**, then
un-breaks it in the next release — a worse promise than delaying the tag, and irreversible in a way a
delay is not. The premise is also weaker because Rust's physical multiplexer, coalescer, and matcher
already exist. If the schedule genuinely cannot absorb Phases 2–5, delay the tag.

**Queue concurrent scans.** A scan is not a bounded operation — it runs until its client stops it — so
a queued second scan may never start. The client observes silence with no error: the failure mode this
proposal exists to remove, reintroduced by design.

**Cap at one scan and error, as the whole fix.** That is `single`, and it is strictly better than
today's silent breakage — but it cannot be the default, because it makes the ordinary two-scanner app
fail in a way consumers must code around per agent host.

**Filter client-side.** Would push every advertisement to every client over the link, and the filters
are already an agent-side protocol field.

**Per-platform defaults.** Fixes the radio and keeps the client host-dependent.

## Definition of done

- Concurrent scan behaviour is a configured, advertised, spec-defined property on both reference
  agents, and the Kotlin/Rust divergence is closed.
- Filter semantics are identical in the spec, Kotlin, Rust, and simulation, with multi-predicate tests.
- Kotlin admits scans synchronously, agent-wide, before replying to `scan.start`; same-key rebind
  works in both guaranteed modes, and stale cleanup from a previous generation cannot clobber it.
- Both agents enforce `MAX_ACTIVE_SCANS` per stable client in the guaranteed modes, counting
  grace-held keys without charging same-key rebind.
- Replay has a published window and cap, every retained matching entry is replayed, and identity is
  merged before shared filtering.
- Each logical scan has its own bounded mailbox on both agents; fair arbitration prevents one scan
  from monopolizing scan admission to a connection's shared outbound queue.
- New clients offer all three capabilities automatically; old clients never receive `SCAN_UNAVAILABLE`.
- `SCAN-CONC-01`…`12` pass on both agents, and the agent-local cases pass.
- The staggered two-`scanRun` reproduction runs clean on Rig B against the default mode, **and** the
  iOS-background-peripheral case has been run, with its result reflected in the scope statement.
- `uncontrolled` is never the default.
