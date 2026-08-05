# Agent Parity Verification (2026-07-13)

> Verification of the Kotlin agent (`:agent`) vs the Rust agent (`agent-rs`) after the
> 0.9.0 logging implementation. This is a point-in-time feature inventory, not an overall parity
> verdict. The 2026-07-15 review found additional runtime differences and assigned their release
> disposition in the accepted
> [`0.9.1-hardening-decisions.md`](proposals/0.9.1-hardening-decisions.md).

---

## 1. Protocol Operations

Both agents share the same `Op` enum (17 variants) in their respective protocol modules.
The dispatch tables differ:

| Op | Kotlin (`BleAgent.handle()`) | Rust (`server.rs::execute_op()`) |
|---|---|---|
| scan.start / scan.stop | ✅ | ✅ |
| connect / disconnect | ✅ | ✅ |
| discover | ✅ | ✅ |
| read / write | ✅ | ✅ |
| observe.start / observe.stop | ✅ | ✅ |
| requestMtu | ✅ | ✅ |
| desc.read | ✅ | ✅ |
| desc.write | ✅ | ✅ |
| pair | ✅ | ❌ → `Unsupported` |
| unpair | ✅ | ❌ → `Unsupported` |
| conn.priority | ✅ | ❌ → `Unsupported` |
| rssi | ✅ | ❌ → `Unsupported` |
| conn.params | ✅ | ❌ → `Unsupported` |

**Root cause — corrected 2026-08-05.** This previously read "btleplug exposes no APIs for
descriptors, pairing, connection priority, connected RSSI, or connection parameters." That is
wrong for descriptors, and the error mattered: it moved `desc.read`/`desc.write` from "not built
yet" into "cannot be built", where nothing would revisit them.

`btleplug` 0.11.8 declares `Peripheral::read_descriptor` and `Peripheral::write_descriptor`, and
exposes `Characteristic.descriptors`. Kable's JVM backend is btleplug too and binds both
(`PeripheralInterface.readDescriptor` / `writeDescriptor` in `kable-btleplug-ffi`), which is what
the JVM Kotlin agent advertises `descriptors` on — truthfully, so rule 3 is not violated there.

So the split is:

- **`desc.read` / `desc.write` — implemented in 0.10.1.** `BleBackend` gained the two methods,
  `btleplug_impl` resolves a `DescRef` exactly as it resolves a `CharRef`, and `execute_op`
  dispatches both — authorizing first, so moving them out of the catch-all arm did not reopen the
  cross-client hole Rig A case 3 closed. Discovery now reports each characteristic's descriptor
  UUIDs too: that list was hard-coded empty, which would have left the capability advertised but
  unreachable, since a client discovers the tree to learn what it may address.
- **`pair` / `unpair` / `conn.priority` / `rssi` / `conn.params` — genuinely unavailable.**
  btleplug has no pairing, connection-priority, or interval control, and its RSSI is the cached
  advertisement value rather than a connected read.

The remaining five are CBOR-decoded correctly and answered `Unsupported` at dispatch, which stays
truthful.

**Origin:** Pre-existing and not introduced by logging. Release handling is capability-specific;
the 0.9.0 addendum requires truthful `UNSUPPORTED` behavior wherever Rust cannot comply.

---

## 2. Capabilities Advertised

Compare **like hosts**. The Kotlin column below is Kable's Android backend, which is the most
capable one; the JVM column is the fair comparison for `agent-rs`, because Kable's JVM target is
itself btleplug. Reading the Android column as the standard makes Rust look four capabilities
poorer than it is — `rssi`, `conn.priority`, and `conn.params` are Android radio features that the
JVM Kotlin agent does not advertise either.

