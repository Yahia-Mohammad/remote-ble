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

**PASS on both agents, 2026-07-28 (evening).** The client reaches `State.Disconnected` on a genuine
unsolicited BLE drop. `agent-rs` — btleplug/CoreBluetooth reports it in **145 ms**:

```
agent   16:02:08.666  btleplug event received: DeviceConnected(PeripheralId(003af0e7-…))
rig     16:02:18      phone Bluetooth switched off      (real link-layer teardown)
agent   16:02:18.811  btleplug event received: DeviceDisconnected(PeripheralId(003af0e7-…))
agent   16:02:18.811  BLE device disconnected (unsolicited): 003af0e7-…
client                PASS after 2.24s — State.Disconnected, reason "peer disconnected"
```

Kotlin agent (`:agent`, Kable engine on macOS) — **PASS in 2.86 s**, native path, same stimulus:

```
agent   DEBUG  Kable state → Disconnected [dev=c474d8cf-…] : unsolicited drop
agent   INFO   unsolicited disconnect [c=8 dev=c474d8cf-… reason=peer disconnected]
client         PASS after 2.86s — reason AgentError(kind=DISCONNECTED, message="peer disconnected")
```

The Kotlin agent **self-attributes**, unlike `agent-rs`: its poll path leaves the reason `null` and
its native path populates it. Both appear in this session's log, which is what validates the
discriminator —

```
WARN  liveness probe failed [dev=77369175-…] — declaring unsolicited disconnect
INFO  unsolicited disconnect [dev=77369175-… reason=null]              ← poll path
DEBUG Kable state → Disconnected [dev=c474d8cf-…] : unsolicited drop
INFO  unsolicited disconnect [dev=c474d8cf-… reason=peer disconnected] ← native path
```

— so on the Kotlin agent a non-null reason on the wire is sufficient proof the native path fired.
(The `null` run above was an aborted attempt where the stimulus fired during the driver's 8 s
stability hold; the driver correctly self-aborted as INCONCLUSIVE rather than banking it.)

**The validation plan's stimulus is invalid and must be changed.** *Force disconnect all* on an
Android peripheral does **not** terminate the link. `BluetoothGattServer.cancelConnection()` releases
the GATT *server's* reference to a connection the remote central established; it does not send a
link-layer terminate. The app still logs `CONNECTIONS: 0` and `CentralDisconnected` — that is its own
bookkeeping, not a stack callback — so the peripheral looks like it disconnected while the radio link
is still up. Use a real teardown instead (`adb shell cmd bluetooth_manager disable`, powering the
peripheral down, or walking it out of range).

**Controlled matrix.** Eight runs; the last two are the controls that made the result attributable:

| Stimulus | Link probe | Outcome | Native `DeviceDisconnected`? |
|---|---|---|---|
| Force disconnect (×6) | off | FAILED, nothing in up to 240 s | none |
| Force disconnect (×2) | on, 10 s | "PASS" in 16-19 s | yes — but see below |
| Force disconnect (control B) | off | FAILED, nothing in 90 s | none |
| **Bluetooth off (control C)** | off | **PASS in 2.24 s** | **yes, 145 ms** |

Control B vs C isolates the stimulus: the tap does nothing, a real teardown works instantly.

**The two middle runs were an artifact — do not cite them.** They passed only because the newly-added
link probe issues a GATT read, which on an unbonded link provokes a macOS pairing request; the
operator rejected it, and the rejection tore the connection down. That *is* a real unsolicited drop,
which is why a native event appeared — but it was caused by the probe, not by the stimulus under
test, and the 16-19 s timing is the pairing dialog, not a supervision timeout. Interpreting them as a
pass was wrong. **Lesson: never introduce the instrument and change the stimulus in the same run**;
both were changed together here, and the result was attributed to the wrong one.

**Retractions.** Two earlier readings in this section were wrong and are withdrawn:

- That btleplug/CoreBluetooth "never reports a peer-initiated disconnect on macOS." It reports it in
  145 ms. The six silent runs were the invalid stimulus, not a backend gap. Nothing about this case
  belongs on Rig B.
- That a "wedged CoreBluetooth peripheral identity" explained the failures. Control B failed against
  a fresh `PeripheralId`, so identity was never the variable. (The identity does rotate on every
  peripheral-app restart, which is what made the correlation look real.)

