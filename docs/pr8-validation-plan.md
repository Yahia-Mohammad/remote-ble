# PR8 — deferred hardware-validation test plan

**Purpose:** the exact, checkable test list behind [`0.10.0-scope.md`](proposals/0.10.0-scope.md)
Workstream D and [`release-candidate.md`](release-candidate.md)'s "Complete PR8's real-radio, iOS,
TLS-proxy, Ubuntu, and Pi evidence" line. [`phase7-bringup.md`](phase7-bringup.md) is the mechanical
runbook for driving the real-radio rig; this document is the checklist of *what* to run on *which*
rig, so each one can be set up once and fully exhausted before moving to the next.

**Evidence rule (applies to every rig below):** for each test case, record host/device details,
agent version/commit, exact command, and a redacted log or screenshot. Evidence is archived with
the release commit before tag approval — see `release-candidate.md` step 4.

---

## Progress — 19 of 25 cases run

**Updated 2026-07-30.** Every case below is marked inline with its outcome. Per-case detail lives in
the evidence docs; this table is the index, and
[`0.10.0-progress-status.md`](proposals/0.10.0-progress-status.md) carries the open-gap list that the
runs produced.

| Rig | Cases | Status | Evidence |
|---|---|---|---|
| **A** — real radio | 8/8 | ✅ **COMPLETE** (2026-07-27 → 07-28) | [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md) |
| **B** — iOS agent lifecycle | 6/6 | ✅ **COMPLETE** (2026-07-29), spill-over closed 07-30 | [pr8-rig-b-evidence.md](pr8-rig-b-evidence.md) |
| **C** — TLS reverse proxy | 5/5 | ✅ **COMPLETE** (2026-07-27) | [tls-proxy-recipe.md](tls-proxy-recipe.md) |
| **D** — Rust container hosts | 0/6 | ⛔ **NOT RUN** — the only rig outstanding | — |

**What is left, precisely:**

1. **Rig D's run** — the whole rig. Scope decision already taken (**option 1**): run the available
   Nobara amd64 laptop, then relax the acceptance criteria to "one amd64 Linux host validated,
   AppArmor and arm64 unvalidated" and label the image accordingly. This is a **release blocker**.
2. **Gap 13's mechanism** (Rig B case 5's follow-up) — one discriminating run against an *unbonded*
   peripheral. The link outliving a killed agent is measured at ≥26 min, but it may belong to this
   rig's bonded phone pair rather than to iOS. Not a blocker.
3. **Rig B case 3's screen-lock half** — the background half is measured; a manual lock was never
   performed, because the agent disables the idle timer while running. Not a blocker.

**Three things the runs changed about this plan itself**, all marked inline below: two stimuli were
invalid as written (Rig A case 2, Rig B case 6), one expectation was disproved by the hardware (Rig B
case 3), and one case turned out to be partly unreachable on its rig (Rig A case 5). A reader
following this plan should read those corrections before running the case, not after.

---

## Rig A — Real-radio phone rig (re-validation + parity)

