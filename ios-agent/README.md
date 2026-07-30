# ios-agent

The iOS launcher for [`:agent`](../agent/build.gradle.kts) — **not** a second implementation.
`:agent` is a Compose Multiplatform module (`jvm()` + `androidTarget()` +
`iosArm64/iosSimulatorArm64`): its `commonMain`/`mobileMain` hold the BLE-central + WebSocket
server (`AgentRunner`, `EngineBleBackend`, `AgentWebSocketServer`) and the whole status UI
(`AgentApp`), shared verbatim with [`:android-agent`](../android-agent). This directory only
contains the three files every Compose Multiplatform iOS app needs — an `App`, a `ContentView`,
and a `ComposeView: UIViewControllerRepresentable` hosting the shared framework's
`IosAgentEntry()` — with no business logic. The one piece of glue: `ComposeView`'s `Coordinator`
disposes the `IosAgentSession` from its `deinit`, so the runner and its observing scope are torn
down when the view goes away rather than leaked.

> ⚠️ **Not built/verified in this repo's CI environment.** Unlike the Android APK, an iOS app can
> only be built on a Mac with **Xcode**. `:agent`'s klibs compile without it
> (`./gradlew :agent:compileKotlinIosSimulatorArm64`); the framework build, `xcodegen generate`,
> and the actual app run need Xcode — and a **physical iPhone**, since the Simulator has no real
> Bluetooth radio to drive.

## Build & run

Requires **Xcode** and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
# 1. From the repo root, with Xcode selected, build the framework for native consumers:
sudo xcode-select -s /Applications/Xcode.app
./gradlew :agent:assembleRemoteBleAgentReleaseXCFramework -PiosFramework
#   → produces agent/build/XCFrameworks/release/RemoteBleAgent.xcframework

# 2. Generate the Xcode project and open it:
cd ios-agent
xcodegen generate
open RemoteBleAgent.xcodeproj

# 3. Run on a physical iPhone (see above — the Simulator can't scan/connect real BLE hardware).
#    Tap Start; a laptop on the same network can then point a client (or
#    `:e2e-runner:scanRun`) at ws://<iphone-ip>:8080/agent.
```

If you prefer not to use XcodeGen, create an iOS App target by hand, add the files under
`Sources/`, set `Info.plist`, and drag in `RemoteBleAgent.xcframework` (Embed & Sign).

## The screen-lock caveat

Keep the app foregrounded. `IosAgentSession` disables the idle timer while the agent is running so
the screen can't auto-lock, and `AgentApp` shows a matching on-screen reminder.

**The caveat is narrower than it used to read here.** Measured on hardware (Rig B case 3 —
[`docs/pr8-rig-b-evidence.md`](../docs/pr8-rig-b-evidence.md)), where this section previously
asserted that new inbound connections "cannot be accepted" while backgrounded:

| Backgrounded 91 s with… | New inbound WebSocket connections |
|---|---|
| an active BLE link | **all accepted** (38/38), 92/92 GATT reads served |
| no BLE link | **hang within ~8 s**, until foregrounded again |

`UIBackgroundModes: bluetooth-central` keeps the *process* scheduled while it holds a
CoreBluetooth connection, and a scheduled process keeps running its Ktor accept loop. So an agent
with a client mid-session stays fully reachable; an idle one stops answering within seconds.

Do not design around this. It is a side effect of a background mode declared for the radio, and it
disappears the moment the last link closes. Foregrounded and unlocked remains the only supported way
to run it.

### Discovery while backgrounded (observed behaviour, not a supported mode)

**Decided 2026-07-30: the foreground is the only supported mode for this agent.** What follows is
recorded because it explains what an incidentally-backgrounded agent does — a client switching away
mid-session — not as a configuration to build on. Nothing below is contract, and no part of the
agent's design accommodates it.

iOS ignores a `nil`-serviceUUIDs scan entirely while an app is backgrounded, and `nil` is what Kable's
Apple scanner passes when a client sends no filter. This used to be recorded here as a prediction;
it is now **measured** (2026-07-29, four runs with one variable changed, agent holding a BLE link
throughout so the process stayed scheduled):

| Scan through the iOS agent | Foregrounded | Backgrounded |
|---|---|---|
| unfiltered (`nil` serviceUUIDs) | **38 devices** | **0 devices** |
| filtered on one service UUID | 1 (the test peripheral) | **1 (unaffected)** |

The loss is total rather than degraded, and it is specific to discovery: across the same window the
backgrounded agent served continuous GATT reads on its existing link and accepted every new inbound
WebSocket connection. So a backgrounded agent can serve a session and simultaneously be unable to
see anything new — unless the client scans **by service UUID**, which works normally.

A client that scans by service UUID is unaffected — but **do not read that as the remedy**. It was
written here as one before the foreground-only decision, and offering it invites apps to depend on a
mode this project does not support; it also makes discovery host-dependent, which is exactly what
[the scan-concurrency work](../docs/proposals/scan-concurrency-modes.md) exists to remove. The
supported answer to "my scans stopped" is that the agent must be foregrounded.

Verify with `:e2e-runner:scanRun`, whose third argument sends a service filter:

```sh
./gradlew :e2e-runner:scanRun --args "ws://<iphone-ip>:8080/agent 20"                                        # unfiltered
./gradlew :e2e-runner:scanRun --args "ws://<iphone-ip>:8080/agent 20 a1b2c3d4-0000-4000-8000-000000000001"   # filtered
```
