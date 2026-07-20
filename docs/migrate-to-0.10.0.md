# Migrate to RemoteBLE 0.10.0

0.10.0 is the consolidated Maven Central release for changes accumulated since the previous Central
release. Keep the same `dev.warsha.remoteble:client-sdk` dependency, but update its version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.10.0")
}
```

`client-sdk` brings its shared `:protocol` and `:log` dependencies through published metadata. Do
not replace them with project dependencies or a composite build in a consumer application.

## Required source change: `authToken` is now a provider

The token setting on both `WebSocketAgentTransport` and `RemoteBleClientConfig` changed from a
`String?` to `suspend () -> String?`. Wrap a fixed token in a lambda:

```kotlin
// Before
authToken = "secret"

// 0.10.0
authToken = { "secret" }
```

The provider runs for every connection attempt, including reconnects. Use it to obtain a refreshed
credential when tokens rotate; return `null` (or a blank value) for an unauthenticated agent. The
SDK does not cache the returned credential.

## Platform and protocol compatibility

- The wire protocol remains **v1**. Existing Kotlin agents and clients interoperate when their
  negotiated capabilities overlap.
- The normal single-agent Kable API and local/remote factories are unchanged. Simulation is an
  agent launch option, not a client API mode.
- `iosX64` is no longer a published target. Use `iosSimulatorArm64` for Apple-silicon simulators or
  `iosArm64` for devices.

Review the [changelog](../CHANGELOG.md) for the cumulative behavior changes before upgrading a
production deployment, especially if it skipped several GitHub-only agent releases.
