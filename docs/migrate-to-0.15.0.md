# Migrate to RemoteBLE 0.15

0.15 forwards more of each advertisement an agent's radio receives
([#63](https://github.com/Yahia-Mohammad/remote-ble/issues/63)): service data, Tx power, whether the
device accepts connections, and the name the agent's platform remembers for it. Update the
dependency version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.15.0")
}
```

**Nothing breaks on the wire.** The wire protocol version stays at **1**, and the new fields reach only a client
that negotiates the new `scan.fields` capability, so 0.15 agents and 0.14 clients work together in
both directions. Coming from a release older than 0.14.2? Read
[migrate-to-0.14.0.md](migrate-to-0.14.0.md) first, and the guides it links.

## Scan results carry more

Every 0.15 session offers `scan.fields`, so there is nothing to opt into. Against an agent that
advertises it, `RemoteAdvertisement` fills Kable's members that used to read as absent:

| Member | Before | From a 0.15 agent |
|---|---|---|
| `serviceData(uuid)` | always `null` | the bytes, where the agent's platform reports them |
| `txPower` | always `null` | dBm, where reported |
| `isConnectable` | always `null` | where reported (not on btleplug) |
| `peripheralName` | the advertised `name` | the platform's remembered name, else `name` |
| `manufacturerData` (one company) | always `null` | the first entry the agent sent |

An absent value means the agent's platform did not report it, not that the advertisement lacked it.
The Android agent reads the raw advertising record and reports everything; the iOS and JVM Kotlin
agents report service data only for advertised service UUIDs, because Kable offers them only a
lookup; `agent-rs` reports all service data but no connectable flag. The
[conformance spec, §5.6](agent-conformance-spec.md#56-scan-fields-capability-scanfields) has the
full table.

Reading the protocol stream directly with `RemoteScanSource`? `AdvertisementDto` has four new fields:
`serviceData` (full 128-bit UUID string to bytes), `txPower`, `isConnectable` and `peripheralName`.

`AdvertisementDto` gains its four fields as constructor parameters with defaults, so source that
builds one still compiles, but code compiled against `protocol` 0.14 that constructs it must be
recompiled against 0.15: the old constructor signature is gone.

## Kotlin agents send manufacturer data

The field has been in the protocol since 0.8.x and `agent-rs` always filled it, but the Kotlin agent
(the Android and iOS apps, and the JVM desktop agent) left it empty. It now sends it to every
client, 0.14 ones included, since the field is part of the v1 baseline. A scanner that treated "no
manufacturer data" as a property of the device will see more devices carry it. The JVM desktop
agent sends only the first entry, because Kable keeps btleplug's map private; Android and
`agent-rs` send every entry.

## `agent-rs` on btleplug 0.13

The Rust agent moved from btleplug 0.11.8 to 0.13.4. On macOS it now reports Tx power, which
btleplug 0.11 never read from CoreBluetooth, and a device's `name` is its advertised name, or the
name macOS remembers when it advertises none, rather than both joined as `Cached [Advertised]`.
The upgrade also brings btleplug's fixes for CoreBluetooth operations that could hang or panic:
failed discovery, refused subscriptions, concurrent connects, and services that change. A write
the device rejects now fails with `WRITE_FAILED` on macOS instead of `TIMEOUT`, and later writes
on the same connection keep completing; the Kotlin JVM agent still times out there.

## Simulation profiles

An advertisement in a simulation profile now also accepts `serviceData`, `manufacturerData`,
`txPower` and `connectable` (see [simulation.md](simulation.md)). Existing profiles stay valid. A
peripheral declared `"connectable": false` also refuses a connection.

## The Android agent app opens as one screen

Opening the agent app by an intent that did not match its task's root (a shortcut, Android Studio's
Run, `adb shell am start -n`) used to stack a second screen that showed **Stopped** beside an agent
that was still serving ([#67](https://github.com/Yahia-Mohammad/remote-ble/pull/67)). Every launch
now reaches the one screen that owns the running agent.
