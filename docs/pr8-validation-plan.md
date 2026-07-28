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

1. **Full `:e2e-runner` 14-step run**, both agents (Kotlin `:agent` **and** `agent-rs`) — scan,
   connect, discover, read (exact), write with/without response, negotiated MTU, notify (no
   miss/dup), disconnect. `phase7-bringup.md` steps 0–3.
2. **F3 unsolicited disconnect** — an unsolicited BLE-level drop mid-session; confirm both agents
   emit the drop event and the client reaches `State.Disconnected`.
   **Stimulus corrected (2026-07-28, see [pr8-rig-a-evidence.md](pr8-rig-a-evidence.md) case 2):**
   *not* "Force disconnect all" — Android's `BluetoothGattServer.cancelConnection()` releases the
   server's reference without terminating a link the central established, so the radio stays up and
   the case silently measures nothing. Kill the link for real: `adb shell cmd bluetooth_manager
   disable`, power the peripheral down, or take it out of range.
3. **Two-client authorization on real radio** — client B scans and can see client A's leased
   device but read/write/observe/configure/disconnect all fail; run against both agents.
4. **`setConnParams`** — `LOW_POWER`/`BALANCED`/`LOW_LATENCY` returns `Ok` on the Android agent;
   JVM, `agent-rs`, and iOS answer `UNSUPPORTED` (capability omitted), not an error.
5. **Reconcile-under-translation, live** — cross-platform client (translated identifiers) with a
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
6. **Write-without-response throughput + ordering** — `ThroughputMain` baseline, then
   `RemotePeripheral.writeWithoutResponseBurst` with `window > 1`; confirm a measured improvement,
   submission order preserved in the peripheral's write log, and no regression to with-response
   writes. Run against both agents (the Rust write-reservation guarantee needs on-radio
   confirmation, not just its unit test).
7. **Battery/Device-Info on a non-iOS peripheral** — confirm live values (not frozen), using the
   Android `health-peripheral` app or `nRF Connect`'s GATT server. Do not use an iPhone peripheral
   for this case (it silently shadows 0x180F/0x180A with iOS's own values — see memory
   `0-8-1-hardware-test-rig`).
8. **CONN-1** — start the client first, start the agent later; confirm the client self-heals
   without a restart once the agent comes up.

**Exit:** all 8 pass on both agents where applicable; results archived per the evidence rule above.

---

## Rig B — iOS agent lifecycle rig

**Hardware:** a Mac with Xcode, a physical iPhone (the Simulator has no real radio), one BLE
peripheral in range (reuse Rig A's).

**Setup:** [`ios-agent/README.md`](../ios-agent/README.md) — build the `RemoteBleAgent.xcframework`,
`xcodegen generate`, run on the physical iPhone.

### Test cases

1. **Start** — launch the app, tap Start, confirm the agent listens and its dashboard/`/api/state`
   is reachable from a client on the same network.
2. **Ordinary session** — scan/connect/read/notify against the Rig A peripheral through the
   iOS-hosted agent, proving the shared `AgentRunner`/`EngineBleBackend` behaves the same as the
   Mac/Android hosts already validated in Rig A.
3. **Screen-lock/background caveat** — background the app or lock the screen mid-session; confirm
   the documented behavior: existing radio links may linger briefly, but no *new* inbound
   WebSocket connections are accepted. Capture what an already-connected client observes.
4. **Stop** — tap Stop (or navigate away); confirm `IosAgentSession` is disposed
   (`Coordinator.deinit`), the radio/lease is released, and the dashboard is no longer reachable.
5. **Cancellation mid-operation** — start a scan or an active connection, then stop the agent app
   mid-flight; confirm clean teardown with no crash and no leaked native connections (the iOS
   analogue of `SHUTDOWN-01`).
6. **Failure recovery** — deny the Bluetooth permission prompt on first launch and confirm a
   graceful UI state (no crash, clear messaging); then grant it and confirm Start succeeds on
   retry.

**Exit:** all 6 pass; results archived per the evidence rule above.

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

1. **WebSocket upgrade** — client connects via `wss://<proxy-host>/agent` and completes the
   handshake through the proxy.
2. **Bearer forwarding** — an authenticated `wss://` connection succeeds; one without the bearer
   token is rejected, proving the proxy forwards the header rather than stripping it.
3. **CA trust** — the client validates the proxy's certificate against the configured CA; a
   deliberately untrusted cert (wrong CA, expired, or hostname mismatch) fails closed rather than
   silently connecting.
4. **Reconnect through the proxy** — induce a transport blip (restart the proxy or toggle network)
   and confirm the client reconnects through `wss://` and resumes its lease within grace.
5. **Live notification delivery** — subscribe to the peripheral's notify characteristic and confirm
   a sustained stream of updates arrives through the proxy without buffering stalls or drops (long-
   lived WebSocket frames are exactly what a misconfigured proxy breaks).

**Exit:** all 5 pass; record the exact proxy config (redacted of any real secrets/certs) alongside
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

### Test cases (per host)

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

**Exit:** both hosts pass all 6; capture host OS, Docker/Podman version, BlueZ version, adapter
model, image digest, exact command, and redacted logs for each — `rust-agent-container.md`'s
acceptance criteria require both reference hosts before the image is called supported.

---

## Suggested order

Rigs are independent and can run in any order or in parallel if you have the hardware for more
than one at once. If sequencing one at a time, Rig A first is recommended — it re-validates the
most code (including the recent `agent-rs` dependency bumps) and shares its peripheral with Rigs B
and C.
