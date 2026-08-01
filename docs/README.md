# RemoteBLE — System Reference

This is the **implementation reference** for the RemoteBLE transport: what each
piece is, the public API surface, how the pieces fit, and *why* they are built the
way they are. It documents the system as it actually exists in the tree, not as a
plan.

For quickstart/build commands see [`../README.md`](../README.md).

Release scope is tracked separately from this implementation reference in
[`proposals/0.10.0-scope.md`](proposals/0.10.0-scope.md) (the current release) and the
[`0.9.1-hardening-decisions.md`](proposals/0.9.1-hardening-decisions.md) record; the
[CHANGELOG](../CHANGELOG.md) is the shipped history. Detailed day-to-day planning notes are kept
maintainer-internal and are not part of the published docs.

## Documents

| Document | Covers |
|---|---|
| [getting-started.md](getting-started.md) | **Start here if you're building an app.** Run an agent, connect a client, the local↔remote swap, lifecycle & errors |
| [scanning.md](scanning.md) | **Discovery, for app developers** — filters and their exact semantics, holding two scanners at once, the three agent scan-concurrency modes, replay/late-join, reconnect behaviour, limits |
| [migrate-to-0.10.0.md](migrate-to-0.10.0.md) | Upgrade a Maven Central consumer to 0.10.0; `authToken` provider change and platform compatibility |
| [architecture](#architecture) (below) | The layered model, module map, the seams, glossary |
| [agent-conformance-spec.md](agent-conformance-spec.md) | **The normative conformance spec** — what an independent agent/proxy (or client) MUST do to interoperate. The *contract*, language-agnostic; the others explain *why*. |
| [protocol.md](protocol.md) | The wire contract: every frame/op/result/event type, the codec, serialization rules |
| [client-sdk.md](client-sdk.md) | The client SDK: transport → session → GATT/scan → Kable adapters, every public class |
| [agent.md](agent.md) | The agent: WebSocket server, backend abstraction, op handler, Kable engine backend |
| [proposals/0.10.0-scope.md](proposals/0.10.0-scope.md) | 0.10.0 release scope: conformance, simulated CI agent, Rust container, validation, and publication |
| [proposals/0.10.0-progress-status.md](proposals/0.10.0-progress-status.md) | 0.10.0 implementation handoff: PR0–PR7 landed, verification and remaining hardware/publication work |
| [release-gates.md](release-gates.md) | 0.10.0 permanent CI release gates, source SBOM, policy boundaries, and consumer fixture |
| [simulation.md](simulation.md) | Versioned radio-less JVM agent profile, CLI use, supported behaviors, and validation limits |
| [rust-agent-container.md](rust-agent-container.md) | PR5 local Rust-agent Docker image, smoke checks, and the supported-host boundary |
| [release-candidate.md](release-candidate.md) | 0.10.0 version source, artifact inventory, and pre-tag approval checklist |
| [proposals/agent-proxy.md](proposals/agent-proxy.md) | Future transparent multi-agent proxy design that preserves the existing client/Kable API |
| [proposals/rust-agent-container.md](proposals/rust-agent-container.md) | Linux real-radio OCI image contract for `agent-rs` |
| [proposals/agent-tunable-configuration.md](proposals/agent-tunable-configuration.md) | **Not started** — making agent timeouts/limits settable without a recompile: full inventory of hardcoded values, which of them should *not* become knobs (wire contract), and the delivery options per host |
| [proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) | **Release blocker for 0.10.0** — the design record behind [scanning.md](scanning.md): why concurrent scans were not isolated on Apple hosts (and the Kotlin/Rust agents diverged), the three agent modes (`multiplexed`/`single`/`uncontrolled`), their wire surface, and the rejected alternatives |
| [flows.md](flows.md) | End-to-end walkthroughs (with sequence diagrams): connect, read, write, observe, scan, reconnect, auth |
| [design-decisions.md](design-decisions.md) | The rationale — *why it is built this way*; concurrency, errors, ids, timeouts, MTU, reconnection |
| [prior-art.md](prior-art.md) | **Credit where due** — the ESPHome Bluetooth Proxy architecture RemoteBLE is inspired by, a feature-by-feature comparison + where the two diverge, and the CBOR-vs-Protobuf serialization rationale |
| [build-and-testing.md](build-and-testing.md) | Modules, multiplatform targets, the Kable (Maven Central) dependency, Gradle quirks, the test suite & fakes |
| [phase7-bringup.md](phase7-bringup.md) | **The live bring-up runbook** — run the agent + a test peripheral + `:e2e-runner` against a real radio, no discrete BLE hardware |
| [pr8-validation-plan.md](pr8-validation-plan.md) | The PR8 hardware-validation checklist, grouped by rig: real-radio phones, iOS agent lifecycle, TLS reverse proxy, Ubuntu/Pi container hosts |
| [tls-proxy-recipe.md](tls-proxy-recipe.md) | **The supported `wss://` recipe** (`TLS-PROXY-01`) — Caddy config, throwaway-CA handling that never touches a system trust store, and the five checks covering upgrade, bearer forwarding, certificate rejection, reconnect, and notification delivery |
| [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md) | **Rig A real-radio evidence** — per-case results, the two peripheral defects that had to be fixed before the rig could run, the operator prerequisites, and what remains |
| [agent-parity-verification.md](agent-parity-verification.md) | Kotlin agent vs `agent-rs` feature parity verification (ops, capabilities, logging, dashboard, liveness, translation, registry, scan, errors, auth) |
| [conformance/0.9.1-scenarios.md](conformance/0.9.1-scenarios.md) | Kotlin/Rust executable-conformance scenario skeleton for 0.9.1 |

### Proposals (design records)

Design records for capability extensions — each is the design of record for a feature, kept even
after it ships (the **Status** column tracks whether it's landed and in which release).

| Proposal | Covers | Status |
|---|---|---|
| [proposals/connection-parameters.md](proposals/connection-parameters.md) | Capability-gated BLE connection-interval control (`conn.params`), generalizing the Android-only `conn.priority` | **Implemented in 0.8.2** (coarse Android profile; `hint` reserved) |
| [proposals/agent-side-identifier-translation.md](proposals/agent-side-identifier-translation.md) | Agent translates device handles into each client's native Kable `Identifier` format (reverse map for op routing); hybrid default + strict dashboard toggle | **Implemented in 0.8.0** (Kotlin agent + `agent-rs`) |
| [proposals/0.9.1-hardening-decisions.md](proposals/0.9.1-hardening-decisions.md) | Security, lifecycle, incompatibility, and overload decisions | **Accepted for 0.9.1** |
| [proposals/0.10.0-scope.md](proposals/0.10.0-scope.md) | Validated CI/deployment release without changing the client programming model | **Accepted for 0.10.0** |
| [proposals/rust-agent-container.md](proposals/rust-agent-container.md) | Multi-architecture Linux image using host BlueZ through D-Bus | **Implemented in 0.10.0** (host validation release-gated) |
| [proposals/agent-proxy.md](proposals/agent-proxy.md) | One transparent endpoint aggregating several upstream agents | **Detailed design; deferred beyond 0.10.0** |
| [proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) | Agent-wide scan concurrency mode (`multiplexed` default), the `scan.concurrency.*` capabilities, and `SCAN_UNAVAILABLE` | **Implemented on both agents with paired conformance evidence; hardware validation blocks the 0.10.0 tag** |

---

## What this system is

A **"remote mode" for a Kotlin-Multiplatform BLE stack.** Application code is
written once against [Kable](https://github.com/JuulLabs/kable)'s `Peripheral` /
`Scanner` interfaces. At construction time you choose whether that `Peripheral` is
driven by the **local radio** (ordinary Kable) or by a **remote agent** — a process
near the physical device that owns the real Bluetooth radio and is reached over an
IP link (WebSocket today). The app logic in between does not change.

```
   ┌──────────────────────────┐                       ┌───────────────────────────┐
   │      Client process       │     IP link           │       Agent process       │
   │  (phone / laptop / CI)    │   (WebSocket/CBOR)    │   (near the BLE device)   │
   │                           │                       │                           │
   │  app code                 │                       │                           │
   │    │ uses                 │                       │                           │
   │    ▼                      │   Command  ───────▶   │   BleAgent ──▶ BleBackend │
   │  Kable Peripheral         │                       │                  │        │
   │   = RemotePeripheral      │   ◀───────  Reply     │                  ▼        │
   │    │                      │   ◀───────  Event     │            real radio     │
   │    ▼                      │                       │          (CoreBluetooth,  │
   │  AgentSession             │                       │           Android BLE…)   │
   │    │                      │                       │                  │        │
   │    ▼                      │                       │                  ▼        │
   │  AgentTransport ──────────┼───────────────────────┼──▶ AgentWebSocketServer   │
   └──────────────────────────┘                       └────────────────┬──────────┘
                                                                  physical BLE
                                                                        │
                                                                   ┌────▼────┐
                                                                   │ device  │
                                                                   └─────────┘
```

The promise, proven by [`KableAdapterTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/remoteble/client/KableAdapterTest.kt):
a function written purely against Kable's `Peripheral` compiles and runs unchanged
against a `RemotePeripheral` talking to an agent over a real WebSocket.

## Architecture

The system is three Gradle modules and a strict **layered** design inside the
client. Every boundary is a narrow interface ("a seam") so each layer can be tested
and swapped in isolation.

| Module / Project | Role | Dependencies | Targets |
|---|---|---|---|
| [`:log`](../log) | Shared logging facade: `Logger` (global object), `LogLevel`, `LogSink`, platform sinks, `bytesPreview`, `RateLimitedLog`. Zero external deps. | Maven Central (`dev.warsha.remoteble:log`) | JVM, Android, iOS |
| [`:protocol`](../protocol) | The wire contract + CBOR/JSON codec. Pure data, **no BLE, no network**. | kotlinx-serialization only | JVM, Android, iOS |
| [`:client-sdk`](../client-sdk) | Transport, session, GATT/scan ops, Kable adapters. | `:protocol`, `:log`, coroutines, Ktor client, Kable | JVM (tests), Android, iOS |
| [`:agent`](../agent) | The remote Bluetooth agent: WebSocket server, op handler, radio engine, + a Compose Multiplatform status UI on mobile. | `:protocol`, `:log`, coroutines, Ktor server, Kable, Compose Multiplatform | JVM, Android, iOS |
| [`agent-rs`](../agent-rs) | Native cross-platform Bluetooth agent. Standalone CLI agent, same CBOR wire contract (interop-tested). Run: `run-agent-rs.sh`. | tokio, tokio-tungstenite, btleplug, serde/ciborium, tracing | macOS, Linux |

`:protocol` is the shared contract both sides compile against. `:client-sdk` and
`:agent` never depend on each other in production — they only meet on the wire. (A
test-only `:client-sdk → :agent` edge exists so end-to-end tests can stand up a real
agent; see [build-and-testing.md](build-and-testing.md).)

### The client's three layers

The client SDK is deliberately stratified. Reading top to bottom is reading from
"what the app sees" down to "bytes on a socket":

```
  Layer 3  Kable adapters        RemotePeripheral : Peripheral
           (the public face)     RemoteScanner : Scanner
                                 peripheralFor(mode, advertisement, session)   ← local/remote decision
              │  implemented in terms of ▼
  ─────────────────────────────────────────────────────────────────────────────
  Layer 2½ GATT / scan op layer  RemoteGattClient   (connect/read/write/observe/discover/mtu)
           (protocol-typed)      RemoteScanSource   (advertisements)
              │  built on ▼
  ─────────────────────────────────────────────────────────────────────────────
  Layer 2  Session              AgentSession / DefaultAgentSession
           (request/response    · correlation-id matching   · per-request timeout
            + event demux)       · event fan-out by sub/scan id
                                 · reconcile-on-reconnect (replay connections+subscriptions)
              │  built on ▼
  ─────────────────────────────────────────────────────────────────────────────
  Layer 1  Transport            AgentTransport  (pluggable byte pipe)
           (bytes, BLE-agnostic) WebSocketAgentTransport (Ktor) / InMemoryTransport (tests)
              │  carries ▼
  ─────────────────────────────────────────────────────────────────────────────
  Layer 0  Wire contract        :protocol — Frame / Op / OpResult / AgentEvent + ProtocolCodec
```

Each layer depends only on the interface of the layer beneath it:

- **Layer 1 (`AgentTransport`)** is one message-oriented, BLE-agnostic link to one
  agent. A WebSocket impl, a raw-TCP impl, a cloud-relay impl all satisfy it. The
  endpoint (URL, host:port, MagicDNS name) is handed in at construction and is none
  of the interface's business.
- **Layer 2 (`AgentSession`)** turns that byte pipe into a request/response + event
  API: it assigns correlation ids, matches replies to awaiting calls, enforces a
  timeout per request, demultiplexes unsolicited events to the right stream, and on
  a transport reconnect re-establishes BLE state.
- **Layer 2½ (`RemoteGattClient` / `RemoteScanSource`)** is the GATT/scan surface
  expressed in *protocol* types (`CharRef`, `DeviceHandle`). Non-Kable callers and
  tests use it directly.
- **Layer 3 (Kable adapters)** wraps Layer 2½ in Kable's `Peripheral` / `Scanner`
  interfaces so app code is identical local vs remote.

The agent mirrors this: [`AgentWebSocketServer`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentWebSocketServer.kt)
is the network seam, [`BleAgent`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt)
is the protocol op handler, and [`BleBackend`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleBackend.kt)
is the radio seam (real [`EngineBleBackend`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/EngineBleBackend.kt)
or a fake) — all `commonMain`, shared unchanged across the agent's JVM/Android/iOS targets
(see [agent.md](agent.md#android--ios-a-phone-as-the-agent)).

### The two state machines (do not conflate them)

A recurring theme — and the single subtlest part of the design — is that there are
**two independent link states**:

| State | Type | Meaning | Surfaced by |
|---|---|---|---|
| **IP transport** | `TransportState` (`CONNECTING/CONNECTED/DISCONNECTED`) | Is the socket to the agent up? | `AgentSession.transportState` |
| **Physical BLE** | `BleConnState` (`CONNECTING/CONNECTED/DISCONNECTING/DISCONNECTED`) | Is the agent's radio linked to the device? | `AgentEvent.ConnectionState` events → `RemotePeripheral.state` |

A momentary IP blip does **not** mean the BLE link dropped. The reconnection policy
(see [design-decisions.md](design-decisions.md#reconnection)) is built entirely
around keeping these two separate: a transport reconnect transparently *replays* the
BLE state it believes is live, and never fabricates a BLE-disconnect.

## Glossary

| Term | Meaning |
|---|---|
| **Agent** | The process near the device that owns the real radio and serves the protocol. |
| **`DeviceHandle`** | An opaque, **agent-scoped** device token (a MAC, a CoreBluetooth UUID — the client never parses it). Minted by the agent from its own scan results. |
| **`CharRef`** | A characteristic addressed by service + characteristic UUID (+ optional instance index). Resolved on the agent. |
| **`cid`** | Correlation id: client-assigned, monotonic. A `Reply` echoes the `cid` of its `Command`. |
| **`subId` / `scanId`** | Session-global stream ids tagging notification / scan-result events back to the flow that opened them. |
| **Frame** | The top-level wire envelope: `ClientHello`, `ServerHello`, `Command`, `Reply`, or `Event`. |
| **Op** | One operation in a `Command` (connect, read, write, observe.start, descriptor r/w, pair, conn.priority, …). Mirrors the GATT surface 1:1. |
| **Capability** | An optional feature negotiated in the `ClientHello`/`ServerHello` handshake (a `Set<String>`, e.g. `descriptors`, `pairing`, `slots`). The agent advertises `backend ∪ agent` capabilities; ops/events outside the negotiated set are `UNSUPPORTED` or not emitted. |
| **Seam** | A narrow interface boundary inserted so a layer can be tested/replaced in isolation (`AgentTransport`, `AgentBackend`, `BleBackend`). |
| **Reconcile-on-reconnect** | On IP reconnect, re-issue `Connect` + `ObserveStart`/`ScanStart` for everything the session believes is live. |
