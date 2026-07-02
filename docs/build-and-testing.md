# Build, Targets & Testing

[← back to index](README.md)

How the project is assembled, how the multiplatform targets and Kable fit together, the
build-environment quirks, and the test suite. CI runs the same commands in
[`.github/workflows/build.yml`](../.github/workflows/build.yml) (JVM tests + Android/iOS klib
compile checks) and [`.github/workflows/rust.yml`](../.github/workflows/rust.yml) (`agent-rs`).

## Modules & targets

| Module | Targets | Plugin set |
|---|---|---|
| [`:protocol`](../protocol/build.gradle.kts) | JVM, Android, iOS (arm64/x64/sim) | `kotlin.multiplatform`, `kotlin.serialization`, `android.kotlin.multiplatform.library` |
| [`:client-sdk`](../client-sdk/build.gradle.kts) | JVM (tests), Android, iOS (arm64/x64/sim) | same |
| [`:agent`](../agent/build.gradle.kts) | JVM only | `kotlin.multiplatform`, `kotlin.serialization` |
| [`:e2e-runner`](../e2e-runner/build.gradle.kts) | JVM only | `kotlin.multiplatform` |
| [`:android-client`](../android-client/build.gradle.kts) | Android app | `com.android.application` (AGP 9 built-in Kotlin — no separate kotlin-android) |

`:protocol` is pure `commonMain`, so its Android/iOS targets add **no source** — they
just publish the klibs the client SDK consumes. `:client-sdk` keeps a `jvm()` target
**only for fast tests** (the session/transport/adapters are BLE-agnostic and the agent
is JVM-only); the shipping targets are Android + iOS. `:agent` is JVM because it runs on
a host beside the device; its radio engine is Kable's JVM (`btleplug`) backend.