| Capability | Level | Kotlin (Android) | Kotlin (JVM) | Rust | Notes |
|---|---|---|---|---|---|
| `slots` | agent | ✅ | ✅ | ✅ | **Match** since 0.10.1 — agent-global and lease-aware in both |
| `identifier.translate` | agent | ✅ | ✅ | ✅ | **Match** — both implement |
| `scan.batch` | agent | ✅ | ✅ | ✅ | **Match** since 0.10.1 — same 100 ms window and 16-result cap |
| `agent.status` | agent | ✅ | ✅ | ✅ | **Match** since 0.10.1 — same DTO, same disclosure rules, same skipped defaults on the wire |
| `descriptors` | backend | ✅ | ✅ | ✅ | **Match** since 0.10.1 — see §1 |
| `rssi` | backend | ✅ | ❌ | ❌ | Match on JVM: btleplug reports cached advertisement RSSI, not a connected read |
| `conn.priority` | backend | ✅ | ❌ | ❌ | Match on JVM: Android's `requestConnectionPriority` has no equivalent |
| `conn.params` | backend | ✅ | ❌ | ❌ | Match on JVM: no interval control |
| `pairing` | backend | ❌ | ❌ | ❌ | Match — neither advertises |
| `radio.state` | backend | ✅ | ❌ | ❌ | Match on JVM: neither observes adapter state there |

See the conformance spec, §5.3, for what "agent" and "backend" level oblige. Agent-level
capabilities are advertised unconditionally by both agents — in Rust via
`capabilities::AGENT_CAPABILITIES`, applied in `Negotiation::on_hello` where no backend answer can
narrow it; in Kotlin via `BleAgent.AGENT_CAPABILITIES`.

**No same-host divergence remains.** Every capability above either matches on a given host, or
differs only where the host's radio genuinely differs.

One caveat worth carrying: the simulator does not model descriptors and the real-agent descriptor
tests are kept separate, so **neither** agent's descriptor path is exercised by the default CI
matrix. The Rust implementation is covered by dispatch and authorization tests against a fake
backend; that it works over a real radio is unproven on either agent and wants a rig run.

---

## 3. Logging

| Feature | Kotlin | Rust |
|---|---|---|
| `REMOTE_BLE_LOG` env var | ✅ | ✅ |
| Levels TRACE→ERROR | ✅ | ✅ |
| Client-attributable log lines | ✅ (`[c=$clientId]` prefix) | ✅ (`tracing` span fields) |
| Default level | INFO | INFO |
| Zero cost when off | ✅ (lazy lambda, level check first) | ✅ (`tracing` level macros) |
| Live log-level toggle | ❌ (`GET /api/log-level` is read-only; mutation removed for 0.9.0) | ❌ (startup flag only) |
| Pluggable sinks | ✅ (`LogSink` — Println, Android, Apple) | ❌ (single `tracing-subscriber`) |
| JSON log format | ❌ | ✅ (`--log-format json`) |
| `RUST_LOG` per-module filter | N/A | ✅ (takes precedence over `--log-level`) |
| Per-connection spans | ❌ (manual prefix) | ✅ (`info_span!("conn", client, peer)`) |
| Rate-limited logging | ✅ (`RateLimitedLog` helper) | ❌ |

**Parity assessment:** Both agents emit the same *kind* of story at the same levels —
INFO lifecycle, DEBUG per-op, WARN anomalies, ERROR failures — each line
client-attributable. The implementation mechanisms differ by design (Kotlin uses the
shared `Logger` object from `:log`; Rust uses `tracing`/`tracing-subscriber`). The
plan acceptance criteria (§7: "same kind of story at the same levels, each line
client-attributable") is met. The mechanical differences (pluggable sinks vs.
structured spans, dashboard toggle vs. JSON format) are symmetric and by design.

---

## 4. HTTP Dashboard

| Feature | Kotlin | Rust |
|---|---|---|
| HTML dashboard at `/` | ✅ (`Dashboard.kt`) | ❌ |
| `/api/state` JSON snapshot | ✅ | ❌ |
| `/api/peripheral/exclusive` toggle | ❌ (removed for 0.9.0) | ❌ |
| `/api/strict` strict-mode toggle | ❌ (removed for 0.9.0) | ❌ |
| `/api/log-level` toggle | ❌ (removed for 0.9.0) | ❌ |
| In-process Compose UI (mobile) | ✅ | ❌ |

**Root cause:** Kotlin's Ktor is both an HTTP server and WebSocket server — the
dashboard routes mount in the same `routing {}` block as the WebSocket route. Rust's
`agent-rs` uses `tokio-tungstenite` directly on a raw `TcpListener` with no HTTP routing
layer. `Cargo.toml` has no HTTP framework dependency (no axum/warp/actix). The only HTTP
surface is returning a `401` status during the WebSocket upgrade callback.