**Method notes for the next rig session.**

- *Verify the stimulus before trusting a negative.* `PeripheralStateMain` takes a link-probe interval
  (arg 5; `0` disables) that issues a GATT read through the window — a read can only succeed over a
  live link. Leave it **off** by default: on an unbonded peripheral it provokes pairing and can
  manufacture the very drop being measured. Turn it on only to prove a link is still up, and expect
  a pairing prompt when you do.
- *The reason field does not discriminate on `agent-rs`.* It hardcodes
  `reason: Some("peer disconnected")` in `report_unsolicited_disconnect`, the single path shared by
  its native handler and its liveness prober. Attribute a PASS from
  `btleplug event received: DeviceDisconnected` at `--log-level debug`, not from the wire reason.
  Only the Kotlin agent leaves the poll path's reason `null`.
- *The peripheral app stops advertising after a disconnect* — re-tap **START SERVER** between runs,
  and confirm with `:e2e-runner:scanRun` before concluding anything from a scan timeout.
- *`adb shell input tap` is silently dropped when the phone's display has slept.* Pin it with
  `adb shell svc power stayon usb`.

**One more operational trap:** after any phone-Bluetooth toggle, Android's advertiser frequently
fails to restart even though the app logs `STARTED — advertising RBTestPeripheral`. Force-stop and
relaunch the app, tap **START SERVER**, and confirm with `:e2e-runner:scanRun` before every run. A
scan that returns other devices but not `RBTestPeripheral` is the peripheral's fault, not the
agent's — checking that distinction takes one command and saves a wasted run.

**Case 2 is complete.** Both agents confirmed on a real teardown, native path verified in each
agent's own log.

### Case 3 — Two-client authorization on real radio

**PASS on both agents, 2026-07-28 (evening).** Kotlin agent **11/11**; `agent-rs` **10/11 with 1
gated** after the fixes below (8/11 with 3 gated as first run). No check failed on either agent — client B was never allowed through any device-bearing
operation, which is what this case exists to prove.

New driver [`TwoClientMain.kt`](../e2e-runner/src/jvmMain/kotlin/dev/warsha/remoteble/e2e/TwoClientMain.kt)
(`:e2e-runner:twoClientRun`). **Two sessions in one process is a faithful harness, not a shortcut:**
ownership keys on `session_key(principal, clientId)` and `WebSocketAgentTransport` mints `clientId`
as a fresh `Uuid.random()` per instance, so two transports are two distinct clients to the agent. A
second physical device would add no coverage. Cross-*principal* auth is a different question, already
covered by `AUTH-PRINCIPAL-01`'s paired tests. Ops go through `AgentSession.request` rather than the
`Peripheral` facade because this case asserts on the *typed error*, which the facade converts to
exceptions.

```
PASS  B can still scan and see the leased device
PASS  B's connect / read / write / observe / discover / disconnect  — PERIPHERAL_BUSY
PASS  A's own read still works while B is refused
PASS  B can connect once A releases                   (Kotlin agent)
```

**Two `agent-rs` divergences were found, gated rather than accommodated** (assertions left intact so
an XPASS signals a stale gate — same practice as the conformance suite). One is now **fixed**; one is
not, for a reason worth recording.

1. **Capability was checked before authorization — FIXED 2026-07-28, gate removed.** `ReadRssi` and `SetConnParams` fall to a catch-all
   `_ => OpResult::err(Unsupported)` arm in `transport/server.rs` that never calls
   `authorize_connected`, so a non-owner gets `UNSUPPORTED` where the Kotlin agent gives
   `PERIPHERAL_BUSY` (its `BleAgent` authorizes in every device-bearing branch first). The plan's
   requirement is still met — the op *is* refused — and nothing device-specific leaks, since the
   capability set is already public from the handshake. The catch-all now authorizes first via a new
   `Op::device_handle()` accessor (read-only counterpart to `translate::map_op_device`, so new
   device-bearing variants must be added to both). Two unit tests pin it: a non-owner gets
   `PERIPHERAL_BUSY`, the owner still gets the honest `UNSUPPORTED`. Re-confirmed on the rig.
