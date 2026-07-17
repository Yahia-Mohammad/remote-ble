# Live bring-up runbook

The end-to-end procedure for exercising the whole RemoteBLE stack against a **real radio**, using
a phone running a test peripheral app as the device under test — no discrete BLE hardware required.

```
  ┌─────────────────────────┐        ┌──────────────────────────┐        ┌────────────────────────┐
  │  :e2e-runner (or app)    │  WS    │  :agent (macOS central)   │ radio  │ phone: test peripheral   │
  │  client SDK + Kable      │ ─────▶ │  Kable btleplug engine    │ ─────▶ │ test peripheral GATT srv │
  │  RemotePeripheral        │ ◀───── │  EngineBleBackend         │ ◀───── │ TestProfile             │
  └─────────────────────────┘        └──────────────────────────┘        └────────────────────────┘
```

You need **two pieces of hardware**: one Mac (Bluetooth-capable — it hosts *both* the agent and
the runner over localhost) and one phone (the peripheral). A second phone can host the client
instead of the Mac, but it isn't required.

## The four components

| Component | Module | Runs on |
|---|---|---|
| Test peripheral (device under test) | your own app implementing `TestProfile` below | a phone (Android today) |
| Agent (owns the real radio) | [`:agent`](../agent) | the Mac |
| Live E2E runner (the client) | [`:e2e-runner`](../e2e-runner/README.md) | the Mac (localhost to the agent) |
| Shared GATT contract | `TestProfile` (defined in `:e2e-runner`) | both ends must match |

### TestProfile — the contract

One service exercising the full op set. The peripheral declares it; the runner drives it.

| Attribute | UUID | Properties |
|---|---|---|
| Service | `a1b2c3d4-0000-4000-8000-000000000001` | — |
| Readable | `a1b2c3d4-0000-4000-8000-000000000002` | Read |
| Writable | `a1b2c3d4-0000-4000-8000-000000000003` | Write, WriteWithoutResponse |
| Notify | `a1b2c3d4-0000-4000-8000-000000000004` | Read, Notify |

Advertised local name: `RBTestPeripheral`.

## Prerequisites

- Nothing special to install for Kable — it resolves from Maven Central
  (`com.juul.kable:kable-core:0.43.1`). Kable's JVM (`btleplug`) backend is what gives the agent a
  real radio.
- A test peripheral app: any GATT server (Android `BluetoothGattServer`, a dedicated peripheral SDK,
  or a debug build of your own app) that advertises the `TestProfile` service below and exposes
  simple debug controls — bump the readable value, push a notification, toggle a write error, force
  a disconnect. This lets you drive the test scenarios interactively during the run.
- A Bluetooth-capable Mac, Bluetooth on.
- An Android phone whose chipset supports BLE **peripheral/advertising** (most modern devices do).

## Step 0 — Build everything

```sh
./gradlew build                       # all modules + targets compile; JVM unit suites run
```

Build/install your test peripheral app separately per its own instructions.

## Step 1 — Start the test peripheral (phone)

Launch your test peripheral app, grant the Bluetooth prompts, and start advertising. Confirm it's
broadcasting the local name `RBTestPeripheral` (or update the name `:e2e-runner` scans for). Leave
it foreground.

Whatever debug controls it exposes are your scripting surface during the run — the steps below
assume: **Notify** (push a notification), **Bump readable value**, **Toggle write error**, **Force
disconnect all**.

## Step 2 — Start the agent (Mac)

```sh
REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080
```

