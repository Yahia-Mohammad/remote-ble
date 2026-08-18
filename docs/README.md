# RemoteBLE — System Reference

This is the **system reference** for RemoteBLE: its protocol, agent implementations,
client implementation, public API surfaces, and the reasons behind their boundaries.
It documents the system as it actually exists in the tree, not as a plan.

RemoteBLE is organized around a protocol. The protocol is the stable interoperability contract;
agents implement its radio-facing side, and clients implement its consumer-facing side. This
repository includes two agents and a Kotlin Multiplatform client SDK whose public adapters
use Kable.

For quickstart/build commands see [`../README.md`](../README.md).

Release scope is tracked separately from this implementation reference. The current release is
**0.12.0** — see [`migrate-to-0.12.0.md`](migrate-to-0.12.0.md) for what it carries and why, and
[`migrate-to-0.11.0.md`](migrate-to-0.11.0.md) with
[`proposals/cli-readiness-progress.md`](proposals/cli-readiness-progress.md) for the line before it. Earlier scope records are [`proposals/0.10.0-scope.md`](proposals/0.10.0-scope.md) and the
[`0.9.1-hardening-decisions.md`](proposals/0.9.1-hardening-decisions.md) record; the
[CHANGELOG](../CHANGELOG.md) is the shipped history. Detailed day-to-day planning notes are kept
maintainer-internal and are not part of the published docs.

## Documents

Grouped by what you are doing. Start with the system map, then follow the agent or client path
that matches your role; the final sections are for extending the protocol or auditing a release.

### Start here

