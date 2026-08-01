# Scanning (for app developers)

[← back to index](README.md)

Everything an app needs to know about discovering devices through a remote agent: how to open a
scan, what a filter actually matches, what happens when you hold two scanners at once, what survives
a reconnect, and which guarantees stop at the agent's front door.

If you only ever hold **one** scanner and never reconnect, the first section is the whole document.
The rest exists because concurrent scanning is where a remote agent stops resembling a local radio.

> **Reference material lives elsewhere.** The class-by-class API is in
> [client-sdk.md](client-sdk.md#remotescanner-and-remoteadvertisement); the wire types are in
> [protocol.md](protocol.md); the normative agent obligations are in
> [agent-conformance-spec.md](agent-conformance-spec.md#scan-concurrency-modes); the design record
> and its rejected alternatives are in
> [proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md).

---

## The short version

```kotlin
val scanner = RemoteScanner(session)                       // Kable's Scanner<RemoteAdvertisement>
scanner.advertisements.collect { advertisement ->
    println("${advertisement.name} ${advertisement.rssi} → ${advertisement.handle}")
}
```

The scan starts when you **collect** and stops when you **cancel** the collection. There is no
`start()`/`stop()` pair to get wrong, and no scan is left running because a coroutine was cancelled.

`RemoteScanner` is Kable's `Scanner`, so app code cannot tell it from a local one. The field that
matters is `advertisement.handle` — the agent-scoped `DeviceHandle` you pass to `RemotePeripheral`
to connect. Use `.handle`, not `.identifier`: the handle is the portable identity across every
client platform ([why](client-sdk.md#identifier-across-platforms--devicehandletoidentifier)).

For the protocol-level DTO instead of the Kable type, use `RemoteScanSource(session).advertisements()`.

---

## Filters

```kotlin
RemoteScanner(
    session,
    filters = listOf(
        ScanFilter(service = "180d"),                       // any heart-rate monitor
        ScanFilter(service = "1809", name = "Thermo-7"),    // that one thermometer
    ),
)
```

`ScanFilter(service: String? = null, name: String? = null)`. The matching rule is **OR across the
list, AND within one entry**:

```text
matches(filters, advertisement) =
    filters is empty
    OR any filter where
        (filter.name is null OR filter.name == advertisement.name)
        AND
        (filter.service is null OR canonical(filter.service) ∈ canonical(advertisement.serviceUuids))
```

Consequences worth stating outright, because each one has bitten someone:

- **An empty list matches everything**, and so does a bare `ScanFilter()`. Neither is an error.
- **Two entries widen; two fields narrow.** `listOf(ScanFilter(service = a), ScanFilter(service = b))`
  gives you both services. `ScanFilter(service = a, name = n)` gives you only devices that are both.
- **Names are compared exactly** — no prefix, no case folding, no trimming.
- **Service UUIDs are compared canonically**, so `"180d"`, `"0000180D"` and the full
  `0000180d-0000-1000-8000-00805f9b34fb` are the same filter. The 16- and 32-bit short forms are
  expanded with the Bluetooth base UUID.
- **The agent is the filtering authority.** Filtering happens agent-side against a merged view of
  each device (below), not on your process and not necessarily in the radio's controller. A device
  that advertised its service UUID once and then sends a bare RSSI refresh still reaches a
  service-filtered scan, because the agent remembers what it learned about that device.

At most **64** filters per scan (`MAX_SCAN_FILTERS`); more is an `INVALID_REQUEST`.

---

## Holding more than one scanner

This is the case the agent has an explicit policy for. One app holding a heart-rate scanner and a
thermometer scanner is ordinary, and on some hosts the underlying radio cannot serve both: a
CoreBluetooth central has exactly one scan, and starting a second one with different parameters
*replaces* the first's parameters rather than failing.

So the agent — not the platform, and not your app — decides what concurrent scanning means, and it
tells you which policy it is running in the handshake:

```kotlin
when (session.awaitScanConcurrencyMode()) {
    ScanConcurrencyMode.MULTIPLEXED       -> { /* the default; concurrent scans are isolated */ }
    ScanConcurrencyMode.SINGLE            -> { /* one scan agent-wide; expect SCAN_UNAVAILABLE */ }
    ScanConcurrencyMode.UNCONTROLLED      -> { /* no isolation guarantee — operator opted out */ }
    ScanConcurrencyMode.LEGACY_OR_UNKNOWN -> { /* pre-0.10.0 agent; nothing is promised */ }
}
```

| Mode | What the agent does | Isolation guaranteed |
|---|---|---|
| `multiplexed` (**default**) | One physical scan serves every logical scan; the agent filters per subscriber on the way out | filter correctness + lifecycle — **not** discovery completeness on Apple |
| `single` | One logical scan agent-wide; a competing scan is refused with a typed, transient error | yes, by refusal |
| `uncontrolled` | Every scan goes straight to the platform | **no** — host-dependent |

A few notes on reading this:

- **You do not have to branch on it.** The default is `multiplexed` on every host, chosen precisely
  so an app never has to know whether its agent is a Mac, a phone or a Linux box. Read the mode when
  you want to *degrade deliberately* — for instance, to serialize your own scanners against a
  `single` agent instead of retrying into contention.
- **`LEGACY_OR_UNKNOWN` is not `uncontrolled`.** It means the agent did not tell you, so treat
  concurrency as unspecified. An agent that deliberately opted out of isolation says so explicitly.
- The mode is fixed for the agent's process lifetime. It cannot change under a live session.

### What `multiplexed` actually promises

Two things, and it is worth being precise because the third thing people assume is not promised:

1. **Filter correctness.** Every advertisement delivered to your scan satisfies *your* filters.
   Another client's filters never widen or narrow what you see.
2. **Lifecycle isolation.** Another scan starting, stopping, replacing itself, or losing its
   transport never cancels, completes, or redefines yours.

Not promised: **discovery completeness identical to an isolated radio scan.** On Apple hosts a
peripheral advertising a service UUID in the overflow area is discoverable only by a scan that names
that UUID. When your service-filtered scan coexists with a broad one, the agent must run the physical
scan unfiltered, and your scan can therefore see *fewer* devices than it would alone. This is a
platform constraint the agent reduces but cannot remove; it is documented rather than hidden. On
Linux and Windows hosts the constraint does not exist.

Delivery is **best-effort** in the ordinary BLE sense: advertisements are lossy under pressure, the
next packet is a fresher snapshot, and the contract is about which advertisements are *eligible* for
your scan, not about receiving every packet the radio saw.

---

## Errors you can actually get

| Error | When | What to do |
|---|---|---|
| `SCAN_UNAVAILABLE` (transient) | `single` mode, and another logical scan holds the agent-wide slot | Retry, or wait for the incumbent. The SDK's default retry policy already retries transient errors. |
| `AGENT_BUSY` (transient) | Same situation, but your session did not negotiate the `single` capability | Same handling. Only reachable on a session built without the SDK's default capability set. |
| `INVALID_REQUEST` | More than 16 active scans for your client, or more than 64 filters | Fix the call; retrying will not help. |
| `RADIO_OFF` (transient) | Radio is off, and your session negotiated `radio.state` | Wait for `AgentEvent.RadioState`. |

`SCAN_UNAVAILABLE` is **capability-gated**: an agent only sends it to a client that negotiated
`scan.concurrency.single`, because an unknown error-kind name would break an older client's decode.
Every session built by this SDK offers all three `scan.concurrency.*` strings automatically, so you
get the typed error whether you construct `DefaultAgentSession` yourself or take it from Koin.

**The scan cap counts differently in the guaranteed modes.** `MAX_ACTIVE_SCANS` is 16. In
`multiplexed` and `single` it is enforced per *stable client identity*, and it counts scans that are
detached in the transport-grace window as well as live ones — so dropping and reconnecting does not
buy a fresh allowance. Re-issuing a `scanId` you already hold is free.

---

## Late joins and the replay window

A device that already advertised is not obliged to advertise again soon, so a scan that starts while
another is running would otherwise sit silent for an unpredictable time. The agent keeps a bounded
cache and replays it to a joining scan:

- the cache retains at most **256** devices whose latest observation falls within **30 seconds**,
  keeping the most recently observed and evicting oldest-observation-first;
- a joining scan immediately receives every **retained** entry its filters match, without waiting for
  a re-advertisement.

Two practical consequences: a device seen 45 seconds ago will not be replayed, and a replayed entry
means "recently observed", not "present right now" — treat it exactly as you would any advertisement,
which is as evidence of recent presence rather than a live connection guarantee.

The entries the agent replays are **merged**: it retains the last known name and service UUIDs per
device and backfills packets that omit them. That is why a sparse RSSI-only update still reaches a
service-filtered subscriber, and why `advertisement.name` can be non-null on a packet that did not
itself carry a name. RSSI is never merged — it legitimately varies per packet and is passed through.

---

## Reconnects

Scans are part of the session's replay set, so a transport blip is normally invisible:

1. The transport drops. Your scan is **detached**, not destroyed, and enters the agent's
   transport-grace window. Other clients' scans are unaffected.
2. You reconnect. The session re-handshakes and replays your active scans with the *same* `scanId`.
3. The agent recognizes the same logical scan — ownership is keyed on your stable client identity
   plus `scanId` — and **rebinds** it rather than treating it as a competing scan. In `single` mode
   this is what stops you contending with your own scan for the slot you already owned.
4. If you never come back, grace expires and the scan is released.

Two things follow that are easy to miss:

- **Supply a stable client identity if you want reconnect continuity.** Without one, ownership falls
  back to the socket, and continuity degrades to the connection's lifetime — the same trade
  peripheral leases already make.
- **In `single` mode the slot stays held for the whole grace window**, deliberately: a brief blip must
  not let another client steal the scanner out from under a reconnecting owner. Another client's
  `scan.start` in that window gets a transient refusal, which is honest rather than silent.

Watch `AgentSession.readiness` for `RECONCILING` → `READY`, and
`AgentSession.reconciliationReport` for what was replayed, if you want to surface it.

---

## Limits and constants

| | Value | Notes |
|---|---|---|
| `MAX_ACTIVE_SCANS` | 16 | Per stable client in `multiplexed`/`single`; per connection in `uncontrolled` |
| `MAX_SCAN_FILTERS` | 64 | Per scan |
| Replay window | 30 s | Per device, by latest observation |
| Replay cache | 256 devices | Oldest-observation-first eviction |
| Logical scan mailbox | 320 entries | 256 replay reservation + 64 steady state, drop-newest |

The mailbox depth is the one worth understanding: each logical scan gets its **own** bounded buffer,
so a high-volume scan cannot consume another scan's capacity, and a per-connection round-robin
arbiter drains them fairly into the shared outbound link. That isolation ends at the link — scan
events still share the final best-effort transport with notifications, replies and connection events,
so there is no separate end-to-end bandwidth budget per scan.

---

## Batching

If your session negotiates the `scan.batch` capability, the agent coalesces advertisements into
`ScanResultBatch` events (100 ms window, 16 max) instead of one event per advertisement. This is
transparent: `RemoteScanSource` unpacks batches and emits the same flow of advertisements either way.
It reduces frame overhead on a busy scan and nothing else. `agent-rs` does not implement batching —
it sends per-advertisement events — which is a permitted difference, not a defect.

---

## Choosing a mode (operators)

App developers do not select the mode; whoever runs the agent does, and the default is the safe one.

```sh
# Kotlin agent (JVM launcher)
REMOTE_BLE_SCAN_CONCURRENCY=multiplexed ./gradlew :agent:jvmRun

# agent-rs
./agent-rs --scan-concurrency multiplexed        # or REMOTE_BLE_SCAN_CONCURRENCY
```

`multiplexed` | `single` | `uncontrolled`, defaulting to `multiplexed`. An unrecognized value fails
startup rather than silently taking the default, and the running mode is printed at startup. Phone
agents read neither environment nor CLI, so they always run the default.

Pick `single` when you want contention to be **loud** — one scanning client at a time, and everyone
else gets a typed transient error instead of degraded results. Pick `uncontrolled` only when you know
the deployment has exactly one scanning client and you want the platform's own path (controller
offloaded filtering, no agent-side matching); it makes no isolation guarantee and is advertised
explicitly so a client can tell it apart from an old agent.

---

## Known limitations

- **Apple discovery completeness under mixed filter classes** — described above; being validated on
  hardware before 0.10.0 is tagged. Filter correctness and lifecycle isolation are unaffected.
- **iOS agents are foreground-only.** Backgrounded behaviour is observed, never contract, and no
  design accommodates it.
- **`uncontrolled` is explicitly non-conformant** with the isolation guarantee. That is the point of
  it, and why it is never the default.
- **A physical scan that dies takes its logical scans quiet, not down.** If the radio disappears
  after a scan starts, registrations are not leaked, but re-establishing the physical scan happens on
  the next `scan.start` or replacement rather than automatically. Reconnect logic that replays scans
  recovers this; a long-lived idle scan may not.
