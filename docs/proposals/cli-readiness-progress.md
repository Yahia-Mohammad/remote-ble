# CLI-readiness branch: what landed, what is left

Working note for `feat/cli-readiness`, written 2026-08-05. It exists so the next person on this
branch — or the same person a week later — does not have to re-derive the scope decision or
re-verify what already passed. The changes themselves are described in `CHANGELOG.md` under
`[Unreleased]`; this file is about state and intent.

## Why this branch exists

The `remoteble-tools` CLI is a process-per-command client of this agent: one process per command,
each expecting the next to resume the same connection. Reviewing its implementation plan against
the `v0.10.0` tree turned up gaps that are invisible to every existing consumer — Kable apps hold
one long-lived session, so none of them exercises the seams a short-lived process does.

## Landed here

| Item | Change | Where |
|---|---|---|
| Transport grace | default 10 s → 120 s, JVM and Rust | `AgentModule.kt`, `PeripheralRegistry.kt`, `main.rs`, `peripheral_lease.rs` |
| Lease denial | holder named under a disclosure policy | `LeaseDisclosure.kt`, `lease_disclosure.rs` |
| Slot accounting | agent-wide, lease-aware, reported at handshake | `PeripheralRegistry.kt`, `BleAgent.kt` |
| Identifier format | client may declare its own | `AgentSession.kt` |
| Agent-level capabilities | unconditional in `agent-rs`; `slots` advertised and emitted | `frame.rs`, `negotiation.rs`, `server.rs`, `peripheral_lease.rs` |
| Capability levels | agent-level vs backend-level written into the spec | `agent-conformance-spec.md` §5.3 |
| Slot cap default | aligned at 8, both agents | `BleAgent.kt`, `peripheral_lease.rs` |
| Warm-resume | replayed `connect` no longer re-drives a live radio link | `BleAgent.kt`, `server.rs`, both registries |
| `scan.batch` | implemented in `agent-rs`; agent-level set now complete | `server.rs`, `frame.rs`, `RustAgentInteropTest.kt` |

Verified: `./gradlew build` green, `cargo test` 136 passed, `cargo clippy --all-targets` and
`cargo fmt --check` clean. Both warm-resume regressions were confirmed to fail with the fix
disabled, rather than assumed to be meaningful. No wire-protocol change — no new op, event, or
capability string, and no `@SerialName` touched — so the cross-language CBOR interop gates were
never in play. `agent-rs` does start advertising and emitting `slots` / `SlotState`, both of which
already existed in the vocabulary, and only to clients that negotiate them.

Two things found while doing this, both now fixed and worth knowing about: `cargo fmt --check` was
already failing before any of this work, so the Rust diff includes a few reformatted lines nobody
here wrote; and the two agents bounded a disclosed identity differently — Rust capped input
characters, Kotlin capped rendered length — which for an all-escaped identity meant ~288 characters
from one agent and ~48 from the other, under a policy whose whole point was that they agree.

## Still open on the Rust agent

- **`descriptors`** is advertised by the Kotlin agent on JVM and not by Rust — and the recorded
  reason ("btleplug has no descriptor API") turned out to be false. btleplug 0.11.8 declares
  `read_descriptor`/`write_descriptor`, and Kable's JVM backend binds both, so the Kotlin agent is
  truthful and Rust simply has not implemented them. Backend work in `btleplug_impl.rs`; see
  `agent-parity-verification.md` §1. The wrong root cause is the lesson: it had parked a buildable
  feature in the "cannot be built" column where nothing would revisit it.

## Deliberately not in scope

- **Phone-agent grace settings.** Android and iOS expose no operator control. The desktop agents
  already had env vars and flags; only their defaults moved. This is the gap that gates hardware
  validation, and it is mobile work rather than a continuation of this branch.
- **A versioned status contract.** `/api/state` is loopback-gated plaintext HTTP, and `agent-rs`
  has no HTTP server at all, so a status command cannot be HTTP-only. The shape that works on every
  reference agent is an additive capability-gated op over the existing WebSocket session.
- **Per-principal write policy.** The actual security boundary, and a design exercise of its own.

## The publication question

The `identifierFormat` parameter is the one change a CLI consumes directly from the published SDK,
and `client-sdk:0.10.0` predates it. Until a release carries it, a consumer needs a composite build
— which the CLI's own release gates forbid. Either publish 0.10.1 or accept that the CLI cannot
declare `STRING` in a release build yet.

## Before merging

- Decide whether `leaseGrace` should move too. It stayed at 10 s on the argument that its radio link
  is already down, which is a different situation from a client's transport dropping — but it is a
  judgement, not a measurement.
- Re-run the agent parity checks if anything else lands on `main` first; the docs table in
  `docs/agent-parity-verification.md` was updated by hand here.
