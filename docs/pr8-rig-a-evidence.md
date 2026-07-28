# PR8 · Rig A — real-radio evidence

Evidence record for [pr8-validation-plan.md](pr8-validation-plan.md) Rig A, per that document's
evidence rule (host/device details, agent version, exact command, redacted result per case).

**Session:** 2026-07-27/28 · **Status:** Case 1 closed on both agents; see [Remaining](#remaining) for
what's left.

## Rig

| | |
|---|---|
| Host | macOS 26.5.2, arm64, Bluetooth on |
| JDK | OpenJDK 17.0.14 |
| Repo commit | `c7c6680` for the 2026-07-27 session; `910a350` plus this session's uncommitted fixes for the 2026-07-28 continuation (below) |
| Kotlin agent | 0.10.0, `agent/run-agent.sh 8080`, Kable engine on Mac OS X |
| Rust agent | `agent-rs 0.10.0`, `agent-rs/run-agent-rs.sh 8080` |
| Peripheral | Pixel 8 / Android 17 / `CP2A.260705.006`, `../ble-peripheral` `sample-peripheral`, `TestProfile`, advertising `RBTestPeripheral` |
| Client | `:e2e-runner` on the same Mac (no radio of its own) |

## 2026-07-28 continuation — Case 1 confirming run, both agents

Picked up exactly where the [Remaining](#remaining) list left off: item 1, the confirming re-run of
the `GATT_OP_TIMEOUT`/`REMOTE_BLE_WRITE_FAIL_FAST` work. Three findings came out of it, two of them
new defects caught only by running on real hardware — the reason this bundle is a release gate
rather than something the unit suites alone could sign off.

1. **`e2e-runner:jvmRun` didn't forward stdin when launched non-interactively.** The Kotlin
   Multiplatform-generated `jvmRun` task doesn't wire the launching process's stdin through to the
   child JVM by default, so `Main.kt`'s `readlnOrNull()` operator prompts returned `null`
   immediately instead of blocking — the run raced ahead of the operator and failed at the first
   prompt. Fixed in [`e2e-runner/build.gradle.kts`](../e2e-runner/build.gradle.kts):
   `tasks.withType<JavaExec>().configureEach { if (name == "jvmRun") standardInput = System.\`in\` }`.
   This only matters for non-interactive invocation (a real terminal Gradle inherits stdin from
   was already fine); worth having regardless since it makes the runner drivable from a script or a
   remote session, not just a human at a keyboard.
2. **Fail-fast wrongly gated `WriteWithoutResponse` too (Kotlin).** The first confirming attempt
   showed the "WWR still returns `Ok`" step **failing** — a step that isn't gated and is supposed to
   always pass — with `degradedWriteRejection`'s message. Root cause:
   `EngineBleBackend.write()` consulted the degraded-connection gate for every write regardless of
   type, but the gate only exists to protect against write-**with-response** completions that never
   arrive; `WriteWithoutResponse` never awaits an ATT response in the first place, so it can't be
   affected by that wedge and must not be short-circuited by it. This had never been exercised on
   hardware before — the unit suite only ever drove the gate with a bare device handle, never a
   write type. Fixed by folding `withResponse` into `degradedWriteRejection`'s own gate (now
   `degradedWriteRejection(device, withResponse)`) so the split-for-testability shape is preserved;
   added `EngineBleBackendJvmTest.aDegradedDeviceStillLetsWriteWithoutResponseThrough` as a
   regression test. Confirmed fixed on hardware: re-run shows the WWR step passing even while the
   connection is degraded.
3. **`agent-rs` reproduces the same write-poisoning, and now has the same fail-fast fix.** Item 2 in
   [0.10.0-progress-status.md](proposals/0.10.0-progress-status.md#open-gaps-carried-into-the-next-session)
   flagged this as unconfirmed. Confirmed on hardware: the "succeeds again" step took the full 10s
   and reported the plain `GATT_OP_TIMEOUT` message before any fix — same defect, same symptom.
   Ported the Kotlin fix as `DegradedWrites` in
   [`agent-rs/src/ble/btleplug_impl.rs`](../agent-rs/src/ble/btleplug_impl.rs) (a free-standing
   struct, not a `BtleplugBackend` method, so it's unit-testable without a live `Adapter` — mirrors
   why the Kotlin gate was split out) plus a `--write-fail-fast`/`REMOTE_BLE_WRITE_FAIL_FAST` CLI
   flag (default on) in `main.rs`. Six new Rust tests mirror the Kotlin suite case-for-case
   (`agent-rs` test count: 74 → 80). Confirmed fixed on hardware: the "succeeds again" step now
   returns the fail-fast message in ~3s instead of waiting out the full 10s.

**Operational note:** killing an agent process with a bare `kill`/`pkill` (not the launcher's own
shutdown path) can leave the phone's Bluetooth stack holding stale GATT server connections — seen
as a spurious `WRITE_FAILED`/timeout on the very next ordinary write on a fresh run. Force-stopping
and relaunching the peripheral app cleared it. Separately, killing the launched `.app` with `-9`
left the *Mac's* CoreBluetooth manager wedged badly enough that a fresh `agent-rs` launch hung
during `Manager::new()` and never reached its listen line; toggling the Mac's Bluetooth off/on
cleared that. Prefer a clean shutdown (the launcher's own stop path, or a plain `kill` and a wait)
over `-9` when re-running this rig.

### Case 1 result after the fixes above (both agents)

`RESULT: 12 passed, 0 failed, 2 known-failing` on **both** the Kotlin agent and `agent-rs` — all 14
steps reached, including the previously-unreached `Disconnect` step. The two XFAILs are exactly the
documented btleplug gaps (no ATT error delivery; write-poisoning until reconnect); everything else,
including WWR on a degraded connection, now passes on both agents.

Both agent launchers must be used on macOS rather than running the binaries directly — a bare
process is killed by TCC the moment it touches CoreBluetooth (`agent-rs` exits 134 without its
launcher).

## Peripheral prerequisites fixed during this rig

Rig A could not start at all until two defects in `../ble-peripheral` were fixed. Both are
committed there and verified on air:

- **`369f265`** — advertising never worked. A 128-bit service UUID (18 bytes) plus the mandatory
  flags (3) plus `RBTestPeripheral` (18) needs 39 bytes in a 31-byte legacy PDU, so `Start server`
  always reported `ADVERTISE_FAILED_DATA_TOO_LARGE`. The arithmetic is OS-version independent, so
  this configuration could never have advertised. The device name now goes in the scan response;
  service UUIDs stay in the primary PDU so UUID-filtered scans still match. Confirmed both halves
  arrive: `RBTestPeripheral … uuids=[a1b2c3d4-0000-4000-8000-000000000001]`.
- **`5390307`** — the app crashed on every incoming write. `MainActivity.log()` appended to a
  TextView directly while the SDK delivers GATT callbacks on a background dispatcher, so the first
  write killed the process with `CalledFromWrongThreadException`. Reads were unaffected only
  because the read handlers do not log, which made it look like a central-side write failure.

## Operator note

The peripheral must stay foregrounded and the screen awake for the duration of a run — its GATT
server stops when the app is backgrounded, and the phone's 30-second screen timeout ends a run
mid-flight. Set `stay_on_while_plugged_in` to include USB (value `3`) for the session and restore
it afterwards. The adb driver used here refuses to tap unless the peripheral is the foreground
activity, so a lost foreground aborts loudly instead of sending taps into whatever else is open.

## Case results

### Case 1 — full 14-step E2E

Run against the Kotlin agent built from `claude/festive-joliot-de1b5f` (bounded `gattOp` + XFAIL
runner). **10 passed, 1 known-failing, 1 failed, 2 unreached.**

| Step | Result |
|---|---|
| Transport connects | PASS |
| Scan finds the peripheral | PASS |
| Connect + discover services | PASS — 5 services |
| Locate profile characteristics | PASS |
| Read the readable characteristic (baseline) | PASS |
| Read exactness (F) | PASS — reflects a driven bump, not a cached value |
| Write (with response) | PASS |
| Write (without response) | PASS |
| Negotiated MTU write length | PASS — 20 bytes |
| Write-with-response error → `WRITE_FAILED` | **XFAIL (known)** — got `TIMEOUT`; btleplug does not deliver ATT errors for write-with-response |
| WWR still returns Ok despite the reject | PASS |
| Write-with-response succeeds again (no session poisoning) | **XFAIL (known)** — see below; was a hard FAIL when this run was made |
| Observe 2 notifications | not reached in that run |
| Disconnect | not reached in that run |

Notification delivery was verified separately instead (see case 1 addendum below), so the only
unverified step is the runner's own `Disconnect`.

**The XFAIL gate works**: the run now continues past the btleplug gap instead of aborting at step
10, which is what raised the run from 6 steps to 12.

**The failing step is a new finding**, filed separately: an ATT error poisons write completions for
the *lifetime of that connection*. The peripheral's own log proves the final write arrived with
error injection already off —

```
injectWriteError = true
WRITE ee from FC:B2:14:C6:39:8D      <- rejected write, hung, bounded at 10s
WRITE ee from FC:B2:14:C6:39:8D      <- the WWR
injectWriteError = false             <- toggle OFF landed
WRITE 01 02 from FC:B2:14:C6:39:8D   <- final write ARRIVED, injection off
```

— yet its completion never came back. Reads on that connection still work, and a **fresh**
connection completes the identical write in **66 ms**. So the 10 s bound stops the permanent wedge
but the connection stays write-poisoned until it is torn down.

**Addressed after this run**, in two parts: the step is now gated XFAIL on btleplug-backed agents
(no agent-side handling can make it pass while the backend behaves this way), and the agent
short-circuits writes on a degraded connection so they fail immediately with the same error instead
of costing 10 s each — switchable via `REMOTE_BLE_WRITE_FAIL_FAST`. **A confirming rig run has not
been done**: re-running case 1 should now reach all 14 steps with two XFAILs, and that is the first
thing to do when the rig is next up.

#### Case 1 addendum — notification delivery

Verified against `agent-rs` with `TlsProxyMain` pointed at the `TestProfile` notify characteristic:
4 notifications, payloads `01 → 02 → 03 → 04`, strictly incrementing, no duplicates or misses,
inter-arrival 3242–3299 ms matching the driven tap cadence. This also served as the real-radio
re-confirmation of `TLS-PROXY-01` case 5.

### Case 2 — F3 unsolicited disconnect

**Partial, still.** Agent half confirmed on `agent-rs`: tapping *Force disconnect all* mid-session
produced `btleplug event received: DeviceDisconnected(PeripheralId(...))` and the connection was
retired.

Built [`PeripheralStateMain.kt`](../e2e-runner/src/jvmMain/kotlin/dev/warsha/remoteble/e2e/PeripheralStateMain.kt)
(`:e2e-runner:peripheralStateRun`) for the client half — every prior runner in this doc only watched
*transport* (WebSocket) state, which a BLE-level drop correctly leaves untouched; this one watches
`Peripheral.state` directly. **Attempted three times on 2026-07-28, none conclusive**, each for a
different reason:

1. Connected, tapped Force disconnect all — no observable event at all within 60s, even though the
   agent log confirmed the connection was live. Later traced to a stray Bluetooth **bond** between
   the Mac and phone (accepted accidentally mid-Case-6, see case 6's evidence) that appears to have
   suppressed the disconnect from reaching CoreBluetooth.
2. After clearing the bond (forget device + Bluetooth toggle): got a `PASS` in ~10s — too fast to
   have been a real tap. The agent log showed why: `liveness probe failed ... declaring unsolicited
   disconnect`, i.e. the agent's own health-check timed out the connection, not a genuine
   `DeviceDisconnected` from btleplug. False positive, not counted.
3. Repeated with an explicit stability check (held `Connected` for 8s before tapping) — the phone's
   own event log confirmed `FORCED DISCONNECT` fired, but no BLE-level event of any kind reached the
   agent within 60s.

**Read on this:** not a product defect as far as this session can tell — three different failure
shapes across three attempts points at accumulated radio/bond instability from a very long single
session (this doc's Case 5/6/7 sections each separately needed a Bluetooth toggle, a bond removal,
or an agent restart to recover a wedged Mac-side Bluetooth stack). The driver itself is sound (it
correctly detected and reported the state transition in attempt 2, just for the wrong underlying
reason). **Needs a fresh rig session** — ideally the first thing run, before any of the other cases
have had a chance to leave the radio in a strange state — to get a clean confirmation.

### Case 3 — Two-client authorization on real radio

**Not started.**

### Case 4 — `setConnParams`

**PASS** on `agent-rs`. Negotiated capabilities `identifier.translate`; `conn.params` and
`conn.priority` both absent; all three profiles answered cleanly:

```
setConnParams(LOW_LATENCY) ... ERR: Operation not supported on this agent
setConnParams(BALANCED   ) ... ERR: Operation not supported on this agent
setConnParams(LOW_POWER  ) ... ERR: Operation not supported on this agent
PASS — no conn.params backend; every request degraded to UNSUPPORTED cleanly.
```

**Repeated on the Kotlin agent 2026-07-28 — PASS**, identical result (all three profiles cleanly
`UNSUPPORTED`, message `connection parameters not supported`). The Android agent's expected `Ok`
path remains untested — that needs a phone-hosted agent, i.e. Rig B.

### Case 5 — Reconcile under translation, live

**Partial — real hardware, real cross-platform client, but the scenario as specified is
unreachable on Rig A. 2026-07-28.**

Built and installed `:android-client` on the Pixel 8 (`local.properties` was missing a `sdk.dir` in
this checkout — added, gitignored). Bridged it to the Mac-hosted agent over USB with
`adb reverse tcp:8080 tcp:8080` (the phone's `localhost:8080` tunnels to the Mac's), pointed the
app at `ws://127.0.0.1:8080/agent`, scanned, and connected to the (rebuilt) health peripheral —
confirmed genuinely cross-platform: the agent log shows `handshake [c=6]: ... fmt=STRING`, i.e. the
client actually declared Android's `IdentifierFormat.STRING`, not the `fmt=UUID` every JVM driver
in this doc uses.

**Transport blip (no agent restart):** `adb reverse --remove` alone does **not** sever an
already-established tunnel (it only blocks new connections) — actually breaking the live socket
needed `adb kill-server && adb start-server`. Confirmed the app's own drop detection (banner:
"Agent disconnected — device status below may be stale", values frozen at their last reading), then
restored the reverse tunnel: the banner cleared and Heart Rate/Battery notifications resumed
automatically — no rescan, no manual reconnect tap, same identifier, subscriptions still active.
The agent log shows the lease actually expired first (the kill-server/start-server cycle exceeded
the 10s transport grace), so this exercised the *cold* resume path (fresh radio reconnect,
transparent to the user) rather than a *warm* within-grace resume — still a valid pass of "the
session resumes and replayed ops route correctly" without user-visible rescanning.

**Agent restart (the documented residual):** killed and restarted the Kotlin agent process
entirely. The app showed the same stale-data banner, then — once the new agent process came back —
resumed automatically again, with **no rescan required**. This does not match the residual as
written in [pr8-validation-plan.md](pr8-validation-plan.md) ("the client rescans rather than
resuming"), and investigating why is the useful part of this result:

**Why: this rig cannot exercise real identifier rewriting at all, on any client.**
[`HandleTranslator.needsRewrite`](../agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/HandleTranslator.kt#L127)
only synthesizes a client-side handle when the client's format is `UUID` or `MAC_ADDRESS` **and**
differs from the agent's native format. `IdentifierFormat.STRING` (Android) is *never* rewritten —
a string can already hold any native format losslessly — so the `fmt=STRING` connection above was
still an identity pass-through, exactly like every `fmt=UUID` JVM client in this doc (macOS/Kable's
native format is itself `UUID`, so a `UUID`-declaring client — JVM, and also iOS, which the doc
lists as `IdentifierFormat.UUID` too — never triggers synthesis against this agent either). The
handle value is therefore the same real, OS-stable identifier before and after the agent restart
(same physical device, same CoreBluetooth-assigned UUID), which is exactly why resume-without-rescan
kept working: there was never a synthetic handle for the restart to invalidate.

Genuine rewriting needs an agent whose *native* format is `MAC_ADDRESS` (Windows) or `BLUEZ_JSON`
(Linux) paired with a `UUID`- or `MAC_ADDRESS`-declaring client — i.e. **Rig D or a Windows host**,
not reachable from Rig A's macOS agent with any client this repo ships (JVM, Android, or iOS). This
is a scope correction worth carrying into `pr8-validation-plan.md` itself: as written, case 5 reads
as achievable on Rig A, but it structurally is not, on any current or planned real-radio host.

**What is confirmed:** reconcile/resume works live, end-to-end, through an actually-negotiated
`identifier.translate` capability and a genuinely cross-platform client, across both a transport
blip and a full agent restart. What is **not** confirmed anywhere yet: the rewrite path itself
surviving reconcile, and the "agent restart invalidates the handle" residual — both need Rig D or a
Windows host to even attempt.

### Case 6 — WWR throughput + ordering

**Baseline captured** on `agent-rs`, 120 serial `WithoutResponse` writes at 20 bytes:

```
throughput     : 28482.6 bytes/s (27.82 KB/s)
per-write (ms) : min=0.38  p50=0.62  mean=0.70  p90=1.01  p99=1.23  max=3.04  stddev=0.30
```

**Both halves closed 2026-07-28, Kotlin agent — PASS.** Built
[`WwrBurstMain.kt`](../e2e-runner/src/jvmMain/kotlin/dev/warsha/remoteble/e2e/WwrBurstMain.kt)
(`:e2e-runner:wwrBurstRun`) since no driver exercised
`RemotePeripheral.writeWithoutResponseBurst` against real hardware before — only a JVM unit test
did. Ran 40 strictly-incrementing single-byte payloads (so submission order is trivially checkable
on the peripheral's own write log) both serially and via the burst API (`window=8`):

```
serial   : 40 writes in 98.2 ms (2.45 ms/write)
burst    : 40 writes in 35.4 ms (0.88 ms/write)
RESULT: all 40 burst writes succeeded; burst was 2.78x the serial baseline's speed
```

Order confirmed independently by screenshotting the peripheral's on-screen write log: a
perfectly monotonic `0x0f → 0x27` (15 → 39 decimal) with no gaps or duplicates across both the
serial and burst sections — the pipelining guarantee (`BleAgentTest.concurrentWritesToOneDeviceReachBackendInSubmissionOrder`
/ `transport::server::tests::cancelled_write_reservation_unblocks_the_next_write`) holds on a real
radio, not just in CI.

### Case 7 — Battery/Device-Info on a non-iOS peripheral

**PASS, 2026-07-28.** `com.warsha.ble.peripheral.health` had not been rebuilt since 2026-07-08 (20
days stale relative to the sample-peripheral's 2026-07-27 rebuild with this rig's own fixes) —
rebuilt and reinstalled (`:health-peripheral:installDebug`) before this case could run at all,
same category of prerequisite fix as the two peripheral defects at the top of this doc. Built
[`HealthMain.kt`](../e2e-runner/src/jvmMain/kotlin/dev/warsha/remoteble/e2e/HealthMain.kt)
(`:e2e-runner:healthRun`) since no driver read Battery/Device-Info before. Device Information read
`manufacturer="Warsha BLE" model="RBL Health Sim 1"`; Battery Level read twice around a manual
slider drag on the phone — `17% -> 62%` — confirming a live, not frozen/cached, read.

**Operational note:** the Mac's BLE scan kept reporting this peripheral's identity under its
*previous* app's advertised name (`RBTestPeripheral`) for a long stretch even after the health app
was confirmed advertising as `Warsha HRM` on-screen, surviving a Bluetooth off/on toggle and an
agent restart. Forgetting the device in macOS Bluetooth settings *and then* toggling Bluetooth
off/on cleared it. A plain toggle alone was not enough this time — worth trying the forget-device
step first if this recurs.

### Case 8 — CONN-1, client before agent

**PASS** on `agent-rs`. Client started 3 s before any agent existed, held at `connecting
transport …`, then self-healed once the agent came up and completed a full scan (37 devices) with
no restart. An earlier attempt failed only because the agent's first launch rebuilds its `.app`
bundle and took ~35 s, exceeding the runner's own 15 s patience — not a stack failure.

**Repeated on the Kotlin agent 2026-07-28 — PASS.** Same shape: client started first, held at
`connecting transport …`, self-healed once the agent came up (warm-built this time, so well within
the runner's 15 s patience), completed a full scan (40 devices).

## Remaining

1. ~~**Case 1**~~ — **done 2026-07-28**, both agents: 12 passed, 0 failed, 2 known-failing, all 14
   steps reached. See the continuation section above.
2. **Case 2** — client-side `State.Disconnected`. Driver built
   (`:e2e-runner:peripheralStateRun`), three attempts on 2026-07-28 all inconclusive due to
   accumulated radio/bond instability late in a long session — run this **first** next time, before
   anything else has a chance to wedge the Mac's Bluetooth stack.
3. **Case 3** — two-client authorization; needs a two-client harness.
4. ~~**Case 5**~~ — **done 2026-07-28**, with a scope correction: reconcile/resume confirmed live
   (transport blip and full agent restart both auto-resumed, no rescan), but genuine identifier
   *rewriting* is structurally unreachable on Rig A with any client this repo ships — see the
   continuation section above and `pr8-validation-plan.md`'s case 5 note.
5. ~~**Case 6**~~ — **done 2026-07-28**, Kotlin agent: burst/ordering half closed, 2.78x measured
   improvement, order confirmed on the peripheral's own log.
6. ~~**Case 7**~~ — **done 2026-07-28**: Battery/Device-Info confirmed live against the
   (rebuilt) health peripheral.
7. ~~**Kotlin-agent repeats**~~ of cases 4, 6, 8 — **done 2026-07-28**, all PASS, identical
   results to their `agent-rs` runs.

Case 3 still needs observation the current runners do not provide (a two-client harness). Case 2's
driver exists and works — it just needs a clean rig session to confirm against.
