# Migrate to RemoteBLE 0.12.0

0.12.0 fixes a defect in **simulated** agents and makes two silent failure modes speak. Update the
dependency version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.12.0")
}
```

**There is no required source change** unless your code hard-codes a simulation profile's `id`, and
no wire-protocol change: nothing in the frame vocabulary moved, and the agent's own identifier
format is never sent.

Coming from a release older than 0.11.0? Read [migrate-to-0.11.0.md](migrate-to-0.11.0.md) first —
two agent defaults move there. From older than 0.10.0, start at
[migrate-to-0.10.0.md](migrate-to-0.10.0.md), whose `authToken` provider change is breaking.

## The one behaviour change: handles from a simulated agent

**Applies only to `--simulate`.** A profile `id` like `sim-hrm-1` is a simulation identity, not a
platform one, and a simulated agent used to declare the *host radio's* identifier format regardless.
On a host whose format matched the client's, translation was skipped and the client received
`sim-hrm-1` as, say, a UUID — so every access of `.identifier` threw
`RemoteIdentifierUnavailableException`. The shared `:client-ui` reads it for every sighting, so an
Apple-format client scanning a simulated agent hit this on its scan screen.

The simulated backend now declares `IdentifierFormat.STRING`, which is the truth, and the ordinary
`identifier.translate` handshake takes it from there:

| Client | Handle it now sees | `.identifier` |
| --- | --- | --- |
| Android, or any `STRING` client | the profile `id` verbatim — unchanged | works |
| Apple, macOS-host JVM, Windows-host JVM | **a synthesized UUID/MAC**, deterministic per `id` | works (it threw before) |
| Linux-host JVM | the profile `id` verbatim — unchanged | still falls back to `.handle` |

### What to change

Only if you match on a literal profile id:

```kotlin
// Before — breaks against a UUID/MAC client, and always did on a real radio
val device = advertisements.first { it.handle.value == "sim-hrm-1" }

// After — the portable identity is the handle you were given, and you find it by what it advertises
val device = advertisements.first { it.name == "Sim HRM" }
```

`.handle` remains the portable identity and is what every op keys off; it is simply no longer
guaranteed to equal the profile's `id` string. This is the same rule that has always applied against
a real radio, where handles come from the host's Bluetooth stack. See
[simulation.md](simulation.md#handles-and-identifier) for the full table and its two preconditions.

## Two things that used to fail silently

Neither needs action; both change what appears in your logs.

- **The agent** warns when a connection ends without ever delivering a `ClientHello`. Previously the
  only trace was a `client connected` line with no `handshake` after it.
- **The client SDK** warns when a `ClientHello` fails to send, and says the session will not retry
  on its own. It previously logged `hello sent` regardless and put the failure at `debug`.

If you parse agent logs, note also that lease lines now render owners as `principal/clientId`. They
previously carried a raw NUL byte, which made `agent.log` a binary file — `grep` reported no match
for strings that were plainly present.

## For anyone embedding the agent

`AgentWebSocketServer.resolvedPort` reports the port actually bound. Pass `port = 0` and read it back
to take a free port without racing anything else on the host for it.
