# Per-principal write policy (U7)

Implemented on `feat/write-policy`, 2026-08-06. This remains the normative operator-facing shape
for the policy file; implementation and verification are recorded in
[`write-policy-progress.md`](write-policy-progress.md).

## Why this is the only real control

The `remoteble-tools` CLI has a `policy:` block in its own configuration, and its safety model is
blunt about what that block is worth:

> **CLI-side policy is advisory.** The `policy:` block lives in a YAML file on the same filesystem
> as the coding agent the CLI exists to serve. An agent that can run `remoteble` can also edit that
> file, select a different profile, or pass an overriding flag.

The agent accepts any client presenting valid credentials, so whatever the CLI refuses to do can be
done by a Kable app, `e2e-runner`, a second CLI with a different config, or a shell on the same
token. Only policy enforced **at the agent, keyed to the authenticating principal** holds regardless
of which client shows up.

The identity primitive already exists: `REMOTE_BLE_TOKENS='lab-a=secret-a,lab-b=secret-b'`
establishes per-principal credentials, and `ClientCredentials.sessionKey` already scopes every lease
by principal. The agent now consults that principal for every mutation before reaching the backend.

## Configuration: a JSON policy file

`REMOTE_BLE_POLICY_FILE=/etc/remoteble/policy.json`, read once at startup (Rust also accepts
`--policy-file`). JSON keeps the contract shared by Kotlin (`kotlinx.serialization`) and Rust
(`serde_json`; added to `agent-rs` for this feature). Both decoders are strict: unknown fields,
malformed JSON, an unknown version, an unknown principal, and a negative or out-of-range
`maximumBytes` fail startup before the BLE backend or listener starts. An unset path is permissive;
a supplied blank or whitespace-only path is also treated as unconfigured, with a startup warning.

```json
{
  "version": 1,
  "principals": {
    "lab-a": {
      "writes": [
        { "service": "0000180d-0000-1000-8000-00805f9b34fb", "characteristic": "00002a39-0000-1000-8000-00805f9b34fb", "maximumBytes": 1 },
        { "service": "0000180f-0000-1000-8000-00805f9b34fb", "characteristic": "*", "maximumBytes": 20, "withResponse": true }
      ],
      "descriptorWrites": [
        { "service": "0000180d-0000-1000-8000-00805f9b34fb", "characteristic": "00002a37-0000-1000-8000-00805f9b34fb", "descriptor": "00002902-0000-1000-8000-00805f9b34fb", "maximumBytes": 2 }
      ],
      "pairing": false
    },
    "ci": { "writes": [] }
  }
}
```

Matching is case-insensitive exact equality against the resolved wire-form UUIDs, or the explicit
wildcard `"*"`. A write rule matches `CharRef.service`, `CharRef.characteristic`, payload size, and
write type. A descriptor rule matches all of `DescRef.service`, `DescRef.characteristic`, and the
required `DescRef.descriptor`; granting one descriptor never grants its siblings. `maximumBytes` is
a nullable nonnegative signed 32-bit value: `null` is unlimited and `0` permits only an empty
payload. Existing operation limits still reject payloads above 512 bytes. Pairing is the separate,
coarse per-principal boolean because `desc.write` and `pair` are mutations too and a policy that
covered only `Op.Write` would be a door left open next to a locked one.

### Absent, empty, and unlisted are three different states

This is the part most likely to be got wrong, so it is stated as a rule:

| Configuration | Meaning |
|---|---|
| No path (`REMOTE_BLE_POLICY_FILE` unset or blank) | **Allow all writes.** A blank value logs a startup warning; an unset value is silent. |
| File present, principal unlisted | **Deny all writes** for that principal. |
| File present, principal listed with `"writes": []` | **Deny all writes** for that principal. |

An absent file and an empty rule set must never collapse into the same state. "I have not
configured this yet" and "I have configured this to permit nothing" are opposite intentions, and a
system that reads them the same way will eventually read one as the other.

Malformed JSON, an unknown field/version/principal, or an invalid byte bound **fails startup**
rather than degrading to allow-all — the same reasoning as `REMOTE_BLE_WRITE_FAIL_FAST`, whose
typo'd value also refuses to boot. A security control that silently broadens or disables itself on a
syntax error is worse than no control, because the operator believes it is on.

