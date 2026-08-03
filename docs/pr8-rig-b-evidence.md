# PR8 · Rig B — iOS agent lifecycle evidence

Evidence record for [pr8-validation-plan.md](pr8-validation-plan.md) Rig B, per that document's
evidence rule (host/device details, agent version, exact command, redacted result per case).

**Sessions:** 2026-07-29 (night), 2026-07-29 (day) ·
**Status: Rig B COMPLETE — all 6 cases run.**
Nine defects found, **six fixed**, including the carried-over unexplained crash — root-caused and
fixed as the same defect. **Five are not iOS-specific at all**, and one of those invalidates an
assumption behind Rig A's case 1. Rig B's main deliverable is **achieved**: the Apple native Kable
backend delivers ATT errors correctly, so Rig A's two XFAIL gates are confirmed btleplug-specific
rather than a general gap (case 2).

Cases 3, 4 and 5 all **contradict documented behaviour rather than confirming it**: a backgrounded
agent holding a BLE link stays fully reachable (the caveat is wrong in the case that matters), a
killed agent leaves its radio link up on the peripheral (so "no leaked native connections" does not
hold for an abrupt exit), and — the most serious of the three — **tapping Stop did not stop the
WebSocket server** on either mobile platform, leaving the agent listening and authenticating while
the UI showed `Start`, and aborting the process on the next Start. **Finding 8 is fixed and the fix
re-verified on the same hardware**: after Stop the port now refuses connections within ~2 s, and
Stop → Start starts cleanly.

## Rig

| | |
|---|---|
| Host | macOS 26.5.2 (Darwin 25.5.0), arm64, 24 GB RAM, Bluetooth on |
| Toolchain | Xcode 26.6 (17F113), XcodeGen 2.46.0 |
| Signing | Team `K2364Z5744`, **free** Apple developer profile (see prerequisites) |
| iOS agent host | iPhone 14 (`iPhone14,7`), iOS 26.5.2, UDID `00008110-001C55882611401E`, developer mode enabled, LAN `192.168.178.85` |
| Client | `:e2e-runner` on the Mac (`192.168.178.78`), no radio of its own |
| Peripheral | Pixel 8, `com.warsha.ble.peripheral.sample`, `../ble-peripheral` at `5390307`, `TestProfile`, advertising `RBTestPeripheral` |
| Repo commit | Runs made on `b0e5aee` plus the fixes below, now committed as `96b235a`, `3aa665e`, `133e940` |
| Agent version | 0.10.0 |

## Rig prerequisites discovered

Both cost real time and belong in the runbook before the next iOS session.

- **The free developer profile caps the device at three installed apps.** Installing the agent
  failed with `MIInstallerErrorDomain error 13` / `ApplicationVerificationFailed` until one was
  removed. `health-peripheral-ios` was chosen (Rig B reuses Rig A's *Android* peripheral, and
  Rig A case 7 explicitly steers away from an iPhone peripheral). A paid account would remove the
  limit entirely.
- **The profile must be trusted on-device before first launch** — Settings → General → VPN &
  Device Management. Until then `devicectl` refuses with *"its profile has not been explicitly
  trusted by the user"*, which reads like a signing failure but is not one.

## Case results

### Case 1 — Start — **PASS, with a scope correction**

The agent launches, listens, and is reachable from a LAN client. Ktor reports
`Responding at http://0.0.0.0:8080` (mobile binds `0.0.0.0` by design — `MOBILE_LAN_BIND_HOST`).
Probed from the Mac against `192.168.178.85:8080`:

| Request | Result |
|---|---|
| `GET /agent`, no credential | **401** |
| `GET /agent`, wrong bearer | **401** |
| `GET /agent`, correct bearer, non-WebSocket | **400** (not an upgrade) |

That is the bearer gate working over a real network, not loopback.

**Scope correction — the dashboard half of this case is unreachable on any mobile agent.**
The plan asks to confirm "its dashboard/`/api/state` is reachable". Both return **404**, because
`dashboardRoutes` is registered only when *both* `statusMonitor` **and** `operatorCredentials` are
non-null
([AgentWebSocketServer.kt](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/AgentWebSocketServer.kt)),
and no mobile entry point supplies an operator token. The desktop agent logs the same
`status dashboard disabled: configure a separate operator credential` warning when run without one.

**Correction (2026-07-30).** This was first written up as **structural** — "`operatorToken` is
settable only from `jvmMain`'s CLI/env" — and that is **wrong**. `AgentConfig.operatorToken` is a
plain `commonMain` field (`di/AgentModule.kt`), `AgentWebSocketServer` is `commonMain` and takes it
directly, and `AgentModule` already forwards `config.operatorToken` on every platform. Nothing about
`mobileMain` prevents it. What actually happens is that `AgentApp` constructs
`AgentConfig(bindHost = MOBILE_LAN_BIND_HOST)` and thereafter only ever `copy(authToken = …)`, so
`operatorToken` stays `null` — and `MainActivity` does the same. **This is a wiring gap, the same
class as follow-up 8, not an impossibility**, and a mobile agent would serve the dashboard the moment
its entry point passed one.

