# Scan-concurrency hardware validation — closed 2026-08-03

[← back to index](README.md)

**Status: CLOSED.** The release blocker recorded in
[proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) and as gap 21 in
[proposals/0.10.0-progress-status.md](proposals/0.10.0-progress-status.md) is closed — see
[Evidence — 2026-08-03 run](#evidence--2026-08-03-run) below (the section further down this
document). One case, `SC-HW-06` (the Apple
overflow-advertising wording gate), did not run for lack of a second Apple device; it does not gate
the blocker and is ready to run whenever one is available.

**Purpose (historical).** This plan closed the release blocker recorded in
[proposals/scan-concurrency-modes.md](proposals/scan-concurrency-modes.md) and as gap 21 in
[proposals/0.10.0-progress-status.md](proposals/0.10.0-progress-status.md). The implementation and
its automated evidence were complete beforehand; what remained was real-radio confirmation, on the
platform the defect actually lives on, plus a decision about one sentence of Apple wording that only
hardware can settle.

**Evidence rule** (same as [pr8-validation-plan.md](pr8-validation-plan.md)): for every case record
host/device details, agent version **and commit SHA**, **configured mode**, **negotiated mode**, the
exact command, and a redacted log. Archive with the release commit before tag approval.

**Scope.** This plan covers scanning only. The other open 0.10.0 gates — Rig D's container run, gap
13's mechanism, CI evidence on the tag candidate — are tracked in their own documents and are not
repeated here.

---

## What is already green (do not re-run to "prove" it)

Re-running these tells you nothing new; the point of listing them is so the hardware run can be kept
small and pointed.

| Evidence | Where |
|---|---|
| `SCAN-CONC-01`…`12` over real WebSocket transport, both agents | `ScanConcurrencyWebSocketTest` (Kotlin), `server::tests::scan_conc_*` (Rust) |
| Fencing, collector ownership, arbiter fairness, replay expiry/capacity, stable-client cap, linearizable `single` admission, exactly-one capability | `ScanCoordinatorTest`, `ScanOutboundArbiterTest`, `scan_coordinator::tests::*` |
| Legacy `uncontrolled` path still takes the backend route | `uncontrolledModeUsesTheLegacyBackendPathForBothScans`, `uncontrolled_mode_uses_the_legacy_backend_path_for_both_scans` |
| Full gates | `./gradlew build conformanceTest`; `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test --locked` |

**What none of them can establish**, and therefore what this plan is for: whether one physical Apple
radio actually behaves the way the design assumes when two logical scans share it.

---

## Instruments

Two additions to `:e2e-runner`, both already in the tree and smoke-tested against the simulated
agent:

```sh
# The probe: two staggered scans through one agent, graded on both hazard directions.
./gradlew :e2e-runner:scanConcurrencyRun --args "<ws-url> <service-uuid> [same-client|two-clients] [total] [bDelay] [bWindow]"

# The baseline / single-scan client, which now prints the negotiated scan-concurrency mode.
./gradlew :e2e-runner:scanRun --args "<ws-url> <seconds> [service-uuid]"
```

The probe runs a broad **scan A** for the whole window and a service-filtered **scan B** inside it,
then grades three things separately:

- **stop direction** — did A keep receiving after B stopped? (`CentralManager.stopScan()` takes no
  arguments, so B's stop could plausibly end A's scan.)
- **start direction** — did A narrow to B's service while B ran? Apple *documents* that a second
  scan's parameters replace the running scan's, which makes this the worse hazard and the silent one.
- **B filter correctness** — did B receive only advertisements carrying its service? This is the
  property `multiplexed` actually guarantees, so it is graded on its own rather than folded in.

`same-client` puts both scanners on one session — the ordinary app holding two `RemoteScanner`s,
which is what makes the defect reachable without a second process, and which exercises the
coordinator's per-stable-client accounting. `two-clients` uses two sessions. **Run both:** they take
different paths through admission.

### The instrument reports INCONCLUSIVE, and you must respect it

