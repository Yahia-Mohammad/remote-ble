# CLI-readiness branch: what landed, what is left

Working note for `feat/cli-readiness`, last updated 2026-08-05. It exists so the next person on
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

**No capability now differs between two agents on the same host.** That was not the original scope
— it grew out of the judgement that differing capability support between the reference agents is
itself a disparity, not a platform fact — and it is what §5.3's two rules now require of anyone
writing a third agent.

### Verification

`./gradlew build` green; `cargo test` 139 passed; `cargo clippy --all-targets` and
`cargo fmt --check` clean. Note there is **no Gradle format-check task in this repo** — `formatCheck`
belongs to the CLI repo, not this one.

Tests that assert a defect is fixed were confirmed to fail with the fix disabled, rather than
assumed meaningful: both warm-resume regressions, and the `scan.batch` coalescing test. The
coalescing test has an inherent timing hazard (three emits could straddle a flush tick), so it was
stressed 60× with no failures.

### Wire-protocol position

No new op, event, or capability string, and no `@SerialName` touched, so the cross-language CBOR
interop gates were never in play. What did change is that `agent-rs` now advertises and emits
things that already existed in the vocabulary — `slots`/`SlotState`, `scan.batch`/`ScanResultBatch`,
and the two descriptor ops — reaching only clients that negotiate them.

One consequence needed covering: `agent-rs` could always *decode* a `ScanResultBatch` but had never
sent one, so that direction had no interop coverage while it could not fail.
`RustAgentInteropTest.eventScanResultBatch` now pins a Kotlin client's decode of the
definite-length CBOR the Rust agent actually emits.

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

## Still open

### Next up: U3, a status contract

The largest remaining piece, and **design-first**: it defines a new wire surface, which makes it
the first change here that would touch the CBOR interop gates. Agree the DTO shape and the
disclosure rules before four agents implement them.

Settled so far: it cannot be HTTP-only. `/api/state` is loopback-gated plaintext HTTP and
`agent-rs` has no HTTP server at all, so the shape that reaches every reference agent is an
additive, capability-gated op over the existing authenticated WebSocket session. Disclosure is
scoped to the caller — a normal principal sees its own leases and aggregate counts; a caller with
operator scope sees holders. It should carry agent identity/version, uptime, effective grace
settings, connected-client summaries, and per-lease handle, display name, holder (as the caller is
entitled to see it), connected/in-grace state, and remaining grace.

`LeaseDisclosure` is the precedent for the holder-visibility half and should be reused rather than
re-derived.

### Then: U7, per-principal write policy

The actual security boundary, and a design exercise of its own. The CLI's own policy stays
labelled advisory until this exists and is independently verified against a raw SDK client.

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
which the CLI's own release gates forbid. So U1 stays open until 0.10.1 ships.

Publishing is deliberately **last** in the sequence. The risk to watch is that it gets forgotten as
the final blocker once everything else looks done.

## Before merging

- This branch changes two shipped defaults (`transportGrace` 10 s → 120 s, slot cap 4 → 8) and the
  meaning of a third (the slot cap became agent-wide). Defensible for a 0.10.1, but it is more
  behavioural change than the branch name suggests, and the CHANGELOG carries the explanation
  rather than a migration note. Decide whether that is how it should land.
- Decide whether `leaseGrace` should move too. It stayed at 10 s on the argument that its radio
  link is already down, which is a different situation from a client's transport dropping — but
  that is a judgement, not a measurement.
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
