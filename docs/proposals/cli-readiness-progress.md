# CLI-readiness branch: what landed, what is left

Working note for `feat/cli-readiness`, last updated 2026-08-10. It exists so the next person on
this branch — or the same person a week later — does not have to re-derive the scope decisions or
re-verify what already passed. The changes themselves are described in `CHANGELOG.md` under
`[Unreleased]`; this file is about state and intent.

## Why this branch exists

The `remoteble-tools` CLI is a process-per-command client of this agent: one process per command,
each expecting the next to resume the same connection. Reviewing its implementation plan against
the `v0.10.0` tree turned up gaps that are invisible to every existing consumer — Kable apps hold
one long-lived session, so none of them exercises the seams a short-lived process does.

The CLI's plan numbers its upstream asks U1–U7 (`remoteble-tools/docs/implementation-plan.md`).
This branch is the upstream half.

## Landed here

| Item | Change | Where |
|---|---|---|
| Transport grace | default 10 s → 120 s, JVM and Rust | `AgentModule.kt`, `PeripheralRegistry.kt`, `main.rs`, `peripheral_lease.rs` |
| Lease denial (U5) | holder named under a disclosure policy | `LeaseDisclosure.kt`, `lease_disclosure.rs` |
| Slot accounting (U4) | agent-wide, lease-aware, reported at handshake | `PeripheralRegistry.kt`, `BleAgent.kt` |
| Identifier format (U6) | client may declare its own | `AgentSession.kt` |
| Capability levels | agent-level vs backend-level written into the spec | `agent-conformance-spec.md` §5.3 |
| Agent-level capabilities | unconditional in `agent-rs`; the whole set now matches | `frame.rs`, `negotiation.rs` |
| `slots` | advertised and emitted by `agent-rs` | `server.rs`, `peripheral_lease.rs` |
| `scan.batch` | implemented in `agent-rs` | `server.rs`, `frame.rs`, `RustAgentInteropTest.kt` |
| `descriptors` | implemented in `agent-rs`; discovery reports them | `backend.rs`, `btleplug_impl.rs`, `server.rs` |
| Slot cap default | aligned at 8, both agents | `BleAgent.kt`, `peripheral_lease.rs` |
| Warm resume (U2 half) | replayed `connect` no longer re-drives a live radio link | `BleAgent.kt`, `server.rs`, both registries |
| Status contract (U3) | `agent.status` op, both agents, caller-scoped disclosure | `AgentStatus.kt`, `status.rs`, `BleAgent.kt`, `server.rs` |
| Operator scope | `X-RemoteBle-Operator` on the upgrade; `agent-rs` gained an operator token | `AgentWebSocketServer.kt`, `server.rs`, `main.rs` |
| Grace deadlines | both registries now track *when* a release fires, not just that one is pending | `PeripheralRegistry.kt`, `peripheral_lease.rs` |
| Holder diagnostics (U5, rest) | `lease.holder` capability + structured `AgentError.holder` | `Capabilities.kt`, `Errors.kt`, `LeaseDisclosure.kt`, `lease_disclosure.rs` |
| Device-scoped write rules (U7, rest) | optional `device` on both rule kinds, default `"*"` | `WritePolicy.kt`, `write_policy.rs` |

**No capability now differs between two agents on the same host.** That was not the original scope
— it grew out of the judgement that differing capability support between the reference agents is
itself a disparity, not a platform fact — and it is what §5.3's two rules now require of anyone
writing a third agent.

### Verification

`./gradlew build` green; `cargo test` 188 passed; `cargo clippy --all-targets` and
`cargo fmt --check` clean. Note there is **no Gradle format-check task in this repo** — `formatCheck`
belongs to the CLI repo, not this one.