The start-direction check needs a device that A can see **which does not carry B's service**. Without
one, a narrowed scan and an unnarrowed scan produce identical output, so the probe says
`INCONCLUSIVE` rather than `PASS`. The simulated-agent smoke run does exactly this, because
`sim-hrm.json` has a single peripheral carrying the filtered service.

This is the repo's own recurring failure mode — *a check that could not fail, looking exactly like a
check that passed* — so it is built into the instrument rather than left to the operator. **An
`INCONCLUSIVE` start direction does not close this gate.** Fix the rig (below) and re-run.

---

## Rig prerequisites

Reusing Rig B, whose full setup is in [pr8-rig-b-evidence.md](pr8-rig-b-evidence.md).

| | |
|---|---|
| iOS agent host | iPhone 14, UDID `<redacted-udid>`, LAN `<iphone-lan-ip>`, needs a **manual Start tap** |
| JVM/`agent-rs` agent host | the Mac (`<mac-lan-ip>`), `agent/run-agent.sh` |
| Client | `:e2e-runner` on the Mac — no radio of its own |
| Filtered peripheral | Pixel 8, `com.warsha.ble.peripheral.sample`, service `a1b2c3d4-0000-4000-8000-000000000001`, Start at `adb shell input tap 540 314` |
| Token | `REMOTE_BLE_TOKEN=secret` |

Three prerequisites specific to *this* plan:

1. **At least one advertising device in range that does NOT carry the filtered service.** Ambient BLE
   traffic usually supplies this, but it must be **asserted, not assumed**: run
   `:e2e-runner:scanRun` for 30 s first and confirm the listing contains a device whose `uuids` lack
   `a1b2c3d4-…-0001`. If the room is quiet, add a second peripheral. Without this, the case that
   matters most reports `INCONCLUSIVE`.
2. **Verify the Pixel is actually advertising** with `adb exec-out screencap -p` before trusting any
   result. An `adb shell input tap` on a locked device succeeds silently and does nothing.
3. **The overflow case needs an iOS peripheral, and the free developer profile caps the device at
   three installed apps.** `health-peripheral-ios` was removed to make room for the agent during Rig
   B. Plan the reinstall (or a paid account) *before* the session — this is the one prerequisite that
   can end a day.

---

## Cases

### Phase 1 — baselines (must run first)

| ID | What | Command | Pass |
|---|---|---|---|
| `SC-HW-00` | Discriminating device present | `:e2e-runner:scanRun --args "ws://<iphone-lan-ip>:8080/agent 30"` | listing contains ≥1 device without the filtered service |
| `SC-HW-01` | A lone 60 s scan does not end on its own | `:e2e-runner:scanRun --args "ws://<iphone-lan-ip>:8080/agent 60"` | advertisements arriving throughout, including the final seconds |

`SC-HW-01` is not a formality. Without it, scan A going quiet later is unattributable — it could be
interference, or the rig.

### Phase 2 — the blocker (iOS agent, default `multiplexed`)

| ID | Topology | Command |
|---|---|---|
| `SC-HW-02` | two clients | `:e2e-runner:scanConcurrencyRun --args "ws://<iphone-lan-ip>:8080/agent a1b2c3d4-0000-4000-8000-000000000001 two-clients 60 15 10"` |
| `SC-HW-03` | one client, two scanners | `… a1b2c3d4-0000-4000-8000-000000000001 same-client 60 15 10"` |

**Pass:** `stop direction` PASS, `start direction` PASS, `B filter correctness` PASS, negotiated mode
recorded as `MULTIPLEXED`. Any `INCONCLUSIVE` on stop or start means the run does not count.

`SC-HW-03` is the case gap 21 was re-ranked for — one ordinary app with two scanners — so it is the
one to run first if the session is short.

### Phase 3 — parity (the same two cases, other agents)

| ID | Agent | Why |
|---|---|---|
| `SC-HW-04` | Kotlin JVM agent on macOS (btleplug via Kable) | Same CoreBluetooth-free path as Rig A; separates "Apple host" from "Apple radio API" |
| `SC-HW-05` | `agent-rs` on the same Mac | The other reference agent; its coordinator must produce the same three verdicts |

