# Rig D evidence — Rust-agent container host (Linux amd64)

Per-case evidence for [`validation-plan.md`](validation-plan.md)'s **Rig D**, run under the
**option 1** scope decision recorded in
[`0.10.0-progress-status.md`](proposals/0.10.0-progress-status.md) item 2: validate one available
amd64 Linux host, then relax the acceptance criteria and label the image honestly. **arm64 and
AppArmor stay unvalidated** — see [What this run does not prove](#what-this-run-does-not-prove),
which is the more important half of this document.

## Rig record

| | |
|---|---|
| **Host** | Nobara Linux 44 (Fedora-based), KDE Plasma edition |
| **Kernel / arch** | `7.1.4-200.nobara.fc44.x86_64` / x86_64 (amd64) |
| **Container runtime** | Docker 29.7.1 (build `e9452d6`), rootful via the `docker` group |
| **BlueZ** | `bluetoothctl` 5.86, `bluetooth.service` active |
| **Adapter** | `30:03:C8:54:FA:74` (`nobara-pc`), built-in |
| **SELinux** | **Disabled** on this install — see the caveat below, this matters |
| **Image** | locally built from [`agent-rs/Dockerfile`](../agent-rs/Dockerfile), `remoteble-agent-rs:local` |
| **Image ID** | `sha256:977bb0c84b654e0e44280574bc04d643592c6ee222195794d520d3cfa8004811` |
| **Source commit** | `263d297` plus the two fixes this rig produced (below) |
| **Peripheral** | `RBTestPeripheral` (`TestProfile`, service `a1b2c3d4-…-000000000001`), phone-hosted |
| **Date** | 2026-08-03 |

The image was **not** pulled from GHCR: no `v*` tag exists in this repository, so
[`agent-container.yml`](../.github/workflows/agent-container.yml)'s `publish` job has never fired and
`ghcr.io/…/remoteble-agent-rs:0.10.0` does not exist yet. Building locally from the release
Dockerfile is what the plan's "or a locally built image" allows; the published-digest half of
[`rust-agent-container.md`](proposals/rust-agent-container.md) C5 remains outstanding and is
unaffected by this run.

## Case results — 6 of 6 PASS

### 1. Adapter visible to host BlueZ — PASS

```
$ bluetoothctl list
Controller 30:03:C8:54:FA:74 nobara-pc [default]
```

`hciconfig` is **not** installed and was not used: it ships with the deprecated `bluez-utils`/
net-tools generation and is absent from current Fedora-family installs. `bluetoothctl` is the
supported BlueZ CLI and answers the same question. The plan's wording (`bluetoothctl list` /
`hciconfig`) already allows either; noted so a future runner does not install a deprecated package
believing the case requires it.

### 2. Authenticated start — PASS

```sh
docker run -d --name remoteble-agent -p 8080:8080 \
  -e REMOTE_BLE_TOKEN='<redacted>' \
  -v /run/dbus/system_bus_socket:/run/dbus/system_bus_socket \
  remoteble-agent-rs:local
```

```json
{"level":"INFO","fields":{"message":"Starting RemoteBLE Agent (Rust) v0.10.0 | log level: info"}}
{"level":"INFO","fields":{"message":"Scan concurrency: Multiplexed (REMOTE_BLE_SCAN_CONCURRENCY)"}}
{"level":"INFO","fields":{"message":"Starting btleplug event listener task..."}}
{"level":"INFO","fields":{"message":"Agent WebSocket server listening on ws://0.0.0.0:8080"}}
{"level":"INFO","fields":{"message":"Subscribed to btleplug adapter events stream"}}
```

`Subscribed to btleplug adapter events stream` is the line that matters: it is the agent reaching the
**host's** BlueZ through the mounted socket, not merely the process starting. The runtime shape was
confirmed against `rust-agent-container.md` §4/§10 rather than assumed:

```
User=65532:65532  Privileged=false  NetworkMode=bridge  CapAdd=[]
Binds=[/run/dbus/system_bus_socket:/run/dbus/system_bus_socket]
```

So the supported path needs **no `--privileged`, no host networking, no `/dev` passthrough, and no
added capabilities**, and the image's **non-root** user (65532) reached the mounted socket unaided —
which discharges the first bullet of §6's hardening target on this host. Note the socket is
`srw-rw-rw-` here, so this shows uid 65532 is *not blocked*; it is not evidence about hosts with a
tighter socket mode or a restrictive D-Bus policy.

