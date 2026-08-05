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

Verified at the last commit: `./gradlew build` green, `cargo test` 121 passed, `cargo clippy
--all-targets` clean. No wire-protocol change — no new op, event, or capability string, and no
`@SerialName` touched — so the cross-language CBOR interop gates were never in play.

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