> **The agent SIGABRT (exit 134) is fixed — use `agent/run-agent.sh`, not `:agent:jvmRun`.**
> A bare JVM is killed by macOS **TCC** the instant it touches CoreBluetooth: the process's main
> bundle must declare `NSBluetoothAlwaysUsageDescription`, and the request is only honored for apps
> launched via LaunchServices (editing the JDK plist, an embedded section, or launching a `.app`
> directly from a shell all still abort). The script compiles a tiny JNI launcher
> ([`agent/macos-launcher/launcher.c`](../agent/macos-launcher/launcher.c)), wraps it in a signed
> `RemoteBleAgent.app` carrying the key, and starts it with `open` (it streams the agent log).
> First run prompts once for Bluetooth permission. See [agent.md](agent.md#the-runnable-agent--main).
>
> **Smoke-test the radio without the peripheral:** `agent/run-agent.sh` is already enough to scan.
> Run `./gradlew :e2e-runner:scanRun --args "ws://localhost:8080/agent 15"` (or the `:android-client`
> emulator app, which points at `ws://10.0.2.2:8080/agent`) to confirm the agent lists nearby
> devices, and watch them on the dashboard at `http://localhost:8080/`.

## Step 3 — Run the live E2E (Mac)

```sh
REMOTE_BLE_TOKEN=secret ./gradlew :e2e-runner:jvmRun --args "ws://localhost:8080/agent"
```

It runs a series of checks — including the 0.8.3 exact-completion assertions (F) — and prints
`PASS`/`FAIL` per step (process exits non-zero on any failure). It now pauses at three points for a
debug-control toggle on the phone (bump the readable value, force a write error on, force it off
again) plus the existing notify prompt:

```
• Transport connects ... PASS — CONNECTED
• Scan finds the peripheral ... PASS
• Connect + discover services ... PASS — 1 services
• Locate profile characteristics ... PASS
• Read the readable characteristic (baseline) ... PASS — 00
  >>> Now change the readable characteristic's value on the phone ('Bump readable value'), then press Enter <<<
• Read exactness (F) — reflects the just-set bump, not a stale/cached value ... PASS — 01
• Write (with response) ... PASS
• Write (without response) ... PASS
• Negotiated MTU write length ... PASS — 244 bytes
  >>> Now toggle 'Force write error' ON on the phone, then press Enter <<<
• Write-with-response error surfaces WRITE_FAILED (F) ... PASS — WRITE_FAILED as expected
• WWR still returns Ok despite the same peripheral-side reject (inherent BLE limit, not a bug) ... PASS — Ok, as expected
  >>> Now toggle 'Force write error' OFF on the phone, then press Enter <<<
• Write-with-response succeeds again — a failed write never poisons the session ... PASS
  >>> Now press 'Notify (counter +1)' on the phone TWICE (within 60s) <<<
• Observe 2 notifications, no miss/dup ... PASS — 2 received: 01 02
• Disconnect ... PASS
RESULT: 14 passed, 0 failed
```

When prompted: change the readable value, toggle the write-error control on then off, and press
**Notify** twice. A green run proves the headline promise on real hardware — app code written against
Kable's `Peripheral` ran unchanged against a remote agent — **and** that read/write-with-response
completion is exact while WWR/notify remain best-effort by BLE design, not by implementation gap.

## What to shake out (EngineBleBackend)

This is the first time [`EngineBleBackend`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/EngineBleBackend.kt)
meets a real radio. There is no polling anywhere in this path — `discover`/`read`/`write`/`observe` are
direct Kable suspend calls that resume on the real GATT completion callbacks. Watch for (see
[design-decisions.md](design-decisions.md)):

- **Reads** are exact — `peripheral.read()` resumes on Kable's `onCharacteristicRead` — verify the
  value returned is the one just set on the phone (this is confirming exactness, not "polling
  artifacts").
- **Writes (with response)** are exact — resume on `onCharacteristicWrite`/the ATT write response —
  confirm the phone's write log matches. **Writes without response** resume as soon as the write is
  handed to the local controller; there is no ATT acknowledgement to wait for (a BLE protocol property,
  not a gap in this backend) — confirm `Ok` still returns even when the peripheral would reject it.
- **Notification streaming** uses Kable's `observe` Flow (CCCD-enable awaited on first collect, then
  unacknowledged pushes) — watch for missed or duplicated deliveries, but there is no polling/timeout to
  tune here.
- **MTU** — confirm `maximumWriteValueLengthForType` reflects a real negotiated value, not the ATT
  default of 20 (= 23 − 3).

## Follow-ups this bring-up exercises

Two items only a real peripheral can validate:

- **Agent-side unsolicited-drop events.** Tap **Force disconnect all** on the phone mid-session and
  confirm the agent emits a `ConnectionState(DISCONNECTED)` that drives `RemotePeripheral` to
  `State.Disconnected` (today the agent only emits on an explicit `Op.Disconnect`; a backend
  connection-state stream is the clean follow-up).
- **Write-without-response throughput.** Drive a burst of `WriteType.WithoutResponse` writes and
  measure throughput/latency (`:e2e-runner`'s `ThroughputMain`); this establishes the baseline any
  coalescing design (0.8.3 feature C) is measured against.
- **Write-without-response *ordering* under pipelining (0.8.3 / C).** After measuring the baseline,
  drive `RemotePeripheral.writeWithoutResponseBurst` with `window > 1` and confirm the peripheral's
  write log records the payloads in submission order. Both agents now guarantee this in code: the
  Kotlin agent chains writes per device (asserted by
  `BleAgentTest.concurrentWritesToOneDeviceReachBackendInSubmissionOrder`), and the 0.9.0 addendum
  added the same reservation to the Rust agent (asserted by
  `transport::server::tests::cancelled_write_reservation_unblocks_the_next_write`). Run this check
  against both agents before tagging to confirm the guarantee holds on a real radio, not just in CI.

The **error paths** (write-with-response `WRITE_FAILED`, WWR still `Ok` on the same reject) are now
exercised automatically by the runner's "Force write error" prompts above, not a separate manual step.

## What a successful run proves

1. The agent runs on the Mac against a real radio.
2. `:e2e-runner` reports **14 passed, 0 failed** against your test peripheral.
3. The `EngineBleBackend` exact-completion behavior (read, write-with-response, notify-enable) is
   confirmed on real hardware, and the inherent WWR/notify best-effort limits are confirmed as
   BLE-design properties, not implementation gaps.
4. Both follow-ups above are verified (unsolicited drop, write-without-response throughput baseline).

## 0.10.0 hardware-validation gate

In addition to the real-radio checks above, the 0.10.0 Maven Central release requires this deferred
validation bundle:

1. Run the mobile-agent lifecycle on iOS hardware, including start, stop, cancellation, and failure
   recovery evidence. The Android JVM host suite already covers the shared runner core.
2. Put a credentialed loopback-bound agent behind a TLS-terminating reverse proxy and connect via
   `wss://`. Verify the WebSocket upgrade, bearer forwarding, certificate trust, reconnect, and live
   notification delivery against the test peripheral.
3. Archive the exact CI, version-consistency, real-radio, iOS, and TLS results with the release
   commit/tag before starting the Maven Central publication workflow.

These requirements are intentionally deferred from 0.9.1; they are mandatory release evidence for
0.10.0.