A bare `GET /` returned an empty reply (no dashboard without an operator credential — the 0.10.0
default, consistent with Rig B case 1) and an unauthenticated WebSocket upgrade to `/agent` returned
`401 Unauthorized`.

### 3. Unauthenticated non-loopback startup fails closed — PASS

Same command with `REMOTE_BLE_TOKEN` removed:

```
Error: "non-loopback bind requires REMOTE_BLE_TOKEN/--token; use REMOTE_BLE_ALLOW_INSECURE_LAN=true
        only for local development"
exit 1
```

Stronger than the case asks for: there is no listener to reject requests. `curl` to `127.0.0.1:8080`
fails with connection-refused, and the container exits rather than idling in a half-up state.
`BIND-SECURITY-01` therefore holds inside the container, where the image's own
`REMOTE_BLE_BIND=0.0.0.0` default makes it load-bearing.

### 4. `--version` and architecture — PASS

```
$ docker run --rm remoteble-agent-rs:local --version
agent-rs 0.10.0
```

Architecture is amd64 by construction (built natively on this host), so this case's
manifest-resolution half is **not** exercised here — it needs the published multi-arch manifest, and
is carried by `rust-agent-container.md` C5, not by this run.

### 5. Ordinary client session — PASS (12 passed, 0 failed, 2 XPASS)

`:e2e-runner:jvmRun` (the full 14-step live E2E) against the containerised agent, driving the Kable
`Peripheral` surface end to end:

```
• Transport connects ... PASS — CONNECTED
• Scan finds the peripheral ... PASS
• Connect + discover services ... PASS — 7 services
• Locate profile characteristics ... PASS
• Read the readable characteristic (baseline) ... PASS
• Read exactness (F) — reflects the just-set bump ... PASS — 02
• Write (with response) ... PASS
• Write (without response) ... PASS
• Negotiated MTU write length ... PASS — 20 bytes
• Write-with-response error surfaces WRITE_FAILED (F) ... XPASS
• WWR still returns Ok despite the same peripheral-side reject ... PASS — Ok, as expected
• Write-with-response succeeds again — a failed write never poisons the session ... XPASS
• Observe 2 notifications, no miss/dup ... PASS — 2 received: 02, 03
• Disconnect ... PASS
RESULT: 12 passed, 0 failed, 2 unexpectedly passing
```

The 20-byte MTU is the documented `agent-rs` answer, not a regression: btleplug exposes no negotiated
ATT MTU, so `request_mtu` returns `UNSUPPORTED` and the client keeps the ATT default.

**This case did not pass on the first attempt — it found finding 1 below, which was a release
blocker.** An unfiltered scan additionally listed 20 real devices, and a service-filtered scan
isolated the test peripheral (`RBTestPeripheral rssi=-56 id=hci0/dev_7A_F1_F7_3B_72_AB`).

### 6. `SIGTERM` handling and restart — PASS

```
$ docker stop -t 10 remoteble-agent      # returned in 0.25s
{"level":"INFO","fields":{"message":"Shutdown signal received; disconnecting peripherals and exiting"}}
Status=exited ExitCode=0 OOMKilled=false
```

Returning in 0.25 s against a 10 s grace period is the evidence that the structured path ran and the
process exited on its own — a hard kill would have consumed the full timeout and exited non-zero.
After restart the peripheral was rediscovered immediately (service-filtered scan, 1 device), proving
no leaked BlueZ/D-Bus or lease state across the cycle.

## Findings

### Finding 1 — `agent-rs` could discover a device on Linux but never read, write, or observe it (release blocker; fixed)

Case 5 failed **deterministically** on two consecutive runs, at the first read after a successful
discover:

```
• Connect + discover services ... PASS — 7 services
• Locate profile characteristics ... PASS
• Read the readable characteristic (baseline) ... FAIL:
    Char a1b2c3d4-…-000000000002 not found in service a1b2c3d4-…-000000000001
```

