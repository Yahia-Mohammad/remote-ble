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

It runs nine checks and prints `PASS`/`FAIL` per step (process exits non-zero on any failure):

```
• Transport connects ... PASS — CONNECTED
• Scan finds the peripheral ... PASS
• Connect + discover services ... PASS — 1 services
• Locate profile characteristics ... PASS
• Read the readable characteristic ... PASS — 00
• Write (with response) ... PASS
• Write (without response) ... PASS
• Negotiated MTU write length ... PASS — 244 bytes
  >>> Now press 'Notify (counter +1)' on the phone TWICE (within 60s) <<<
• Observe 2 notifications ... PASS — 2 received: 01 02
• Disconnect ... PASS
RESULT: 9 passed, 0 failed
```

When prompted, press **Notify** on the phone twice. A green run proves the headline promise on real
hardware: app code written against Kable's `Peripheral` ran unchanged against a remote agent.

## What to shake out (EngineBleBackend)

This is the first time [`EngineBleBackend`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/EngineBleBackend.kt)
meets a real radio. Watch for its known approximations (see
[design-decisions.md](design-decisions.md)):

- **Reads** poll the characteristic value by reference identity (no read-complete callback) — verify
  the value returned is the one just set on the phone.
- **Writes** are best-effort (no write-complete callback) — confirm the phone's write log matches.
- **Notification streaming / discovery** use bounded polling — watch for missed or duplicated
  notifications, and tune the polling/timeouts if reads or discovery are flaky.
- **MTU** — confirm `maximumWriteValueLengthForType` reflects a real negotiated value, not the ATT
  default of 20 (= 23 − 3).

## Follow-ups this bring-up exercises

Two items only a real peripheral can validate:

- **Agent-side unsolicited-drop events.** Tap **Force disconnect all** on the phone mid-session and
  confirm the agent emits a `ConnectionState(DISCONNECTED)` that drives `RemotePeripheral` to
  `State.Disconnected` (today the agent only emits on an explicit `Op.Disconnect`; a backend
  connection-state stream is the clean follow-up).
- **Write-without-response coalescing.** Drive a burst of `WriteType.WithoutResponse` writes and
  measure throughput; this is where any coalescing optimization is validated.

Also exercise the **error paths** live: **Toggle write error** on the phone and confirm a write
surfaces `WRITE_FAILED` on the client while the session stays usable.

## What a successful run proves

1. The agent runs on the Mac against a real radio.
2. `:e2e-runner` reports **9 passed, 0 failed** against your test peripheral.
3. The `EngineBleBackend` polling/timeout behavior is confirmed (or tuned) from the live run.
4. Both follow-ups above are verified (unsolicited drop, write-without-response throughput).