Source layout for the two `expect`/`actual` pairs (`defaultWebSocketHttpClient()` — the Ktor
engine; and `deviceHandleToIdentifier()` — the platform-safe handle→`Identifier` conversion
that skips Android's MAC validation, see [client-sdk.md](client-sdk.md)):

```
client-sdk/src/
  commonMain/…/WebSocketClient.kt          expect fun defaultWebSocketHttpClient()
  jvmMain/…/WebSocketClient.jvm.kt          actual → Ktor CIO
  androidMain/…/WebSocketClient.android.kt  actual → Ktor OkHttp
  iosMain/…/WebSocketClient.ios.kt          actual → Ktor Darwin

  commonMain/…/RemoteIdentifier.kt         expect fun deviceHandleToIdentifier(value)
  jvmMain/…/RemoteIdentifier.jvm.kt         actual → value.toIdentifier() (opaque PeripheralId)
  androidMain/…/RemoteIdentifier.android.kt actual → the String as-is (no MAC check)
  iosMain/…/RemoteIdentifier.ios.kt         actual → Uuid.parse(value)
```

## Common commands

```sh
./gradlew build                      # all modules + targets compile; JVM tests run
./gradlew :protocol:jvmTest          # round-trip + Rust-interop suite (35 tests)
./gradlew :client-sdk:jvmTest        # session / transport / kable / error-path / identifier suites
(cd agent-rs && cargo test)          # native Rust agent: 20 unit + cross-language interop tests
agent/run-agent.sh 8080                                       # run the real macOS JVM agent (NOT :agent:jvmRun)
agent-rs/run-agent-rs.sh 8080                                 # run the native Rust agent on macOS (Linux/Win: cargo run --bin agent-rs)
REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080              # …with bearer auth
./gradlew :e2e-runner:scanRun --args "ws://localhost:8080/agent 15"   # radio-less scan smoke test
./gradlew :android-client:assembleDebug                      # build the emulator client APK
```

> `agent/run-agent.sh` exists because a bare `:agent:jvmRun` is killed by macOS TCC on first
> CoreBluetooth use — see [agent.md](agent.md#the-runnable-agent--main) for the full story.

`build` compiles **every** target (JVM/Android/iOS klibs) but runs the unit suite on
the **JVM only** (see [the iOS-test caveat](#the-ios-test-caveat)).

## Kable, built from source

Kable is consumed via `mavenLocal()`, built from a plain checkout of upstream
[`JuulLabs/kable`](https://github.com/JuulLabs/kable) — **not a fork**; no code is modified.
`settings.gradle.kts` lists `mavenLocal()` first so the local build wins. This is needed because
Maven Central's published `com.juul.kable:kable-core` (currently `0.37.1`) doesn't yet include the
JVM/desktop `btleplug` backend that `:agent`'s radio engine runs on (merged upstream in
[kable#901](https://github.com/JuulLabs/kable/pull/901), not yet released) — one BLE library across
the whole stack, which is why the Blue-Falcon agent engine was retired in favor of it.

| Dependency | Coordinates | Why built from source |
|---|---|---|
| Kable | `com.juul.kable:kable-core` version **`unspecified`** | an untagged checkout derives no version; consumed for `Peripheral`/`Scanner` (client) and the unreleased `btleplug` JVM backend (agent) |

The full set published to mavenLocal:

- Kable: `kable-core` + `kable-btleplug-ffi` — published for **JVM + Android
  (debug/release) + iOS (arm64/x64/sim)** + metadata.

The exact publish commands (and the toolchain quirks they need — Rust for Kable's
JVM `kable-btleplug-ffi` backend, `ANDROID_HOME` for the configured `androidTarget`,
`-PRELEASE_SIGNING_ENABLED=false`) are in the root [README](../README.md#building-kable-from-source-mavenlocal).
Apple klibs require a macOS host but **no** Rust FFI (Apple uses CoreBluetooth,
Android the platform BLE; the `btleplug` FFI is the JVM/desktop backend). Once Kable ships a
release with the JVM backend, this step and the `mavenLocal()` entry go away in favor of Maven
Central.

## Build-environment notes (the non-obvious bits)

Adding the Android + iOS targets surfaced four environment requirements, all encoded
in the build so `./gradlew build` is reproducible:

1. **Root plugin classpath.** AGP is declared `apply false` in the root
   [`build.gradle.kts`](../build.gradle.kts) so every subproject shares one AGP
   classpath — required for AGP's version inference under the plugins DSL. Without it:
   *"Can't infer current AndroidGradlePluginVersion."*
2. **AndroidX opt-in.** Kable's Android variant pulls AndroidX (`androidx.core`,
   `androidx.startup`), so [`gradle.properties`](../gradle.properties) sets
   `android.useAndroidX=true`.
3. **Daemon memory.** The multiplatform + AGP + Kotlin/Native build exhausts the
   default daemon Metaspace; `gradle.properties` bumps `org.gradle.jvmargs`
   (`-Xmx3g -XX:MaxMetaspaceSize=1g`).
4. **Android SDK location.** Resolved from `local.properties` (`sdk.dir`) — gitignored,
   machine-specific.

AGP `9.2.1`, `compileSdk 37`, `minSdk 24` — pinned to match the Kable checkout this project
builds against, so the consumed klibs line up. The `:android-client` **app** applies `com.android.application`
only: AGP 9 has built-in Kotlin, so adding `org.jetbrains.kotlin.android` is an error.
All AGP plugins are declared `apply false` in the root build so the app and the KMP
library modules share one AGP classpath.

### The iOS-test caveat

`build` compiles the iOS library klibs (verifying the targets) but **does not link or
run iOS unit-test binaries**. Linking a native test executable needs a full Xcode
simulator toolchain (`xcode-select` pointed at `Xcode.app`, not the Command Line
Tools). Both `:protocol` and `:client-sdk` therefore disable iOS test tasks:

```kotlin
tasks.matching { it.name.contains("Test") && it.name.contains("ios", ignoreCase = true) }
    .configureEach { enabled = false }
```

The shared logic is fully covered by the JVM suite (and would run on iOS once a
simulator toolchain is selected). To re-enable: `sudo xcode-select -s
/Applications/Xcode.app` and drop those two blocks.

## The test suite

**95 tests, JVM-run.** The suites below total 93; the other two are the Koin graph-verify
tests (`AgentKoinTest`, `ClientKoinTest`). The end-to-end tests stand up a real agent via the
test-only `:client-sdk → :agent` dependency.

| Module | Suite | Tests | What it proves |
|---|---|---|---|
| `:protocol` | [`ProtocolCodecTest`](../protocol/src/commonTest/kotlin/dev/warsha/ble/remoteble/protocol/ProtocolCodecTest.kt) | 27 | every wire variant round-trips through the codec (structural equality) — incl. the handshake frames and the extension ops/events |
| `:protocol` | [`RustAgentInteropTest`](../protocol/src/commonTest/kotlin/dev/warsha/ble/remoteble/protocol/RustAgentInteropTest.kt) | 8 | the **native Rust agent**'s exact CBOR output (definite-length, signed-byte arrays, `gattStatus`) decodes to the right Kotlin frames — cross-language wire compat |
| `:agent` | [`BleAgentTest`](../agent/src/commonTest/kotlin/dev/warsha/ble/remoteble/agent/BleAgentTest.kt) | 18 | op routing, slot cap + release, backend→`ErrorKind` mapping, scan/observe streaming, capability-handshake intersection, descriptor/pairing/conn-priority dispatch + `UNSUPPORTED` fallback, slot events, batched scan |
| `:agent` | [`PeripheralRegistryTest`](../agent/src/commonTest/kotlin/dev/warsha/ble/remoteble/agent/PeripheralRegistryTest.kt) | 6 | exclusive peripheral ownership: lease/resume, transport- and BLE-disconnect grace windows |
| `:agent` | [`ConnectionWatcherTest`](../agent/src/jvmTest/kotlin/dev/warsha/ble/remoteble/agent/ConnectionWatcherTest.kt) | 2 | unsolicited-drop detection via `BleBackend.isConnected`: starts the release grace on a drop, leaves a live link alone |
| `:client-sdk` | [`SessionEndToEndTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/SessionEndToEndTest.kt) | 14 | session over in-memory transport: ops resolve, observe/scan stream + tear down, timeout, drop, per-op timeouts, capability negotiation + `awaitCapabilities`/`supportsCapability` helpers, descriptor/pairing/conn-priority round-trips, batched-scan flattening |
| `:client-sdk` | [`WebSocketEndToEndTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/WebSocketEndToEndTest.kt) | 7 | full op set over a real WS, restart→reconnect, subscription replay, disconnect-not-replayed, auth accept/reject |
| `:client-sdk` | [`BleAgentOverWebSocketTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/BleAgentOverWebSocketTest.kt) | 1 | the **production** agent handler over a real WS (stub radio) |
| `:client-sdk` | [`KableAdapterTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/KableAdapterTest.kt) | 5 | app code vs Kable's `Peripheral` runs unchanged remotely; observe; scan+factory; negotiated-MTU; factory threads an injected `DispatcherProvider` into the peripheral scope |
| `:client-sdk` | [`ErrorPathTest`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/ErrorPathTest.kt) | 3 | write/read rejection surfaces + session stays usable; disconnect reflected in Kable state |
| `:client-sdk` | [`RemoteAdvertisementIdentifierTest`](../client-sdk/src/commonTest/kotlin/dev/warsha/ble/remoteble/client/RemoteAdvertisementIdentifierTest.kt) | 2 | reading a remote advertisement's `identifier` for a UUID handle doesn't throw (the Android MAC-validation crash) |

### The test doubles

The fakes are first-class — they're what made hardware-free development possible.

| Double | Stands in for | Notes |
|---|---|---|
| [`InMemoryTransport`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/InMemoryTransport.kt) | `AgentTransport` | two channel-joined endpoints in one process; `drop()` simulates an abrupt loss |
| [`FakeAgent`](../agent/src/commonMain/kotlin/dev/warsha/ble/remoteble/agent/FakeAgent.kt) | a whole agent | canned replies; periodic scan/notify; `replyDelay` to hold requests in-flight; `activeScanCount`/`activeNotifyCount` for teardown assertions |
| [`FakeBleBackend`](../agent/src/commonTest/kotlin/dev/warsha/ble/remoteble/agent/FakeBleBackend.kt) | `BleBackend` (agent tests) | deterministic; `failConnectFor`, `characteristicNotFound`, records calls |
| [`StubBleBackend`](../client-sdk/src/jvmTest/kotlin/dev/warsha/ble/remoteble/client/StubBleBackend.kt) | `BleBackend` (client E2E) | drives the **real** `BleAgent` from client tests; `failWrites`/`failReads` inject radio errors |
| `BlackholeBackend` | `AgentBackend` | never replies — exercises client request timeouts |

### Notable testing techniques

- **Virtual time.** `SessionEndToEndTest` uses `runTest`; `FakeAgent.replyDelay` and
  `withTimeoutOrNull` advance on the virtual clock, so timing assertions (e.g.
  "connect's 1s budget absorbs a 200ms agent but a 50ms read times out") are
  deterministic, not wall-clock-flaky.
- **Real-network reconnect.** `WebSocketEndToEndTest` actually stops and restarts a
  Ktor server on a free port to prove the transport's backoff reconnect and the
  session's subscription replay against a *fresh* agent that holds no state.
- **Production-path coverage.** `BleAgentOverWebSocketTest`, `KableAdapterTest`, and
  `ErrorPathTest` run through the real `BleAgent` op handler over a real WebSocket
  (only the radio is stubbed), so the wiring that ships is the wiring under test.

## The native Rust agent (`agent-rs`) tests

Run with `cd agent-rs && cargo test` — **20 tests**, no hardware. Alongside the codec
round-trips and `PeripheralRegistry` lease/slot tests, the key suite is
[`src/protocol/interop_tests.rs`](../agent-rs/src/protocol/interop_tests.rs): it decodes
**byte-for-byte CBOR captured from the Kotlin codec** and asserts the reconstructed
frames, locking the Rust agent to the Kotlin wire contract. The reverse direction —
Kotlin decoding the Rust agent's exact (definite-length) output — is covered by
`:protocol`'s `RustAgentInteropTest` above, so both ends are pinned. These guard the
subtle bits: `ByteArray` as an array of *signed* bytes (`0xFF → -1`), `gattStatus`
casing, SCREAMING_SNAKE_CASE enum strings, and `[tag, payload]` polymorphic framing.

`cargo fmt --check` and `cargo clippy --all-targets` are both clean (no warnings).