2. **A handle stops resolving after disconnect — NOT fixed; a fix was tried and reverted.** `agent-rs` resolves handles by scanning
   `adapter.peripherals()`, and btleplug drops a peripheral from that list once it disconnects with
   no scan running — so B's reconnect after A released returned `UNKNOWN_DEVICE`, and a client must
   rescan before it can reconnect. The Kotlin agent builds a Kable `Peripheral` straight from the
   identifier and has no such dependency. This is the more consequential of the two: it changes the
   reconnect contract between the agents.

   **Retaining connected peripherals in a cache, with `find_peripheral` falling back to it, was
   implemented and then reverted.** On the rig the handle did resolve — but `connect()` on the
   retained handle never completed: no `DeviceConnected`, no error, ~45 s until the client's own
   timeout, surfacing as an opaque `TIMEOUT` with no message. That trades a fast, actionable error
   for a silent stall, which is worse for a caller — `UNKNOWN_DEVICE` at least says exactly what to
   do. The rationale is recorded on `BtleplugBackend::find_peripheral` so it is not attempted blind
   again. Closing this properly needs a way to re-establish from a bare identifier, which btleplug
   does not currently offer.

**A third finding, from the run that exposed it.** The first attempt failed on `A's own read` because
the driver targeted "the first readable characteristic", which on an Android peripheral is one of the
platform's own SIG services (`00001849`/`00002b93`, Volume Control) — those require encryption, so
the read raised a pairing dialog and stalled. Two things came out of that:

- The driver now prefers a **vendor** (non-SIG-base) service, falling back to any readable one.
  Encryption requirements are a GATT *security permission*, not a property bit, so `properties.read`
  cannot identify a safe target — service-UUID class is the only signal available client-side.
- **`GATT_OP_TIMEOUT` was exercised by an unplanned real stall** and behaved exactly as designed:
  `TIMEOUT — read did not complete within 10s`, instead of parking the command coroutine forever.
  First field exercise of that fix outside a deliberate test.

**Related agent finding — the liveness probe could kill a healthy connection. FIXED in both agents.**
In an earlier attempt
(default 15 s probe interval) the agent's own watchdog tore down client A's connection:

```
INFO  device connected [c=13 dev=004302b7-…]
WARN  liveness probe failed [dev=004302b7-… deepCheck=true] — declaring unsolicited disconnect
INFO  unsolicited disconnect [c=13 dev=004302b7-… reason=null]
```

`checkLiveness` probes with a real GATT read on the first readable characteristic; when that one
demands encryption the read blocks on a pairing dialog, `LIVENESS_PROBE_TIMEOUT` (5 s) expires, and
the watchdog declares a **false** unsolicited disconnect on a link that was never in trouble. It has
the same unavoidable blind spot as the driver did — it cannot tell which characteristic will demand
pairing, because encryption is a GATT security *permission* and is not visible in the discovered
table.

**Fix: both agents now require two consecutive failed deep probes before declaring a drop**
(`ConnectionWatcher.LIVENESS_FAILURES_BEFORE_DROP`, and the same constant in `btleplug_impl.rs`). One
stalled round trip is weak evidence; two in a row is not. The cost is one extra probe interval before
a genuine silent drop is declared, which matters little given the native stream reports a real drop
in ~145 ms (case 2) — this loop is the backstop, not the primary detector.

> A first cut of the fix was wrong in an instructive way: it let *any* successful check reset the
> counter. But the shallow per-tick check reads the platform's cached state — precisely what goes
> stale when a peripheral vanishes — so it reset the counter between every pair of deep probes and
> the threshold could never be reached. The pre-existing
> `deepLivenessProbeCatchesAStaleConnectedState` test caught it. Only a successful *deep* probe
> clears the count.

Case 3 was still run with `REMOTE_BLE_LIVENESS_PROBE_MS=300000` so the watchdog could not interfere
with a case that is not about liveness.

> The Kotlin 11/11 run predates the PASS/XFAIL reporting split, but the assertions themselves did not
> change — only how a non-`PERIPHERAL_BUSY` refusal is reported. Every Kotlin check returned
> `PERIPHERAL_BUSY` or `Ok`, so it is 11/11 under either build.

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