**Hardware:** a Bluetooth-capable Mac (or the Pixel 8 + Android emulator pair), one BLE peripheral
device (a phone advertising the `TestProfile`/HRM service, e.g. the sibling `../ble-peripheral`
apps or `nRF Connect`'s GATT server), a second phone for the two-client cases.

**Setup:** either bring-up path already proven works —
- Mac-hosted: [`phase7-bringup.md`](phase7-bringup.md) (`agent/run-agent.sh` + `:e2e-runner`), or
- mobile-hosted: the cold-start checklist in memory `0-8-1-hardware-test-rig` (Android emulator
  client → USB `adb forward` → Pixel agent → iOS peripheral).

**Why re-run scenarios already proven in 0.8.1:** `agent-rs` picked up a major dependency bump
since then (`tokio-tungstenite` 0.24→0.30, fixed in commit `12a9ea7`) and the whole conformance
suite has grown substantially — this is the first real-radio run against that code, not a repeat.

### Test cases

1. ✅ **PASS** (both agents) — *but see gap 9:* the recorded reason it passed is questionable. It
   asserted an exact advertised-name match on the Kotlin agent, which Rig B finding 4 shows that
   agent could not have seen; it most plausibly passed on a warm CoreBluetooth GAP-name cache and
   would not reproduce on a cold host. Re-examine before relying on it.

   **Full `:e2e-runner` 14-step run**, both agents (Kotlin `:agent` **and** `agent-rs`) — scan,
   connect, discover, read (exact), write with/without response, negotiated MTU, notify (no
   miss/dup), disconnect. `phase7-bringup.md` steps 0–3.
2. ✅ **PASS** (both agents, 2026-07-28 evening) — closed only after the original stimulus was found
   to measure nothing; see the correction below.

   **F3 unsolicited disconnect** — an unsolicited BLE-level drop mid-session; confirm both agents
   emit the drop event and the client reaches `State.Disconnected`.
   **Stimulus corrected (2026-07-28, see [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md) case 2):**
   *not* "Force disconnect all" — Android's `BluetoothGattServer.cancelConnection()` releases the
   server's reference without terminating a link the central established, so the radio stays up and
   the case silently measures nothing. Kill the link for real: `adb shell cmd bluetooth_manager
   disable`, power the peripheral down, or take it out of range.
3. ✅ **PASS** (both agents, 2026-07-28 evening) — found two `agent-rs`-only divergences, both
   **gated rather than accommodated**: capability checked before authorization, and a handle stops
   resolving after disconnect (open as gap 4, P4 — needs a btleplug capability that does not exist).

   **Two-client authorization on real radio** — client B scans and can see client A's leased
   device but read/write/observe/configure/disconnect all fail; run against both agents.
4. ✅ **PASS** (2026-07-28 afternoon).

   **`setConnParams`** — `LOW_POWER`/`BALANCED`/`LOW_LATENCY` returns `Ok` on the Android agent;
   JVM, `agent-rs`, and iOS answer `UNSUPPORTED` (capability omitted), not an error.
5. ⚠️ **PASS, reduced scope** (2026-07-28 afternoon) — reconcile/resume proven across both a
   transport blip and an agent restart; the *rewrite* half is structurally unreachable on this rig.
   See the scope correction below.

   **Reconcile-under-translation, live** — cross-platform client (translated identifiers) with a
   bonded connection, then a transport blip (toggle Wi-Fi / kill `adb forward`); confirm the
   session resumes and replayed ops route correctly. Separately confirm the documented residual:
   after an **agent restart** (not just a transport blip), the client rescans rather than resuming.
   **Scope correction (2026-07-28, see [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md) case 5):** the
   *rewrite* half of this is unreachable on Rig A regardless of client platform.
   `HandleTranslator.needsRewrite` only synthesizes for a `UUID`/`MAC_ADDRESS` client format that
   differs from the agent's own; Rig A's agent is macOS/Kable (native `UUID`), so a `UUID` client
   (JVM, iOS) is an identity match and `STRING` (Android) is never rewritten at all — every client
   this repo ships hits a no-rewrite path against this agent. Genuine rewriting needs an agent whose
   native format is `MAC_ADDRESS` (Windows) or `BLUEZ_JSON` (Linux), i.e. Rig D or a Windows host.
   What Rig A *can* prove — reconcile/resume with the capability negotiated and a genuinely
   cross-platform client (`fmt=STRING`), across both a transport blip and an agent restart — is
   still real evidence, just not the rewrite-survives-reconcile property this case implies.
6. ✅ **PASS** (both agents, 2026-07-28 afternoon) — the fail-fast gate ported to `agent-rs` here,
   and a regression in it was found and fixed (it wrongly covered `WriteWithoutResponse`).

   **Write-without-response throughput + ordering** — `ThroughputMain` baseline, then
   `RemotePeripheral.writeWithoutResponseBurst` with `window > 1`; confirm a measured improvement,
   submission order preserved in the peripheral's write log, and no regression to with-response
   writes. Run against both agents (the Rust write-reservation guarantee needs on-radio
   confirmation, not just its unit test).
7. ✅ **PASS** (2026-07-28 afternoon).

   **Battery/Device-Info on a non-iOS peripheral** — confirm live values (not frozen), using the
   Android `health-peripheral` app or `nRF Connect`'s GATT server. Do not use an iPhone peripheral
   for this case (it silently shadows 0x180F/0x180A with iOS's own values — see memory
   `0-8-1-hardware-test-rig`).
8. ✅ **PASS** (2026-07-28 afternoon).

   **CONN-1** — start the client first, start the agent later; confirm the client self-heals
   without a restart once the agent comes up.

**Exit: MET (2026-07-28).** All 8 pass on both agents where applicable; results archived per the
evidence rule above. The rig also found three real defects in our own code and two blocking defects
plus one stale build in the `../ble-peripheral` test peripheral — all of which only real hardware
surfaced. Case 1's *reason* remains under review (gap 9).

---

## Rig B — iOS agent lifecycle rig

**Hardware:** a Mac with Xcode, a physical iPhone (the Simulator has no real radio), one BLE
peripheral in range (reuse Rig A's).

**Setup:** [`ios-agent/README.md`](../ios-agent/README.md) — build the `RemoteBleAgent.xcframework`,
`xcodegen generate`, run on the physical iPhone.

### Test cases

1. ⚠️ **PASS, scope corrected** (2026-07-29) — the agent listens and is reachable. The
   dashboard/`/api/state` half is **structurally impossible on mobile**, not a defect: `operatorToken`
   is settable only from `jvmMain`, so a mobile-hosted agent cannot serve `/` or `/api/state` at all.
   A `404` on `/` is therefore the healthy answer from a running mobile agent; probe the WebSocket
   endpoint or a `401` instead.

   **Start** — launch the app, tap Start, confirm the agent listens and is reachable from a client on
   the same network.
2. ✅ **PASS, 14/14** (2026-07-29) — **the rig's headline deliverable.** The Apple native Kable
   backend delivers ATT errors correctly: both gated steps XPASS reproducibly across 3 runs, which
   confirms Rig A's two XFAIL gates are genuinely btleplug-specific and should stay as-is for the
   btleplug agents. Getting here required fixing a defect that blocked *every* read on iOS
   (`forceCharacteristicEqualityByUuid`, finding 5).

   **Ordinary session** — scan/connect/read/notify against the Rig A peripheral through the
   iOS-hosted agent, proving the shared `AgentRunner`/`EngineBleBackend` behaves the same as the
   Mac/Android hosts already validated in Rig A.
3. ⚠️ **PASS (background half); screen-lock half NOT RUN** (2026-07-29) — and it **disproved the
   expectation it was written to confirm**. A backgrounded agent holding a BLE link stays *fully*
   reachable: 92/92 GATT reads and 38/38 brand-new inbound connections across 91 s. A `nolink`
   control — same instrument, one variable removed — suspended ~8 s in, which is what makes the
   result attributable. **A 2026-07-30 follow-up (gap 15) then found the one thing a backgrounded
   agent cannot do: discover.** An unfiltered scan returns 0 devices where a foregrounded one returns
   38, because iOS ignores a `nil`-serviceUUIDs scan while backgrounded; a service-filtered scan is
   unaffected. So a backgrounded agent serves everything and sees nothing new.

   **Screen-lock/background caveat** — background the app or lock the screen mid-session and
   capture what an already-connected client observes, *and* whether new inbound connections are
   still accepted.
   **Expectation corrected (2026-07-29, see [pr8-rig-b-evidence.md](pr8-rig-b-evidence.md) case 3):**
   this case previously asserted that "no *new* inbound WebSocket connections are accepted", which
   the hardware disproved. `UIBackgroundModes: bluetooth-central` keeps the process — and therefore
   the Ktor accept loop — scheduled while it holds an active CoreBluetooth link, so a backgrounded
   agent with a client mid-session stays **fully reachable** (38/38 new connections accepted over
   91 s). The restriction only applies with **no** BLE link, where the app suspends ~8 s after
   backgrounding. Any re-run must include that no-link control, or it attributes to "backgrounding"
   what is really "backgrounding without a link". The **screen-lock** half is still unrun: the
   agent disables the idle timer while running, so it needs a manual lock.
4. ✅ **PASS after a fix** (2026-07-29) — **the rig's only live defect, and a safety issue.** Stop
   released the radio and notified the client, but left the WebSocket server **listening and
   authenticating**: 0 of the inbound probes across a 240 s window were refused, ~187 s of it after
   the tap, while the button, the runner state and `AgentStopResult.serverStopped` all reported the
   agent as off. It also root-caused the Stop → Start process abort (`EADDRINUSE` on the orphaned
   listener). Fixed, and re-verified on **both** iOS and Android hardware.

   **Stop** — tap Stop (or navigate away); confirm `IosAgentSession` is disposed
   (`Coordinator.deinit`), the radio/lease is released, and the agent is no longer reachable.
   **Found a defect (2026-07-29, see [pr8-rig-b-evidence.md](pr8-rig-b-evidence.md) case 4):** the
   radio was released but the WebSocket server kept listening *and authenticating*, and the next
   Start aborted the process on `EADDRINUSE`. Fixed by having `AgentRunner.stop()` stop the server
   explicitly. When re-running, probe the port after Stop — checking only that device operations
   fail is what let this through, since they fail for the unrelated reason that the radio is gone.
   Note "the dashboard" is unreachable on mobile in any case (case 1's scope correction).
5. ⚠️ **PASS on the client side; found a defect on the radio side** (2026-07-29) — killing the agent
   mid-operation tears down cleanly for the client (typed `TRANSPORT_LOST` on every op, no hangs, no
   crash) but **leaves the BLE link up**. Measured 2026-07-30 at **≥26 minutes** (gap 13), on two
   instruments, with the mechanism still unconfirmed — see gap 13 for the one run that settles
   whether this is iOS or this rig's bonded phone pair. Also surfaced that the client's
   `Peripheral.state` never left `Connected` when the agent died (fixed via `TransportState.GAVE_UP`).

   **Cancellation mid-operation** — start a scan or an active connection, then stop the agent app
   mid-flight; confirm clean teardown with no crash and no leaked native connections (the iOS
   analogue of `SHUTDOWN-01`).
6. ⚠️ **PARTIAL PASS** (2026-07-29) — unrunnable as written, then partly failing once given a valid
   stimulus. **No crash under any of it, the server is unaffected by radio state, and recovery is
   automatic** (Bluetooth back on → 32 devices, no Stop/Start). **The "clear messaging" half failed
   outright:** a scan with the radio off returned zero devices with no error, indistinguishable from
   an empty room. That headline finding turned out to be **cross-platform** — neither agent noticed
   Bluetooth being off (gap 17) — with an iOS-only wiring gap underneath it (evidence 8). **Both are
   now fixed and verified on hardware:** radio state is reported over the wire as a gated event plus
   `ErrorKind.RADIO_OFF`, and the iOS Start gate was confirmed on 2026-07-30 with the permission
   genuinely revoked (Start greyed out, warning shown, Settings route offered).

   **Failure recovery** — with the radio unavailable, confirm a graceful UI state (no crash, clear
   messaging), then restore it and confirm the agent recovers.

   **Stimulus corrected (2026-07-29, see [pr8-rig-b-evidence.md](pr8-rig-b-evidence.md) case 6).**
   This case used to say "deny the Bluetooth permission prompt on first launch". **There is no such
   prompt** — on a fresh install of a fresh bundle id, none appeared at launch, at Start, or on the
   first scan; the scan simply succeeded. The case was therefore unrunnable as written, not failing.

   Use **Settings → Bluetooth → off** instead. It tests the same property (graceful degradation and
   recovery when the radio is unavailable) via a stimulus that actually exists.
   **It must be Settings, not Control Centre** — since iOS 11 the Control Centre toggle only
   disconnects accessories and leaves Bluetooth available to apps, so using it applies no stimulus
   at all and yields a false pass. Verify the stimulus landed before interpreting anything: a
   `:e2e-runner:scanRun` that still reports devices means Bluetooth is on, whatever the toggle looks
   like.

   Sequence: baseline `scanRun` (confirm devices are seen) → Bluetooth off → `scanRun` again →
   Stop, then Start with the radio still off (does Start report success?) → Bluetooth on →
   `scanRun` without restarting the agent (does it recover on its own?).

   **The real defect this case should be testing** is a wiring gap, not a prompt.
   `MainActivity` (Android) passes `startEnabled = bluetoothGranted`, a `permissionWarning`, and
   `onRequestPermissionSettings`. `IosAgentEntry` passes **none** of the three, so they default to
   `true`/`null`/`null`: on iOS, Start is always enabled, there is no warning surface, and there is
   no route to the app's settings page. Whatever the stimulus, that asymmetry is what to assert
   against.

**Exit: MET (2026-07-29), with two residuals.** All 6 cases run; nine findings, six fixed during the
rig and three more closed in follow-up sessions. Residuals: case 3's **screen-lock** half was never
run (the agent disables the idle timer while running, so it needs a manual lock), and case 5's
lingering-link **mechanism** is unconfirmed (gap 13). Note that cases 3, 4 and 5 each *contradicted*
the behaviour they were written to confirm — the plan's own expectations were the thing most often
wrong here.

---

## Rig C — TLS reverse-proxy rig (`TLS-PROXY-01`)

**Hardware:** any host that can run a reverse proxy plus the agent (the Rig A Mac works fine); the
Rig A peripheral for the live-notification case. No dedicated BLE hardware beyond that.

**Setup (no documented recipe exists yet in this repo — this is the first one):**

1. Run the agent loopback-bound: `REMOTE_BLE_BIND=127.0.0.1 REMOTE_BLE_TOKEN=<secret> agent/run-agent.sh 8080`
   (or the `agent-rs` equivalent).
2. Put a TLS-terminating reverse proxy in front (Caddy or nginx both work; Caddy auto-provisions
   certs if you have a real domain). It must forward the WebSocket `Upgrade`/`Connection` headers
   and the `Authorization` bearer header upstream to `127.0.0.1:8080`.
3. If no public domain is available, use a local CA (`mkcert`) and explicitly configure the test
   client to trust that CA — do not disable certificate validation, since that would silently pass
   the CA-trust case without proving anything.

### Test cases

1. ✅ **PASS** (2026-07-27).

   **WebSocket upgrade** — client connects via `wss://<proxy-host>/agent` and completes the
   handshake through the proxy.
2. ✅ **PASS** (2026-07-27).

   **Bearer forwarding** — an authenticated `wss://` connection succeeds; one without the bearer
   token is rejected, proving the proxy forwards the header rather than stripping it.
3. ✅ **PASS** (2026-07-27).

   **CA trust** — the client validates the proxy's certificate against the configured CA; a
   deliberately untrusted cert (wrong CA, expired, or hostname mismatch) fails closed rather than
   silently connecting.
4. ✅ **PASS** (2026-07-27).

   **Reconnect through the proxy** — induce a transport blip (restart the proxy or toggle network)
   and confirm the client reconnects through `wss://` and resumes its lease within grace.
5. ✅ **PASS** (2026-07-27 against the simulated backend, **re-confirmed on real radio** during Rig A
   — which is what discharges the "not a fake-only claim" requirement).

   **Live notification delivery** — subscribe to the peripheral's notify characteristic and confirm
   a sustained stream of updates arrives through the proxy without buffering stalls or drops (long-
   lived WebSocket frames are exactly what a misconfigured proxy breaks).

**Exit: MET (2026-07-27).** All 5 pass; cases 1–4 test proxy properties and are backend-independent.
Record the exact proxy config (redacted of any real secrets/certs) alongside
the evidence rule above — this becomes the documented recipe referenced by `docs/agent.md`'s
"TLS-terminating reverse proxy" guidance.

---

## Rig D — Rust-agent container host rig (Ubuntu + Raspberry Pi)

**Hardware:** **two** separate hosts, each with its own Bluetooth adapter and a peripheral in
range — (1) an Ubuntu amd64 machine or VM with Docker, (2) a Raspberry Pi (Raspberry Pi OS or
Debian, arm64) with Docker or Podman. Both need host BlueZ + system D-Bus.

**Image:** `ghcr.io/yahia-mohammad/remoteble-agent-rs:0.10.0` (or a locally built image from
[`agent-rs/Dockerfile`](../agent-rs/Dockerfile), now fixed to build cleanly per commit `75ba5a1`).

Full procedure: [`rust-agent-container.md`](proposals/rust-agent-container.md) §8 "Real-host smoke
tests". Run the following **on each host independently** — this is not one test run split across
two machines, it's the full sequence twice:

### Test cases (per host) — ⛔ NONE RUN

**Status 2026-07-30: this is the only rig still outstanding, and it is a release blocker.** The scope
decision is taken (**option 1**): a Nobara (Fedora-based) amd64 laptop is available and can run all 6
cases on a real radio, which retires the substantive risk that the container does not work on Linux.
It does **not** satisfy the acceptance criteria as written — Ubuntu is named in
[`rust-agent-container.md`](proposals/rust-agent-container.md) *because of* AppArmor, and Fedora ships
SELinux — so the plan is to run it, then relax the criteria to "one amd64 Linux host validated,
AppArmor and arm64 unvalidated" and label the image accordingly. **arm64 stays entirely uncovered.**
Docker Desktop on macOS cannot substitute: no host Bluetooth in its Linux VM.


1. **Adapter visible to host BlueZ** — `bluetoothctl list` / `hciconfig` shows the adapter before
   touching the container.
2. **Authenticated start** — mount `/run/dbus/system_bus_socket`, set `REMOTE_BLE_TOKEN`, start the
   image per the documented `docker run` command in `rust-agent-container.md` §4.
3. **Unauthenticated non-loopback startup fails** — confirm the bind policy still fails closed
   inside the container (no credential → no listener on `0.0.0.0`).
4. **`--version` reports 0.10.0** and the manifest resolves to the host's native architecture
   (amd64 on Ubuntu, arm64 on the Pi).
5. **Ordinary client session** — an unmodified RemoteBLE client connects through the published
   port and completes scan → connect → discover → read → disconnect against the real peripheral.
6. **`SIGTERM` handling** — stop the container and confirm the structured shutdown/disconnect path
   runs (not a hard kill), then restart and repeat step 5 to prove no leaked state.

**Exit: NOT MET — 0 of 6 on 0 of 2 hosts.** Under option 1 this becomes "one amd64 Linux host passes
all 6", with the arm64 host and the AppArmor reference host recorded as unvalidated. Capture host OS,
Docker/Podman version, BlueZ version, adapter
model, image digest, exact command, and redacted logs for each — `rust-agent-container.md`'s
acceptance criteria require both reference hosts before the image is called supported.

---

## Suggested order

Rigs are independent and can run in any order or in parallel if you have the hardware for more
than one at once. **Rigs A, B and C are done — only Rig D remains, so there is no ordering left to
choose.** The original advice, kept for a future release's re-run: sequence Rig A first, since it
re-validates the most code and shares its peripheral with Rigs B and C.
