# e2e-runner

A runnable JVM program that drives the RemoteBle **live end-to-end** path:

```
e2e-runner (client SDK) ──WebSocket──▶ :agent (macOS central) ──radio──▶ phone (test peripheral)
```

It exercises the full op set against `TestProfile` (defined in this module) using the **Kable
`Peripheral` API** (`RemoteScanner` → `peripheralFor(REMOTE)` → connect / discover / read / write /
observe). A green run is therefore also proof that app logic written against Kable runs unchanged
against a remote agent. See [`docs/bringup.md`](../docs/bringup.md) for the full
live bring-up procedure, including what a test peripheral app needs to expose.

## What it checks

1. Transport connects to the agent
2. Scan finds `RBTestPeripheral`
3. Connect + discover services
4. Locate the profile's readable / writable / notify characteristics
5. Read the readable characteristic
6. Write (with response) and write (without response)
7. Report the negotiated-MTU write length
8. Observe 2 notifications (you press **Notify** on the phone twice)
9. Disconnect

Each step prints `PASS`/`FAIL`; the process exits non-zero if any step failed.

## Run

```
# 1. Phone: launch your test peripheral app, start advertising.
# 2. Mac:   start the agent (grant Bluetooth permission on first run).
#           Use run-agent.sh, NOT :agent:jvmRun (a bare JVM is killed by macOS TCC).
REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080

# 3. Mac:   run the E2E (same machine can host both agent and runner over localhost).
REMOTE_BLE_TOKEN=secret ./gradlew :e2e-runner:jvmRun --args "ws://localhost:8080/agent"
```

> For a quick radio check without a peripheral, use the scan-only client instead:
> `./gradlew :e2e-runner:scanRun --args "ws://localhost:8080/agent 15"`.

`--args "<ws-url> [token]"` — URL defaults to `ws://localhost:8080/agent`; the token is also read
from `REMOTE_BLE_TOKEN`.

> **Needs hardware.** This is the live bring-up: a Mac with Bluetooth (running the agent) and a
> phone running the test peripheral. It is not part of `./gradlew build` (it only compiles there) —
> it's launched by hand against real devices. The automated fake-backed coverage lives in
> `:client-sdk`'s JVM test suite.
