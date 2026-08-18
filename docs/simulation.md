# Radio-less JVM agent simulation

The JVM agent can run a declared GATT world instead of constructing the Kable radio engine. This is
for CI, demos, and reproducible client integration tests; it is not a Rust-agent feature and does
not change the client API or wire protocol.

```sh
./gradlew :agent:jvmRun --args="--simulate agent/simulation/sim-hrm.json"
# or, from the released fat JAR:
java -jar remoteble-agent-<version>-all.jar --simulate sim-hrm.json
```

`REMOTE_BLE_SIMULATE=sim-hrm.json` is equivalent to `--simulate`. The profile is decoded and
validated before Koin or the WebSocket listener starts, so malformed/unbounded input never opens a
port. Normal bind and credential policy still applies.

[`agent/simulation/sim-hrm.json`](../agent/simulation/sim-hrm.json) is the canonical Heart Rate,
Battery, and Device Information profile. It includes heart-rate notifications at one second and a
writable Heart Rate Control Point, matching the live-rig shape closely enough for ordinary client
scan/connect/discover/read/write/observe coverage.

## Profile v1

The top-level shape is:

```json
{
  "schemaVersion": 1,
  "seed": 17,
  "peripherals": [{ "id": "stable-handle", "advertisement": {}, "connect": {}, "services": [] }]
}
```

- `id` is the stable remote device handle (`[A-Za-z0-9._-]`, up to 128 characters). It is a
  simulation identity, not a platform one — see [Handles and `.identifier`](#handles-and-identifier).
- `advertisement` accepts `name`, `serviceUuids`, `rssi`, optional `rssiJitter`, and `intervalMs`
  (50–60,000 ms). Short 16-/32-bit Bluetooth UUIDs expand to their standard base UUID.
- `connect` accepts `latencyMs`, `failFirst`, and optional `dropAfterMs`. The latter produces one
  unsolicited simulated disconnect after a successful connection.
- Each service has `uuid` and non-empty `characteristics`; characteristics declare matching
  `properties` (`read`, `write`, `writeWithoutResponse`, `notify`, `indicate`) and the behavior
  for each declared property.
- `read.static`, `read.sequence`, and `read.counter` are mutually exclusive value sources. Values
  are even-length hexadecimal with a 512-byte maximum; sequence values loop and counters are
  big-endian integers (`start`, `step`, `widthBytes` 1–4). `notify` supplies an `intervalMs` and a
  value source. `write` supplies `accept` and optional `storesValue` readback behavior.

## Handles and `.identifier`

A profile `id` like `sim-hrm-1` is deliberately readable, so it is not a valid platform identifier
on any host (a macOS UUID, a Windows MAC, a Linux bluez id). The simulated backend therefore
declares `IdentifierFormat.STRING` — the same thing Android's radio declares — and the ordinary
`identifier.translate` handshake takes it from there:

| Client | Handle it sees | `.identifier` |
| --- | --- | --- |
| Android, or any `STRING` client | the profile `id` verbatim | works (Android holds any string) |
| Apple, macOS-host JVM, Windows-host JVM | a synthesized UUID/MAC, deterministic per `id` | works |
| Linux-host JVM | the profile `id` verbatim | throws `RemoteIdentifierUnavailableException` |

Two preconditions apply to the middle row, and they are the ordinary translation rules rather than
anything simulation-specific: the client must negotiate `identifier.translate` (the bundled client
SDK requests it by default), and the agent's strict mode must be off. Under strict mode the agent
deliberately stops rewriting so a format mismatch surfaces on the client instead of being papered
over — against a simulated agent that means the profile `id` reaches a client that cannot parse it.

The last row is the pre-existing stubbed `BLUEZ_JSON` synthesizer described in
[`HandleTranslator`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/HandleTranslator.kt),
not something simulation-specific; it applies to a real cross-platform agent the same way.

So don't hard-code a profile `id` on the client side — the portable identity is `.handle`
(`DeviceHandle`), and it is what ops key off. Match on `advertisement.name` or a service UUID to
find a simulated peripheral, exactly as you would against a real radio.

## Capabilities

**Backend-level**, the simulator models exactly one capability beyond the v1 baseline: connected
RSSI. Descriptor, pairing, and connection-parameter operations remain unsupported, so those surfaces
cannot be validated this way.

**Agent-level capabilities are a different matter, and they are all present.** They are
radio-independent, so `advertisedCapabilities()` unions `BleAgent.AGENT_CAPABILITIES` into every
backend's set — the simulator included. A simulated agent therefore advertises `slots`,
`scan.batch`, `identifier.translate`, `agent.status`, `write.policy`, and `lease.holder`, plus its
one `scan.concurrency.*` mode. That is deliberate rather than incidental: those capabilities are
bookkeeping, and an agent that could not answer them would be non-conforming (see
[agent-conformance-spec.md §5.3](agent-conformance-spec.md)).

The practical consequence is that lease contention, holder disclosure, write-policy enforcement,
slot accounting, and the status contract are all exercisable with **no Bluetooth hardware at all** —
which is the whole point of having a scriptable agent, and the reason a hostile advertised name can
be a CI fixture rather than a thought experiment.

There is no scripting language, record/replay, or Rust profile interpreter as of 0.11.0.

The profile parser rejects unknown fields, duplicate handles/services/characteristics, invalid UUIDs
or hex, unsupported property combinations, unsafe cardinalities, and excessive schedules. See
[`SimulationProfile.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulationProfile.kt)
for the executable contract.
