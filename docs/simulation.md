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

- `id` is the stable remote device handle (`[A-Za-z0-9._-]`, up to 128 characters).
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

The simulator currently advertises only the capability it actually models beyond the v1 baseline:
connected RSSI. Descriptor, pairing, and connection-parameter operations remain unsupported. There
is no scripting language, record/replay, or Rust profile interpreter in 0.10.0.

The profile parser rejects unknown fields, duplicate handles/services/characteristics, invalid UUIDs
or hex, unsupported property combinations, unsafe cardinalities, and excessive schedules. See
[`SimulationProfile.kt`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/SimulationProfile.kt)
for the executable contract.