| Document | Covers |
|---|---|
| [architecture](#architecture) (below) | **The system map:** protocol at the interoperability boundary, agent and client roles, module dependencies, seams, and glossary |
| [getting-started.md](getting-started.md) | Run an agent, connect the bundled Kable-compatible client, and exercise the complete system |
| [protocol.md](protocol.md) | **The contract itself** — every frame/op/result/event type, negotiation, codec, serialization rule. Read it end-to-end if you're implementing an endpoint; skim it if you're just using the bundled client |

### For app developers

| Document | Covers |
|---|---|
| [scanning.md](scanning.md) | **Discovery, for app developers** — filters and their exact semantics, holding two scanners at once, the three agent scan-concurrency modes, replay/late-join, reconnect behaviour, limits |
| [migrate-to-0.12.0.md](migrate-to-0.12.0.md) | Upgrade to 0.12.0; no source change required unless you match on a simulation profile's literal id |
| [migrate-to-0.11.0.md](migrate-to-0.11.0.md) | Upgrade to 0.11.0; no source change required, but two agent defaults move and three capabilities are added |
| [migrate-to-0.10.0.md](migrate-to-0.10.0.md) | Upgrade a Maven Central consumer to 0.10.0; `authToken` provider change and platform compatibility |
| [client-sdk.md](client-sdk.md) | The bundled client implementation: transport → session → GATT/scan → Kable adapters, every public class |
| [simulation.md](simulation.md) | Versioned radio-less JVM agent profile, CLI use, supported behaviors, and validation limits — test app logic in CI with no Bluetooth hardware |

### Running an agent

| Document | Covers |
|---|---|
| [agent.md](agent.md) | The agent: WebSocket server, backend abstraction, op handler, Kable engine backend |
| [bringup.md](bringup.md) | **The live bring-up runbook** — run the agent + a test peripheral + `:e2e-runner` against a real radio, no discrete BLE hardware |
| [rust-agent-container.md](rust-agent-container.md) | The local Rust-agent Docker image, smoke checks, and the supported-host boundary |
| [tls-proxy-recipe.md](tls-proxy-recipe.md) | **The supported `wss://` recipe** (`TLS-PROXY-01`) — Caddy config, throwaway-CA handling that never touches a system trust store, and the five checks covering upgrade, bearer forwarding, certificate rejection, reconnect, and notification delivery |

### The wire contract & internals

For implementing an independent agent, client, or proxy against the protocol, or understanding how
this one is built.

| Document | Covers |
|---|---|
| [agent-conformance-spec.md](agent-conformance-spec.md) | **The normative conformance spec** — what an independent agent/proxy (or client) MUST do to interoperate. The *contract*, language-agnostic; the others explain *why*. |
| [flows.md](flows.md) | End-to-end walkthroughs (with sequence diagrams): connect, read, write, observe, scan, reconnect, auth |
| [design-decisions.md](design-decisions.md) | The rationale — *why it is built this way*; concurrency, errors, ids, timeouts, MTU, reconnection |
| [prior-art.md](prior-art.md) | **Credit where due** — the ESPHome Bluetooth Proxy architecture RemoteBLE is inspired by, a feature-by-feature comparison + where the two diverge, and the CBOR-vs-Protobuf serialization rationale |
| [build-and-testing.md](build-and-testing.md) | Modules, multiplatform targets, the Kable (Maven Central) dependency, Gradle quirks, the test suite & fakes |
| [agent-parity-verification.md](agent-parity-verification.md) | Kotlin agent vs `agent-rs` feature parity verification (ops, capabilities, logging, dashboard, liveness, translation, registry, scan, errors, auth) |

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
| [proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) | Agent-wide scan concurrency mode (`multiplexed` default), the `scan.concurrency.*` capabilities, and `SCAN_UNAVAILABLE` | **Implemented on both agents with paired conformance evidence, and [hardware-validated](scan-concurrency-validation.md) 2026-08-03** |
| [proposals/agent-tunable-configuration.md](proposals/agent-tunable-configuration.md) | Making agent timeouts/limits settable without a recompile: full inventory of hardcoded values, which of them should *not* become knobs (wire contract), and the delivery options per host | **Not started, bar two landed precedents** (`REMOTE_BLE_WRITE_FAIL_FAST`, `REMOTE_BLE_SCAN_CONCURRENCY`) |

### Release process & evidence

Dated engineering records — the checklists and hardware results that justified taking each release
through its gate. Written for continuity between working sessions, not for a first read; skip this
section unless you're auditing how a specific claim was verified or picking up the release process
itself. `Rig A`/`B`/`D` below are just the shorthand each hardware setup is called by throughout
these documents — Rig A is the real-radio bench, Rig B the iOS device, Rig D the Linux container
host.

| Document | Covers |
|---|---|
| [release-candidate.md](release-candidate.md) | Version sources, artifact inventory, pre-tag approval checklist, and the published evidence for 0.10.0 and 0.11.0 |
| [release-gates.md](release-gates.md) | 0.10.0 permanent CI release gates, source SBOM, policy boundaries, and consumer fixture |
| [proposals/0.10.0-scope.md](proposals/0.10.0-scope.md) | 0.10.0 release scope: conformance, simulated CI agent, Rust container, validation, and publication |
| [proposals/0.10.0-progress-status.md](proposals/0.10.0-progress-status.md) | 0.10.0 implementation handoff and working log — status, open items, and session-by-session history |
| [validation-plan.md](validation-plan.md) | The hardware-validation checklist, grouped by rig: real-radio phones, iOS agent lifecycle, TLS reverse proxy, Ubuntu/Pi container hosts |
| [rig-a-evidence.md](rig-a-evidence.md) | **Rig A — real-radio evidence.** Per-case results, the two peripheral defects fixed before the rig could run, operator prerequisites, and what remains |
| [rig-b-evidence.md](rig-b-evidence.md) | **Rig B — iOS agent lifecycle evidence.** Backgrounding, kill/relaunch, Bluetooth-off, and the ATT-error findings that turned out to be CoreBluetooth-specific |
| [rig-d-evidence.md](rig-d-evidence.md) | **Rig D — Linux container-host evidence.** The GATT-resolution defect it found in `agent-rs`, and the macOS re-check that followed |
| [scan-concurrency-validation.md](scan-concurrency-validation.md) | **Closed 2026-08-03** — the hardware run that closed the concurrent-scan blocker: instruments, rig prerequisites, cases, and what each result is allowed to change |
| [conformance/0.9.1-scenarios.md](conformance/0.9.1-scenarios.md) | Kotlin/Rust executable-conformance scenario skeleton for 0.9.1 |

---

## What this system is

A **protocol-centered remote BLE system.** The protocol defines how a client controls a BLE
radio across a network without prescribing either endpoint's language, BLE library, or transport
implementation. An **agent** near the physical device owns the radio and serves that contract; a
**client** sends commands and consumes replies and events.

The bundled Kotlin Multiplatform client SDK adds one integration on top of that: application
code written against [Kable](https://github.com/JuulLabs/kable)'s `Peripheral` / `Scanner`
interfaces can choose at construction time between the **local radio** (ordinary Kable) and a
**remote agent**, with nothing above that boundary changing.

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

The bundled client's Kable compatibility promise, proven by [`KableAdapterTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/remoteble/client/KableAdapterTest.kt):
a function written purely against Kable's `Peripheral` compiles and runs unchanged
against a `RemotePeripheral` talking to an agent over a real WebSocket.

## Architecture

The system has three core responsibilities — protocol, agent, and client — with multiple
implementations and supporting modules. The dependency direction makes the protocol the center:
both sides depend on the contract, while neither production side depends on the other. Every
boundary is a narrow interface ("a seam") so layers and implementations can be tested and swapped
in isolation.

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
