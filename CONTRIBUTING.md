# Contributing to RemoteBLE

Thanks for your interest in RemoteBLE! This is a Kotlin Multiplatform "remote
mode" for [Kable](https://github.com/JuulLabs/kable) — an independent,
unaffiliated extension, Apache-2.0 licensed. Contributions of all kinds are
welcome: bug reports, docs, tests, and code.

## Getting set up

Requirements:

- **JDK 17** (the Gradle toolchain targets 17)
- **Android SDK** (`ANDROID_HOME`, or `sdk.dir` in `local.properties`) for the
  Android targets
- **A Mac with Xcode** only if you're building/running the iOS targets or the
  XCFramework; a plain `./gradlew build` skips iOS *test* binaries automatically
  when Xcode isn't active (the klibs still compile)

Kable — including its JVM `btleplug` backend — resolves from **Maven Central**
(`com.juul.kable:kable-core:0.43.1`). There is no "build Kable from source" step
and no `mavenLocal()` anymore; `./gradlew build` pulls everything it needs.

```sh
git clone https://github.com/Yahia-Mohammad/remote-ble.git
cd remote-ble
./gradlew build          # compiles all targets (JVM/Android/iOS klibs) + runs the JVM tests
```

## Running the tests

The suite runs on the **JVM** (fast, no radio) against in-memory transports and
fake/stub BLE backends:

```sh
./gradlew build                 # everything: all klibs compile + full JVM test suite
./gradlew :protocol:jvmTest     # wire-codec round-trip + compatibility tests
./gradlew :client-sdk:jvmTest   # session, transport, reconnection, Kable adapters
./gradlew :agent:jvmTest        # agent Koin graph, op handling
```

iOS *unit tests* aren't run in CI (they need a full Xcode toolchain, not just
Command Line Tools); the shared logic they'd cover is exercised by the JVM
suite, and the iOS klibs are compile-checked on a macOS runner. See
[docs/build-and-testing.md](docs/build-and-testing.md#the-ios-test-caveat).

The Rust agent (`agent-rs`) has its own suite plus cross-language interop tests
that verify byte-identical CBOR against the Kotlin codec:

```sh
cd agent-rs && cargo test
```

## Before you open a PR

- **Keep it building.** `./gradlew build` must pass. If you touch the wire
  protocol, `:protocol:jvmTest` and the `agent-rs` interop tests both matter —
  the `@SerialName` discriminators are the frozen wire identity across two
  implementations.
- **Match the surrounding style.** An [`.editorconfig`](.editorconfig) encodes
  the Kotlin official style (4-space indent, no wildcard imports, etc.). Keep
  diffs focused; avoid unrelated reformatting.
- **Note wire-protocol impact.** New optional features should degrade
  gracefully via capability negotiation (a `Set<String>`), not a version bump —
  see [docs/protocol.md](docs/protocol.md) and
  [docs/agent-conformance-spec.md](docs/agent-conformance-spec.md).
- **Update docs + CHANGELOG.** Add a line under `[Unreleased]` in
  [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

## Where things live

Start with [docs/README.md](docs/README.md) — it's the architecture index and
glossary. When filing an issue, note your **platform** and which **agent** you're
running (JVM, Rust, or a phone agent) and the **Kable version**; the issue
templates ask for these.

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same as the project.
