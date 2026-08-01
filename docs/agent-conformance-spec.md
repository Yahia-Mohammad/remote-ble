# RemoteBLE Agent / Proxy Conformance Specification

**Spec version:** `1.0` &nbsp;·&nbsp; **Status:** stable &nbsp;·&nbsp; [← back to index](README.md)

This document is the **normative, implementation-agnostic contract** for a RemoteBLE
**agent** (a.k.a. proxy): the process that owns a Bluetooth radio and serves the protocol
over an IP link. Any program that satisfies this spec interoperates with any conformant
client — including the reference `:client-sdk` — regardless of language, BLE stack, or OS.
A conformant **client** is specified here too, because some agent guarantees only hold if
the client upholds its side (identity, reconcile).

The reference implementation lives in this repo (`:agent`, `:client-sdk`, `:protocol`); the
prose docs ([protocol.md](protocol.md), [agent.md](agent.md), [design-decisions.md](design-decisions.md))
explain *why*. **This file is the *what*.** Where they differ, this file governs interop.

## 1. Conformance language

The key words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are used per
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119). "The agent" / "the client" denote a
conformant implementation of each role. "The reference" denotes this repo's implementation.

An implementation is **conformant** if it satisfies every MUST in §§3–10 for its role and the
checklist in §13. Optional features (§12) do not affect conformance.

## 2. Roles & model

- **Client** — issues operations and consumes results/events. Holds no radio.
- **Agent / proxy** — owns exactly one BLE *central* radio (or a façade over one), executes
  operations against physical peripherals, and is the **authority** on peripheral ownership.

One agent serves zero or more concurrent clients over independent connections. The agent's
radio is a single shared resource; §10 governs how it is arbitrated. There is no
agent-to-agent federation in this version (a client talks to one agent at a time).

```
client ──Command──▶ agent ──▶ radio ──▶ peripheral
client ◀──Reply──── agent          (one Reply per Command)
client ◀──Event──── agent          (unsolicited; scan results, notifications, BLE state)
```

## 3. Transport binding (WebSocket)

The reference binding is WebSocket. Another message-oriented, ordered, reliable, bidirectional
binding MAY be substituted, but both peers MUST agree on it; this section specifies the
WebSocket binding that all reference-compatible implementations MUST provide.

1. The agent MUST expose a WebSocket endpoint at a configurable path; the reference default is
   **`/agent`**.
2. Each protocol **frame** (§5) MUST be carried as exactly **one binary WebSocket message**
   (`opcode 0x2`). One message = one frame. Implementations MUST NOT split a frame across
   messages or pack multiple frames into one message. Text messages are not part of this spec.
3. The link MUST preserve **order** within a connection. Replies and events are delivered in
   the order the agent emits them.
4. Either side MAY close the connection at any time. Closure is a transport-level event
   (§9, §10) — it is **not** in itself a BLE disconnect.
5. The endpoint locator (URL / host:port / DNS name) is out of scope; it is supplied to the
   client out of band.

## 4. Handshake

On the WebSocket upgrade request:

1. **Authentication (optional, agent-configured).** If the agent is configured with a bearer
   token, it MUST require the header `Authorization: Bearer <token>` and MUST reject a missing
   or non-matching credential with HTTP **401** *before* completing the upgrade (the client
   never reaches a connected state). If no token is configured, the endpoint is open. The
   protocol defines no other identity/authorization system.
2. **Client identity (required for resume).** The client SHOULD send header
   **`X-RemoteBle-Client: <stable-id>`** — an opaque, stable string identifying the *client
   session*, generated once and re-sent **unchanged on every reconnect**. It is an identifier,
   **not** a credential. The agent MUST use it as the ownership key (§10). A client that omits
   it MUST still be served, but the agent MUST treat each of its connections as a distinct
   owner (such a client cannot resume a lease across a drop — §10.4).
3. The agent MAY assign its own per-connection id for logging/monitoring; that id is internal
   and MUST NOT be used as the ownership key.

This section covers the **transport-level** handshake only. The in-band **protocol** handshake —
version and capability negotiation via the `hello` / `server_hello` frames — happens after the
upgrade, over the established link (§5.3).

## 5. Serialization & message envelope

### 5.1 Codec

- The **canonical wire codec is CBOR**, as produced by kotlinx.serialization's CBOR format
  with **default class-discriminated polymorphism**: each polymorphic value carries a
  discriminator field **`type`** whose value is the type's `@SerialName` (the strings in §6),
  alongside that type's own fields. A **JSON** codec with the identical model is defined for
  debugging. Two peers MUST agree on one codec; the reference uses CBOR in production.
