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

iOS does not support a backgrounded, listening TCP server — once the app backgrounds or the
screen locks, nothing can reach this agent anymore (existing radio *links* may linger briefly
under the `bluetooth-central` background mode, but new inbound WebSocket connections cannot be
accepted). The `IosAgentSession` disables the idle timer while the agent is running so the screen
can't auto-lock, and `AgentApp` shows an on-screen reminder to keep the app open — there is no way
around this on iOS short of the user leaving the phone plugged in and unlocked.