**Root cause.** Every backend op resolved its own `Peripheral` through
`BtleplugBackend::find_peripheral` → `find_peripheral_by_id`, which walks `Adapter::peripherals()`
and returns a **fresh, independent instance** each time. `discover()` called `discover_services()` on
*its* instance; `read`/`write`/`start_observe`/`stop_observe` then looked up their own instances,
whose `services()` were empty, so `find_characteristic` could never match. On the Apple/CoreBluetooth
btleplug backend this happens to work, which is why Rig A never saw it; on BlueZ only the instance
that ran discovery reports a populated table.

**Fix.** `connected` now maps a handle to a `ConnectedDevice { event_tx, peripheral }` — the live
handle from the `connect()` that established the link — and the five GATT ops resolve through
`find_connected_peripheral`. This is **not** a new design: it is the Rust parity of the Kotlin
agent's `EngineBleBackend.peripherals`/`resolve`, which has always kept one long-lived `Peripheral`
per connected device. `agent-rs` was the outlier. It also removes a full `Adapter::peripherals()`
enumeration per op.

**Deliberately *not* the reverted cache.** `find_peripheral`'s doc records a peripheral cache that
was tried and reverted because it turned a fast `UNKNOWN_DEVICE` into a ~45 s hang. That cache was
for resurrecting a handle **across a disconnect**; this one only ever serves a link that is currently
up. `connect()`'s own resolution is untouched and still always does a fresh lookup, entries are
removed on both explicit and unsolicited disconnect, and a missing entry fails fast. **Gap 4
(`agent-rs` cannot re-resolve a handle after disconnect) is therefore still open and unaffected.**

A missing entry returns `ErrorKind::NotConnected`, matching the Kotlin agent's `requireConnected`;
`UnknownDevice` stays reserved for a handle identifying no device at all. `stop_observe` treats an
already-disconnected device as a no-op rather than an error, because the physical subscription died
with the link and erroring there would abort the connection-teardown loop partway through the other
observations it still has to stop.

**Verification.** `cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings`, and
`cargo test --locked` (**113 passed, 0 failed**) all clean, plus the case 5 run above on real
hardware. No headless regression test was added: `BtleplugBackend` needs a live `Adapter` and
`Peripheral` is not constructible without hardware, the same documented seam limitation that makes
`SHUTDOWN-01` an honest proxy on the Rust side. The hardware run is the regression evidence.

### Finding 2 — the `BLUEZ_JSON` identifier stub, observed for the first time (harness fix)

The first scan crashed the client with `RemoteIdentifierUnavailableException` on
`hci0/dev_59_D0_C4_D0_23_92`, plus a Rust panic inside Kable's btleplug FFI
(`peripheral_id.rs`, serde `expected value` — it wants a JSON peripheral id, not a bluez object path).

This is **documented, intended behaviour, not a defect**: `agent_identifier_format()` reports
`BLUEZ_JSON` on Linux, `needs_rewrite` returns `false` for a `BLUEZ_JSON` *client*, and both
`RemoteIdentifier.kt` and `translate.rs` describe `BLUEZ_JSON` as "the still-stubbed" synthesiser
whose Linux-host-JVM client "falls back to `.handle`". The exception message says the same thing.
The bug was in the harness: `ScanMain.kt` keyed its dedup set on `adv.identifier` (a *local-platform*
convenience) instead of `adv.handle` (the portable identity every other runner and every op already
uses). Fixed; it was the only `.identifier` use in `e2e-runner`.

Worth stating plainly because it is easy to misread as a product bug: on a Linux host, a JVM client
cannot build a Kable `Identifier` from a Linux agent's handle **even though both are Linux**, and
that is by current design.

### Finding 3 — both btleplug ATT-error gates XPASS on BlueZ; they are CoreBluetooth-specific

`Main.kt` gates two steps as expected-to-fail on any btleplug-backed agent, citing Rig A hardware
evidence:

- `BTLEPLUG_ATT_ERROR_GAP` — a write-with-response answered by an ATT error never completes, so the
  agent reports `TIMEOUT` instead of `WRITE_FAILED`.
- `BTLEPLUG_WRITE_POISONING` — after one such error, write completions stop for the rest of the
  connection; only reconnecting recovers.

**Both XPASSed here, reproducibly (two runs).** `WRITE_FAILED` was delivered correctly and the next
write succeeded without reconnecting. So neither gap is a property of *btleplug*; both are properties
of **btleplug on CoreBluetooth**, which is the only backend Rig A could reach. The gates are stated
per-backend ("btleplug does not…") and are now known to be too broad. Left in place rather than
narrowed in this session — changing a gate is a deliberate act that wants its own commit and a
re-read of the Rig A evidence, and the runner already prints `NOTE: an XPASS means a backend gate is
stale`. Recorded as a P3 evidence-integrity item.