**Origin:** Pre-existing and not introduced by logging. Rust need not add a dashboard for parity;
the Kotlin management mutations must still be authenticated or removed for 0.9.0.

---

## 5. Connection Watcher / Liveness

| Feature | Kotlin | Rust |
|---|---|---|
| Cheap `isConnected` poll (1s) | ✅ | ❌ |
| Deep `checkLiveness` probe (15s) | ✅ (read char/CCCD) | ✅ (`discover_services`) |
| Native unsolicited-drop stream | ✅ (`connectionDrops()` Flow) | ✅ (`DeviceDisconnected` event) |
| Adapter reset resilience | N/A (Kable handles) | ✅ (exponential backoff re-subscribe) |
| Probe failure containment | ✅ | ✅ |

**Pre-existing:** Yes. The Rust agent uses `discover_services` as its probe (comments
acknowledge this can momentarily re-discover during a racing client op); Kotlin uses a
readable characteristic read, which is safer. Kotlin additionally has a 1s cheap poll
that Rust lacks.

---

## 6. Handle Translation

| Feature | Kotlin | Rust |
|---|---|---|
| FNV-1a 128-bit digest | ✅ (same seeds) | ✅ (same seeds) |
| UUID v5-shaped output | ✅ | ✅ |
| MAC locally-administered | ✅ | ✅ |
| `needsRewrite` matrix | ✅ (identical) | ✅ (identical) |
| Strict-mode pass-through | ✅ (closure) | ✅ (`Arc<AtomicBool>`) |
| Reverse-map cap (4096, LRU) | ✅ | ✅ |
| `prime(warmLeases)` on handshake | ✅ | ✅ |
| `evict(real)` on release | ✅ | ✅ |
| Forward-translate outgoing events | ✅ | ✅ |
| Platform identifier format map | ✅ (identical) | ✅ (identical) |

**Parity:** Full match. The algorithms are byte-faithful (verified by cross-language
interop tests in `protocol/interop_tests.rs`).

---

## 7. Peripheral Registry / Ownership

| Feature | Kotlin | Rust |
|---|---|---|
| Acquire with exclusive/shared | ✅ | ✅ |
| Re-acquire cancels grace | ✅ | ✅ |
| Lease grace / transport grace | ✅ (10s/120s) | ✅ (10s/120s) |
| Warm-link teardown on expiry | ✅ | ✅ |
| `heldBy(clientKey)` for priming | ✅ | ✅ |
| `onTransportDropped` | ✅ | ✅ |
| Race-safe grace vs. resume | `Job.cancel()` | epoch comparison |
| Slot cap scope | global (registry) | global (registry) |
| Default max slots | 8 | 8 | 
| Occupancy change signal | `StateFlow<Int>` | `watch::Sender<usize>` |
| `SlotState` at handshake + on change | ✅ | ✅ |
| Registry-level client-notifier map | ✅ | ❌ (backend handles notify) |
| Dashboard lease snapshot / exclusive toggle | ✅ | ❌ (no dashboard) |

**The max-slots divergence is resolved at 8.** It was tolerable while the Kotlin cap was per
session and `agent-rs` emitted no `SlotState` at all — a client could not compare the two numbers
because it only ever saw one of them. Once both agents began reporting a global, lease-aware count
over the same capability, the same client on the same host would have got 4 from one and 8 from the
other. Kotlin moved, because 8 is the more permissive of the two: aligning downward would have
tightened `agent-rs` for its existing users *and* left the Kotlin per-session→agent-wide conversion
at its most restrictive, turning an effective 4×clients into 4 total.

