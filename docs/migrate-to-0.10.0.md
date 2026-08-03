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

## Required build change for Android consumers on AGP 9

**An Android consumer on AGP 9 must put Kotlin 2.4+ on its build classpath, or the SDK will not
compile.** AGP 9's *built-in* Kotlin compiler is 2.2.0 and reads Kotlin metadata only up to 2.3.0,
while this SDK publishes 2.4.0 metadata. A stock AGP 9 module therefore fails with:

> was compiled with an incompatible version of Kotlin

Declare KGP with `apply false` in the consumer's `plugins` block. That puts 2.4.x on the build
classpath for AGP's built-in compilation without applying a second Kotlin plugin:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("com.android.library") version "9.3.0"
}
```

Do **not** reach for `org.jetbrains.kotlin.android` instead — AGP 9 removed it, and applying it is a
hard error. This affects Android consumers only; JVM and Apple consumers are unaffected, which is
why it is easy to miss until an Android build is attempted.

## Platform and protocol compatibility

- The wire protocol remains **v1**. Existing Kotlin agents and clients interoperate when their
  negotiated capabilities overlap.
- **New capabilities and a new error kind, both backward compatible.** Agents advertise exactly one
  of `scan.concurrency.multiplexed` / `.single` / `.uncontrolled`, and 0.10.0 clients offer all three
  automatically — no source change, including for sessions you construct yourself. The new
  `SCAN_UNAVAILABLE` error kind is capability-gated behind `scan.concurrency.single`, so a client
  built against an older SDK keeps receiving `AGENT_BUSY` and its decoder never meets an enum name it
  does not know. Both directions of the mixed upgrade are safe: an old client against a new agent, and
  a new client against an old agent (which reads as `LEGACY_OR_UNKNOWN`).
- The normal single-agent Kable API and local/remote factories are unchanged. Simulation is an
  agent launch option, not a client API mode.
- `iosX64` is no longer a published target. Use `iosSimulatorArm64` for Apple-silicon simulators or
  `iosArm64` for devices.

## Behaviour change worth knowing: concurrent scans

If your app holds **more than one `RemoteScanner` at a time**, the agent now isolates them instead of
handing each one its own platform scan. Nothing in your code changes, and on hosts where the old
behaviour worked the observable result is the same. What changes is that it now works on Apple hosts
too, where two scanners previously interfered silently — and that the agent will tell you which
policy it is running rather than leaving it to the host. Read [scanning.md](scanning.md) before
relying on concurrent discovery; the one guarantee it deliberately does **not** make is Apple
discovery completeness under mixed filter classes.

Review the [changelog](../CHANGELOG.md) for the cumulative behavior changes before upgrading a
production deployment, especially if it skipped several GitHub-only agent releases.