Tests that assert a defect is fixed were confirmed to fail with the fix disabled, rather than
assumed meaningful: both warm-resume regressions, the `scan.batch` coalescing test, and — for U3 —
the grace-deadline test (deadline write removed) and the disclosure tests on both agents (scope
check forced true, which fails all three Rust status tests and the Kotlin caller-scoped one). The
coalescing test has an inherent timing hazard (three emits could straddle a flush tick), so it was
stressed 60× with no failures. The two 2026-08-10 additions were held to the same bar:
`the_structured_holder_is_omitted_without_the_capability` fails when the gate is forced open, and
`aDeviceScopedRuleIsEnforcedAtDispatch` fails when `matchesChar` stops consulting `device` — which
is what makes the latter prove the *dispatch point* passes a real handle, rather than a wildcard
that would leave the field decorative.

The operator-header path is covered end to end over a real socket, not only at the unit level:
`BleAgentOverWebSocketTest.agentStatusCarriesOperatorScopeOnlyForTheOperatorCredential` drives a
client credential alone, a wrong operator secret, and the right one against the production server,
and asserts the wrong secret **connects** at normal scope rather than failing the handshake.

### Wire-protocol position

Through the capability-parity work: no new op, event, or capability string, and no `@SerialName`
touched, so the cross-language CBOR gates were never in play. What changed is that `agent-rs` now
advertises and emits things that already existed in the vocabulary — `slots`/`SlotState`,
`scan.batch`/`ScanResultBatch`, and the two descriptor ops — reaching only clients that negotiate
them.

One consequence needed covering: `agent-rs` could always *decode* a `ScanResultBatch` but had never
sent one, so that direction had no interop coverage while it could not fail.
`RustAgentInteropTest.eventScanResultBatch` now pins a Kotlin client's decode of the
definite-length CBOR the Rust agent actually emits.

**U3 changed that position.** `agent.status` is the branch's first new `@SerialName` — one op, one
result payload, one capability string — so the interop gates are now genuinely in play, and both
directions are pinned: `RustAgentInteropTest.replyOkAgentStatus` (Kotlin decodes the exact ciborium
bytes `agent-rs` emits) and `interop_tests::command_agent_status` (Rust decodes the exact
kotlinx bytes for the op). The second one earned its keep immediately: Kotlin's `data object`
encodes its payload as an **empty map**, and a serde unit type would have written null, so the only
argument-less op in the protocol would have disagreed over one byte.

The status DTOs also carry `skip_serializing_if` conditions on the Rust side that mirror Kotlin's
`encodeDefaults = false` field by field. That is deliberate: it makes "diff the two agents' status
replies" a real check rather than two encodings a decoder happens to tolerate.

**`lease.holder` moved it again**, in a way worth naming because it is a different shape of change
from `agent.status`: a new *field on an existing type* rather than a new op. That reads as the
safest possible addition and is in fact the most dangerous one available here, because `AgentError`
decodes under `Cbor.Default`, which does not ignore unknown keys — so an ungated `holder` breaks a
v1 client's decode of the entire error frame. `ProtocolCodecTest.anUngatedHolderFieldBreaksAV1Decode`
pins that behaviour rather than leaving it as a claim in a KDoc; if the codec ever becomes lenient
the test says so and the gate can be revisited. Both directions are pinned as usual
(`RustAgentInteropTest.replyErrPeripheralBusyCarriesTheHolder` and
`interop_tests::reply_err_peripheral_busy_carries_the_holder`), which is what would catch Rust's
`#[serde(rename = "clientId")]` going missing — a dropped rename still decodes, into an error whose
holder is silently absent.

## Findings worth carrying forward

- **The parity record was comparing the wrong pair of agents.** It measured `agent-rs` against
  Kable-on-Android, so four Android radio features read as Rust deficiencies although the JVM
  Kotlin agent does not advertise them either. `agent-parity-verification.md` §2 is now built
  around like-host comparison.
- **"btleplug has no descriptor API" was false**, and the false root cause is the lesson: it had
  parked a buildable feature in the "cannot be built" column, where nothing would revisit it.
