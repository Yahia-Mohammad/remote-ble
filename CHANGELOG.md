# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The **wire protocol** version is tracked separately from the library version — it
is a distinct compatibility contract for agent/client implementers. Current wire
protocol version: **1**.

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

[0.8.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.8.0
[0.7.0]: https://github.com/Yahia-Mohammad/remote-ble/releases/tag/v0.7.0
