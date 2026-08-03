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
| desc.read | ✅ | ❌ → `Unsupported` |
| desc.write | ✅ | ❌ → `Unsupported` |
| pair | ✅ | ❌ → `Unsupported` |
| unpair | ✅ | ❌ → `Unsupported` |
| conn.priority | ✅ | ❌ → `Unsupported` |
| rssi | ✅ | ❌ → `Unsupported` |
| conn.params | ✅ | ❌ → `Unsupported` |

**Root cause:** `btleplug` (the Rust BLE library) exposes no APIs for descriptors,
pairing, connection priority, connected RSSI, or connection parameters. The Rust
`BleBackend` trait doesn't declare those methods at all. These 7 ops are CBOR-decoded
correctly but answered `Unsupported` at dispatch.

**Origin:** Pre-existing and not introduced by logging. Release handling is capability-specific;
the 0.9.0 addendum requires truthful `UNSUPPORTED` behavior wherever Rust cannot comply.

---

## 2. Capabilities Advertised

| Capability | Kotlin | Rust | Notes |
|---|---|---|---|
| `descriptors` | ✅ (Kable, all platforms) | ❌ | btleplug has no descriptor API |
| `pairing` | ❌ (not advertised) | ❌ | Match — neither advertises |
| `slots` | ✅ (`AGENT_CAPABILITIES`) | ❌ | Rust has helpers but never emits `SlotState` |
| `conn.priority` | ✅ (Android only) | ❌ | btleplug has no priority API |
| `rssi` | ✅ (Android/Apple) | ❌ | btleplug has no connected RSSI read |
| `scan.batch` | ✅ (`AGENT_CAPABILITIES`) | ❌ | Rust never emits `ScanResultBatch` |
| `conn.params` | ✅ (Android only) | ❌ | btleplug has no interval control |
| `identifier.translate` | ✅ | ✅ | **Match** — both implement |

**Pre-existing:** Yes. btleplug limitation.

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
| Lease grace / transport grace | ✅ (10s/10s) | ✅ (10s/10s) |
| Warm-link teardown on expiry | ✅ | ✅ |
| `heldBy(clientKey)` for priming | ✅ | ✅ |
| `onTransportDropped` | ✅ | ✅ |
| Race-safe grace vs. resume | `Job.cancel()` | epoch comparison |
| Default max slots | 4 (per-session cap) | 8 (global) |
| Registry-level client-notifier map | ✅ | ❌ (backend handles notify) |
| Dashboard lease snapshot / exclusive toggle | ✅ | ❌ (no dashboard) |

**Pre-existing differences:** The max-slots divergence (4 vs 8) is a behavioral
difference a client could observe if it negotiates `slots`. The notifier architecture
differs (Kotlin: registry owns the client→notifier map; Rust: the backend itself sends
the disconnect event on the device's `event_tx`). Both are functionally equivalent for
the client-facing behavior.

---

## 8. Scan Batching

| Feature | Kotlin | Rust |
|---|---|---|
| Per-advertisement `ScanResult` | ✅ | ✅ |
| Coalesced `ScanResultBatch` | ✅ (100ms / 16) | ❌ |
| Name/UUID coalescing | ✅ coordinator-owned, before matching | ✅ coordinator-owned in guaranteed modes; bounded legacy backend coalescer in `uncontrolled` |
| Concurrent-scan handling | ✅ configured coordinator, explicit uncontrolled escape hatch | ✅ configured coordinator, explicit uncontrolled escape hatch |

**Pre-existing:** Yes. Rust never implemented scan batching.

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

### Previously recorded differences
- 7 ops Unsupported in Rust (btleplug limitation — ROADMAP: "blocked upstream")
- 6 capabilities not advertised in Rust (btleplug limitation)
- No HTTP dashboard in Rust (architecture — raw `tokio-tungstenite`, no HTTP framework)
- No scan batching in Rust
- No `slots` SlotState events in Rust
- No cheap 1s `isConnected` poll in Rust
- Default max slots divergence (4 vs 8)

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
