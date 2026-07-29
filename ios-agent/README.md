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

Do not design around this. It is a side effect of a background mode declared for the radio, it
disappears the moment the last link closes, and iOS additionally ignores a `nil`-serviceUUIDs scan
while backgrounded — which is what Kable's Apple scanner passes today, so a backgrounded agent may
serve an existing link yet be unable to discover anything. Foregrounded and unlocked remains the
only supported way to run it.
