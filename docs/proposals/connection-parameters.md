# Enhancement: BLE Connection Parameters (connection-interval control)

[← back to index](../README.md)

- **Status:** Implemented in 0.8.2 — `conn.params` op + Android backend shipped; the fine-grained
  `hint` path stays reserved (no engine honors it yet), so today it's the coarse Android profile only
- **Type:** Capability-gated protocol extension
- **Prior art:** ESPHome Bluetooth Proxy `BLE connection parameters API` (added ~ESPHome 2026.3.0)
- **Relates to:** existing `conn.priority` capability (Android-only); [prior-art.md](../prior-art.md#part-a--remoteble-vs-esphome-bluetooth-proxy) Part A

---

## Summary

Add an optional, capability-gated operation that lets a client request the
**connection parameters** — principally the connection interval — of an active BLE
connection through the agent's radio. This generalizes the existing Android-only
`conn.priority` capability into a cross-engine capability with a defined wire op, and
leaves the door open for engines that expose fine-grained interval control.

## Motivation

The BLE **connection interval** governs how often the two radios (the agent's and the
physical peripheral's) wake up and exchange packets — from 7.5 ms to ~4 s, in 1.25 ms
steps. It is primarily a **power-vs-latency** control on the peripheral side:

- A short interval (~7.5 ms) gives low latency and high throughput but drains the
  peripheral's battery quickly.
- A long interval (~1 s) spares the peripheral's battery but adds latency and caps
  throughput.

The canonical pattern — which ESPHome adopted for devices like Yale/August locks via
`yalexs-ble` — is to connect with a **fast** interval for quick service discovery and
setup, then switch to a **slow** interval for the long idle "always connected" phase.
This can be a large battery win on the peripheral.

### Why existing mechanisms do *not* already cover this

It is tempting to think RemoteBLE's pervasive backpressure (coroutine `Flow`s,
WebSocket flow control) already handles this. It does not — the two live at different
layers and solve different problems:

- **Connection interval** is a *link-layer radio* parameter on the *agent ↔
  peripheral* RF link. Its effect is on the peripheral's radio duty cycle and power
  draw.
- **Backpressure** is *application/transport-layer* flow control on the *client ↔
  agent ↔ software* path. It decides what happens when a producer outruns a consumer.

The peripheral has no knowledge of the client's WebSocket backpressure. A flawless
suspending `Flow` all the way down does nothing to the peripheral's power draw if its
radio is waking every 7.5 ms; conversely, a 1 s interval saves that battery whether
or not any backpressure exists. Backpressure governs *our* consumption rate; the
connection interval governs *the peripheral's radio*. So this is a genuine gap, not
something the current design already addresses through another mechanism.

## The reality check: engine support on RemoteBLE's target platforms

This is where copying ESPHome one-for-one breaks down, and it must shape the design.
ESP32's BLE stack exposes direct connection-parameter control
(`esp_ble_gap_update_conn_params`), which is why ESPHome could add the feature
cleanly. General-purpose OS Bluetooth stacks are more restrictive:

| Engine / platform | Connection-parameter control | Notes |
|---|---|---|
| **Android** | Coarse only — `requestConnectionPriority(BALANCED / HIGH / LOW_POWER)` | Already surfaced by RemoteBLE as `conn.priority`. Not exact intervals — a class request the OS honors best-effort. |
| **iOS / CoreBluetooth** | **None** exposed to apps | The OS manages intervals; there is no app-facing API. iOS agents cannot honor this. |
| **JVM / `btleplug`** | Effectively none via the current API | `btleplug` does not expose interval control cross-platform. On Linux/BlueZ some control exists at the OS level, but not through `btleplug`'s API today. **Needs verification.** |

**Implication:** on RemoteBLE's actual engines this is realistically actionable only
on Android — where a coarse version *already ships* as `conn.priority` — with a
possible future path on a BlueZ-direct or ESP-class agent. That significantly tempers
the value versus ESPHome. The honest framing is therefore:

1. **Generalize** the existing Android-only `conn.priority` into a cleaner, explicitly
   specified capability (`conn.params`) with a defined wire op and a documented coarse
   fallback, rather than treating it as an Android special case.
2. **Reserve** the door for engines that *do* support fine-grained intervals (a future
   BlueZ-direct backend, or an ESP-based agent), without over-promising on the
   engines that ship today.

This is a "generalize and future-proof an existing partial capability," not "add a
brand-new feature that works everywhere." Presenting it as the latter would
misrepresent what the target platforms can deliver.

## Proposed design

### Capability

- New capability string: **`conn.params`** (negotiated in the `Set<String>`; absent
  when the engine can't honor it — exactly as `pairing` / `conn.priority` are dropped
  on `btleplug`).
- `conn.priority` remains for backward compatibility; `conn.params` is the superset.
  An agent advertising `conn.params` implies the coarse behavior of `conn.priority`.

### Operation

A new capability-gated `Op` carrying a **coarse class** as the portable primary
field, with **optional exact-interval hints** that finer engines may honor and
coarser engines ignore:

```
Op.SetConnParams(
    device: DeviceHandle,
    profile: ConnProfile,            // portable, always meaningful
    hint: ConnParamHint? = null,     // optional; honored only by fine-grained engines
)

enum ConnProfile { LOW_LATENCY, BALANCED, LOW_POWER }

ConnParamHint(
    minIntervalMs: Double,           // 7.5 .. 4000, 1.25 ms granularity
    maxIntervalMs: Double,
    latency: Int,                    // peripheral latency (skippable events)
    supervisionTimeoutMs: Int,
)
```

- **Portable path:** `profile` maps to Android's `requestConnectionPriority`
  (`LOW_LATENCY → HIGH`, `BALANCED → BALANCED`, `LOW_POWER → LOW_POWER`) — i.e. what
  `conn.priority` does today, now under a shared name.
- **Fine-grained path:** `hint`, when present and the engine supports it, requests
  specific intervals. Engines without support ignore `hint` and honor `profile`
  only. This keeps one op meaningful across the whole spread from CoreBluetooth
  (honors neither) to a hypothetical BlueZ-direct backend (honors both).

### Result

`OpResult` reports what was *requested* and, where the engine can tell us, what was
*applied* (Android returns only success/failure of the request, not the resulting
interval — so the applied value is best-effort and may be `null`). This mirrors the
existing "reached the radio and it said no" vs. "never reached the radio" error
split.

### Reconnection semantics

The last requested `SetConnParams` for a device should participate in
**reconcile-on-reconnect**, alongside `Connect` / `ObserveStart` / `ScanStart`, so a
transport blip does not silently revert a peripheral to a fast (battery-hungry)
interval. Idempotent replay, same as the other reconciled ops.

### Client API surface & the parity question

There is a wrinkle for the "unchanged app code" promise. Kable's common `Peripheral`
interface does not expose a portable connection-parameters method (Android has
priority control; iOS does not). So `conn.params` likely surfaces as a
**RemoteBLE-specific extension** on `RemotePeripheral` (or via a capability-checked
cast), *not* as something that flows transparently through unchanged Kable app code —
unlike the core op-set. This should be stated plainly in the client SDK docs:
connection-parameter control is an opt-in extension whose availability depends on the
agent's advertised capabilities, and app code that uses it is knowingly stepping
outside the local/remote-parity guarantee.

## Non-goals

- Not attempting exact interval control on engines that don't expose it (iOS,
  current `btleplug`). The op degrades to the coarse profile there.
