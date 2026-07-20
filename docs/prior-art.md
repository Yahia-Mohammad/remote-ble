# Prior Art & Design Comparisons

[← back to index](README.md)

RemoteBLE's core idea is **not original** — it is the architecture that
[ESPHome's Bluetooth Proxy](https://esphome.io/components/bluetooth_proxy/) established for
Home Assistant. This document credits that prior art in detail (Part A), and — using ESPHome as
a foil, since it faced a near-identical problem and chose differently — explains RemoteBLE's
serialization choice of CBOR over Protobuf (Part B). Both parts are written to be honest about
where RemoteBLE is weaker, not just where it differs. Companion to
[design-decisions.md](design-decisions.md).

This project is independent and not affiliated with or endorsed by the ESPHome or Home Assistant
projects. No ESPHome or Home Assistant **code** is used; the inspiration is architectural.

---

## Part A — RemoteBLE vs. ESPHome Bluetooth Proxy

ESPHome's Bluetooth Proxy was the original inspiration for RemoteBLE, and the resemblance is not
superficial: both arrive at the same core trick. Understanding where they converge — and, more
usefully, where they diverge — clarifies what RemoteBLE is actually for.

### The shared idea: backend substitution at the GATT layer

The move both systems make is to plug in *underneath* an existing, standard BLE abstraction, so
that code written for a local radio runs unchanged against a remote one.

- **ESPHome** presents each proxy to Home Assistant as another Bluetooth adapter via HA's
  `bleak` / `habluetooth` backend. Device integrations written against local BlueZ work
  identically whether the radio is a USB dongle on the HA host or an ESP32 across the house.
- **RemoteBLE** has `RemotePeripheral` / `RemoteScanner` implement Kable's own `Peripheral` /
  `Scanner` interfaces. App code written against Kable runs unchanged whether the peripheral is
  local or driven by a remote agent — [design-decisions.md](design-decisions.md#the-central-bet-program-against-kable-swap-the-implementation)
  calls this "the central bet."

The parallel is close to one-to-one:

```
ESPHome proxy  :  HA bleak/habluetooth backend
RemoteBLE      :  Kable Peripheral / Scanner

  ┌──────────────────────┐          ┌──────────────────────┐
  │  Home Assistant      │          │  App code            │
  │  integration         │          │  (unchanged)         │
  │        │             │          │        │             │
  │  bleak backend       │          │  Kable Peripheral    │
  │  (local BlueZ  OR    │          │  (Local  OR          │
  │   ESPHome proxy)     │          │   RemotePeripheral)  │
  └────────┼─────────────┘          └────────┼─────────────┘
           │  protobuf/TCP                   │  CBOR/WebSocket
           ▼                                 ▼
  ┌──────────────────────┐          ┌──────────────────────┐
  │  ESP32 proxy firmware│          │  Agent process       │
  │  → radio             │          │  → radio (btleplug / │
  │                      │          │    CoreBluetooth /   │
  │                      │          │    Android BLE)      │
  └──────────────────────┘          └──────────────────────┘
```

Both tunnel at the **GATT-operation level** (connect, discover, read, write, subscribe, plus
advertisement/scan streaming) — not raw HCI (impractical over a lossy IP hop) and not decoded
semantic values (too narrow). Both keep a command/reply shape plus a separate unsolicited-event
stream for notifications and advertisements. Both had to solve what happens to a live GATT
connection when the IP link blips.

### The divergence axis: where the agent runs

Almost every meaningful difference traces back to one fact — the host the agent runs on.

ESPHome's proxy is **ESP32 microcontroller firmware**: bare-metal, ~1 KB of RAM per connection
slot, a default of 3 concurrent GATT connections (realistically capped around 5). RemoteBLE's
agent needs a **real OS Bluetooth stack** — `btleplug` requires a Pi-class host or larger, or a
phone via CoreBluetooth / Android BLE. That single difference cascades:

| Dimension | ESPHome Bluetooth Proxy | RemoteBLE |
|---|---|---|
| **Agent host** | ESP32 microcontroller firmware | Real-OS process: JVM+`btleplug` on macOS/Linux/Pi, native Rust agent, or Android/iOS |
| **Consumer / seam** | HA `bleak` / `habluetooth` backend | Kable `Peripheral` / `Scanner` |
| **App-code-unchanged** | Yes (HA integrations) | Yes (Kable app code) |
| **Tunneling level** | GATT ops + raw advertisements | GATT ops + advertisements |
| **Wire protocol** | Length-prefixed **Protobuf** over TCP (port 6053) | Length-prefixed **CBOR** over WebSocket |
| **Security** | Noise encryption key required as of [ESPHome 2026.1.0](https://esphome.io/changelog/2026.1.0/) (plaintext password auth removed) | Optional bearer token enforced at the WebSocket handshake |
| **Scan modes** | Passive vs. active split (active gates GATT + scan-response) | Always full op-set |
| **Connection slots** | Explicit, default 3, ~1 KB RAM each, ~5 practical max | Bounded by host; surfaced via the `slots` capability, not a headline constraint |
| **Multi-agent fan-out** | Mature — HA aggregates every proxy + local adapters and routes connections across them | One agent endpoint per client session; a transparent [AgentProxy](proposals/agent-proxy.md) is designed but deferred beyond 0.10.0 ([intentional v1 cut](design-decisions.md#single-agent-multiple-clients)) |
| **Device ownership / sharing** | None — HA is the sole controller | [Per-peripheral leasing](design-decisions.md#peripheral-ownership) (exclusive by default), grace windows, ownership survives reconnect |
| **Reconnection** | Yes | [Reconcile-on-reconnect](design-decisions.md#reconnection); IP-transport and BLE state kept as two independent state machines |
| **Second implementation** | ESPHome firmware + HA client | Two agents (Kotlin/Kable + Rust/`btleplug`), byte-identical CBOR, plus a [normative wire spec](agent-conformance-spec.md) |
| **Primary purpose** | Extend HA's BLE reach across a home, cheaply | Dev tooling: emulator/CI testing, remote-lab access, device sharing, retrofitting remote access onto Kable apps |
| **Ecosystem coupling** | Tightly coupled to Home Assistant | Standalone SDK; no assumptions about the consuming app |

### What follows from that

- **Cost and density.** An ESP32 proxy is a sub-$10 board you scatter around a house for
  coverage — the entire ESPHome value proposition is "cheap Bluetooth range extender, many of
  them." A RemoteBLE agent is a whole machine. RemoteBLE is not competing on "sprinkle eight of
  these around a building."
- **Multi-agent fan-out (ESPHome's advantage).** Because ESPHome expects many cheap proxies, HA
  has a mature routing layer that aggregates all proxies plus local adapters, picks which proxy
  owns a connection, and balances slots. RemoteBLE explicitly ships "one agent per deployment, no
  registry" as a v1 cut. This is the single biggest maturity gap in ESPHome's favor — and it is a
  direct consequence of ESPHome *needing* fan-out where RemoteBLE does not yet.
- **Ownership / leasing (RemoteBLE's advantage).** Conversely, RemoteBLE's per-peripheral leasing
  — exclusive by default, grace windows, client-id survival across reconnect — is something
  ESPHome essentially lacks, because HA is the single controller and there is no "share one scarce
  device across a team of independent clients" problem to solve.
- **Purpose and coupling.** ESPHome's proxy is a feature of a home-automation ecosystem,
  wire-locked to Home Assistant as the consumer. RemoteBLE is a general-purpose SDK whose consumer
  is arbitrary app code — the test/CI/lab/sharing framing has no ESPHome analogue.
- **Passive vs. active.** ESPHome splits passive (advertisement forwarding only, the cheap
  constant hot path) from active (full GATT, gated behind `active: true` and a slot budget)
  because on a microcontroller that cost gradient is decisive. RemoteBLE can afford to always
  expose the full op-set.

### One idea worth borrowing: connection-parameter tuning

ESPHome added a **BLE connection-parameters API** — proxies can renegotiate the connection
interval mid-connection (fast during setup, slow afterward to spare a peripheral's battery on
always-connected devices like locks). This is adjacent to RemoteBLE's Android-only
[`conn.priority`](protocol.md#operations--op) capability, which today maps to Android's coarse
priority buckets. It was generalized into a cross-engine `conn.params` capability in **0.8.2** —
with the honest caveat that the engine support for fine-grained intervals on RemoteBLE's target
platforms (`btleplug`, CoreBluetooth) is far more constrained than on an ESP32, so on today's
engines it is realistically only the coarse Android path (the `hint` field is reserved but unused). See
[proposals/connection-parameters.md](proposals/connection-parameters.md) for the full
treatment: motivation, the per-engine support matrix, the op, and why it is "generalize
an existing partial capability" rather than "add a feature that works everywhere."

---

## Part B — CBOR vs. Protobuf (and why CBOR here)

ESPHome is a useful foil for this question too: it faced a near-identical problem — stream GATT
operations and advertisements over IP — and chose **Protobuf**, while RemoteBLE chose **CBOR**.
That divergence is not arbitrary; it falls out of the same host-constraint axis as everything in
Part A. (See also [design-decisions.md](design-decisions.md#cbor-by-default-json-for-debugging).)

### What each is

- **CBOR** ([RFC 8949](https://www.rfc-editor.org/rfc/rfc8949)) is a binary, self-describing
  format. A CBOR map carries its keys inline; bytes decode into a structured value without an
  external schema. In kotlinx-serialization's mapping, class properties become map entries keyed
  by their (string) names.
- **Protobuf** is a schema-first binary format. The `.proto` file is the contract; the wire
  carries integer field *tags* plus wire-types and is meaningless without the schema. Field names
  are never transmitted.

### Dimension by dimension

| Dimension | CBOR | Protobuf |
|---|---|---|
| **Schema model** | Schemaless / self-describing; decodes without a `.proto` | Schema-first; wire is meaningless without the schema |
| **Field identity on the wire** | Map keys (string names by default) travel in every message | Integer field tags only; names never sent |
| **Wire size** | Larger — string keys repeated per message, looser packing | Smaller, especially for numeric-heavy payloads |
| **Encode/decode CPU** | Moderate, general-purpose | Very low, hand-optimizable to microcontroller budgets |
| **Schema evolution** | Add fields freely; unknown fields decode as ignorable generic values | Add fields freely, but renumbering breaks everything; discipline enforced by field numbers |
| **Cross-language tooling** | Simple; no codegen step (ciborium in Rust, kotlinx CBOR in Kotlin) | Excellent and ubiquitous, but needs the `.proto` + codegen in every language |
| **Human-debuggability** | Poor raw, but maps trivially to/from JSON with the same object graph | Poor raw; needs the schema even to pretty-print |

The headline trade is **schemaless flexibility vs. schema-enforced compactness.** Protobuf pays
for its small wire and cheap CPU with a mandatory out-of-band contract and a codegen step; CBOR
pays for its zero-ceremony flexibility with fatter messages and no compiler forcing the two ends
into sync.

### Why CBOR was the right call *for this project*

In rough order of weight:

1. **It comes free from the codec already in use.** RemoteBLE's protocol types are
   `@Serializable` kotlinx classes with frozen `@SerialName` discriminators. That one annotation
   set yields both CBOR (production) and JSON (debugging) from the *same* types, the *same* codec,
   and the *same* polymorphic-discriminator logic. Protobuf would mean a parallel `.proto` schema,
   a codegen step, and reconciling proto's type model against a sealed Kotlin hierarchy. The
   "JSON for debugging, CBOR for the wire" duality is nearly free with CBOR and awkward with
   Protobuf.
2. **Forward-compat doesn't lean on the serializer.** Capabilities are negotiated as a
   `Set<String>` where unknown strings degrade harmlessly, and the protocol is explicitly
   versioned. RemoteBLE therefore does not rely on Protobuf's field-number discipline to manage
   evolution — the compatibility story lives a layer up. Protobuf's single biggest structural
   advantage over CBOR (compiler-enforced schema evolution) is one RemoteBLE largely neutralized
   by design.
3. **Cross-language interop worked without codegen.** The byte-for-byte CBOR interop between the
   Kotlin codec and the Rust agent (ciborium) is exactly where CBOR's self-describing nature
   shines: two independent implementations agreeing on a self-describing format, verified by
   round-tripping, no shared generated code. The [normative conformance spec](agent-conformance-spec.md)
   already plays the role Protobuf's `.proto` would.
4. **The wire-size and CPU penalties don't bite.** This is where ESPHome is the perfect contrast.
   ESPHome invests heavily in Protobuf-encoding CPU optimization on ESP32 — because on a
   microcontroller forwarding a firehose of advertisements, every byte and cycle is decisive, and
   Protobuf's compactness plus hand-tunable C encoders wins outright. **RemoteBLE does not run on
   a microcontroller.** Its agent is a Pi-class host or larger; CBOR's larger frames and
   general-purpose codec are noise against a real CPU and network stack. The constraint that makes
   Protobuf obvious for ESPHome is simply absent here.

**Verdict: CBOR was the right decision** — not because it beats Protobuf in the abstract (it
doesn't, on wire size or CPU), but because the axes where Protobuf wins are exactly the ones
RemoteBLE's architecture already de-risked (evolution → capability negotiation; contract →
conformance spec) or never cared about (CPU/bytes → not an ESP32), while the axis CBOR wins on (a
zero-ceremony shared codec with a free JSON debug path from the same `@Serializable` types) is
worth real money to a small team.

### Honest caveats

- **String keys are a standing tax.** kotlinx CBOR emits field *names* as map keys, so every frame
  carries its schema inline — meaningfully heavier than Protobuf on small frames. See the lever
  below.
- **No compiler enforcing the contract.** `@SerialName` discriminators being "the actual wire
  identity" is a correctness landmine that Protobuf field numbers would guard structurally.
  Mitigated by frozen-discriminator discipline and the cross-language interop tests, but it is
  discipline, not a compiler — worth a golden-file test over the discriminator set so a rename
  can't silently break the wire.
- **A third serialization target** (e.g. a browser/JS client) would favor Protobuf's broader
  tooling. Not the current situation.

### The one lever that narrows the gap: `@CborLabel`

*(Not implemented — documented so the option is understood.)* If scan-stream bandwidth ever
becomes a measured problem, kotlinx CBOR can encode integer keys instead of string names, giving
CBOR the "field numbers, not names" compactness Protobuf has natively — **without giving up the
JSON debug path**:

```kotlin
@Serializable
@SerialName("cmd")
data class Command(
    @CborLabel(1) val cid: Long,
    @CborLabel(2) val op: Op,
)

val wireCbor = Cbor { preferCborLabelsOverNames = true }  // {1: 42, 2: {…}} on the wire
val debugJson = Json { /* … */ }                          // still {"cid":…, "op":…}
```

A small integer label costs a single byte versus several for a short string key — saving roughly
5–7 bytes per field per frame, which can roughly halve framing overhead on tiny advertisement
frames (though it is noise on large characteristic payloads). JSON ignores `@CborLabel` entirely,
so the diagnostic path stays readable.

**Costs, and why it's back-pocket rather than default:**

- The labels become a **frozen wire contract**, identical in kind to Protobuf field numbers — they
  must go in the conformance spec and warrant a golden-file test.
- It **complicates the Rust interop.** ciborium/serde maps struct fields to *string* keys, and
  serde's `rename` takes strings, not integers, so matching integer-keyed maps requires
  hand-written `Serialize`/`Deserialize` impls on every protocol struct. The interop that
  currently "just works" from matching field names becomes hand-maintained — spending some of the
  very thing that made CBOR attractive.

Given the agents run on real hosts over WiFi/Ethernet rather than coin-cell radios over
constrained links, this lever likely never rises to the top of the list.

---

## Summary

RemoteBLE and ESPHome's Bluetooth Proxy share a genuinely clever core — backend substitution at
the GATT layer so app code runs unchanged across local and remote radios — but differ on nearly
everything downstream because ESPHome targets cheap microcontrollers and RemoteBLE targets
real-OS hosts. That same axis explains the serialization split: Protobuf's byte- and cycle-thrift
is decisive on an ESP32 and irrelevant on a Pi, so RemoteBLE could take CBOR's zero-ceremony,
JSON-debuggable, codegen-free ergonomics without meaningful cost. Both are coherent responses to
different constraints, not a right/wrong split. Credit where due — the core substitution move is
ESPHome's.