### Finding 4 — Rig D still does **not** exercise identifier rewriting, and here is what would

Item 2 folds "genuine identifier rewriting (Rig A case 5)" into Rig D on the grounds that
`BLUEZ_JSON` is Linux's native format. That is necessary but not sufficient, and this run shows why:
rewriting is driven by the **client's** declared format, and `needs_rewrite` returns `false` for both
`STRING` (Android) and `BLUEZ_JSON`. A Linux-host JVM client against a Linux agent is
`BLUEZ_JSON`→`BLUEZ_JSON` — an identity pass-through, exactly what we observed.

To actually exercise `HandleTranslator`/`synthesize`, point a client that declares **`UUID`** at this
Linux agent: an **iOS client**, or a **macOS-host JVM client**. `needs_rewrite(UUID, BLUEZ_JSON)` is
then true and the agent must synthesise UUID-shaped handles that still reverse-map to
`hci0/dev_…`. That run is cheap once a Mac or iPhone is pointed at this laptop's agent, and it is the
only known way to close Rig A case 5's rewrite half.

## What this run does not prove

The honest boundary of the option-1 relaxation. None of these is a defect; each is uncovered ground.

1. **arm64 / Raspberry Pi — entirely uncovered.** No arm64 host was run. Docker Desktop on macOS
   cannot substitute (no host Bluetooth in its Linux VM).
2. **AppArmor — uncovered.** Ubuntu is named in `rust-agent-container.md` *because of* AppArmor. This
   host is Fedora-family.
3. **SELinux enforcing — uncovered, and not for the reason the plan expected.** Item 2 predicted
   SELinux would block the D-Bus socket mount without `:z`/`:Z`. It did not — because SELinux is
   **Disabled** on this install, not because the mount is SELinux-clean. A host with SELinux
   enforcing is still unvalidated, and the predicted friction should stay in the runbook.
4. **Podman and rootless — uncovered.** Item 2 anticipated Podman-default friction; this host had
   Docker, so the run used rootful Docker via the `docker` group. The rootless-Podman D-Bus socket
   problem the item describes is untested and remains a real expectation for a Fedora operator.
5. **The published GHCR image and its multi-arch manifest — untested.** No `v*` tag exists, so the
   image was built locally. C5's "public digest matches recorded release evidence" is open.
