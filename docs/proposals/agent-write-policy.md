# Per-principal write policy (U7)

Design note, 2026-08-05. No code on `feat/cli-readiness` — this exists so the shape is agreed
before four agents implement it, the same way U3's status contract was settled before it was built.

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
by principal. What is missing is anything that consults the principal when a write is dispatched.

## Configuration: a JSON policy file

`REMOTE_BLE_POLICY_FILE=/etc/remoteble/policy.json`, read once at startup. JSON rather than YAML
because it parses with `kotlinx.serialization` and `serde_json` with **no new dependency on either
agent**, and the two must read byte-identical files.

```json
{
  "version": 1,
  "principals": {
    "lab-a": {
      "writes": [
        { "service": "180d", "characteristic": "2a39", "maximumBytes": 1 },
        { "service": "180f", "characteristic": "*", "maximumBytes": 20, "withResponse": true }
      ],
      "descriptorWrites": [],
      "pairing": false
    },
    "ci": { "writes": [] }
  }
}
```

Matching is on the resolved `CharRef` — service UUID, characteristic UUID, payload size, and write
type — plus the descriptor and pairing surfaces, because `desc.write` and `pair` are mutations too
and a policy that covered only `Op.Write` would be a door left open next to a locked one.

### Absent, empty, and unlisted are three different states

This is the part most likely to be got wrong, so it is stated as a rule:

| Configuration | Meaning |
|---|---|
| No `REMOTE_BLE_POLICY_FILE` | **Allow all writes.** Today's behaviour, so no existing consumer breaks on upgrade. |
| File present, principal unlisted | **Deny all writes** for that principal. |
| File present, principal listed with `"writes": []` | **Deny all writes** for that principal. |

An absent file and an empty rule set must never collapse into the same state. "I have not
configured this yet" and "I have configured this to permit nothing" are opposite intentions, and a
system that reads them the same way will eventually read one as the other.

Malformed JSON, an unknown `version`, or an unknown principal name **fails startup** rather than
degrading to allow-all — the same reasoning as `REMOTE_BLE_WRITE_FAIL_FAST`, whose typo'd value also
refuses to boot. A security control that silently disables itself on a syntax error is worse than
no control, because the operator believes it is on.

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

`StatusSettingsDto.writePolicyEnforced` already exists in the `agent.status` reply for this, wired
`false` on both agents. It becomes the honest answer once policy lands, so a CLI can tell an
enforcing agent from a permissive one without a second round-trip.

## Denial messages must not enumerate the policy

A refused caller learns **that** it was refused, not the shape of the allowlist. "write not
permitted for this principal" — never "permitted characteristics are …", and never a diff against
what was attempted. The allowlist is operator configuration describing what other principals may
reach; probing it one write at a time should return no more information than probing it once.

This is the same reasoning `LeaseDisclosure` applies to holder identity, and the two should read as
one policy on disclosure rather than two independent judgements.

## Test plan

The gate the CLI's own plan sets is explicit, and it is the right one:

> Test the same allowed/denied characteristic matrix through the CLI **and a second raw SDK client**
> so enforcement is proven independent of this executable.

So the matrix runs twice against one agent: once through the packaged CLI, once through a bare
`DefaultAgentSession`. A test that only drives the CLI proves the CLI refuses things, which is
precisely the advisory property this work exists to replace. Both agents run the same matrix, since
a control that holds on the JVM agent and not the Rust one is not a control.

Add to that:

- absent file, empty rule set, and unlisted principal produce the three distinct outcomes above;
- a malformed file fails startup rather than booting permissive;
- a `POLICY_DENIED` reply reaches a client that negotiated `write.policy` and an `INVALID_REQUEST`
  reaches one that did not, both decoding cleanly;
- the denial arrives *after* `PERIPHERAL_BUSY` for a caller that owns neither the lease nor the
  permission.

## Open questions

- **Live reload.** Read-once at startup is proposed. A `SIGHUP` reload is operationally nicer but
  adds a window where two connections disagree about policy mid-session; deferred rather than
  designed around.
- **Whether `pair`/`unpair` belong in the same file or their own switch.** They are mutations, so
  they are in scope here, but they are coarse (one boolean per principal) where writes are
  fine-grained, and mixing granularities in one schema may not age well.
- **Phone agents.** Android and iOS construct `AgentConfig` with defaults and expose no operator
  control, which is the same gap that blocks their grace settings. A policy file on a phone needs a
  UI or a provisioning story that does not exist yet.