- **The warm-resume defect was found by writing U2's regression test**, not by reading the code.
  Both agents re-drove the radio on every resumed `connect`, so `transportGrace` kept the lease
  while silently discarding its entire benefit — and a slow `Ok` is indistinguishable from a fast
  one, so no client could have noticed.
- **`cargo fmt --check` was already failing before this work**, so the Rust diff includes a few
  reformatted lines nobody in these commits wrote.

## Reviewed against the CLI's contract, 2026-08-10

The first pass through `remoteble-tools`' `implementation-plan.md` treated U5 and U7 as complete
when only the half this repo could see had landed. Diffing the CLI's **vendored** protocol slice
(`remoteble-tools/core/src/commonMain/kotlin/dev/warsha/remoteble/protocol/`) against `:protocol`
found the rest — that diff is the sharpest tool available here, because the CLI is already written
against the wire contract it expects, so any drift is a compile break or a silent degradation
waiting to happen. Three gaps and two doc defects came out of it:

- **U5 was half done.** The prose message landed; `lease.holder` and the structured
  `AgentError.holder` did not. The CLI already renders `error.holder` as `{principal, clientId}`
  (`cli/.../Main.kt`, `SessionProtocol.kt`) and maps `Capabilities.LEASE_HOLDER` in `AgentFeature`.
  Because the field is nullable with a default, this failed *silently*: the CLI would have decoded
  every refusal with `holder = null` forever and no error anywhere, and U5's gate — assert the
  holder without parsing logs — would have quietly stayed unmet. Re-vendoring would additionally
  have deleted `Capabilities.LEASE_HOLDER` and broken `core`'s compile.
- **`busyMessage` ignored operator scope**, so an operator was told less by the refusal than by the
  `agent.status` row describing the same lease. Both now render one decision from one function.
- **U7 was device-blind.** The plan asks for "the optional exact/wildcard device rule" and
  `safety-model.md` documents `device` defaulting to `"*"` as authoritative agent behaviour; neither
  rule type had the field, and no rationale had been recorded for dropping it.

### Settled: operator scope stays additive

U3's text allows a *standalone* operator bearer session that may read management status but perform
no BLE operations. This branch keeps operator scope as a **widening of disclosure on top of a valid
client credential** (`X-RemoteBle-Operator` on the upgrade) rather than a second admission path.
The trade is explicit: there is no read-only management principal, and an operator is necessarily
also a BLE-capable client. What U3 actually requires is already met — the operator secret must be
distinct from every client credential, so a normal bearer token cannot silently gain operator
access. `remoteble-tools/docs/implementation-plan.md` §U3 needs amending to match; that is the one
item this branch deliberately pushes back rather than implements.

### Still to do in the CLI repo

Not upstream defects, but they block the handoff and are invisible from there:

- The vendored slice has **no `OPERATOR_HEADER`**, so the CLI cannot request operator scope today
  even though both agents support it.
- It carries `Op.AgentSlots` / `ResultPayload.AgentSlots` as deprecated compatibility decoders for
  an op this repo never shipped. Consistent with U4's "deliberately no explicit slot query", but it
  should be recorded so nobody adds it upstream to satisfy the decoder.
- `AgentStatusDto` lives in `Results.kt` there and `AgentStatus.kt` here, so re-vendoring is a
  manual merge, not a file copy.

## Still open

### U7, per-principal write policy — done (merged from `feat/write-policy`, device rule added here)

The actual security boundary and last upstream ask other than publishing. The delivered schema,
capability/error gate, and verification record are in
[`agent-write-policy.md`](agent-write-policy.md) and
[`write-policy-progress.md`](write-policy-progress.md).

The crux, and the reason it is worth reading that doc before writing any code: a new
`ErrorKind.POLICY_DENIED` **cannot simply be sent**. An unknown enum name fails a v1 client's CBOR
decode — the documented reason `RADIO_OFF` is capability-gated — so a denial kind must sit behind a
`write.policy` capability, with `INVALID_REQUEST` as the ungated fallback. The implementation now
wires `StatusSettingsDto.writePolicyEnforced` to the actual configured policy on both agents.

