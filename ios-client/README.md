# ios-client

The iOS launcher for [`:client-ui`](../client-ui/build.gradle.kts) — **not** a second
implementation. `:client-ui` is a Compose Multiplatform library (`androidLibrary` +
`iosX64/iosArm64/iosSimulatorArm64`): its `commonMain` holds the whole UI (`RemoteBleApp` —
`ScanScreen`/`DeviceScreen`) and the scan/connect/GATT orchestration (`RemoteBleController`) on top
of [`:client-sdk`](../client-sdk), shared verbatim between [`:android-client`](../android-client)
and this project. This directory only contains the three files every Compose Multiplatform iOS app
needs — an `App`, a `ContentView`, and a `ComposeView: UIViewControllerRepresentable` that hosts the
shared framework's `ComposeUIViewController` — with no business logic.

> ⚠️ **Not built/verified in this repo's CI environment.** Unlike the Android APK, an iOS app can
> only be built on a Mac with **Xcode** (this tree's `xcode-select` points at the Command Line
> Tools). `:client-ui`'s klibs compile here (`./gradlew :client-ui:compileKotlinIosSimulatorArm64`);
> the framework build, `xcodegen generate`, and the actual app run need Xcode.

## Build & run

Requires **Xcode** and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
# 1. From the repo root, with Xcode selected, build the framework for native consumers:
sudo xcode-select -s /Applications/Xcode.app
./gradlew :client-ui:assembleRemoteBleClientReleaseXCFramework -PiosFramework
#   → produces client-ui/build/XCFrameworks/release/RemoteBleClient.xcframework

# 2. Generate the Xcode project and open it:
cd ios-client
xcodegen generate
open RemoteBleClient.xcodeproj

# 3. Run — on the Simulator or a device, either works (this app has no local radio, it talks to
#    the agent over WebSocket). Update the "AGENT ENDPOINT" field to point at your agent.
```

If you prefer not to use XcodeGen, create an iOS App target by hand, add the files under
`Sources/`, set `Info.plist`, and drag in `RemoteBleClient.xcframework` (Embed & Sign).

## Why there's no Swift networking/UI code here

A more conventional approach would consume a shared Kotlin SDK through a Swift-facing callback
facade and reimplement the UI in SwiftUI on each platform. This app takes a different, more direct
route: `:client-ui` itself targets iOS via Compose Multiplatform, so the *same* Kotlin UI and
controller run on both platforms — nothing to keep in sync by hand.

## Agent URL differs from the Android emulator default

`:android-client`'s default agent URL is `ws://10.0.2.2:8080/agent` — `10.0.2.2` is the Android
emulator's special alias for the host loopback, which doesn't exist on iOS. On this app:

- **iOS Simulator**, with the agent running on the same Mac: `ws://127.0.0.1:8080/agent` (or
  `ws://localhost:8080/agent`) works directly — the Simulator shares the Mac's network namespace.
- **A physical iPhone**: use the Mac's LAN IP (e.g. `ws://192.168.1.42:8080/agent`) — edit the
  "AGENT ENDPOINT" field in the app (`ScanScreen`) at runtime, no rebuild needed.

`Info.plist` sets `NSAllowsArbitraryLoads` so the plain `ws://` connection isn't blocked by ATS
regardless of which host/IP you point it at (mirrors `android-client`'s
`network_security_config.xml`, which scopes cleartext to `10.0.2.2`/`localhost`/`127.0.0.1` — iOS
ATS can't be scoped to an IP only known at runtime, hence the broader exception here). Dev/test app
only; don't carry `NSAllowsArbitraryLoads` into a shipping build.
