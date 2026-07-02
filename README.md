# Remote BLE Transport

[![Build](https://github.com/Yahia-Mohammad/remote-ble/actions/workflows/build.yml/badge.svg)](https://github.com/Yahia-Mohammad/remote-ble/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A "remote mode" for a Kotlin Multiplatform BLE stack: client app code written against
[Kable](https://github.com/JuulLabs/kable)'s `Peripheral` runs **unchanged** whether the
peripheral is physically local or driven by a remote **agent** over an IP link
(WebSocket). Not affiliated with JUUL Labs or the Kable project.

## How it works

In **remote** mode, `RemotePeripheral`/`RemoteScanner` implement Kable's own `Peripheral`/
`Scanner` interfaces, but forward every call over an IP link to an **agent** process near the
physical device, which drives the real radio and streams results/events back:

```
   ┌──────────────────────────┐                       ┌───────────────────────────┐
   │      Client process       │     IP link           │       Agent process       │
   │  (phone / laptop / CI)    │   (WebSocket/CBOR)    │   (near the BLE device)   │
   │                           │                       │                           │
   │  app code                 │                       │                           │
   │    │ uses                 │                       │                           │
   │    ▼                      │   Command  ───────▶   │   BleAgent ──▶ BleBackend │
   │  Kable Peripheral         │                       │                  │        │
   │   = RemotePeripheral      │   ◀───────  Reply     │                  ▼        │
   │    │                      │   ◀───────  Event     │            real radio     │
   │    ▼                      │                       │          (CoreBluetooth,  │
   │  AgentSession             │                       │           Android BLE…)   │
   │    │                      │                       │                  │        │
   │    ▼                      │                       │                  ▼        │
   │  AgentTransport ──────────┼───────────────────────┼──▶ AgentWebSocketServer   │
   └──────────────────────────┘                       └────────────────┬──────────┘
                                                                  physical BLE
                                                                        │
                                                                   ┌────▼────┐
                                                                   │ device  │
                                                                   └─────────┘
```

In **local** mode it's the box on the left minus the IP link — ordinary Kable, talking to the
radio on the same device. Switching modes is a factory choice (`peripheralFor(mode, …)`), not an
app-code change. See [docs/README.md](docs/README.md#architecture) for the full layered
breakdown inside the client SDK.

📖 **Docs:** new to this? Start with the [**getting-started tutorial**](docs/getting-started.md).
Full implementation reference (APIs, internals, rationale) in [`docs/`](docs/README.md) —
architecture, [protocol](docs/protocol.md), [client SDK](docs/client-sdk.md),
[agent](docs/agent.md), [end-to-end flows + sequence diagrams](docs/flows.md),
[design rationale](docs/design-decisions.md), [build & testing](docs/build-and-testing.md).

## Modules

| Module | Role | Deps |
|---|---|---|
| `:protocol` | The wire contract (`Frame`/`Op`/`OpResult`/`AgentEvent`) + CBOR/JSON codec | kotlinx-serialization only — **no BLE/network**. Targets: JVM + Android + iOS |
| `:client-sdk` | Session, transport, `RemotePeripheral`/`RemoteScanner` | `:protocol`, coroutines, Kable. Targets: JVM (tests) + Android + iOS |
| `:agent` | Remote Bluetooth agent (JVM) + live status dashboard. Run via `agent/run-agent.sh` | `:protocol`, coroutines, Ktor server, Kable (JVM target) |
| `agent-rs` | Native cross-platform Bluetooth agent (Rust 2024). Run via the self-bootstrapping `run-agent-rs.sh` | tokio, tokio-tungstenite, btleplug, serde/ciborium. Targets: macOS + Linux |
| `:e2e-runner` | Live E2E runner (`jvmRun`) + radio-less scan smoke test (`scanRun`) | `:client-sdk` (JVM). See [README](e2e-runner/README.md) |
| `:client-ui` | The central demo's UI (`RemoteBleApp`: `ScanScreen`/`DeviceScreen`) + orchestration (`RemoteBleController`) — Compose Multiplatform, shared by `:android-client` and `ios-client/` | `:client-sdk`. Targets: Android (library) + iOS |
| `:android-client` | Thin Android app shell around `:client-ui`: scans through the host agent over `ws://10.0.2.2:8080/agent` (no radio, `INTERNET` only) | `:client-ui` |
| `ios-client/` | Thin, logic-free XcodeGen launcher shell for `:client-ui`'s iOS target (standalone Xcode project, **not** a Gradle module) | `:client-ui`'s exported `RemoteBleClient.xcframework`. See [README](ios-client/README.md) |

> **You'll need a test peripheral to try the full connect/read/write/observe path.** Any GATT
> peripheral works for exercising `:android-client` — e.g. a Heart Rate / Battery Service
> advertiser for the demo UI, or your own test app exposing custom UUIDs for exercising the raw
> op-set (reads/writes/notify/pairing/forced errors). See
> [`docs/phase7-bringup.md`](docs/phase7-bringup.md) for a scripted live bring-up procedure against
> a real radio.

## Pinned versions

| | Version |
|---|---|
| Kotlin | 2.4.0 |
| kotlinx-coroutines | 1.11.0 |
| kotlinx-serialization (+cbor) | 1.9.0 |
| Gradle | 9.5.1 (wrapper) |
| Android Gradle Plugin | 9.2.1 (compileSdk 37, minSdk 24) |
| JDK toolchain | 17 |
| Kable | `com.juul.kable:kable-core`, built from source — powers **both** the client SDK and the JVM agent's radio engine |

Wire format: **CBOR** over the transport; a JSON codec is available for debugging.

## Building / testing

```sh
./gradlew :protocol:jvmTest          # Phase 1 round-trip suite
./gradlew build                      # all modules + targets (JVM/Android/iOS klibs)
```

`build` compiles every target but runs the unit suite on the **JVM** only. iOS test
*binaries* are skipped because linking them needs a full Xcode simulator toolchain
(`xcode-select` pointed at `Xcode.app`, not the Command Line Tools) — the library klibs
still compile, so the Android/iOS targets are verified. A bumped `org.gradle.jvmargs`
(in `gradle.properties`) gives the daemon the Metaspace the multiplatform + AGP + Native
build needs. Android builds resolve the SDK from `local.properties` (`sdk.dir`).

```sh
# Needs a Mac with Xcode — builds the framework ios-client/ embeds:
sudo xcode-select -s /Applications/Xcode.app
./gradlew :client-ui:assembleRemoteBleClientReleaseXCFramework -PiosFramework
```

## Building Kable from source (mavenLocal)

This project needs [Kable](https://github.com/JuulLabs/kable) built from source and
published to `mavenLocal()` — **not** because it's forked or modified (it's plain
upstream `JuulLabs/kable`, unchanged), but because Maven Central's published
`com.juul.kable:kable-core` (currently `0.37.1`) doesn't yet include the JVM/desktop
`btleplug` backend this project's `:agent` runs on (merged upstream in
[kable#901](https://github.com/JuulLabs/kable/pull/901), not yet released). Once Kable
ships a release with that backend, this step — and the `mavenLocal()` entry in
[`settings.gradle.kts`](settings.gradle.kts) — can go away in favor of Maven Central.

```sh
# Clone plain upstream Kable next to this repo.
git clone https://github.com/JuulLabs/kable.git ../kable
cd ../kable

# Needs Rust (rustup) for the kable-btleplug-ffi JVM backend;
# -PRELEASE_SIGNING_ENABLED=false skips signing for local publish.
# Publishes as version "unspecified" (an untagged checkout derives no version) —
# this project's Kable dependency is pinned to that same "unspecified" version.
PATH="$HOME/.cargo/bin:$PATH" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :kable-btleplug-ffi:publishToMavenLocal \
  :kable-core:publishKotlinMultiplatformPublicationToMavenLocal \
  :kable-core:publishJvmPublicationToMavenLocal \
  -PRELEASE_SIGNING_ENABLED=false

# Kable Android + iOS variants — needed for :client-sdk / :client-ui / :android-client /
# ios-client. Apple klibs require a macOS host; no Rust FFI (Apple uses CoreBluetooth,
# Android the platform BLE), but ANDROID_HOME is still needed to configure the androidTarget.
PATH="$HOME/.cargo/bin:$PATH" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :kable-core:publishAndroidPublicationToMavenLocal \
  :kable-core:publishIosArm64PublicationToMavenLocal \
  :kable-core:publishIosSimulatorArm64PublicationToMavenLocal \
  :kable-core:publishIosX64PublicationToMavenLocal \
  -PRELEASE_SIGNING_ENABLED=false
```

`mavenLocal()` is listed first in `settings.gradle.kts` so the local build wins. Kable is
Apache 2.0 licensed, same as this project.

## Running the agent (live, macOS)

```sh
agent/run-agent.sh 8080                       # ws://0.0.0.0:8080/agent, real CoreBluetooth

# Require a bearer token (clients must pass the same value as WebSocketAgentTransport.authToken):
REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080
```

**Use the script, not `./gradlew :agent:jvmRun`.** A bare JVM process is killed with
`SIGABRT` the instant it touches CoreBluetooth: macOS TCC requires the running
process's main bundle to declare `NSBluetoothAlwaysUsageDescription`, and the request
is only honored for apps launched via LaunchServices. The script compiles a tiny JNI
launcher (`agent/macos-launcher/launcher.c` + a Swift menu bar UI in `MenuBar.swift`),
wraps it in a signed `RemoteBleAgent.app` carrying that key, and starts it with `open`,
then streams the agent log (Ctrl-C stops the agent). On first run macOS prompts once
for Bluetooth permission. A menu bar status item (🟢/🟡) shows at a glance whether the
agent is running, with a dropdown of recent activity and a link to the dashboard.

Point a client `WebSocketAgentTransport` at `ws://<host>:8080/agent`. When
`REMOTE_BLE_TOKEN` is set, a client without the matching token is rejected at the
handshake (`401`).

### Running the native Rust agent (`agent-rs`)

For lightweight, cross-platform deployments on macOS or Linux.

Use the wrapper script. It's **self-bootstrapping**: on a bare checkout
with nothing preinstalled, it installs the Rust toolchain via `rustup` if `cargo` isn't found,
and on Linux additionally installs the OS build prerequisites (a C toolchain, `pkg-config`, and
the D-Bus dev headers `btleplug`'s BlueZ backend needs — via `apt`/`dnf`/`yum`/`pacman`/`zypper`/
`apk`, whichever is present), before building and running:

```sh
agent-rs/run-agent-rs.sh 8080
REMOTE_BLE_TOKEN=secret agent-rs/run-agent-rs.sh 8080
```

On **macOS** specifically, the script also does one more thing a plain `cargo run` can't: a bare
`cargo run --bin agent-rs` aborts with **SIGABRT**, because macOS TCC kills any process that
touches CoreBluetooth without an `NSBluetoothAlwaysUsageDescription` in its main bundle, honored
only when launched via LaunchServices (same root cause as the JVM agent's `:agent:jvmRun`). So on
macOS the script wraps the compiled binary in a signed `RemoteBleAgentRs.app` carrying that key
and launches it with `open`; on Linux there's no such dance — it just builds and runs the binary
directly, talking to BlueZ over D-Bus.

The first launch on macOS shows a one-time "RemoteBleAgentRs would like to use Bluetooth"
prompt — approve it (re-run if the first scan comes back empty). The script streams
the agent log; Ctrl-C stops the app.

### Status dashboard

The agent serves a live, mobile-friendly status page at `http://<host>:8080/` (same
port as the WebSocket endpoint). It shows connected RemoteBLE clients, connected
hardware, and a rolling activity log, polling `GET /api/state` (JSON) once a second.
It's read-only and never touches the radio. See `AgentMonitor` / `Dashboard.kt`.

### Scan-only smoke test (no hardware peripheral needed)

```sh
./gradlew :e2e-runner:scanRun --args "ws://localhost:8080/agent 15"
```

Lists every BLE advertisement the agent's radio sees for 15s, then exits. The client
has no radio of its own — it only sends scan ops over WebSocket — so it doubles as the
proof that app code can scan through a remote host (e.g. an emulator via the host Mac).
The full op-set live runner is `:e2e-runner:jvmRun` (needs a phone peripheral).

## Status

Proven end-to-end, on real radios and in tests: app logic written purely against Kable's
`Peripheral`/`Scanner` API runs unchanged against a `RemotePeripheral` talking to an agent
over WebSocket — connect, discover, read, write, observe (notify), scan, and reconnect.

Highlights:
- **Protocol** — a versioned, capability-negotiated wire contract (CBOR, JSON for
  debugging) covering the full GATT surface plus descriptors, pairing, connection
  priority, connection slots, and batched scan results. See
  [`docs/protocol.md`](docs/protocol.md).
- **Resilience** — reconcile-on-reconnect (auto-replays connections/subscriptions/scans
  after a transport reconnect), per-op-class timeouts, negotiated MTU surfaced through
  Kable's API, bearer-token auth at the handshake.
- **Two agents, one wire contract** — a JVM agent (Kable's `btleplug` backend) and a
  native Rust agent (`agent-rs`, direct `btleplug`), cross-language interop tested
  against the same CBOR contract.
- **Reference apps** — an Android client (`:android-client` + shared `:client-ui`) and an
  iOS launcher (`ios-client/`), both driving the same Compose Multiplatform UI over a
  remote agent, no local radio required.

> **Deployment targets:** macOS and Linux (including Raspberry Pi) — not iOS/Android radios. The
> agent isn't bare-metal/microcontroller firmware: `btleplug` needs a real OS Bluetooth stack
> underneath it (CoreBluetooth on macOS, BlueZ/D-Bus on Linux), so it targets a Pi-class Linux
> host, not an ESP32 or similar. The `pairing`/`conn.priority` capabilities are engine-gated and
> ship advertised only on a backend that supports them; Kable's `btleplug` backend does not, so
> the reference agent advertises neither.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). This project is independent and not
affiliated with or endorsed by JUUL Labs or the [Kable](https://github.com/JuulLabs/kable)
project; Kable itself is also Apache 2.0 licensed.