The one genuine cost, and the likely reason nobody wired it: `AgentWebSocketServer`'s `init`
**requires the operator token to be distinct from every client credential**, so it cannot reuse the
token the user already typed — the UI would have to collect a *second* secret on a phone keyboard.
**Resolved 2026-07-30: the field was added** (item 20) — optional, blank by default, so the 404 above
remains the untouched default and filling it serves the read-only dashboard. The native `AgentApp` already
shows the same data in-process (its KDoc calls it "a native mirror of the desktop agent's HTML status
dashboard"), so what a mobile dashboard would add is **remote** viewing from another device and
nothing else — the dashboard is read-only, four `GET` routes with no mutation endpoint by design.

What remains true for anyone running this case: **a `404` on `/` is the healthy answer from a mobile
agent as shipped**, and the reachability check should target the WebSocket endpoint (or a `401`)
instead.

**Operator note:** the agent requires a non-blank token *before* Start — the button is disabled
while the masked field is empty (`startEnabled && !token.isNullOrBlank()`), and the token is
persisted on Start, not per keystroke. A fresh install therefore always needs one manual entry.

### Case 2 — Ordinary session + ATT-error gate check — **PASS, 14/14**

Blocked all session by finding 5 (a read that never completed). Once that was fixed, the full
14-step run is green against the iOS-hosted agent with the Apple backend declared
(`REMOTE_BLE_E2E_BTLEPLUG=false`):

```
• Transport connects ... PASS — CONNECTED
• Scan finds the peripheral ... PASS
• Connect + discover services ... PASS — 5 services
• Locate profile characteristics ... PASS
• Read the readable characteristic (baseline) ... PASS
• Read exactness (F) — reflects the just-set bump, not a stale/cached value ... PASS — 01
• Write (with response) ... PASS
• Write (without response) ... PASS
• Negotiated MTU write length ... PASS — 20 bytes
• Write-with-response error surfaces WRITE_FAILED (F) ... PASS — WRITE_FAILED as expected
• WWR still returns Ok despite the same peripheral-side reject (inherent BLE limit, not a bug) ... PASS — Ok, as expected
• Write-with-response succeeds again — a failed write never poisons the session ... PASS
• Observe 2 notifications, no miss/dup ... PASS — 2 received: 01, 02
• Disconnect ... PASS
RESULT: 14 passed, 0 failed
```

#### The ATT-error question is answered: the Apple backend delivers them correctly

This was Rig B's reason to exist. Run with the btleplug gate *active*
(`REMOTE_BLE_E2E_BTLEPLUG=true`), both gated steps report **XPASS**, reproducibly — 3 consecutive
runs, each against a freshly restarted peripheral:

- `Write-with-response error surfaces WRITE_FAILED (F) … XPASS`
- `Write-with-response succeeds again — a failed write never poisons the session … XPASS`

So the two XFAIL gates carried since Rig A are **genuinely btleplug-specific**, not a general
agent gap. They must stay for the btleplug-backed agents (JVM `:agent` on desktop, `agent-rs`),
where they still legitimately fail, and the runner's existing `REMOTE_BLE_E2E_BTLEPLUG` switch is
the correct mechanism — nothing needs removing.

> **Correction, 2026-08-04.** "Genuinely btleplug-specific" was still one generalisation too many —
> this rig could only distinguish *Apple-native Kable* from *btleplug on macOS*, and it read the
> difference as the library. Rig D then XPASSed both steps on **Linux/BlueZ**, so the gates belong to
> btleplug's **CoreBluetooth** backend alone. `REMOTE_BLE_E2E_BTLEPLUG` is replaced by
> `REMOTE_BLE_E2E_AGENT_HOST`; see item 22 in
> [0.10.0-progress-status.md](proposals/0.10.0-progress-status.md). The measurements below stand. What changes is the *knowledge*: the Apple native
Kable backend reports `WRITE_FAILED` on an ATT error and does not poison the connection
afterwards, which [phase7-bringup.md](phase7-bringup.md) had recorded as unverified on hardware.

**One unreproduced anomaly, recorded rather than dropped.** The very first full run after the app
was relaunched reported `FAIL: expected WRITE_FAILED, got AgentException (TIMEOUT)` at that step.
It did not recur in any of the four subsequent runs (3 gated + 1 canonical). Possibly a first-run
CoreBluetooth warm-up effect; cause unknown. Worth watching if the step ever flakes in future runs.

**MTU is 20 bytes** on this host — the bare ATT default (23 − 3), where Rig A measured 244 through
the macOS agent. The step passes either way, which means it is not actually asserting that a real
negotiation happened. Recorded as a follow-up rather than a failure.

### Case 3 — Background caveat — **PASS on the letter, but the documented caveat is wrong**

Run with [`AgentLifecycleMain`](../e2e-runner/src/jvmMain/kotlin/dev/warsha/remoteble/e2e/AgentLifecycleMain.kt)
(`:e2e-runner:agentLifecycleRun`), which holds a live session and records four things on one
timeline: transport state, the radio link (a GATT read every 2 s), wire events, and whether a
*brand-new* inbound connection is still accepted.

**The stimulus needs no human.** Launching any other app backgrounds the agent
(`xcrun devicectl device process launch --device <udid> com.apple.Preferences`), and launching the
agent again *resumes* it rather than cold-starting — it was still serving afterwards with no second
tap of Start. Only the on-screen Stop tap (case 4) still needs an operator.

| Backgrounded for 91 s with… | GATT reads | New inbound connections | Client transport |
|---|---|---|---|
| an active BLE link | **92/92 OK** | **38/38 accepted** | never dropped |
| no BLE link (control) | n/a | **hang from +8 s** | never dropped |

**The plan's expectation — "no new inbound WebSocket connections are accepted" — did not hold, and
the reason is in our own `Info.plist`.** `ios-agent` declares `UIBackgroundModes: bluetooth-central`,
which keeps the process running in the background *while it holds an active CoreBluetooth
connection*. A running process runs its Ktor accept loop too, so a backgrounded agent with a client
mid-session stays **fully reachable** — new connections included. Nothing degraded across 91 s.

The control is what makes this attributable. Same instrument, same stimulus, one variable changed
(`nolink`: transport held, no peripheral connected): the app is suspended ~8 s after backgrounding
and new connections stop being answered. Re-foregrounding restored service within ~5 s.

So the caveat is real only in the case nobody hits — an *idle* agent — and wrong in the case that
matters. Three places state it too strongly and should be corrected: the `Info.plist` comment
("even that only helps already-connected links, not accepting new inbound WebSocket connections
while backgrounded"), `IosAgentEntry`'s `keepScreenOnNotice` ("the agent stops in the background"),
and this case's wording in the plan.

**Both failure modes are distinguishable, and the distinction matters.** A suspended app leaves its
listening socket in the kernel, so a new connection *establishes* and then nothing answers — the
runner classifies that `NO_RESPONSE`. A dead process gives `REFUSED`. A boolean "reachable?" probe
would have erased that difference and made case 3 and case 5 look identical.

**The client's own WebSocket is never closed** in either mode — no FIN, transport stays `CONNECTED`,
the socket just goes silent. A client cannot tell "backgrounded" from "idle" from its own transport.

*Not run:* the **screen-lock** half. The agent disables the idle timer while running
(`UIApplication.idleTimerDisabled = running`), so the screen cannot auto-lock, and a manual lock is
a physical button press. Worth running, but backgrounding is the stimulus that generalises.

### Case 5 — Cancellation mid-operation — **teardown is clean client-side; the radio link is not released**

Stimulus: `SIGKILL` to the agent process (`devicectl device process signal`) while GATT reads were
in flight every 2 s — the scriptable equivalent of swiping the app away. Client-side the teardown is
exactly right:

- transport → `DISCONNECTED`, then the SDK's reconnect loop cycles `CONNECTING`/`DISCONNECTED`,
- every in-flight and subsequent op fails fast and *typed*: `TRANSPORT_LOST: transport not connected`
  (21/21) — no hangs, no `GATT_OP_TIMEOUT` waits, no crash in the runner,
- new inbound connections → `REFUSED (nothing listening)`, correctly distinguished from case 3's
  `NO_RESPONSE`.

**But the radio link outlives the process.** 90 s after the kill, two independent instruments still
report the central connected:

- the peripheral app: no `CentralDisconnected` event, `CONNECTIONS: 1` unchanged;
- `adb shell dumpsys bluetooth_manager` on the Pixel: the iPhone's bonded entry reads
  `[ACL BR/EDR:N LE:Y]` — the LE ACL link is up. The flag is meaningful, not a capability bit: other
  known devices in the same dump read `LE:N`.

That is well past any supervision timeout (≤32 s), so it is not the slow-notice effect Rig A case 2
measured. The likeliest cause is that CoreBluetooth links are owned by `bluetoothd`, not the app, so
killing the app does not terminate a bonded LE connection — but this is **not confirmed**, and how
long iOS keeps it is unmeasured beyond ~2 minutes. It also retroactively explains an oddity from
earlier in the session: a *freshly restarted* peripheral immediately logged `CONNECTIONS: 1` /
`CentralConnected` from the iPhone with no client running.

`SIGKILL` gives the app no chance to run `Coordinator.deinit` → `dispose()` → `runner.stop()`, so
this does not condemn the graceful path — that is case 4, still unrun. What it does establish is
that "no leaked native connections" **cannot be assumed** on iOS when the app dies abruptly, which
is exactly the swipe-to-kill a real user performs.

**Second finding, client-side: `Peripheral.state` never leaves `Connected`.** For the full 70 s
after the agent died it stayed `Connected` while every operation returned `TRANSPORT_LOST`, and no
wire event was emitted (0 seen). A Kable app watching `Peripheral.state` — the idiomatic way to
observe connectivity — would never learn the peripheral is unreachable. It may be deliberate
(transport loss is recoverable; the session is meant to reconcile on reconnect), but an indefinite
`Connected` against a dead agent is misleading. Recorded as a follow-up for a decision, not a fix.

### Case 4 — Stop — **FAIL: the radio is released, the server is not**

Operator tapped `Stop` at **+52.4 s** into a live session (the one case with no injection path on a
physical iPhone). The radio half is exactly right, and notably better than case 5's kill:

- `peripheral -> Disconnected` and a `ConnectionState(…, state=DISCONNECTED, reason=null)` wire
  event, both at +52.4 s — the graceful path *does* notify the client, where the kill did not;
- subsequent reads fail `NOT_CONNECTED: peripheral is not connected` — a more precise error than
  case 5's `TRANSPORT_LOST`.

**But the agent stays fully reachable after Stop.** Across the whole 240 s window — ~187 s of it
after the tap — **not one inbound probe was refused**: 0 `REFUSED`, 0 `NO_RESPONSE`, every one
`ACCEPTED — HTTP/1.1 400`. The client's existing transport never dropped (final state still
`CONNECTED`), and the radio release was not transient either: 84 consecutive reads returned
`NOT_CONNECTED` for the rest of the run. Probed directly afterwards the whole HTTP stack is live:

| Probe after Stop | Result |
|---|---|
| `GET /agent`, correct bearer | **400** (server up, not an upgrade) |
| `GET /agent`, no credential | **401** (auth gate still evaluating) |
| Ktor shutdown in the device console | **nothing logged** |

**Root cause — and it is not iOS-specific.** `AgentRunner.stop()` disconnects the leases and then
calls `graph.close()`, which is only `koinApplication.close()`. Koin drops a `single` by invoking
its `onClose` callback, and
[the module declares none](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/di/AgentModule.kt) —
`single { AgentWebSocketServer(...) }`, no `onClose`. `AgentWebSocketServer` is not `AutoCloseable`
either, so nothing calls its `stop()`. The desktop agent does not have this bug because
[`Main.kt`](../agent/src/jvmMain/kotlin/dev/warsha/remoteble/agent/Main.kt) calls `server.stop()`
explicitly; only the mobile composition root relies on Koin to do it.

`AgentRunner` then reports `AgentStopResult(serverStopped = graphClosed, …)` — it infers
"server stopped" from "graph closed", which is precisely the assumption that does not hold. The
returned value is wrong, not merely incomplete.

**This lives in `mobileMain`/`commonMain`, so `android-agent` is affected identically.** There is no
iOS-specific code on the path. Rig B's fourth non-iOS finding. Confirmed on Android after the fix —
see [the Android confirmation](#android-confirmation-2026-07-29-evening) below.

**The UI actively asserts the opposite, which is what makes this a safety issue rather than an
untidy teardown.** Operator-confirmed at the end of the run: the app was showing the **`Start`**
button, i.e. `AgentRunner` had completed `stop()` and flipped `running` to `false`. So every signal
available to the user — the button, the runner state, and `AgentStopResult.serverStopped` — says the
agent is off, while the port keeps accepting authenticated connections. A user who taps Stop
reasonably believes the phone is no longer serving; any client on the LAN holding the token can
still connect and authenticate. Only device operations fail, and only because the radio was released
separately. `ConnectionWatcher` and the `"agent"`
`CoroutineScope` are dropped by the same unhooked path and are presumed still running too — not
separately verified.

#### Fix applied (2026-07-29)

`AgentRunnerGraph` gained `stopServer()`, implemented by `KoinAgentRunnerGraph` as `server.stop()`,
and `AgentRunner.stop()` now calls it **before** closing the graph — the same order `Main.kt`'s
shutdown hook uses (disconnect leases → `server.stop()`). `serverStopped` reports that call's real
outcome instead of being derived from `graphClosed`.

Chosen over an `onClose` in the Koin module deliberately: the module is shared with the desktop
agent, so an `onClose` would change `Main.kt`'s teardown too, and the explicit call keeps the
teardown order visible at the call site rather than hidden in DI configuration.

The stop runs on `Dispatchers.Default`, not the caller's. Both `server.stop()` (which waits out its
grace + timeout, 600 ms by default) and `Koin.close()` block, and `AgentApp`'s `onStop` calls
`runner.stop()` straight from the composition scope — i.e. `Dispatchers.Main`. Fixing the leak
without this would have traded an open port for a frozen UI.

Two regression tests in `AgentRunnerTest` — one asserting the server is actually stopped, one
asserting a *failing* stop is reported rather than assumed successful. Both were mutation-checked:
reinstating the original two lines (drop the `stopServer()` call, derive `serverStopped` from
`graphClosed`) fails exactly these two tests and nothing else. That matters because the previous
suite could not have caught this — it only ever asserted the lease half of teardown, and
`serverStopped` was structurally incapable of reporting a still-open port.

**Verified on the same hardware** (debug XCFramework, rebuilt and reinstalled — the release link
OOM'd at `-Xmx16g` with the host at ~33 % free, per follow-up 5; the debug framework linked in 63 s):

| Check | Before | After |
|---|---|---|
| `GET /agent` after Stop | `400` for the full 240 s window, 0 refusals | **connection refused**, ~2 s after the tap |
| no-credential probe after Stop | `401` (auth gate still live) | **connection refused** |
| probe latency once closed | — | **300 ms** — refused outright, not the ~5 s `NO_RESPONSE` hang of an orphaned listener |
| Stop → Start | `SIGABRT`, `EADDRINUSE` | **starts cleanly, no crash** |
| app process after Stop | alive | **alive** — Stop stops the server, it does not kill the app |

The restart succeeding is independent confirmation of the port half: a fresh `AgentWebSocketServer`
could only bind `:8080` if the previous one had genuinely released it.

#### Android confirmation (2026-07-29 evening)

Pixel 8, `android-agent` debug build, token `secret`, **three consecutive Stop → Start cycles**:

| Check | Result |
|---|---|
| running, bearer / no credential | `400` / `401` — the pre-fix iOS signature, i.e. the same server behaviour |
| after Stop | connection **refused within the first probe**, ≲1 s after the tap, all three cycles |
| `ss -ltn` after Stop | no `*:8080` listener; it reappears after each Start |
| app PID | **29506 throughout all six taps** — the process never died and never restarted |
| logcat | no `EADDRINUSE`, no `AndroidRuntime` fatal, no abort |

Gap 14 is closed on both platforms. Two limits worth stating rather than leaving implied:

- **The lease-disconnect half of `stop()` is not covered on Android.** It needs a second radio — the
  Pixel is also the rig's peripheral, and it cannot connect to itself. Only the server half ran.
- **The JVM-vs-Native uncaught-handler question is now moot for this path.** Finding 10 predicted
  the JVM would log rather than abort; with the fix there is no bind failure to survive, so the
  prediction stayed untested. It still matters for follow-up 16, where the port is held by *another*
  app.

#### The Stop → Start crash is the same defect (finding 10, now root-caused)

Carried since the night session as an unexplained operator report plus a bare `signal 6`.
Reproduced on the first attempt with the console attached, and it is a **direct consequence of the
above** — not a separate bug:

```
Uncaught Kotlin exception: io.ktor.utils.io.errors.PosixException.AddressAlreadyInUseException:
    EADDRINUSE (48): Address already in use
    at kfun:io.ktor.network.sockets.tcpBind$$inlined$buildOrCloseSocket$1…
    at kfun:io.ktor.server.cio.backend.httpServer$acceptJob$1.$invokeCOROUTINE$0.invokeSuspend
    at kfun:kotlinx.coroutines.MultiWorkerDispatcher…workerRunLoop$1…
App terminated due to signal 6.
```

The chain: `Stop` leaves the old Ktor server bound to `:8080` (finding 8) → `Start` builds a fresh
graph whose `AgentWebSocketServer` tries to bind the same port → `EADDRINUSE`.

**Why `AgentRunner.start()`'s `try/catch` does not turn this into a clean `AgentStartResult.Failed`:**
`AgentWebSocketServer.start()` ends in `instance.start(wait = false)`, so the bind happens
*asynchronously* on a CIO accept job, not on the `start()` call path. `start()` has already
returned `Started` by the time the bind fails, and the exception surfaces on a
`MultiWorkerDispatcher` worker with no handler — which on Kotlin/Native means the whole process
aborts. On the JVM the same failure would be a logged uncaught exception in a coroutine, not a
process kill; **Kotlin/Native is what turns it into `SIGABRT`.**

Note `[INFO] Application started in 0.0 seconds` is logged *before* the bind fails, so the log
claims a successful start immediately before the abort.

Fixing finding 8 removes this crash, and that fix is applied above. Two things remain worth doing
independently (follow-up 16): surface a bind failure as a real `AgentStartResult.Failed` — today it
cannot be reported at all, whatever the cause, so a port genuinely occupied by *another app* still
aborts the process. The second half of this — confirming the sequence on `android-agent` — **ran on
2026-07-29 evening** and is clean; note it did *not* test the JVM's uncaught-exception handling,
because with finding 8 fixed there is no bind failure left to handle.

### Case 6 — Failure recovery — **PARTIAL PASS: no crash, clean recovery, no messaging**

**The stimulus had to be replaced first.** The case says "deny the Bluetooth permission prompt on
first launch". **There is no such prompt.** On a fresh install of a fresh bundle id none appeared at
launch, at Start, or on the first scan — the first scan simply succeeded and returned 35 devices. It
was expected at Start (the Koin graph, and the Kable backend with it, is built in
`AgentRunner.start`), then at first radio use; neither happened. The case was therefore *unrunnable*
as written, not failing.

Replaced with **Settings → Bluetooth → off**, which tests the same property — graceful degradation
and recovery when the radio is unavailable — through a state that actually exists.

> **It must be Settings, not Control Centre.** Since iOS 11 the Control Centre toggle only
> disconnects accessories and leaves Bluetooth available to apps, so it applies no stimulus at all.
> The stimulus was verified rather than assumed: `scanRun` was polled until the count actually
> reached 0, after Rig B's earlier lesson about null runs.

| Step | Result |
|---|---|
| Baseline `scanRun` | **34 devices** — radio healthy |
| Bluetooth off → `scanRun` | **0 devices, no error, no exception** |
| Agent server while off | still listening (`HTTP 400`), app alive |
| Stop → Start with the radio off | **succeeds and reports success**; 6 Ktor starts across the run, **0 crash lines** |
| Bluetooth on → `scanRun` | **32 devices, recovered with no agent restart** |

**What passes:** no crash under any of it, the server is unaffected by radio state, and recovery is
automatic — Bluetooth comes back and scanning resumes with no Stop/Start. That is the substance of
"failure recovery" and it holds.

**What fails: the "clear messaging" half.** Nothing anywhere reports that the radio is unavailable —
not the UI, not the console, not the wire. A scan with Bluetooth off is indistinguishable from a
scan in an empty room: both return zero devices with no error. A user sees an agent that says it is
running and finds nothing.

#### The messaging gap is **not** iOS-specific — corrected mid-case

The obvious framing was "Android gates Start and warns, iOS doesn't". **That is wrong for this
stimulus**, as the operator pointed out from the Android app showing nothing either. Android's
`bluetoothPermissionsGranted` is set from `hasBluetoothPermissions()` and the permission-grant
callback — it tracks the **runtime permissions** (`BLUETOOTH_SCAN`/`CONNECT`), not the adapter. A
grep across `agent/src` and `android-agent/src` finds **nothing** referencing `BluetoothAdapter`
state, `ACTION_STATE_CHANGED`, `STATE_OFF`, or `CBManagerState`/`poweredOff` — only a doc comment.

So there are two distinct gaps, and the bigger one is shared:

1. **Adapter-off is invisible on both platforms.** Neither agent detects or reports that Bluetooth
   is switched off. Both degrade silently. Rig B's *fifth* non-iOS finding.
2. **iOS additionally has no permission gating wiring at all.** `MainActivity` passes
   `startEnabled = bluetoothGranted`, a `permissionWarning`, and `onRequestPermissionSettings`;
   `IosAgentEntry` passes none of the three, so they default to `true`/`null`/`null`. Android's
   gating covers permission denial — a state iOS never presented a prompt for in the first place.

Both platforms already expose the state they need: `CBCentralManager.state` on Apple and
`BluetoothAdapter.isEnabled` + `ACTION_STATE_CHANGED` on Android. Neither is consumed.

## Findings

Ten, seven fixed. **Four are not iOS-specific** (3, 8, 9, 10), which is the most important thing
this rig has produced. The count in this line was stale from the night session — it read "seven,
four fixed" after three more findings had been appended below it.

| # | Finding | State |
|---|---|---|
| 1 | `ios-agent` did not compile — app module name collided with the framework | fixed |
| 2 | `ios-agent` aborted on launch — missing plist key | fixed |
| 3 | Kotlin agent never forwarded `serviceUuids` (any platform) | fixed |
| 4 | Kable does not surface a scan-response local name on Apple hosts | **open by decision** — worked around in the runner. **Scope questioned 2026-07-30:** the write-up says null on *both* the macOS and iOS hosts, which contradicts Rig A recording the name arriving off the same peripheral. Likely a backend seam (JVM/btleplug vs Apple), not a host one — see gap 9 |
| 5 | **iOS reads against a *discovered* characteristic never completed** — Kable matched completions by characteristic *reference*, which Apple does not preserve | **fixed** (`forceCharacteristicEqualityByUuid = true`); case 2 now 14/14. **Narrowed 2026-07-30 (item 18):** a lazy `characteristicOf(uuid, uuid)` read was unaffected all along — it resolves by UUID, so reference identity never entered the matching. "Every read on iOS" was too broad |
| 6 | The documented background caveat is **wrong** while a BLE link is held — `bluetooth-central` keeps the agent fully reachable, new connections included | **fixed** — corrected in four places, not the three first counted (case 3, follow-up 13). **One exception found 2026-07-30 (gap 15):** it cannot **discover** while backgrounded — an unfiltered scan returns 0 devices where a foregrounded one returns 38. Serving is unaffected; a service-filtered scan is unaffected |
| 7 | A killed agent **leaves the BLE link up** — the peripheral still counts the central connected **≥26 minutes** later (measured 2026-07-30; was ≥90 s), on an app-scoped instrument validated in the negative direction | open; **cause still unconfirmed**, and it may not be iOS at all — the surviving entry may ride on an ACL these two *bonded* phones maintain for unrelated system reasons. One run against an unbonded peripheral settles it (gap 13) |
| 9 | **FIXED 2026-07-29 (evening), verified on Android and iOS hardware (gap 17).** Neither agent noticed Bluetooth being switched off — scans return 0 devices with no error, indistinguishable from an empty room; no UI, console or wire signal. `CBCentralManager.state` / `BluetoothAdapter.isEnabled` are now used, behind one process-wide `AgentRadio` source shared by the backend and the UI. **Android was affected identically** | **fixed** — a gated `AgentEvent.RadioState` plus `ErrorKind.RADIO_OFF` on the wire, and a UI notice; the iOS half needed a strong delegate reference (case 6, gap 17) |
| 8 | **`Stop` never stops the WebSocket server** — Koin has no `onClose` for it, so the agent keeps listening and authenticating; `AgentStopResult.serverStopped` reports `true` regardless. **Android is affected identically** | **fixed + verified on iOS and Android hardware** — `AgentRunnerGraph.stopServer()`, called by `stop()` off the Main dispatcher; port refuses ~2 s after the tap on the iPhone, ≲1 s on the Pixel (case 4) |
| 10 | **Stop → Start aborts the process** (`SIGABRT`) — `EADDRINUSE` from finding 8's orphaned listener, thrown on a CIO worker with no handler. **Root-caused; same defect as 8** | **fixed via 8, verified on iOS and Android hardware** — Stop → Start starts cleanly, three consecutive cycles on the Pixel with an unchanged PID; the unreportable async bind failure remains open as follow-up 16 (case 4) |

### 1 — `ios-agent/project.yml`: the launcher shell never compiled

The app target's Swift module was named `RemoteBleAgent`, identical to the Kotlin framework, so
`import RemoteBleAgent` resolved to the app's own module and was discarded
(*"file 'ComposeView.swift' is part of module 'RemoteBleAgent'; ignoring import"*), leaving
`IosAgentSession` out of scope and failing the build.

[`ios-client/project.yml`](../ios-client/project.yml) already solves exactly this, with a comment,
via `PRODUCT_MODULE_NAME` — plus an `EXCLUDED_ARCHS[sdk=iphonesimulator*]` setting for the absent
`iosX64` slice. `ios-agent` had neither. Both were applied, mirroring the sibling's wording.
`:agent` likewise declares only `iosArm64` + `iosSimulatorArm64`, so the second setting applies
equally. This confirms the module README's warning literally: the iOS launcher had never been
built to completion.

### 2 — `ios-agent/Info.plist`: missing `CADisableMinimumFrameDurationOnPhone`

Compose Multiplatform's `PlistSanityCheck` treats this key as a **hard launch-time abort**, not a
warning. Every launch died with `SIGABRT` (signal 6) before any UI appeared. Added with a comment
explaining that it is a launch blocker rather than a performance hint.

**`ios-client/Info.plist` is missing the same key** and would abort identically — meaning the iOS
*client* app has never been launched to completion either. Left unfixed as out of Rig B's scope;
it is a one-line change.

### 3 — `EngineBleBackend.scan` never populated `serviceUuids`

`AdvertisementDto` has carried `serviceUuids` and `manufacturerData` since 0.8.x and `agent-rs`
populates them, but the Kotlin backend constructed the DTO with only `device`, `name` and `rssi`.
Every Kotlin-agent client has therefore seen an empty service-UUID list and could not identify or
filter a peripheral by service, on **every** platform — not just iOS.

Fixed by mapping `advertisement.uuids`. After the fix the Kotlin agent reports 7 UUID-carrying
devices off the same radio and in the same window as `agent-rs` reports 7 — including
`RBTestPeripheral … uuids=[a1b2c3d4-0000-4000-8000-000000000001]` at the same CoreBluetooth
identifier `06eb7989-…` the Rust agent saw. `manufacturerData` is still dropped; untested here and
left alone.

### 4 — Kable does not surface a scan-response local name on Apple hosts

`RBTestPeripheral` advertises its service UUID in the primary PDU and its local name in the **scan
response** — it has to, because a 128-bit UUID plus flags plus the name overflows a 31-byte legacy
PDU (`../ble-peripheral` `369f265`). Through the Kotlin agent, `advertisement.name` is **null** for
this peripheral on both the macOS and iOS hosts. `agent-rs`, off the same radio at the same moment,
reports it by name. So the scan-response name never reaches the agent through Kable on Apple.

A `name = advertisement.name ?: advertisement.peripheralName` fallback was tried and **rejected on
evidence**. `peripheralName` is the platform's *cached* GAP name, so it is host-dependent: the same
peripheral, at the same moment, read as `RBTestPeripheral` through the Mac agent (cold cache) and
`Pixel 8` through the iPhone agent (the iPhone had seen that device before). A name that varies by
agent host is worse than no name — a client matching on it gets a silently different answer per
host. The repo has made this call before, preferring an honest XFAIL to a relaxed assertion.

**Resolution:** `name` stays strictly the advertised local name, null when absent. `:e2e-runner`
now discovers by the TestProfile **service UUID** rather than by name — which is the actual
profile contract and is present in the primary PDU on every path.

**Consequence for Rig A — carried below.**

### 5 — the iOS-hosted agent never completes a characteristic read

Connect and service discovery succeed, the `TestProfile` characteristics are located, and then
`peripheral.read()` never resumes — the operation is cut off by `GATT_OP_TIMEOUT` after 10 s.
Reproducible 5/5. The same read, against the same peripheral, succeeds through the macOS-hosted
Kotlin agent.

**Isolation.** Three wrong explanations were entertained and each was killed by evidence, so the
sequence is worth recording:

| Hypothesis | How it was ruled out |
|---|---|
| Stale peripheral GATT state (Rig A's operator note) | Force-stopped and relaunched the peripheral app. macOS then reached step 10; iOS still failed at step 5. |
| Reads are broken on the Kotlin agent generally | The macOS host passes the same read against the same fresh peripheral. |
| Unanswered/half-formed pairing — the operator was dismissing bonding dialogs | The old bond was removed and a fresh one completed cleanly (`BT_BOND_STATE_BONDED`, Just Works, no dialog). The read still failed. |
| The peripheral changed state when it re-bonded | A macOS control run immediately afterwards, against that same bonded peripheral, passed the read. |

What is left is a single varying axis: **the agent's host.** macOS/btleplug reads; iOS/CoreBluetooth
does not.

**FIXED (2026-07-29).** One line, in
[`PeripheralByIdentifier.ios.kt`](../agent/src/iosMain/kotlin/dev/warsha/remoteble/agent/PeripheralByIdentifier.ios.kt):

```kotlin
@OptIn(ObsoleteKableApi::class)
actual fun peripheralByIdentifier(identifier: Identifier): Peripheral = Peripheral(identifier) {
    forceCharacteristicEqualityByUuid = true
}
```

**Root cause.** Kable matches a completion back to its pending operation by comparing the
characteristic **by reference** unless told otherwise (`forceCharacteristicEqualityByUuid`,
default `false` — "compare by reference"). Apple's CoreBluetooth can hand back a *different*
`CBCharacteristic` instance than the one the operation was issued against, so the comparison fails,
the continuation is never resumed, and the read suspends until `GATT_OP_TIMEOUT` fires. The iOS
factory was constructing a bare `Peripheral(identifier)` with no builder block, so it took the
by-reference default. Writes were unaffected because they resolve through a different path.

With the flag set, the read completes normally and the full 14-step case 2 passes.

**Attribution.** The lead came from the operator noticing Kable 0.44.0's *"Add timeout for
write-without-response … writes will no longer wait indefinitely for Core Bluetooth to notify it is
ready for the next write."* That is a different operation, so it is not this bug — but it is the
same failure *shape* (an Apple operation awaiting a Core Bluetooth signal that never arrives), and
it redirected the search to Kable's Apple layer and its documented workarounds.

**This is our wiring, not a Kable defect.** The workaround is a documented, first-class Kable
option; we simply never set it. Worth reviewing whether the Android and JVM factories want it too —
they are unaffected here, but the reasoning is platform-specific and currently undocumented in
those files.

---

**How it was root-caused (2026-07-29).** Two diagnostics narrowed it before the fix was found.

*Does the read reach the peripheral?* **Yes.** Read logging was added to the sample peripheral's
three `onRead` handlers (`../ble-peripheral` — writes logged, reads did not, which is precisely why
this was ambiguous). With it, every timed-out read appears on the peripheral, answered:

```
READ readable -> 00 from 7F:11:FF:6E:5D:0D
READ readable -> 00 from 7F:11:FF:6E:5D:0D
```

The peripheral receives the ATT read and returns `readSuccess(00)`. The completion simply never
surfaces to the agent. Each logical read is logged **twice**, consistent with a retry after no
completion arrives.

*Is it all GATT ops, or reads specifically?* **Reads specifically.** `writeErrorProbeRun` issues a
single write-with-response — which awaits an ATT completion exactly as a read does — on the same
connection and session:

```
issuing write-with-response 0xEE …
RESULT after 118.251333ms: Ok(payload=null)
link still usable — follow-up read returned: Err(AgentError(kind=TIMEOUT, message=read did not complete within 10s))
```

**118 ms for the write; 10 s timeout for the read on the same link.** The peripheral logs
`WRITE ee` between the reads, so both ops reach it and both are answered.

**Three hypotheses were proposed and each was disproven by experiment**, which is the part worth
keeping — every one of them sounded convincing when proposed:

| Hypothesis | Disproven by |
|---|---|
| Our `checkLiveness` background read collides with the client read on Apple | Parked the probe (`livenessProbeInterval = 1.hours`), rebuilt, redeployed — read still failed |
| Bonding / encryption: reads fail on bonded links | Cleared the bond on **both** sides (`Bonded devices: 0`, fresh unbonded address) — read still failed |
| An asymmetric bond (iPhone forgot, Pixel retained) | Clearing the Pixel's half too changed nothing |

The macOS control passed the same read throughout, on the same peripheral, at every stage.

## Carried to other rigs / follow-ups

1. **The `Peripheral` builder is unconfigured on every platform.** Finding 5 was a Kable builder
   option we never set. `PeripheralByIdentifier.{android,jvm}.kt` construct bare peripherals too;
   `writeWithoutResponseTimeout` and `disconnectTimeout` are likewise defaulted everywhere. Worth a
   deliberate pass over what those defaults should be, rather than discovering them one rig at a time.
2. **Rig A case 1 needs re-examination.** It asserted `12 passed … on both agents`, which required
   an exact name match on the Kotlin agent. Given finding 4, that agent could not have seen this
   peripheral's advertised name; the run most plausibly passed on a warm CoreBluetooth GAP-name
   cache on the Mac. The result is not necessarily wrong, but the reason it passed is not the
   reason recorded, and it would not reproduce on a cold host.
3. **`ios-client/Info.plist`** — same missing plist key as finding 2; the client app cannot launch.
4. **What actually consumes 14+ GB in the release link.** Worth writing down, because the size is
   counter-intuitive: no individual module is large, and every other build step is cheap.

   Measured this session:

   | Task | Outcome |
   |---|---|
   | `compileKotlinIosArm64` (per-module) | fine, never OOMs |
   | `linkDebugFrameworkIosArm64` | **succeeds**, 1m12s, no memory trouble |
   | `linkReleaseFrameworkIosArm64` | **OOMs** at `-Xmx12g` and `-Xmx14g` |

   Only the *release* link fails, which is what identifies the cost. Per-module compilation is
   modular — each module sees its own sources plus klib headers. The release link is the one step
   that performs **whole-program optimization**: Kotlin/Native builds a single in-memory call graph
   and type hierarchy spanning the *entire* transitive graph (Compose Multiplatform, Ktor, Kable,
   coroutines, kotlinx.serialization, Koin) and runs devirtualization analysis over all of it at
   once. That analysis is global and non-incremental by nature, so it cannot be split across
   processes and does not shrink because the individual modules are small — it scales with the
   whole dependency closure, and Compose Multiplatform alone contributes an enormous type
   hierarchy. The existing `gradle.properties` comment names the failing phase
   (`DevirtualizationAnalysis`), which matches.

   Practical consequences: the demand varies with how much the link genuinely has to redo, so an
   incremental run can pass at a heap size a cold run fails at — do not treat one green build as
   proof a value is sufficient. And a debug framework is a valid fallback for on-device testing
   when the release link will not fit; it links in ~1 minute and needs no special heap.
5. **`kotlin.native.jvmArgs` sizing is empirical.** Six release-link attempts this session:
   12g OOM'd at both 43 % and 70 % free; 14g succeeded once on a warm incremental link and OOM'd at
   79 % free on a colder one; 16g linked cleanly at 70–79 % free but **failed to allocate** at 60 %.
   Now set to `-Xmx16g` with those measurements recorded in `gradle.properties` (the previous
   comment claimed 12g was "safe on a 16GB+ host", which is wrong). Note the two distinct failure
   modes: too small → OOM mid-link; too large for available RAM → the JVM never starts. Both
   surface as a failed build and are easy to confuse.
6. **`../ble-peripheral` does not restore the adapter name on stop.** The SDK's "crash-safe
   temporary adapter rename" (`a9092e0`) renames the Bluetooth adapter to `RBTestPeripheral` while
   advertising. After a clean `Stop server` the adapter was still named `RBTestPeripheral`, so the
   restore half does not run. Leaves the test phone's Bluetooth name wrong until fixed by hand.
7. **Report radio-unavailable state, on both platforms** (case 6, finding 9) — **IMPLEMENTED
   2026-07-29 evening; verified on Android hardware, iOS not yet run.** A `BleBackend.radioState`
   signal now feeds both the agent UI and the wire: `AgentEvent.RadioState` and
   `ErrorKind.RADIO_OFF`, both gated behind the `radio.state` capability so a v1 client's decode
   loop is untouched. Android uses `BluetoothAdapter` + `ACTION_STATE_CHANGED`, Apple uses
   `CBCentralManager.state`, and the JVM/btleplug backend declines the capability because it cannot
   observe its adapter at all.

   Worth recording for the next person who reaches for it: **Kable's own `Bluetooth.availability`
   is `@Deprecated` as of 0.43.1** — *"has inconsistent behavior across platforms. Will be removed
   in a future release"* (JuulLabs/kable#737). It is the obvious-looking cross-platform answer and
   it is a dead end. Checking the library's API first was right (note 8); the answer this time was
   that the library had already given up on the problem.

   Wire behaviour confirmed end-to-end against the Pixel 8 with a client negotiating the
   capability: `ON` → scans accepted → `OFF` → `RADIO_OFF` → `ON` → accepted, driven by real
   adapter toggles rather than injection. **Confirmed on the iPhone 14 as well**, second attempt —
   `ON` → `OFF` → 7 × `RADIO_OFF` → `ON`, 23 accepted / 7 rejected / 3 events.

   **The first iPhone attempt failed, and is the more useful half of this entry.**
   `CBCentralManager` holds its delegate weakly and the returned `StateFlow` referenced nothing
   else, so the delegate died when the factory returned — after delivering the initial state. A
   client therefore saw `radio -> ON` and then silence through a real off/on cycle, with 30
   consecutive scans accepted against a radio that was off. Nothing on JVM or Android could have
   caught it: it is Objective-C reference semantics, not Kotlin logic, and the source is correct on
   every other platform. See method note 19.

   One expectation this run corrected: **iOS does error a live scan when the radio is switched
   off** — the operator saw `"scan #1 ended on error: Bluetooth disabled"` in the agent's own
   activity log during the first attempt, when the gate was not yet firing. The write-up (and
   `protocol.md`) had claimed a radio-off scan is never an error on any platform, which is true of
   the Android behaviour that motivated the gap and false here. Corrected in place; the event is
   still worth having, because that error only reaches a client that is mid-scan at the moment the
   radio dies.
8. **iOS permission gating** — **FIXED AND VERIFIED ON HARDWARE 2026-07-30.** `IosAgentEntry` passed
   none of `startEnabled` / `permissionWarning` / `onRequestPermissionSettings`, where `MainActivity`
   passes all three (case 6). Note Android's gating keys on *runtime permissions*, not adapter state,
   so this was a narrower gap than item 7 and never subsumed it.

   All three are now supplied on iOS, derived from the same process-wide `AgentRadio` source the rest
   of the agent uses rather than a second notion of radio state, with `bluetoothPermissionDenied()`
   gating on `UNAUTHORIZED` **only**. Five host tests in `AgentAppGatingTest` pin the four states that
   must *not* gate — `OFF`, `UNKNOWN`, `null`, `UNSUPPORTED` — because each is a plausible-looking
   overreach, and one test asserts the notice and the gate deliberately disagree (three states warrant
   a notice, exactly one warrants the gate).

   Verified on the iPhone 14 with the permission genuinely revoked (Settings → Privacy & Security →
   Bluetooth → off for the app): **Start greyed out, warning shown, and a button offering to open
   Settings.** The button is nested inside the `permissionWarning != null` branch in `AgentApp.kt`, so
   its presence also establishes the warning rendered. There is no client-side way to observe any of
   this — the gate is what prevents starting the agent — so the on-screen check was the only route.

   **Parity note, and the platforms differ on purpose.** Android greys out Start *and* raises a real
   runtime-permission prompt. iOS greys out Start and offers the **Settings deep link with no
   dialog**, because revoking sets authorization to `denied` rather than `notDetermined` and iOS does
   not re-prompt from `denied`. Case 6's "no such prompt exists" therefore still holds after a revoke.

   **Method note.** The Android behaviour was confirmed first and was very nearly recorded *as* the
   iOS result: the operator's "yes, it is greyed out and it asks for the permission" referred to the
   Android app, and the two platforms produce similar-sounding descriptions for different mechanisms.
   Confirm *which device* an operator confirmation refers to before it becomes evidence — a
   parity-shaped answer from the wrong platform is indistinguishable from the result being sought.
9. **Seven runner probes still discover by advertised name** (`healthRun`, `rssiRun`,
   `connParamsRun`, `throughputRun`, `wwrBurstRun`, `peripheralStateRun`, `twoClientRun`,
   `tlsProxyRun`). Given finding 4 they cannot resolve this peripheral through the Kotlin agent.
   `Main.kt` and `WriteErrorProbeMain.kt` were converted to service-UUID matching; the rest are a
   one-line change each.
10. **The MTU step asserts nothing useful.** It reported `20 bytes` (the bare ATT default) on the
   iOS host and passed, where Rig A measured `244` on the Mac. A step that passes on both the
   negotiated and un-negotiated value is not testing negotiation.
11. **iOS agent crashes on Stop → Start** — **reproduced and root-caused** (case 4): finding 8's
    orphaned listener makes the restart's bind fail with `EADDRINUSE`, and because
    `instance.start(wait = false)` binds asynchronously the throw lands on a coroutine worker with
    no handler, aborting the process. Fixed by fixing finding 8; separately, a bind failure should
    be reportable as `AgentStartResult.Failed` rather than being unobservable.
12. **`Peripheral.state` stays `Connected` after the agent dies** (case 5) — **DECIDED AND FIXED
    2026-07-29 (evening).** Decision: `Disconnected` **once reconnect gives up**, not on any drop.

    `TransportState` gained `GAVE_UP` — dropped with nothing retrying (the policy exhausted its
    attempts, or reconnect was disabled) — as distinct from `DISCONNECTED`, which means a recovery
    episode is still running. `RemotePeripheral` tears down and moves to `Disconnected` only on
    `GAVE_UP` (or `INCOMPATIBLE_PROTOCOL`). A blip leaves it `Connected`, because the agent may
    genuinely still be holding the BLE link and disconnecting on every blip would be worse than the
    defect. A deliberate `close()` stays `DISCONNECTED`: "gave up" is news, and it is not news to
    whoever called `close()`.

    Two tests pin the two halves against each other — one that a dead agent with exhausted
    reconnect reaches `Disconnected`, one that an unbounded-reconnect blip does **not** — and each
    mutation (removing the reaction; reacting to plain `DISCONNECTED` too) killed exactly one of
    them. `design-decisions.md`'s "never synthesize a BLE disconnect on an IP blip" is refined
    rather than contradicted: it holds for blips, which is the case it was written for, and an
    unrecoverable transport is not a blip.
13. **The background-caveat strings** (case 3) — **fixed in four places**, one more than first
    counted: the `ios-agent/Info.plist` comment, `IosAgentEntry`'s `keepScreenOnNotice`, Rig B
    case 3 in [pr8-validation-plan.md](pr8-validation-plan.md), and `ios-agent/README.md`'s
    "screen-lock caveat" section. All four asserted a restriction the hardware does not impose
    while a BLE link is held.
14. **Measure how long iOS holds a dead app's BLE link** (case 5). Observed ≥90 s on two
    instruments; the ceiling is unmeasured and the mechanism unconfirmed.
15. **`Stop` leaving the server up** (case 4, finding 8) — **fixed and verified on both platforms**:
    `AgentRunner.stop()` calls `AgentRunnerGraph.stopServer()` before closing the graph, off the
    Main dispatcher, and `serverStopped` reports that call's real outcome. Two mutation-checked
    regression tests. **Closed** — the Android re-verification ran 2026-07-29 evening; see
    [the Android confirmation](#android-confirmation-2026-07-29-evening) under case 4.
16. **Surface asynchronous bind failures** (finding 10's residue) — **FIXED 2026-07-29 evening;
    verified on Android hardware.** `start()` is now `suspend`, awaits the real bind, and throws
    `AgentBindException`; `AgentRunner` turns that into `AgentStartResult.Failed` and the UI shows
    it. The desktop call site was touched as predicted, and now exits with a usable message instead
    of logging "listening on …" before discovering it never bound.

    **The prediction in this entry was half right, and the half it missed is the interesting one.**
    Awaiting `resolvedConnectors()` is necessary but does not by itself fix Android: there, the
    accept job fails on a `DefaultDispatcher` worker and reaches the *thread's* uncaught handler,
    so the process is gone before any await could report anything. The JVM tests passed while the
    phone still died — caught only because the run was repeated on hardware. The fix needed the
    engine to be given a parent job we own, with an exception handler, so its failure has somewhere
    to go that is not `Thread.UncaughtExceptionHandler`.

    Two further details worth carrying: a failed CIO bind arrives as a **`CancellationException`**,
    so "rethrow cancellation untouched" — correct nearly everywhere — silently restores the original
    defect; the usable discriminator is whether the *caller's* job is still active. And on JVM/Ktor
    3.5 the failure is **synchronous** out of `start(wait = false)`, a third shape again. Handling
    only the shape in front of you is how this survived a whole rig.

    **Confirmed on the iPhone 14** — the platform where this was a `SIGABRT` — with a test-only
    build bound to `192.0.2.1` (TEST-NET-1; the device cannot hold it, so `EADDRNOTAVAIL`). PID
    unchanged across the failed Start, no `Uncaught Kotlin exception`, no signal, button stayed on
    `Start`, error line shown. Ktor logs `Application started` regardless but `Responding at http://…`
    only on a bind that took — a cheap tell when reading these consoles.

    Reproducing a bind failure on iOS needed two attempts, and the first is the instructive one:
    **port 80 is bindable by a sandboxed iOS app** (the agent answered `401` on it), so the
    "privileged ports need root" assumption produced a run that tested nothing while looking like a
    pass — process alive, no crash, exactly the shape of a success. See method note 20.
17. **The Apple scanner passes `nil` serviceUUIDs.** Kable logs CoreBluetooth's own warning on every
    scan: *"The recommended practice is to populate the serviceUUIDs parameter rather than leaving
    it nil."* Beyond the advisory, iOS **ignores** a nil-services scan entirely while the app is
    backgrounded — so this interacts directly with case 3: a backgrounded agent kept alive by
    `bluetooth-central` can still serve reads on an existing link but may not be able to *discover*
    anything. Untested; worth a case of its own.

## Method notes worth keeping

Continuing the practice started in [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md#method-notes-worth-keeping).

1. **The A/B across three implementations is what prevented a wrong bug report.** The peripheral
   was invisible through the iOS agent, and the obvious conclusion — an iOS/Kable defect — was
   wrong. Running the *macOS* Kotlin agent against the same peripheral showed the same failure,
   and `agent-rs` on that same Mac radio showed success. That triangulation moved the defect from
   "iOS" to "the Kotlin agent, everywhere" in one step. **When a mobile host looks broken, re-run
   the same stimulus through a desktop host of the same implementation before blaming the platform.**
2. **Capture the error before theorising about it.** The release-link failure was attributed first
   to concurrent Gradle builds, on nothing more than timing. It was an OOM the whole time, and the
   actual message — `Java heap space` — appeared only once the output was read in full instead of
   piped through `tail`/`grep`. Two rounds of speculation were spent on a message already on screen.
3. **Two changes in one run, again.** The `name` fallback and the `serviceUuids` mapping were
   applied and tested together — the same mistake Rig A's notes call out. It was rescued only
   because the two fields are independent code paths, so the UUID count could attribute each
   effect. It should still have been two runs, and the fallback half turned out to be wrong.
4. **A "still not found" result was nearly read as "nothing changed".** After the fix, the iOS
   agent still did not report `RBTestPeripheral` — but it *had* started reporting UUIDs (0 → 8),
   which both proved the new framework was actually deployed and provided the means to locate the
   device without its name. **Check whether the part of a change that did work gives you a new
   instrument.**
5. **Ask what the operator did, not just what the machine did.** Five runs were spent chasing an
   iOS read failure before it emerged that bonding dialogs had been appearing on the *peripheral*
   and were being dismissed. That single fact reframed the whole investigation twice — first as
   "the confound is pairing", then, once a clean bond was established and the read still failed,
   back to a real defect. The operator's side-channel actions are part of the experiment; ask
   early what they saw and did. Rig A's notes warned about pairing-rejection artifacts and it
   still cost five runs.
6. **A control has to be re-run after the environment changes.** The macOS "reads work" control was
   established *before* the peripheral re-bonded with the iPhone. Re-running it afterwards is what
   made the final isolation trustworthy — without it, "iOS fails, macOS passed earlier" would have
   been comparing two different peripheral states.
7. **Watch for harness artefacts masquerading as results.** Two `FAIL`s in this session were the
   harness, not the product: a `REMOTE_BLE_E2E_BTLEPLUG=false` hardcoded in the driver (correct for
   the iOS agent, wrong for the btleplug-backed macOS one, turning a legitimate XFAIL into a FAIL),
   and a `</dev/null` run reporting a stale-read failure because no operator bump ever happened.
   Neither is a defect; both would have been if recorded uncritically.
8. **Check the library's release notes and configuration surface before concluding it is broken.**
   Finding 5 produced three plausible, wrong hypotheses and a confident write-up claiming the
   library dropped read completions. The actual cause was a documented Kable option we had never
   set. The operator broke the deadlock by reading Kable's release notes and spotting an
   Apple-specific fix in an adjacent operation. **When a mature library appears to fail at
   something basic, the prior should be "we are holding it wrong", and the cheapest test of that is
   its own changelog and builder API.**
9. **A conclusion that requires an extraordinary claim needs extraordinary evidence.** "The iOS
   agent cannot read a characteristic" implies every Kable-on-Apple consumer is broken, which was
   never plausible. That objection — raised by the operator, not by the evidence — is what forced
   the re-examination. Weigh how surprising a conclusion is *before* writing it down as a finding.
10. **Timestamps are not provenance.** The peripheral APK was rebuilt because its `lastUpdateTime`
   preceded a fix commit by four minutes. That reasoning was sound but unverifiable after the fact;
   the rebuild was cheap and removed the doubt. Prefer rebuilding a cheap dependency to reasoning
   about whether it is current.
11. **A "documented caveat" is a hypothesis, not a result.** Case 3 existed to *confirm* the
    background restriction, and the first instinct on seeing new connections still accepted 11 s
    after backgrounding was that the stimulus had failed. It had not. The caveat was simply wrong
    in the case that matters, and the evidence for that was sitting in our own `Info.plist` the
    whole time (`UIBackgroundModes: bluetooth-central`). **Read what the code claims about the
    platform as something to test, not as the expected result** — especially when the claim is a
    comment rather than an assertion.
12. **Build the probe to classify, not to pass/fail.** The one design choice that carried both
    cases was making the inbound probe report *how far* a new connection got — `REFUSED` versus
    `NO_RESPONSE` versus an HTTP status — instead of "reachable: yes/no". A boolean would have made
    case 3's suspended app and case 5's dead process look identical, and the whole distinction
    between "the listening socket survives, nothing accepts" and "the listener is gone" would have
    been invisible. The extra classification cost about ten lines.
13. **Scripting the stimulus is worth an hour of setup.** The first case 3 attempt burned a full
    4-minute window waiting on a human tap that never came, and produced a null run. Backgrounding
    turned out to be fully drivable (`devicectl process launch` of any other app backgrounds the
    agent; launching it again *resumes* it), as did case 5's kill (`process signal`). That made the
    control run — the thing that actually made case 3 attributable — cheap enough to be worth
    doing. Rig B's setup memo said "only the on-device taps need a human"; the set of things that
    truly need one was smaller than assumed. Re-derive it before scheduling operator time.
14. **Two "separate" findings turned out to be one defect, and predicting beat probing.** Finding 10
    (Stop → Start aborts) had been carried since the night session as an unexplained `signal 6`.
    Once finding 8 was root-caused the prediction wrote itself — the old listener is still bound, so
    a restart must fail `EADDRINUSE` — and it was stated *before* the operator tapped Start. It
    reproduced on the first attempt, with the stack landing on exactly the predicted frames
    (`tcpBind` → `httpServer$acceptJob`). A crash that had resisted explanation for a whole session
    became a one-attempt confirmation because there was a specific thing to look for.
    **When a new root cause lands, re-read the open findings and ask which of them it predicts** —
    an unexplained symptom is often a known defect seen from another angle.
15. **Attach the console before you need it.** The night session recorded this crash as a bare
    `signal 6` with no stack, which is why it stayed unexplained. Relaunching under
    `devicectl device process launch --console` cost nothing and turned the same crash into a full
    Kotlin/Native trace naming the failing call. For a defect that only appears through the UI,
    the capture has to be armed before the operator is asked to act — there is no second chance on
    a process that aborts.
16. **The comparison baseline needs checking too, not just the thing under test.** Case 6 was framed
    as "Android gates Start and warns, iOS doesn't" — a clean platform-asymmetry story that was
    wrong. The operator checked the Android app and reported it showed nothing either, which moved
    the finding from an iOS wiring gap to a *cross-platform* blind spot worth more than the original
    claim. The error was assuming Android's `bluetoothPermissionsGranted` tracked the adapter when
    it tracks runtime permissions. **When a finding is "platform A does this, platform B doesn't",
    verify platform A actually does it** — the reference implementation is a hypothesis too, and one
    grep would have settled it.
17. **When the transport under test is not the transport being measured, move the probe.** The
    Android re-verification stalled immediately: probing `http://192.168.178.83:8080` from the Mac
    timed out, even though the app showed `Running`. The reflex reading was "the fix does not work
    on Android" — the LAN path was simply blocked (the SYN went unanswered; an earlier probe to the
    same address had been *refused*, so delivery had worked minutes before). Two on-device controls
    settled it in one step: `nc 127.0.0.1 8080` from `adb shell` returned `400`, and so did
    `adb forward tcp:18080 tcp:8080`. **Nothing about case 4 needs the LAN** — it is a question
    about whether a listening socket is released, so probing over the USB bridge tests exactly the
    same thing with a channel the Wi-Fi cannot break.

    One caveat that makes the forwarded probe usable rather than merely convenient: it still
    classifies. With the port closed, `adb` accepts the local connection and then closes it when
    the device refuses, so curl reports **exit 52 (empty reply)** in ~15 ms rather than exit 7
    (refused) — a different code than over the LAN, but just as sharply distinguished from `400`,
    and still distinct from a hang. That control was run against a deliberately dead port
    *before* trusting any of the real measurements, per note 12.
18. **A green test suite on one platform is not evidence about another — even for shared code.**
    Follow-up 16's fix had four passing JVM tests, including one that deliberately squatted on a
    port and asserted the failure was reported. On the Pixel the same build still died with
    `FATAL EXCEPTION: DefaultDispatcher-worker-4`, because the failure arrives by a *different
    route* on Android: an uncaught exception on an engine worker rather than a throw the caller can
    catch. The tests were not wrong, they were answering a question the phone was not asking.
    Note the shape this shares with note 16 and with the `android-agent` re-verification above —
    **shared `commonMain` code plus one platform's green result keeps looking like proof and keeps
    not being it.** Where a defect is about *how a failure propagates*, the propagation is a
    property of the runtime, not of the code, so it has to be re-observed per runtime.
19. **"The first value arrived" is not evidence that a stream works.** The Apple radio-state
    delegate was deallocated the moment its factory returned, because `CBCentralManager` retains
    its delegate weakly. The initial state still arrived — the callback fires while the local
    reference is alive — so every cheap check passed: the capability was advertised, the handshake
    event appeared, the value was correct. Only a *transition* could expose it, and only on device.

    Generalise past the specific API: **a subscription that delivers its current value on
    attachment cannot be validated by reading that value.** Seeding and streaming are separate
    mechanisms, and the seed is usually the one that works. Any test of an observable wants at
    least one change of state, and for a platform callback that means real hardware.

    The comment sitting on the faulty line claimed the retention was handled. Written in good
    faith, wrong, and it made the code *look* audited — worse than no comment, because it answers
    the question a reviewer would otherwise ask.
20. **Validate the stimulus before spending a run on it.** The first iOS bind-failure attempt bound
    port 80, assuming a sandboxed app cannot take a privileged port. iOS allows it — the agent
    answered `401` there. The run therefore produced "process alive, no crash, nothing on screen",
    which is *indistinguishable from a pass* and was briefly written up as one. The operator's
    "what exactly are you looking for?" is what reopened it.

    The retry bound `192.0.2.1` (TEST-NET-1, unassignable → `EADDRNOTAVAIL`) and, crucially,
    **proved that stimulus threw on the JVM first**, which cost seconds and no operator time. For a
    negative test — one whose pass condition is "the bad thing did not happen" — a stimulus that
    silently fails to fire looks exactly like success. Assert that the stimulus works before
    trusting what the subject does with it.
21. **A confident error message is a claim, and it can be wrong.** The bind failure surfaced as
    *"Port 8080 is already in use. Stop whatever is holding it, then try again."* in a run where
    nothing whatsoever held port 8080 — the address was simply unassignable. The message was
    written when the only failure in view was a port conflict, and it hardened one instance into a
    diagnosis. Now: *"Could not open port 8080. Another app may be using it."* Name what is certain,
    suggest the likely cause, assert neither — and a test pins the wording so it cannot drift back.