- All `@SerialName` discriminator strings in §6 are **frozen wire identity**. Changing,
  removing, or renaming one is a breaking change and MUST bump the spec major version.
- Unknown discriminators or fields: a decoder MAY reject a frame it cannot understand; it MUST
  NOT crash the connection for the *peer's* other frames. Producers MUST NOT emit types/fields
  not in this spec on a `1.x` link.

The JSON form of a `Command` is illustrative of the model (CBOR is the binary equivalent):

```json
{ "type": "cmd", "cid": 7, "op": { "type": "read",
  "device": { "value": "AA:BB:CC:DD:EE:FF" },
  "char": { "service": "180d…", "characteristic": "2a37…", "instance": 0 } } }
```

### 5.2 Envelope — `Frame`

Five frame types (discriminator `type`):

| `type` | Direction | Fields | Meaning |
|---|---|---|---|
| `cmd`   | client → agent | `cid: i64`, `op: Op` | A request (§7). |
| `reply` | agent → client | `cid: i64`, `result: OpResult` | The response to the `cmd` with the same `cid`. |
| `event` | agent → client | `event: AgentEvent` | Unsolicited (§8). |
| `hello` | client → agent | `minVersion: i32 = 1`, `maxVersion: i32 = 1`, `capabilities: [string] = []`, `identifierFormat?: string` | In-band handshake: the protocol-version range the client speaks and the capability strings it understands (§5.3). `identifierFormat` is one of §6.1's values. |
| `server_hello` | agent → client | `version: i32`, `capabilities: [string]`, `agentInfo?: string` | The agent's handshake answer: the chosen version and the negotiated capability set (§5.3). `agentInfo` is an optional human-readable engine/platform label for logs. |

Rules:
- `cid` is **client-assigned** and MUST be unique among the client's in-flight requests on a
  connection; the reference mints a per-session monotonically increasing `i64`. The agent MUST
  echo it verbatim in the matching `reply` and MUST NOT mint or reorder it.
- The agent MUST emit **exactly one** `reply` per `cmd` it accepts. (`Event`s carry no `cid`.)
- `Event`s are routed by ids inside the event (`scanId` / `subId` / `device`), not by `cid`.
- The `hello` frames carry no `cid`; a `hello` is answered by a `server_hello`, not a `reply`.

### 5.3 Handshake & capability negotiation (`hello` / `server_hello`)

Optional features beyond the §7/§8 baseline are gated by **capability strings** agreed in the
in-band `hello` exchange:

1. The client SHOULD send a `hello` as its first frame on every connection, declaring the
   protocol-version range it speaks and the capability strings it understands. A client
   requesting `identifier.translate` SHOULD also declare its `identifierFormat` (§6.1).
2. The agent MUST answer every `hello` with a `server_hello` carrying the protocol version it
   speaks and the negotiated capability set — `clientCapabilities ∩ agentSupported`. (With only
   protocol version 1 defined, the reference agents answer `1` unconditionally and do not yet
   inspect the client's declared range; version *selection* becomes meaningful only when a
   second protocol version exists. On a repeated `hello` the reply restates the first
   negotiation regardless of the range it declares — see first-hello-wins below.)
3. The agent MUST NOT advertise a capability it does not actually implement, and MUST NOT send
   a capability-gated event type to a client that has not negotiated that capability — an
   unnegotiated event type would break the client's decode loop. Negotiation does **not** gate
   op execution: an agent MAY serve a capability-specific op the client never negotiated (both
   reference agents do), and `UNSUPPORTED` (§9) signals only that the agent itself does not
   implement the op — so a client MUST gate capability-specific ops on the negotiated set
   rather than rely on rejection.

Properties:

- **Negotiation is lenient, not a gate.** A client MAY issue `cmd`s before — or without ever —
  sending a `hello`. The agent MUST serve such commands at the **v1 baseline**: empty
  capability set, untranslated handles. The baseline is read live, not latched per-command:
  an event stream opened before the hello (e.g. a scan) starts carrying translated handles
  once a later hello negotiates translation, so a client that interleaves commands with its
  `hello` must tolerate the switch — or simply send the `hello` first, as the reference
  client does.