The notifier architecture differs (Kotlin: registry owns the client→notifier map; Rust: the backend
itself sends the disconnect event on the device's `event_tx`). Both are functionally equivalent for
the client-facing behavior.

---

## 8. Scan Batching

| Feature | Kotlin | Rust |
|---|---|---|
| Per-advertisement `ScanResult` | ✅ | ✅ |
| Coalesced `ScanResultBatch` | ✅ (100ms / 16) | ✅ (100ms / 16) |
| Where batching lives | per scan job | the connection's event pump, where both scan paths converge |
| Name/UUID coalescing | ✅ coordinator-owned, before matching | ✅ coordinator-owned in guaranteed modes; bounded legacy backend coalescer in `uncontrolled` |
| Concurrent-scan handling | ✅ configured coordinator, explicit uncontrolled escape hatch | ✅ configured coordinator, explicit uncontrolled escape hatch |

**Closed 2026-08-05.** Rust batches in its per-connection event pump rather than inside each scan
job: the coordinator's arbiter and the uncontrolled backend path both feed that one channel, so a
single implementation covers both instead of the two the Kotlin agent carries. The observable
contract is the same — flush every 100 ms or early at 16 results, never an empty batch, arrival
order preserved within a batch — and the capability is read live, so a scan already running when a
late hello negotiates `scan.batch` starts batching, matching §5.3's rule for handle translation on
an in-flight stream.

The wire form is now covered in the direction that can fail: `agent-rs` could always decode a batch
but never sent one, so `RustAgentInteropTest.eventScanResultBatch` pins a Kotlin client's decode of
the definite-length CBOR the Rust agent actually emits.

**Gap 21 — closed 2026-08-03, parity confirmed on hardware.** Both reference agents route guaranteed
modes through an agent-lifetime coordinator keyed by stable client key and scan ID, with
generation-fenced grace, bounded replay, logical mailboxes, and fair per-connection admission to the
outbound queue. The design is
[proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md).

**This section previously recorded concurrent-scan handling as matching when it did not** — the
Kotlin agent opened one Kable `Scanner` per client while `agent-rs` already reference-counted
subscribers onto a single adapter scan. That was a live parity defect this table asserted was
absent, which is why it is called out here rather than quietly corrected.

Parity is now verified rather than asserted: the [scan-concurrency hardware
run](scan-concurrency-validation.md) put both topologies (two clients; one client holding two
scanners) through the Kotlin JVM agent and `agent-rs` on the same Mac and the same radio, and the
two **agreed on every verdict** — stop direction, start direction, and filter correctness — with no
`INCONCLUSIVE` results. The iOS agent, which is the platform the defect actually lives on, agreed
with both.

---

## 9. Error Handling

| Feature | Kotlin | Rust |
|---|---|---|
| ErrorKind enum (14 variants) | ✅ | ✅ (identical) |
| `transient` per-kind annotation | ✅ | ❌ (pure enum) |
| `NOT_CONNECTED` pre-check on read/write | ✅ | ❌ |
| `gattStatus` field | exists, unused | exists, unused |
| Backend error mapping | ✅ | ✅ (matching kinds) |

**Pre-existing:** The `transient` annotation is client-side retry metadata that lives
with the Kotlin protocol module. The Rust agent doesn't need it (the client SDK owns
retry logic). The `NOT_CONNECTED` pre-check is a Kotlin-side safety gate absent in Rust.

---

## 10. Auth (Bearer Token)

| Feature | Kotlin | Rust |
|---|---|---|
| Required header `Authorization: Bearer` | ✅ | ✅ |
| 401 on mismatch | ✅ | ✅ |
| Token from `REMOTE_BLE_TOKEN` env | ✅ | ✅ (also `--token` CLI) |
| No-auth when token unset | ✅ | ✅ |
| `X-RemoteBle-Client` stable identity | ✅ | ✅ |

**Parity:** Full match.

---

## Summary

### Verified parity in the specifically listed algorithm/schema areas
- Handle translation (byte-faithful, interop-tested)
- Bearer token auth + stable client identity
- ErrorKind enum (14 variants, identical wire form)
- Lease management (acquire/release, grace windows, warm teardown)
- Per-scan identity coalescing
- Native unsolicited-drop detection
- Wire protocol codec (CBOR byte-parity verified by interop tests)
- **Logging levels and taxonomy** (0.9.0 scope — both emit the same story at the same levels)
- **Agent-level capability set** (0.10.1 — `slots`, `identifier.translate`, `scan.batch`, and
  `agent.status` advertised unconditionally by both, and complete: every agent-level capability
  now matches)
- **`agent.status`** (0.10.1 — identical DTO from both agents, including which fields are omitted
  when they equal their defaults, so diffing one agent's status against the other's is meaningful
  rather than merely both-parse-fine. The Rust agent grew a bounded advertised-name cache for it,
  since btleplug offers the name only on the scan path and nothing else retained it)
