# Migrate to RemoteBLE 0.11.0

0.11.0 is readiness work for clients whose **processes are short-lived** — a CLI, a script, a coding
agent running one command per process. Update the dependency version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.11.0")
}
```

**There is no required source change.** Everything added here is either capability-gated on the wire
or a new optional parameter, so an application built against 0.10.0 compiles and runs unchanged. Two
*agent* defaults move, which is the part worth reading before upgrading a deployment.

If you are coming from a Central release older than 0.10.0, read
[migrate-to-0.10.0.md](migrate-to-0.10.0.md) first — its `authToken` provider change is breaking and
still applies.

## Operator change: two agent defaults move

This applies when upgrading the **agent**, not the client SDK. Both are shipped defaults with no
source change to make, which is exactly why they are called out here rather than left to a changelog
line.

| Setting | Was | Becomes | Override |
|---|---|---|---|
| `transportGrace` | 10 s | **120 s** | `REMOTE_BLE_TRANSPORT_GRACE_MS` / `--transport-grace-ms` |
| Connection slot cap | 4, per client session | **8, agent-wide** | `AgentConfig.maxConnections` |

**`transportGrace` 10 s → 120 s.** After a client's WebSocket drops, its peripherals stay leased —
with their radio links warm — for two minutes instead of ten seconds. This is what makes a
process-per-command client resume rather than reconnect between invocations. The cost lands on
**shared hardware**: a peripheral is unavailable to anyone else for up to two minutes after its
holder walks away or is killed. On a shared rig, lower it explicitly.

**Slot cap 4 → 8, and now agent-wide.** The number changed *and* so did what it counts. It was
enforced per client session, so three clients could each open four links against one controller; it
is now the host's total, enforced in `PeripheralRegistry`, and a lease inside its grace window counts
as occupied. If you relied on the old number as a per-client budget, there is no per-client budget
any more — the cap models the controller, which is the thing that was actually running out.

Both are visible at runtime in an `agent.status` reply (`settings.transportGraceMs`, `slots.total`),
so a deployment can confirm what it is actually running with rather than inferring it.

`leaseGrace` deliberately **stays at 10 s**. Its path is an unsolicited BLE disconnect, where the
radio link is already down; there is no warm link to preserve, so the argument for a long window does
not carry over from the transport case.

## New capabilities, all opt-in

The wire protocol remains **v1**. Each addition is negotiated, so an old client against a new agent
and a new client against an old agent both keep working.

| Capability | What it adds |
|---|---|
| `agent.status` | The `agent.status` op: agent identity, uptime, effective grace settings, slot occupancy, and leases — over the session you already have |
| `write.policy` | Agent-enforced per-principal write allowlist, and the `POLICY_DENIED` error kind |
| `lease.holder` | Structured `AgentError.holder` on `PERIPHERAL_BUSY`, alongside the existing message |

**Two of these gate an error kind, and that is not decoration.** `ErrorKind` serializes by name and
`AgentError` decodes strictly, so a client that has not negotiated `write.policy` receives
`INVALID_REQUEST` rather than an unknown `POLICY_DENIED` its decoder would reject. `lease.holder`
gates a *field* for the same reason: an unrecognized key fails the decode of the whole error frame.
Negotiate the capability to receive either.

## If you run an agent with a write policy

`REMOTE_BLE_POLICY_FILE` (Rust: `--policy-file`) loads a per-principal allowlist. Write rules gained
an optional `device` field, defaulting to `"*"`:

```json
{ "device": "AA:BB:CC:DD:EE:FF", "service": "0000180d-…", "characteristic": "00002a39-…" }
```

An existing policy file keeps its exact meaning — omitting `device` matches every peripheral, as
before. Supplying it scopes the rule to one peripheral, which on a shared rig is the difference
between "this principal may write this control point" and "…on the peripheral it leased". The value
matches the device handle the registry leases and `agent.status` reports.

## Reading a lease denial

`PERIPHERAL_BUSY` has always carried a human message. With `lease.holder` negotiated, the same
information arrives as fields:

```kotlin
catch (e: AgentException) {
    val holder = e.error.holder            // null unless lease.holder was negotiated
    holder?.principal                      // always present when holder is
    holder?.clientId                       // null unless you share the principal, or hold operator scope
}
```

`clientId` is withheld across principals on purpose: it is chosen by the client and can carry a
hostname or username it never intended to publish. Both fields are length-bounded and
control-character escaped — they are text the *holder* chose, and they are rendered in your terminal,
your logs, or a coding agent's context.

Review the [changelog](../CHANGELOG.md) for the cumulative behavior changes before upgrading a
production deployment.
