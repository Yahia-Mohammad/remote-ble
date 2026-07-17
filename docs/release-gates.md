# 0.10.0 permanent release gates

`Release gates` is the evidence workflow for the permanent, radio-less parts of the 0.10.0 release
criterion. It runs on pull requests and `main`; the security checks also run weekly so newly
disclosed advisories and leaked historical secrets cannot wait for the next code change.

| Gate | Evidence | Cadence |
|---|---|---|
| Cross-agent conformance | `./gradlew conformanceTest` plus `agent-rs` `cargo test --locked` | PR, `main`, manual, weekly |
| Published JVM consumer | Publishes `:protocol`, `:log`, and `:client-sdk` to Maven local, then builds `consumer-tests/jvm` using only their Maven coordinates | PR, `main`, manual, weekly |
| Kotlin coverage | Kover merges JVM tests for `:protocol`, `:log`, `:client-sdk`, and `:agent` into XML/HTML artifacts | PR, `main`, manual, weekly |
| Rust coverage | Tarpaulin's LLVM engine emits and archives a Cobertura XML line-coverage report | PR, `main`, manual, weekly |
| Secret policy | Gitleaks scans the repository and reachable Git history | PR, `main`, manual, weekly |
| Dependency review | GitHub dependency-review rejects newly introduced high-or-worse vulnerable dependencies | PR |
| Rust advisories and licenses | `cargo audit` and `cargo deny` under [`agent-rs/deny.toml`](../agent-rs/deny.toml) | PR, `main`, manual, weekly |
| SBOM | Anchore Syft emits an SPDX JSON source SBOM and archives it as a workflow artifact | PR, `main`, manual, weekly |

Dependabot tracks Gradle, Cargo, and GitHub Actions dependencies weekly in
[`.github/dependabot.yml`](../.github/dependabot.yml). The dependency-review gate covers changes to
the JVM/Gradle dependency graph; Cargo has the additional advisory and license policy above.

## Deliberate boundaries

- The SBOM is a source/dependency inventory at this stage. PR7 adds versioned release-artifact,
  container-manifest, checksum, and publication inventories; PR9 attaches those to the tag.
- Kover reports JVM execution only; its upstream support does not collect Kotlin/Native or Android
  device-test coverage. Tarpaulin measures Rust on the Linux CI host. The reports are measured
  evidence, not a substitute for PR8's platform and hardware validation.
- `consumer-tests/jvm` is intentionally an independent Gradle build. It proves that the POMs resolve
  from Maven local without composite-build or project-dependency leakage. Android and KMP/iOS clean
  consumers belong to the release-candidate gate in PR7, when their target artifacts and the exact
  `0.10.0` version exist.
- No workflow substitutes for `TLS-PROXY-01`, live-radio, iOS lifecycle, or Ubuntu/Pi BlueZ container
  evidence. Those stay in PR8's hardware-validation bundle.
- `cargo deny` allows only the explicit permissive license set in its checked-in policy. Any future
  exception must name the crate/version and explain the release decision in that file; do not bypass
  the gate in workflow YAML.