Run `SC-HW-02`'s and `SC-HW-03`'s commands against each, changing only the URL. **If Kotlin and Rust
disagree on any verdict, that divergence is itself the finding** and belongs in
[agent-parity-verification.md](agent-parity-verification.md), not in a footnote.

### Phase 4 — the wording gate (iOS background peripheral)

`SC-HW-06` — the overflow-area case. **This is the only case that can change what the docs claim**,
and the ordinary staggered runs above will pass without touching it.

1. Install and run the iOS peripheral; **background it** so its service UUID moves into Apple's
   overflow advertising area.
2. Confirm a *service-filtered* scan finds it: `:e2e-runner:scanRun --args "<ios-agent-url> 30 <svc>"`.
3. Confirm an *unfiltered* scan does **not**: `:e2e-runner:scanRun --args "<ios-agent-url> 30"`.
4. Now run the probe with that service as B's filter while A is broad, and record whether B still
   sees the backgrounded peripheral.

Step 4 is the real question: when a broad logical scan forces the physical scan unfiltered, does the
service-filtered logical scan lose the overflow-advertised peripheral? The design says it may, and
[scanning.md](scanning.md) documents it as an accepted residual. **Confirming it keeps that wording;
disproving it means the wording is too pessimistic and should be narrowed.** Either way the sentence
changes only on the strength of this case.

### Phase 5 — lifecycle on a real radio

| ID | What | Method | Pass |
|---|---|---|---|
| `SC-HW-07` | Reconnect rebinds rather than contends (`SCAN-CONC-10` on hardware) | Start `SC-HW-03`, drop the client's WiFi for ~3 s mid-window, restore | The scan resumes with no `SCAN_UNAVAILABLE`/`AGENT_BUSY` round trip; advertisements continue |
| `SC-HW-08` | Configured mode reaches the wire | Start the JVM agent with `REMOTE_BLE_SCAN_CONCURRENCY=single`, then `uncontrolled`; run `:e2e-runner:scanRun` | Startup log and the client's printed `negotiated scan concurrency` agree, three times out of three |

`single`-mode **refusal** semantics are already proven by `SCAN-CONC-05/06/10` on both agents over
real WebSocket transport; `SC-HW-08` only checks that the operator switch reaches the handshake on a
real host. Do not re-litigate refusal behaviour on hardware.

---

## What each result is allowed to change

Stated up front so the write-up is not negotiated after the fact.

| Result | Permitted consequence |
|---|---|
| Phases 1–3 all PASS | Close the release blocker in `scan-concurrency-modes.md`, `0.10.0-progress-status.md` gap 21, `pr8-validation-plan.md` item 3, and `docs/README.md`'s status column |
| Phase 4 confirms the residual | Keep [scanning.md](scanning.md)'s Apple wording verbatim; record the evidence beside it |
| Phase 4 disproves the residual | **Narrow** the wording to what was measured. Do not delete the limitation — absence of the effect on one peripheral is not its absence on the platform |
| Any Phase 2 case FAILs | The blocker stays open. The design is wrong on hardware, not the test; file it against the proposal |
| Any Phase 2 case is INCONCLUSIVE | Neither open nor closed — fix the rig and re-run. **Do not record it as a pass** |
| Kotlin and Rust disagree | Parity defect; open a gap, update `agent-parity-verification.md`, and do not tag |

---

## Evidence — 2026-08-03 run

Commit `09f2f49` on `codex/scan-concurrency-modes`. Hosts: iPhone 14 (UDID
`<redacted-udid>` / CoreDevice `<redacted-coredevice-id>`) running the
`ios-agent` debug build (release XCFramework link still OOMs per gap 11, so this run used the
established debug-vehicle fallback — production Kotlin/Swift source is identical either way); the
Mac (`<mac-lan-ip>`) running both the Kotlin JVM agent (`agent/run-agent.sh`) and `agent-rs`
(`agent-rs/run-agent-rs.sh`), never concurrently; the Pixel 8 running `sample-peripheral`
(`RBTestPeripheral`, service `a1b2c3d4-0000-4000-8000-000000000001`) as the filtered peripheral
throughout.