- **`slots` accounting and delivery** (0.10.1 — global, lease-aware, delivered at handshake and on
  every occupancy change in both agents)
- **Lease-denial disclosure** (0.10.1 — one policy, and now one *bound*: both cap the rendered
  message rather than the characters consumed, so an all-escaped identity is described identically)

### Previously recorded differences
- 5 ops Unsupported in Rust for genuine btleplug limitations: `pair`, `unpair`, `conn.priority`,
  `rssi`, `conn.params` (ROADMAP: "blocked upstream")
- No HTTP dashboard in Rust (architecture — raw `tokio-tungstenite`, no HTTP framework)
- No cheap 1s `isConnected` poll in Rust

### Resolved in 0.10.1
- Default max slots (was 4 vs 8, now 8 in both) — see §7
- `descriptors` (`desc.read` / `desc.write`) implemented in `agent-rs`, closing the last same-host
  divergence — see §1. Recorded as a btleplug limitation until 2026-08-05; btleplug has the API

### Runtime differences found by the 2026-07-15 review, now fixed

Wire-codec parity does not imply server-behavior parity. The review found missing cross-client
authorization in both agents and Rust differences in stream scoping, task lifetime, write
ordering, observation teardown, MTU truthfulness, and scan filters. All of these are fixed and
covered by regression tests in both agents as part of the 0.9.0 addendum (Kotlin two-client
authorization suite; Rust `transport::server::tests` with a fake backend). Grace/liveness
alignment and permanent executable parity evidence remain scheduled for 0.9.1.

### Symmetric logging differences (by design)
- Kotlin-only: pluggable sinks, `RateLimitedLog`
- Rust-only: structured per-connection `tracing` spans, `--log-format json`, `RUST_LOG` EnvFilter

---

## 0.9.1 hardening parity update (2026-07-16)

The 0.9.1 security/lifecycle hardening (D1–D5) added or aligned the following across **both**
agents. These supersede the stale specifics above where they conflict.

| Area | Kotlin | Rust | Notes |
|---|---|---|---|
| Named-principal credentials | ✅ | ✅ | `name=secret` pairs (`REMOTE_BLE_TOKENS`); `REMOTE_BLE_TOKEN` = legacy `default`; constant-time compare |
| Principal-scoped ownership key | ✅ | ✅ | `(principal, stable client id)`; `X-RemoteBle-Client` never crosses principals |
| Loopback-default bind + policy | ✅ | ✅ | non-loopback needs a credential or the explicit insecure-LAN override |
| Failed-auth rate limiting | ✅ (client + operator planes) | ✅ (client plane) | fixed-memory per-peer/global limiter, LRU eviction, `429` |
| Operator credential | ✅ (`REMOTE_BLE_OPERATOR_TOKEN`) | ✅ (`--operator-token` / `REMOTE_BLE_OPERATOR_TOKEN`) | Must be distinct from every client credential; both fail startup otherwise. Grants the HTTP dashboard (Kotlin only — Rust serves no HTTP) and, on both, `agent.status` holder disclosure via `X-RemoteBle-Operator` |
| Duplicate live session refused | ✅ (post-upgrade `1008`) | ✅ (handshake `409`) | `LEASE-DUPLICATE-01`; transport signal is implementation-specific |
| 1 MiB inbound frame cap | ✅ | ✅ | framing-layer enforcement before decode |
| Argument ceilings → `INVALID_REQUEST` | ✅ | ✅ | ≤64 filters, ≤512-byte writes/descriptors, **MTU 23–517** |
| Version-range negotiation + `1002` close | ✅ | ✅ | shared `VERSION-01` fixture; client maps to `INCOMPATIBLE_PROTOCOL` |
| Late-connect lease guard | ✅ (`connectionLive`) | ✅ (`connection_live`) | a connect completing after transport retirement can't resurrect an abandoned lease |
| Explicit-disconnect immediate release | ✅ | ✅ | never converted to transport grace |

`ErrorKind` now carries `INVALID_REQUEST` on both agents (and a client-side-only
`INCOMPATIBLE_PROTOCOL` in the Kotlin protocol module, never sent on the wire), so the earlier
"14 variants, identical" line is superseded by "the wire-sent kinds remain a shared set, plus the
added `INVALID_REQUEST`."