6. **Finding 1's fix is unverified on macOS.** It changes how `agent-rs` resolves peripherals on
   *every* platform, and Rig A's `agent-rs` evidence predates it. The Kotlin agent has always worked
   this way and the change is strictly narrower than the reverted cache, so the risk is low — but it
   should be confirmed before tag. The procedure is
   [below](#the-outstanding-macos-re-check-finding-1); it is short and needs no phone prompts.
7. **Case 4's manifest half and case 2's tighter-D-Bus-policy half**, as noted inline above.

## The outstanding macOS re-check (finding 1)

Run on the Rig A Mac, with `RBTestPeripheral` advertising in range **of the Mac**. This confirms the
one path finding 1 broke — connect → discover → read through the now-cached peripheral handle — on
the CoreBluetooth backend that Rig A validated before the fix existed.

Use **`peripheralStateRun`, not `jvmRun`**: the full 14-step runner pauses for phone taps, and none
of those steps bear on this question.

```sh
# in the repo, on the branch carrying the fix
cargo build --release

# terminal 1 — 127.0.0.1 is the binary's default bind, so this needs no bind flag
REMOTE_BLE_TOKEN=secret ./target/release/agent-rs --port 8080

# terminal 2
./gradlew :e2e-runner:peripheralStateRun \
  --args "ws://localhost:8080/agent secret RBTestPeripheral 20 5"
```

Two things that will otherwise cost a run each:

- **The port is `--port`, not positional.** `agent-rs 8080` does not parse.
- **Pass the token as `args[1]`,** as above. That runner takes
  `[ws-url] [token] [name] [observe-window-s] [probe-interval-s]` positionally, so the window and
  interval are unreachable without supplying the first two, and an empty-string placeholder is
  shell-dependent rather than reliably blank.

On macOS the *terminal application itself* needs Bluetooth permission (System Settings → Privacy &
Security → Bluetooth). Denied, the scan finds nothing and reports no error — the same false-empty
shape Rig B case 6 documents for a radio that is off.

**Pass looks like this**, and the `read OK` lines are the whole check:

```
state -> Connected
holding for 8s to confirm the link is stable... stable.
link probe: read OK — the radio link is STILL UP
```

**Expected, and not a failure:** `FAILED: client never reached State.Disconnected within 20s` and a
non-zero exit. That runner is Rig A case 2's unsolicited-drop test; no drop is being triggered here,
so the link *staying up* is the positive result. Read the probe lines, not the exit code.

### RUN 2026-08-04 — **PASS**, and the recipe above is wrong in two ways

Host: the Rig A Mac (macOS 26.5.2, Apple silicon), `agent-rs` v0.10.0 built from `d2d4918`, the
`RBTestPeripheral` Android app in range. **Finding 1's fix is verified on CoreBluetooth**: connect,
discover and read all succeed through the cached peripheral handle.

```
• Connect + discover services ................. PASS — 5 services
• Locate profile characteristics .............. PASS
• Read the readable characteristic (baseline) . PASS
```

**Correction 1 — `cargo build --release` plus the bare binary cannot work on macOS.** The recipe
above launches `./target/release/agent-rs` directly; that aborts with `SIGABRT` the instant it
touches CoreBluetooth. macOS TCC kills any process without an `NSBluetoothAlwaysUsageDescription` in
its main bundle, and the permission is only honoured for a bundle launched through LaunchServices —
the crash report names `__TCC_CRASHING_DUE_TO_PRIVACY_VIOLATION__`. Use
[`agent-rs/run-agent-rs.sh`](../agent-rs/run-agent-rs.sh), which exists precisely for this: it
assembles and ad-hoc-signs `RemoteBleAgentRs.app` around the binary and starts it with `open`.

```sh
REMOTE_BLE_TOKEN=secret RUST_LOG="agent_rs=debug,info" ./agent-rs/run-agent-rs.sh 8080
```

**Correction 2 — `peripheralStateRun` is the wrong instrument for this check, and it reports a
false negative.** Its link probe reads
`services.flatMap { characteristics }.firstOrNull { it.properties.read }` — the first readable
characteristic across *all five* discovered services, not the profile's readable one. On this
peripheral that lands on a pairing-gated characteristic, so the ATT transaction never completes and
the agent correctly reports `TIMEOUT` at `GATT_OP_TIMEOUT`:

```
WARN ATT transaction did not complete; reporting TIMEOUT op="read" GATT_OP_TIMEOUT=10s
```

That reproduced on both attempts and looks exactly like finding 1 failing. It is not: the *same
agent, same link* reads the documented readable characteristic without error under
`:e2e-runner:jvmRun`. `Main.kt` already knows to avoid this — it notes that reading the
encryption-required characteristic "triggers OS pairing (needs on-device user interaction), so this
headless runner only confirms it's exposed, never reads it" — and `PeripheralStateMain` never got
that rule. **Use `:e2e-runner:jvmRun` for finding 1**; it reads the characteristic the profile
actually names. Left open as a harness defect: the probe should select the profile's `READABLE`
UUID rather than the first readable characteristic it meets.

An operator who declines pairing prompts (the normal case for a headless run) will therefore always
see this timeout from `peripheralStateRun`, and **no pairing approval is needed** for the check
itself.

## Acceptance criteria, as met on this host

Against [`rust-agent-container.md`](proposals/rust-agent-container.md) §10:

| Criterion | Status |
|---|---|
| One documented `docker run` starts the authenticated real-radio agent | **Met** (case 2) |
| Same versioned binary behaviour as direct `agent-rs` | **Met** (case 5's full E2E) |
| No `--privileged` / host networking / HCI passthrough required | **Met**, verified via `docker inspect` |
| Listener fails closed without credentials | **Met** (case 3) |
| `SIGTERM` executes structured shutdown | **Met** (case 6) |
| amd64/arm64 images, digest, SBOM, revision published together | **Not met** — no tag, nothing published |
| Ubuntu amd64 **and** Pi arm64 hardware evidence | **Not met** — relaxed under option 1 to one amd64 Linux host |