- **First hello wins (per connection).** The negotiated set (and the §6.1 translation
  configuration) is fixed by the **first** `hello` the agent receives on a connection. A
  repeated `hello` MUST be answered idempotently — a `server_hello` carrying the
  already-negotiated set — and MUST NOT renegotiate: renegotiating mid-session could un-gate
  event types the client's decode loop no longer expects and invalidate handle translation
  already in force. A reconnect is a new connection and negotiates from scratch.
- **Unknown capability strings are harmless.** Capabilities are strings, not an enum: a decoder
  MUST accept capability strings it does not know; they simply never intersect. This is the
  forward-compat mechanism — new optional features ship without a version bump.
- The `hello` exchange carries **no auth credential or ownership id** — those stay on the
  upgrade headers (§4).

Capability strings are defined where their feature is specified (this spec defines
`identifier.translate`, §6.1); the reference registry is
[`Capabilities.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Capabilities.kt)
in `:protocol`.

## 6. Identifiers

| Id | Assigned by | Scope | Rules |
|---|---|---|---|
| `cid` | client | one connection | §5.2. Reply echoes it. |
| `DeviceHandle.value` | **agent** | agent | Opaque token (a MAC, a CoreBluetooth UUID, …). The client MUST treat it as **opaque** and MUST NOT parse, construct, or assume a format. The client MUST obtain it from a scan result (`AdvertisementDto.device`) or a handle it already holds. |
| `scanId` / `subId` | client | client session | **Session-global**, not per-peripheral — unique across the whole client session so two streams never collide. The client MUST reuse the *same* id when replaying a stream (§9). |

### 6.1 Handle translation (capability `identifier.translate`)

A `DeviceHandle.value` is minted in the **agent's** platform format, which a client on a different
platform may be unable to represent as its own local identifier. The optional, additive
`identifier.translate` capability lets the agent translate handles into the client's format:

- The client MAY request `identifier.translate` and declare its local format via the
  `hello` frame's `identifierFormat` field (§5.3) — one of `STRING`, `UUID`, `MAC_ADDRESS`, `BLUEZ_JSON`
  (a client that omits the field or the capability gets untranslated handles).
- When the capability is negotiated, the agent MUST mint every **outgoing** `DeviceHandle.value`
  (in `scan.result`/`scan.batch`, `conn.state`, `bond.state`) in the client's declared format, and
  MUST reverse-map handles on **incoming** ops back to the real radio device so routing is
  unaffected. The mapping MUST be stable for a given real handle within the client session.
- Translation is a per-client concern; the value stays **opaque** to the client (§6). Handles are
  still not comparable across clients — `.handle` semantics are unchanged.
- An agent MAY offer a **strict mode** in which it passes handles through untranslated even when the
  capability is negotiated (a diagnostic aid to surface client/agent format mismatches). Absent the
  capability, or under strict mode, a client whose local type can't hold the format MUST fall back
  to using the handle as opaque identity.
- **Resume interaction (leases, §10).** A reconnecting client replays ops carrying the translated
  handles its *previous* connection was issued. Translation synthesis is one-way, so an agent that
  implements both this capability and ownership leases MUST re-establish the reverse mappings for
  the leases the reconnecting client still holds — the reference agents re-mint them
  deterministically from the held real handles when the `hello` configures translation. Without
  this, reconcile-on-reconnect silently fails for translated clients. (After an agent restart
  there are no leases to re-seed from; a translated client must rescan — a documented limitation.)

This is a backward-compatible `1.x` addition: peers that don't name the capability are unaffected.

## 7. Operations (`Op`)

Every `Op` is a variant of `Command.op`. The agent MUST implement all of them. Unless stated,
a successful op replies `OpResult.Ok` with no payload; failures reply `OpResult.Err` (§9).

| `type` | Fields | Agent MUST | Reply payload |
|---|---|---|---|
| `scan.start` | `scanId: i64`, `filters: [ScanFilter]` | Begin scanning; emit a `scan.result` event (§8) per matching advertisement, tagged with `scanId`. Starting a `scanId` already active MUST replace (cancel + restart) it — **replay-safe**. The agent MUST apply `scan.start`/`scan.stop` for the **same** `scanId` in the order it received their `cmd`s, even when handling commands concurrently, so a client may pipeline a replacement without awaiting the first reply. Different `scanId`s need not be ordered. | `Ok` |
| `scan.stop` | `scanId: i64` | Stop the scan for `scanId` (no-op if unknown). Same-`scanId` receive ordering applies as for `scan.start`. | `Ok` |
| `connect` | `device: DeviceHandle` | Acquire ownership (§10) then establish the GATT connection. On success emit `conn.state = CONNECTED`. Connecting an already-connected device **owned by the same client** MUST be an **idempotent** `Ok` (no re-emit). | `Ok` |
| `disconnect` | `device: DeviceHandle` | Tear down the GATT link; emit `conn.state = DISCONNECTED`; start the lease release grace (§10.3). | `Ok` |
| `discover` | `device: DeviceHandle` | Discover services + characteristics. | `Ok{ services }` (`Services`) |
| `read` | `device`, `char: CharRef` | Read the characteristic value. | `Ok{ bytes }` (`Bytes`) |
| `write` | `device`, `char`, `value: bytes`, `withResponse: bool` | Write. `withResponse=false` is write-without-response (best-effort; no completion guarantee). The agent MUST apply writes to the **same** `device` in the order it received their `cmd`s, even when handling commands concurrently — a client may pipeline write-without-response writes without awaiting each reply, and relies on submission order reaching the radio. Writes to different devices need not be ordered. | `Ok` |
| `mtu` | `device`, `mtu: i32` | Request an MTU change; reply the negotiated value. | `Ok{ mtu }` (`Mtu`) |
| `observe.start` | `subId: i64`, `device`, `char` | Subscribe (CCCD); emit a `notification` event per value, tagged with `subId`. Re-issuing an active `subId` MUST replace it — **replay-safe**. | `Ok` |
| `observe.stop` | `subId: i64` | Unsubscribe `subId` (no-op if unknown). | `Ok` |

### Scan concurrency modes

An agent advertises exactly one of `scan.concurrency.multiplexed`,
`scan.concurrency.single`, or `scan.concurrency.uncontrolled`. In `multiplexed` and `single`,
logical scan ownership is `(stable client key, scanId)` and survives a transport drop through the
configured grace; replay of the same key rebinds it, while stale stop/expiry actions are no-ops.
`multiplexed` guarantees agent-side filter correctness and lifecycle isolation with bounded
best-effort delivery, not discovery completeness equal to an independent Apple scan. `single`
refuses a different key without disturbing its incumbent. `SCAN_UNAVAILABLE` is sent only when
`scan.concurrency.single` was negotiated; otherwise the legacy `AGENT_BUSY` error is used.
`uncontrolled` makes no cross-scan isolation guarantee.

`CharRef` = `{ service: uuid-string, characteristic: uuid-string, instance: i32=0 }`; `instance`
disambiguates duplicate-UUID characteristics. `ScanFilter` = `{ service?: uuid-string, name?: string }`.

Idempotency requirements (`connect`, `observe.start`, `scan.start`) exist so a client's
reconcile (§9) is safe whether or not the agent retained state across a blip.

## 8. Events (`AgentEvent`)

Unsolicited, agent → client, inside an `event` frame (discriminator `type`):

| `type` | Fields | When |
|---|---|---|
| `scan.result` | `scanId: i64`, `advertisement: AdvertisementDto` | One per (de-duplicated) advertisement matching an active scan. |
| `notification` | `subId: i64`, `value: bytes` | One per characteristic notification/indication on an active subscription. |
| `conn.state` | `device: DeviceHandle`, `state: BleConnState`, `reason?: AgentError` | The **physical** BLE link state changed. |

- `BleConnState` ∈ `{ CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }`.
- `AdvertisementDto` = `{ device: DeviceHandle, name?: string, rssi: i32, serviceUuids: [uuid-string]=[], manufacturerData: { i32 → bytes }={} }`. The agent **mints** `device` here.
- `conn.state` reflects the **BLE** link only. It is independent of the transport (§9). The
  agent MUST NOT emit a synthetic BLE state change merely because the transport dropped.

## 9. Result & error model

`OpResult` is `Ok{ payload?: ResultPayload }` (`type:"ok"`) or `Err{ error: AgentError }`
(`type:"err"`). `ResultPayload` variants: `bytes` (`{value: bytes}`), `services`
(`{services: [ServiceNode]}`), `mtu` (`{mtu: i32}`). `ServiceNode = {uuid, characteristics:
[CharNode]}`; `CharNode = {uuid, properties: i32, descriptors: [uuid-string]=[]}` where
`properties` is the standard GATT property bitmask.

`AgentError = { kind: ErrorKind, gattStatus?: i32, message?: string }`. The taxonomy splits on
**did the call reach the radio** — this is the information a caller needs to decide whether a
retry could help:

| `kind` | Group | Minted by | Meaning |
|---|---|---|---|
| `CONNECTION_FAILED` | reached radio | agent | The radio tried and the link failed. |
| `DISCONNECTED` | reached radio | agent | Operated on a link that dropped. |
| `GATT_ERROR` | reached radio | agent | Generic GATT-layer failure (`gattStatus` MAY be set). |
| `READ_FAILED` | reached radio | agent | Read rejected/failed at the peer. |
| `WRITE_FAILED` | reached radio | agent | Write rejected/failed at the peer. |
| `CHARACTERISTIC_NOT_FOUND` | reached radio | agent | `CharRef` did not resolve. |
| `NOT_CONNECTED` | reached radio | agent | Op needs a connection that isn't established. |
| `UNKNOWN_DEVICE` | never reached | agent | `DeviceHandle` not known to the agent. |
| `NO_CONNECTION_SLOT` | never reached | agent | The agent's per-client connection cap is full. |
| `PERIPHERAL_BUSY` | never reached | agent | The peripheral is owned by **another** client (§10). |
| `AGENT_BUSY` | never reached | agent | The agent transiently cannot service the op. |
| `UNSUPPORTED` | never reached | agent | The op/feature isn't supported by this agent. |
| `TIMEOUT` | never reached | **client** | No reply within the client's deadline (§11). |
| `TRANSPORT_LOST` | never reached | **client** | The link dropped with the request in flight. |

Rules:
- The agent MUST NOT emit `TIMEOUT` or `TRANSPORT_LOST` — by definition they describe the agent
  being *unreachable*, so the client mints them locally; everything else originates at the agent.
- `gattStatus` MUST only be present for "reached radio" kinds, carrying the raw BLE-stack status.
- The `ErrorKind` set is frozen for `1.x`; new kinds require a minor bump and clients MUST treat
  an unknown kind as a generic failure (fail the op; do not crash).

## 10. Peripheral ownership & leasing

BLE permits only one central↔peripheral link, and all clients share the agent's one radio. The
agent therefore **leases** each peripheral to one client at a time. This is the agent's core
stateful responsibility; an agent MUST implement it.

### 10.1 Exclusivity
- Each peripheral is **exclusive by default**: while client A holds a peripheral's lease, a
  `connect` from any other client B MUST be rejected with `PERIPHERAL_BUSY` **before** any radio
  call. `connect` by the current owner is idempotent `Ok` (§7).
- For the 0.9.0 conformance surface, exclusivity MUST remain enabled. A shared-mode extension is
  conformant only if it records every participant, scopes each participant's streams and grace,
  fans out physical disconnects, and tears down the physical link only after the last participant
  departs. Granting an untracked "guest" is non-conformant. The reference implementations MUST
  use exclusive mode in 0.9.0 until that model is implemented under the 0.9.1 plan.

### 10.2 Lease model
The lease lifecycle is uniform: an **"owner temporarily gone"** event schedules a per-lease
release timer; an **"owner back"** event cancels it. The cause only sets the delay and whether
the radio link is kept warm.

### 10.3 BLE-disconnect grace (`leaseGrace`)
On a BLE disconnect of an owned peripheral — explicit `disconnect` **or** an unsolicited drop
(the agent SHOULD detect these; the reference polls connection state) — the agent MUST schedule
release after a configurable `leaseGrace` (reference default **10 s**). If the owner reconnects
the peripheral within the window, the lease MUST persist; otherwise the agent MUST release it
(making it available to others). This debounces flaps and quick disconnect/reconnect cycles.

### 10.4 Transport-drop grace (`transportGrace`) & resume
When a client's **transport** (WebSocket) drops, the agent:
- MUST NOT immediately surrender the client's leases; it MUST keep the **radio link warm** and
  schedule release after a configurable `transportGrace` (reference default **10 s**).
- MUST allow a client that **reconnects with the same `X-RemoteBle-Client` id within the window**
  to **resume** — re-acquiring its own leases (its replayed `connect`s succeed as the owner, with
  no re-pair/rediscovery), which cancels the pending release.
- MUST release (and tear down the warm link) if no matching client returns before expiry.

Resume therefore **requires** the client identity of §4.2. A client that sends no identity
cannot be recognized on return; the agent MUST still release such a client's leases after
`transportGrace`, but a different connection from that client will be treated as a new owner
(and blocked by §10.1 until the grace elapses).

### 10.5 Configuration
`leaseGrace` and `transportGrace` MUST be operator-configurable. A conformant implementation may
expose ownership mode only after it implements the participant rules in §10.1. Specific knob
names/UI are not normative; the *behaviors* above are.

## 11. Client obligations (reconnect, reconcile, timeouts)

These hold up the agent guarantees above; a conformant client MUST:

1. **Identity.** Send a stable `X-RemoteBle-Client` id, unchanged across reconnects (§4.2), to
   be eligible for resume (§10.4).
2. **Reconcile on reconnect.** After the transport reconnects, re-issue — with their **original
   `scanId`/`subId`** — every `connect`, `observe.start`, and `scan.start` it believes is live.
   This relies on the agent's idempotency (§7). The replay set MUST be built from **successful**
   ops only, and a `disconnect` MUST drop that device's subscriptions from it.
3. **Timeouts.** Apply a per-request deadline and, on expiry, mint `TIMEOUT` locally (§9). On
   transport drop, fail all in-flight requests with `TRANSPORT_LOST`. The reference uses
   per-op-class deadlines (connect ~30 s, discover ~20 s, ordinary ops ~15 s) tuned for the
   relayed worst case; exact values are the client's choice.
4. **Two state machines.** Keep transport state and BLE state distinct (§8); a transport blip
   MUST NOT be surfaced as a BLE disconnect.

An agent MUST NOT assume a client does more than this, and MUST remain correct if a client
reconnects without reconciling (it simply forfeits resume).

## 12. Optional features (non-normative)

These do not affect conformance; an agent MAY provide them:
- A **status/observability surface** (the reference serves an HTTP dashboard at `/` and
  `GET /api/state`, plus the per-peripheral exclusivity toggle). Such a surface MUST be
  read-mostly and MUST NOT let a client bypass §10.
- Additional `Op`s/fields **beyond** this spec — only on a link that has negotiated a higher
  minor version both peers understand (§13). On a plain `1.0` link they MUST NOT appear.

## 13. Conformance checklists

**An agent MUST:**
- [ ] Serve binary, one-frame-per-message framing over the agreed transport (§3).
- [ ] Enforce bearer auth when configured (401 pre-upgrade) and accept the client-id header (§4).
- [ ] Encode/decode all §6 types with the frozen `@SerialName` discriminators and `type` key (§5).
- [ ] Echo `cid`; emit exactly one `reply` per accepted `cmd` (§5.2).
- [ ] Answer every `hello` with the negotiated intersection; first hello wins (a repeated
      `hello` MUST NOT renegotiate); serve pre-hello commands at the v1 baseline; never emit
      an unnegotiated capability-gated event (§5.3).
- [ ] Implement every `Op` with the stated semantics, payloads, and idempotency (§7).
- [ ] Emit `scan.result` / `notification` / `conn.state` events as specified; keep BLE state
      independent of transport state (§8).
- [ ] Use the §9 error taxonomy; never mint `TIMEOUT` / `TRANSPORT_LOST`; set `gattStatus` only
      for reached-radio kinds.
- [ ] Enforce exclusive ownership with `PERIPHERAL_BUSY`; do not expose shared mode without the
      participant model required by §10; honor `leaseGrace`, `transportGrace`, and identity-based
      resume (§10).

**A client MUST:**
- [ ] Treat `DeviceHandle` as opaque and obtain it from the agent (§6).
- [ ] Assign unique `cid`s and session-global `scanId`/`subId`; reuse ids when replaying (§6, §9).
- [ ] Send a stable client-id header to be resume-eligible (§4.2, §10.4).
- [ ] Reconcile on reconnect and mint `TIMEOUT`/`TRANSPORT_LOST` locally (§11).

## 14. Versioning

This spec is versioned `MAJOR.MINOR`.
- **MAJOR** bumps on any breaking wire change: a changed/removed `@SerialName`, a changed field
  meaning, or a changed mandatory behavior. Implementations of different MAJOR versions are not
  interoperable.
- **MINOR** bumps on backward-compatible additions (new `Op`/`ErrorKind`/event/field that older
  peers can ignore). A `1.x` agent and a `1.y` client MUST interoperate at the lower minor: each
  MUST ignore unknown additive types/fields rather than fail the connection.

The reference implementation tracks the latest `1.x`. When in doubt, the wire types in
[`:protocol`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol) are the source of
truth for *shape*; this document is the source of truth for *behavior*.