The CLI's own policy remains advisory by design; the agent policy is independently verified with
the SDK and a raw WebSocket/CBOR client, so neither client controls the security decision.

The optional `device` field was missed on that branch and added here — see the review section above
and `agent-write-policy.md`. Worth noting how it was found: not by reading the code, but by reading
the *consumer's* spec, which named a field the implementation had silently dropped.

### U3 is done — what it cost that the plan did not anticipate

Four pieces of bookkeeping neither agent kept, all of which had to exist before the reply could be
assembled honestly:

- **Grace deadlines.** Both registries recorded only *that* a release was pending, never when it
  fires. `remainingGraceMs` is the number a process-per-command client actually needs.
- **A Rust lease snapshot.** `agent-rs`'s registry had `free_slots`/`held_by` and no snapshot at all.
- **Uptime.** The JVM agent had a private `startedAtMs`; `agent-rs` recorded nothing.
- **Advertised names.** btleplug offers the name only on the scan path and nothing retained it, so
  `name` would have been permanently null on Rust and populated on Kotlin — the same shape of
  divergence this branch spent its first half removing. A bounded cache fixed it.

### Not in scope for this branch

- **Phone-agent grace settings.** Android and iOS inherit the new 120 s default (both construct
  `AgentConfig` with defaults) but expose no operator control. That is the gap gating hardware
  validation, and it is mobile work.
- **Descriptors on real hardware.** The simulator does not model descriptors and the real-agent
  descriptor tests are separate, so neither agent's descriptor path runs in CI. Unit coverage is
  dispatch, authorization, and resolution against a fake backend; a rig run is what would prove the
  radio path — on either agent, since Kotlin's was never covered either.

## The publication question

`identifierFormat` is the one change a CLI consumes directly from the published SDK, and
`client-sdk:0.10.0` predates it. Until a release carries it, a consumer needs a composite build,
which the CLI's own release gates forbid. So U1 stays open until 0.11.0 ships.

Publishing is deliberately **last** in the sequence. The risk to watch is that it gets forgotten as
the final blocker once everything else looks done.

## Before merging

- ~~Two shipped defaults change with no migration note.~~ **Settled**: `docs/migrate-to-0.10.0.md`
  now carries an operator-facing section for `transportGrace` 10 s → 120 s and the slot cap 4 → 8
  becoming agent-wide, including what each costs on shared hardware and how to override it.
- ~~Decide whether `leaseGrace` should move too.~~ **Settled: it stays at 10 s.** Its path is an
  unsolicited BLE disconnect, where the radio link is already down — there is no warm link to
  preserve, so the argument that justifies a long transport grace does not carry over. Recorded in
  the migration note so it stops reading as an open question.
- Re-run the agent parity checks if anything else lands on `main` first; the tables in
  `docs/agent-parity-verification.md` were updated by hand here.

## Commits

```
9f2843a docs: record descriptor parity in agent-rs
baa89f6 feat(agent-rs): implement descriptor read and write
bd28565 docs: record scan.batch parity
b107097 test(protocol): pin the Rust batch CBOR on the wire
45b0f1e feat(agent-rs): coalesce scan results into batches
f8fa447 docs: record capability levels and parity fixes
261db77 feat(agent-rs): advertise agent-level capabilities
9aca90c feat(agent): resume warm leases without a reconnect
81f6995 fix(agent): bound a disclosed identity's length
88c7bce docs: note branch scope and open questions
9495293 docs: record the CLI-readiness agent changes
f736bca feat(client-sdk): let a client declare its identifier format
e24ccd6 feat(agent): make slot accounting agent-wide and lease-aware
f79b98c feat(agent): name the holder on PERIPHERAL_BUSY
0c24902 feat(agent): raise transport grace default to 120s
```