| Case | Configured mode | Negotiated mode | Result |
|---|---|---|---|
| `SC-HW-00` | iOS agent default | `MULTIPLEXED` | PASS — 27 devices, e.g. "[LG] webOS TV UN73006LC" (uuid `0000feb9-…`) and 25 others lack `a1b2c3d4-…-0001`, entry `RBTestPeripheral` carries it |
| `SC-HW-01` | iOS agent default | `MULTIPLEXED` | PASS — new devices still arriving at +27s and +40s of the 60s window |
| `SC-HW-02` (two-clients) | iOS agent default | `MULTIPLEXED` | PASS 4/4 |
| `SC-HW-03` (same-client) | iOS agent default | `MULTIPLEXED` | PASS 4/4 |
| `SC-HW-04` (both topologies) | JVM agent default | `MULTIPLEXED` | PASS 4/4 each |
| `SC-HW-05` (both topologies) | `agent-rs` default | `MULTIPLEXED` | PASS 4/4 each |
| `SC-HW-07` | iOS agent default | `MULTIPLEXED` | PASS — same-client run survived a confirmed 5s WiFi drop (`networksetup -setairportpower en0 off`, verified via ping) at ~t+30s; scan completed PASS 4/4 with no error surfaced and no restart |
| `SC-HW-08` | JVM agent, `REMOTE_BLE_SCAN_CONCURRENCY=single` then `uncontrolled` | `SINGLE` (3/3), `UNCONTROLLED` (3/3) | PASS 6/6 |

**`SC-HW-06` did not run** — needs two Apple devices simultaneously (a foregrounded central and a
separately backgrounded peripheral); only one iPhone was available. See the write-up in
[0.10.0-progress-status.md gap 21](proposals/0.10.0-progress-status.md) for what was found and fixed
while attempting it (`health-peripheral-ios` had no `bluetooth-peripheral` background mode) and what
remains ready to run. `scanning.md`'s Apple paragraph is unchanged.

Full gates (`./gradlew build conformanceTest`; `cargo fmt --check`, `cargo clippy --all-targets -- -D
warnings`, `cargo test --locked`) were not re-run in this session — no production code changed here,
only docs and a sibling repo's `Info.plist`. Re-run them on the exact commit intended for the tag,
per the exit criteria below.

---

## Exit criteria

- [x] `SC-HW-00` and `SC-HW-01` recorded, with a named discriminating device
- [x] `SC-HW-02`–`SC-HW-05`: three PASS verdicts each (four, in fact), no `INCONCLUSIVE` on stop or start
- [ ] `SC-HW-06` run, and its result reflected in [scanning.md](scanning.md)'s Apple paragraph — **blocked, needs a second Apple device**
- [x] `SC-HW-07`, `SC-HW-08` recorded
- [x] Every case names its commit SHA, configured mode and negotiated mode
- [x] Evidence archived; the four status locations above updated in one commit
- [ ] Full gates re-run on the exact commit intended for the tag — **outstanding, do before tagging**

---

## Ways this run can lie to you

Carried from Rig A/B's method notes, because every one of them cost a day and all have the same
shape — *a check that could not fail, looking exactly like a check that passed*.

1. **Assert the stimulus before trusting the subject.** A backgrounded iOS agent with no BLE link
   suspends ~8 s in and reports 0 devices — indistinguishable from interference.
2. **`adb shell input tap` on a locked or sleeping device succeeds and does nothing.** Screenshot
   after any tap that matters.
3. **`devicectl` silently swallows extra launch arguments** while reporting success, and a second
   `--console` stream fails silently while an earlier one holds the device (`pkill -f devicectl`).
4. **Confirm which device a human's confirmation refers to.** A parity-shaped answer about the wrong
   platform is indistinguishable from the result you want.
5. **Do not attach a known mechanism to an observation whose stimulus was not confirmed to fire.**
   Two wrong claims in this repo came from trusting prose — an evidence doc and a code comment —
   instead of reading the routes.
6. **New here:** a one-peripheral room makes the start-direction check unfalsifiable. The probe says
   so; believe it.