Portable policy files must use unique member names in every JSON object. Duplicate names are
invalid and unsupported: current Kotlin decoding can retain the last value, Rust rejects duplicate
DTO fields, and duplicate principal-map behavior is not guaranteed. Rejection hardening is deferred
work, so policy authors must not depend on either outcome.

## Enforcement point

After `authorizeConnected` and before the backend call, in the `Op.Write` / `Op.WriteDescriptor` /
`Op.Pair` / `Op.Unpair` branches of `BleAgent.handle`
([BleAgent.kt](../../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/BleAgent.kt)), mirrored
in `agent-rs`'s dispatch ([server.rs](../../agent-rs/src/transport/server.rs)).

Ordering matters: lease authorization first, then policy. A caller that does not own the peripheral
must get `PERIPHERAL_BUSY` whether or not policy would also have refused it, or the reply leaks
whether the policy permits a characteristic on a device the caller cannot touch.

The principal is the first half of the session key, which `BleAgent` already holds as `clientKey`.

## The error-kind hazard, which is the crux

A new `ErrorKind.POLICY_DENIED` **cannot simply be sent**. `ErrorKind` serializes by name, and an
unknown name fails a v1 client's CBOR decode — which is the documented reason `RADIO_OFF` is gated
behind a capability rather than emitted unconditionally
([Capabilities.kt](../../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Capabilities.kt)).
Adding a denial kind without that gate would turn a refused write into a broken decode loop.

So:

- Add capability `write.policy`. **Agent-level** — policy is bookkeeping, not radio behaviour, so
  §5.3's rule applies and every conforming agent must advertise it once implemented.
- A client that negotiated it receives `POLICY_DENIED` (non-transient — a retry cannot change an
  allowlist).
- A client that did not receives `INVALID_REQUEST`, which is already what an over-limit write
  returns today (`MAX_WRITE_BYTES`, BleAgent.kt) and is the closest existing kind: the request
  exceeded a configured bound.

`StatusSettingsDto.writePolicyEnforced` reports whether a policy file was configured, so a CLI can
tell an enforcing agent from a permissive one without a second round-trip.

## Denial messages must not enumerate the policy

A refused caller learns **that** it was refused, not the shape of the allowlist. "write not
permitted for this principal" — never "permitted characteristics are …", and never a diff against
what was attempted. The allowlist is operator configuration describing what other principals may
reach; probing it one write at a time should return no more information than probing it once.

This is the same reasoning `LeaseDisclosure` applies to holder identity, and the two should read as
one policy on disclosure rather than two independent judgements.

## Verification

The gate the CLI's own plan sets is explicit, and it is the right one:

> Test the same allowed/denied characteristic matrix through the CLI **and a second raw SDK client**
> so enforcement is proven independent of this executable.

`remoteble-tools` is a separate, paused repository, so this repository substitutes a raw
WebSocket/CBOR client for the unavailable packaged CLI. It and `DefaultAgentSession` run the same
allowed/denied matrix against the production Kotlin server. Rust runs the same named-principal
matrix through its WebSocket handshake harness. The external CLI matrix remains an integration
responsibility of `remoteble-tools`; neither client can influence agent enforcement.

Add to that:

- absent file, empty rule set, and unlisted principal produce the three distinct outcomes above;
- malformed, unreadable, unknown-field, negative-bound, and out-of-range files fail startup rather
  than booting permissive;
- exact and wildcard descriptor rules do not broaden to another descriptor on the same
  characteristic;
- a `POLICY_DENIED` reply reaches a client that negotiated `write.policy` and an `INVALID_REQUEST`
  reaches one that did not, both decoding cleanly;
- the denial arrives *after* `PERIPHERAL_BUSY` for a caller that owns neither the lease nor the
  permission.

## Deferred work

- **Live reload.** Read-once at startup is proposed. A `SIGHUP` reload is operationally nicer but
  adds a window where two connections disagree about policy mid-session; deferred rather than
  designed around.
- **Pairing shape.** `pair`/`unpair` are gated on both reference agents. Rust has no pairing
  backend today, so a policy-permitted call reaches `UNSUPPORTED`; a denied call remains denied
  before that answer. Whether pairing later needs a more granular policy remains open.
- **Phone agents.** Android and iOS construct `AgentConfig` with defaults and expose no operator
  control, which is the same gap that blocks their grace settings. A policy file on a phone needs a
  UI or a provisioning story that does not exist yet.
- **Duplicate-member rejection.** Unique JSON member names are required for portable policies, but
  the reference parsers do not yet reject every duplicate-member form consistently.
