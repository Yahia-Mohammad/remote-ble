# Migrate to RemoteBLE 0.13.0

0.13.0 adds a macOS client, lowers the Android compileSdk a consumer needs, and makes Android's
cleartext refusal a named failure. Update the dependency version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.13.0")
}
```

**There is no required source change** and no wire-protocol change. Coming from a release older
than 0.12.0? Read [migrate-to-0.12.0.md](migrate-to-0.12.0.md) first, and the guides it links.

## Android: compileSdk 36 is enough again

0.12.0's AARs declared `minCompileSdk=37`, so a consumer on compileSdk 36 failed
`checkDebugAarMetadata`. 0.13.0 declares 36. It cannot go lower: Kable depends on `androidx.core`
1.18, whose own AAR requires 36, so an older compileSdk would fail on that dependency instead.

## Android: `ws://` agents and the cleartext policy

From targetSdk 28, Android forbids cleartext traffic unless the app opts in, and
`defaultWebSocketHttpClient()` runs on OkHttp, which enforces that. An app with no network security
config could never reach a `ws://` agent through the default client. What changes is how that
failure looks:

| | 0.12.0 | 0.13.0 |
|---|---|---|
| `connect()` with reconnect enabled | returns, then retries in the background forever | throws `CleartextTrafficNotPermittedException` |
| `transport.state` | cycles `CONNECTING` / `DISCONNECTED` | `GAVE_UP` |
| `ReconnectPolicy.onGaveUp` | only after `maxAttempts`, if set | fires once, immediately |
| Log | a WARN per retry naming nothing specific | one ERROR naming the fixes |

To connect, pick one:

- **Use the CIO client** — plain sockets, which the policy does not govern:
  ```kotlin
  val transport = WebSocketAgentTransport(url, scope, cioWebSocketHttpClient())
  ```
  With the Koin module, override the binding:
  `modules(remoteBleClientModule(config), module { single<HttpClient> { cioWebSocketHttpClient() } })`.
- **Permit cleartext for the agent's host** in a network security config, as the repo's
  `android-client` does for the emulator's `10.0.2.2`.
- **Reach the agent over `wss://`** through a [TLS-terminating proxy](tls-proxy-recipe.md). Keep the
  default client for this: CIO's TLS stack supports TLS 1.2 only.

Code that catches connect failures generically keeps working; the new exception extends
`Exception`.

## macOS

Add a `macosArm64()` target to a KMP app and the same coordinate resolves the `client-sdk-macosarm64`
klib. Remote use needs no Bluetooth permission. `BleMode.LOCAL` drives the Mac's own radio through
CoreBluetooth, and macOS grants that only to a signed app bundle declaring
`NSBluetoothAlwaysUsageDescription`.