- Not adding a background "auto fast-then-slow" state machine on the agent. The
  client requests transitions explicitly; automatic profile switching (if ever
  wanted) is a separate, later concern.
- Not changing the default connection behavior. Absent an explicit request, the
  engine's / OS's default parameters apply, exactly as today.

## Open questions

1. **`btleplug` support — verify.** Confirm whether any interval control is reachable
   through `btleplug`'s API on Linux/BlueZ. If genuinely none, the JVM agent simply
   never advertises `conn.params`, and the feature is Android-only in practice for
   now — worth knowing before investing.
2. **Kable priority mapping.** Confirm the exact Kable API RemoteBLE's current
   `conn.priority` binds to, and whether the three-profile enum maps cleanly onto it.
3. **Value ceiling.** Given RemoteBLE's target use cases (emulator/CI testing,
   remote-lab access, device sharing) are not battery-critical always-connected
   deployments, is the payoff worth the wire-contract and doc surface? The honest
   answer may be "generalize `conn.priority` into `conn.params` opportunistically,
   but don't prioritize the fine-grained hint path until an engine that can use it
   exists."

## Rough effort

Small-to-moderate, and mostly additive:

- `:protocol`: one new `Op` + `OpResult` variant + capability string (frozen
  `@SerialName` discriminators; add to the conformance spec and the Rust codec).
- `:agent`: engine binding (Android maps to existing priority control; JVM/iOS
  advertise the capability only if/when supportable).
- `:client-sdk`: extension method on `RemotePeripheral`, capability check, and
  participation in reconcile-on-reconnect.
- Interop: extend the Kotlin↔Rust byte-identity tests to cover the new op.

The bulk of the risk is not code volume — it is confirming the engine-support matrix
above so the capability is advertised honestly on each platform.
