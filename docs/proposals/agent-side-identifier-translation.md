# Enhancement: Agent-side device-handle translation (cross-platform `Identifier`)

[← back to index](../README.md)

- **Status:** Implemented in 0.8.0 (both the Kotlin agent and `agent-rs`). This document is the
  design of record; see the "As shipped" note below for what differs from the original proposal.
- **Type:** Capability-negotiated protocol extension + agent state
- **Relates to:** [client-sdk.md → `identifier` across platforms](../client-sdk.md#identifier-across-platforms--devicehandletoidentifier); the 0.7.0 interim (`RemoteIdentifierUnavailableException`)

---

## Summary

Make a remote peripheral's Kable `Identifier` work on **every** client platform regardless of
which platform the agent runs on, by translating device handles into each client's native
`Identifier` format **on the agent**. The client keeps using Kable's `Peripheral`/`Advertisement`
unchanged; the agent maintains a per-client reverse map so operations keyed on the translated
handle still route to the real radio device.

## Motivation

Kable's `Identifier` is a **local-platform** concept — Android `String`, Apple `Uuid`, and on the
JVM the host radio's native id (authoritative: [`kable-btleplug-ffi/src/peripheral_id.rs`](https://github.com/JuulLabs/kable/blob/main/kable-btleplug-ffi/src/peripheral_id.rs)):

| Client platform / JVM host | `Identifier` string format | Constructor |
|---|---|---|
| Android | any string | typealias `String` |
| Apple, macOS-host JVM | UUID | `Uuid::parse_str` |
| Windows-host JVM | MAC address | `BDAddr::from_str` |
| Linux-host JVM | btleplug bluez `PeripheralId` **JSON** | `serde_json::from_str` |

A remote handle's format is decided by the **agent's** platform (a macOS agent mints UUIDs). So
whenever the client's local `Identifier` type can't hold the agent's format, reading `.identifier`
fails — e.g. a Linux-host JVM client + a macOS agent. Only the Android client is format-agnostic.
Because "app code written against Kable runs unchanged" is the core bet, `.identifier` throwing on
a legitimate cross-platform pairing is a real gap. The 0.7.0 interim makes that failure clean and
catchable (`RemoteIdentifierUnavailableException`, steering to `.handle`); this proposal removes it.

## Why translate on the agent, not the client

The client fundamentally **cannot construct** a foreign-format `Identifier` (the JVM type's only
constructor is a native, host-specific parser). Moving translation to the agent:

- keeps **client changes near zero** — the client receives handles already in its native format, so
  `deviceHandleToIdentifier` reverts to the naive `value.toIdentifier()`;
- makes **strict mode a coherent agent/dashboard setting** — translation is genuinely an agent
  function, so toggling it server-side is natural (vs. an agent flag mysteriously controlling
  client-side exception behavior).

The translation is pure computation (the agent may run on a different OS than the client, so it
can't borrow a local radio FFI), which is feasible for every target *except* the Linux-host JVM's
internal bluez JSON — see [Linux-JVM](#linux-jvm-format).

## Design

### 1. Format declaration (handshake)

The client declares its `identifierFormat` during the existing handshake / capability negotiation.
On the JVM the client resolves the concrete host format at runtime:

```
enum IdentifierFormat { String, Uuid, MacAddress, BluezJson }
```

### 2. Forward translation (agent → client)

For each real handle `H`, the agent computes `clientHandle = synth(H, format)` via a **pluggable
per-format synthesizer registry**, deterministically (e.g. UUIDv5 over `H` with a fixed namespace,
or a hash-derived MAC). All outgoing handles (advertisements, peripheral identity) carry
`clientHandle`. Forward is deterministic, so it needs no storage.

### 3. Reverse map (client → agent)

The agent keeps a per-client `clientHandle → H` table so ops arriving keyed on `clientHandle` route
to the real device. Scope it to the client's **session + active peripheral leases**; evict past the
disconnect grace window. Size is bounded by the number of live peripherals per client. (Forward is
recomputable on rediscovery; the reverse direction isn't invertible from a hash, so this map is the
one piece of required state.)

### 4. Strict mode (dashboard toggle)

When strict mode is enabled on the agent, it **passes real handles through untranslated**. The
client's `toIdentifier()` then succeeds only on a format match and otherwise surfaces the interim
`RemoteIdentifierUnavailableException` — useful in dev/CI to make cross-platform mismatches loud.

### 5. Synthesizer registry — pluggable, ship incrementally

`String` (Android), `Uuid` (Apple / macOS-JVM) and `MacAddress` (Windows-JVM) are synthesizable as
plain strings. <a id="linux-jvm-format"></a>**`BluezJson` (Linux-host JVM)** requires emitting
btleplug's internal bluez `PeripheralId` JSON, which couples the agent to btleplug's serde shape.
It is **registered but stubbed** initially → the Linux-JVM client falls back to `.handle` identity
(the 0.7.0 behavior). A future increment can add a format-pinned, unit-tested `BluezJson`
synthesizer if demand appears.

## Non-goals

- Changing how ops are addressed (they already key off `DeviceHandle`).
- Making `.identifier` semantically meaningful as a *radio address* on the client — it remains an
  opaque, stable, per-peripheral token; `.handle` is the documented portable identity.

## Testing

- Round-trip each synthesizer (`synth` then reverse-map) per format.
- Agent reverse-map lifetime: eviction on disconnect past grace; op routing after translation.
- Strict mode: agent passthrough → client surfaces the clear exception on a mismatch.
- Linux-JVM stub: `.handle` fallback; no attempt to synthesize bluez JSON.

## As shipped (0.8.0)

What differs from the proposal above, as implemented:

- **Capability gate:** `Capabilities.IDENTIFIER_TRANSLATION = "identifier.translate"`, negotiated the
  usual way; `ClientHello` gained an optional `identifierFormat` field. `PROTOCOL_VERSION` stays `1`
  (purely additive). The client always requests translation and declares its format.
- **Identity fast-paths:** translation is skipped (handles pass through) when the client is `STRING`
  (Android holds any string), when the client's format equals the agent's own minted format
  (same-platform pairing), or when the format is the stubbed `BLUEZ_JSON`. Only a genuine
  `UUID`/`MAC_ADDRESS` mismatch is rewritten — so same-platform setups are byte-for-byte unchanged.
- **Client change:** the client's `deviceHandleToIdentifier` keeps its 0.7.0 fail-soft
  `RemoteIdentifierUnavailableException` rather than reverting to a naive `toIdentifier()`, because
  the exception is still the correct outcome under strict mode, the `BLUEZ_JSON` stub, and pre-0.8.0
  agents. With translation active it simply never fires.
- **Synthesizers:** deterministic, dependency-free — a UUIDv5-shaped value and a locally-administered
  MAC, both derived from a non-cryptographic 128-bit digest of the real handle. Cross-language hash
  agreement is *not* required (each agent only ever translates its own radio's handles), only
  per-agent determinism, so the Kotlin and Rust digests need not match byte-for-byte.
- **Strict mode:** the Kotlin agent exposes it as a live dashboard toggle (`POST /api/strict`, shared
  `StrictModeState`); `agent-rs` exposes it as a process flag (`--strict-identifiers` / env).
- **Rust parity:** `agent-rs` implements the full translator (`src/translate.rs`), so both agents
  behave identically on the wire.

## Reconnect & reconcile (added 2026-07-10)

A gap found in review, fixed in both agents plus the client — **no wire change**:

- **The gap.** The reverse map is per-connection and fills only when an *outgoing* event carries a
  handle. Reconcile-on-reconnect replays ops with the handles the client was *previously issued* —
  translated ones, under an active translation. On the fresh connection nothing had populated the
  reverse map yet (synthesis is a one-way digest, so the agent cannot invert an incoming handle),
  and the replayed `connect` reached the backend as an unknown device: **a transport blip
  permanently broke resume for exactly the cross-platform pairings translation exists for.**
  Compounding it, the client used to launch its hello and its reconcile replay as unordered
  coroutines, so replayed ops could even arrive before translation was configured at all.
- **The fix — deterministic re-seeding.** Synthesis is a pure digest of the real handle, so the
  agent can *re-mint* (not invert). On the handshake, after configuring the translator, the agent
  primes the reverse map from the real handles the `PeripheralRegistry` still holds for this
  `clientKey` (`registry.heldBy(clientKey)` / `held_by(client_id)`): those warm leases —
  live links plus the `transportGrace` window — are exactly the handles a reconciling client can
  replay. `HandleTranslator.prime(realHandles)` re-runs the same synthesis `outgoing` would and
  records the mappings. The client now also sends its hello and its reconcile replay from **one**
  coroutine, hello first — frame order on the socket guarantees the agent configures (and primes)
  translation before any replayed op arrives.
- **Conformance note:** an agent that implements both `identifier.translate` and ownership leases
  must re-seed on resume this way, or reconcile silently breaks for translated clients
  (agent-proxy-spec §6.1).
- **Remaining limitation (accepted):** after an **agent restart** there are no leases to seed from,
  so a replayed translated handle cannot route — the client must rescan. Same-platform pairings are
  unaffected (identity fast-path; handles pass through and stay valid wherever the OS keeps them
  stable across agent restarts). Fixing the restart case would need the wire to carry real handles
  back to the client (rejected in this proposal's non-goals) or persistent agent state.
