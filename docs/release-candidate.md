# Release-candidate inventory and release evidence

The **currently released line is 0.11.0** (2026-08-10); the inventory and checklist below were
written for 0.10.0 and remain the procedure of record for every release since. Every version source
a release touches is checked by
[`check-release-version.sh`](../scripts/check-release-version.sh) — run the guard with the intended
tag before any release workflow dispatch:

```sh
bash scripts/check-release-version.sh v0.11.0
```

Substitute the tag being cut. The published evidence for each release is recorded under
[Release evidence](#release-evidence--published-2026-08-04) below, 0.11.0's alongside 0.10.0's.

## Release evidence — published 2026-08-04

**0.10.0 shipped.** Tag `v0.10.0` is annotated object `c0f8657`, on commit `bd921fe`, cut after all
four workflows passed on that commit. It is the first tag this repository has ever pushed. The
sections below this one are the checklist that governed the release; this section is what it
produced. Every hash here is the published artifact's own, read back from the release rather than
from a local build.

### GitHub Release assets

Attached by [`agent-artifacts.yml`](../.github/workflows/agent-artifacts.yml) (run `30912683997`),
each with a SHA-256 sidecar:

| Asset | SHA-256 |
|---|---|
| `remoteble-agent-0.10.0-all.jar` | `f0b6bda65da119c8634bdf8ecbd2492758dd073d627c5d0fae3e08c5b48eb907` |
| `remoteble-agent-rs-linux-x86_64` | `d3394c71b2e63f263889263cebe1a2e4f74011b0ee2043cc2aafd1ff5b36067d` |
| `remoteble-agent-rs-linux-aarch64` | `533532e8557e3a80940c870052407a2b8707df21b8feed2f07734ef6740477e0` |
| `remoteble-agent-rs-windows-x86_64.exe` | `82f8309457fb4eadfd5adb820081a162afb5561f620d2975fe27a705b84c01c3` |

### Rust OCI image

`ghcr.io/yahia-mohammad/remoteble-agent-rs`, tags `0.10.0`, `0.10`, `0`, `latest`, `sha-bd921fe`:

| | Digest |
|---|---|
| **Manifest list (OCI index)** | `sha256:039d9beb4d459c237a242ef52507422dc6f650e9d6371c52d4906993f10ec8a9` |
| `linux/amd64` | `sha256:f473e9804313ca4508e2ecda3559a8a464309fc7746b163f4a6b459de3178bec` |
| `linux/arm64` | `sha256:5457bab7a7faac503bfb2e989ed3b42cdc708f99506a65985ad6e0949fd983ab` |

Plus two attestation manifests (Buildx SBOM and provenance). This closes item 4's last residual: the
multi-arch manifest is now exercised, and item 2's digest record and Rig D's evidence refer to the
same published artifact.

The push took three attempts. The first two were rejected by GHCR with a 403 *"secondary rate
limit"* after the image had already built; the third, ~90 minutes later, succeeded unchanged. The
build was never at fault. Worth knowing for the next release: buildx exports its cache only on
success, so each failed push discarded a ~23-minute emulated arm64 rebuild.

**0.11.0** pushed cleanly on the first attempt — no rate-limit retry was needed. Tags `0.11.0`,
`0.11`, `0`, `latest`, `sha-1b7df09`; OCI index digest
`sha256:d91bb3c329a681c0e601411d80b4a8e977854bfd1b03435016e73270a674e2cd`.

### Maven Central

Published and released by [`release.yml`](../.github/workflows/release.yml) (run `30943065249`) —
its first-ever execution. All 15 coordinates under `dev.warsha.remoteble` at `0.10.0`, each with a
detached signature, resolvable from `repo1.maven.org` ~12 minutes after the run completed:

```
protocol      protocol-jvm      protocol-android      protocol-iosarm64      protocol-iossimulatorarm64
log           log-jvm           log-android           log-iosarm64           log-iossimulatorarm64
client-sdk    client-sdk-jvm    client-sdk-android    client-sdk-iosarm64    client-sdk-iossimulatorarm64
```

Because that first execution would otherwise have been both the debut and the irreversible step,
[`release-preflight.yml`](../.github/workflows/release-preflight.yml) (run `30942658201`) verified
beforehand, on the same runner image, that the signing key and passphrase were correct (84 detached
signatures produced), that all 15 coordinates build there, and that the Portal token was accepted.

### Post-publish consumer resolution

Item 3's replacement check, run 2026-08-04 against the **released** coordinates — all three pass:

| Fixture | Task | Resolved |
|---|---|---|
| `consumer-tests/jvm` | `check` | `client-sdk-jvm` jar/pom/module |
| `consumer-tests/android` | `compileDebugKotlin` | `client-sdk-android.aar` + `log-android`, `protocol-android` |
| `consumer-tests/kmp` | `compileKotlinIosArm64`, `…SimulatorArm64` | `client-sdk-iosarm64.klib`, `…iossimulatorarm64.klib` |

**These fixtures list `mavenLocal()` first, and `0.10.0` was present in the operator's `~/.m2`.** Run
as CI runs them, all three would have resolved locally and passed while proving nothing. They were
run with `-Dmaven.repo.local` pointed at an empty directory and `--refresh-dependencies`; every
artifact came from `repo.maven.apache.org` and the logs contain zero local-`.m2` references. Anyone
repeating this check must neutralize `mavenLocal()` the same way or the result is meaningless.

### Post-publish consumer resolution — 0.11.0

Repeated 2026-08-10 against the released `0.11.0` coordinates, by the same method — all three pass:

| Fixture | Task | Result |
|---|---|---|
| `consumer-tests/jvm` | `clean check` | ✅ |
| `consumer-tests/android` | `clean compileDebugKotlin` | ✅ |
| `consumer-tests/kmp` | `clean compileKotlinIosArm64`, `…SimulatorArm64` | ✅ |

`mavenLocal()` was neutralized with `-Dmaven.repo.local` on an empty directory plus
`--refresh-dependencies`, per the warning above. The empty repository stayed empty, which is the
positive evidence that resolution came from Central rather than from any local cache. The full
closure was also confirmed explicitly rather than inferred from a green build —
`dependencies --configuration runtimeClasspath` resolves `client-sdk:0.11.0` →
`client-sdk-jvm:0.11.0` → `protocol`/`protocol-jvm` + `log`/`log-jvm`, all at `0.11.0`.

All 15 published coordinates were verified present on `repo1.maven.org` before the fixtures ran.

## Artifact inventory

| Artifact | Build/publish path | Required evidence |
|---|---|---|
| JVM agent fat JAR | `:agent:jvmFatJar` → `agent/build/libs/remoteble-agent-0.10.0-all.jar` | `agent-artifacts.yml` publishes a SHA-256 sidecar; also require `--simulate` smoke and the GitHub Release asset |
| Rust binaries | `agent-artifacts.yml` Linux amd64/arm64 and Windows jobs | Workflow publishes SHA-256 sidecars; also require `--version` and GitHub Release assets |
| Rust OCI image | `agent-container.yml` Buildx manifest | amd64/arm64 digest, OCI SBOM/provenance, GHCR tags |
| Protocol, logging, SDK KMP publications | `publishAndReleaseToMavenCentral` | Central coordinates/POMs for `protocol`, `log`, and `client-sdk`; checksums/signatures |
| Source SBOM | `release-gates.yml` SBOM job | archived SPDX JSON artifact |

The published SDK closure includes `:log`: the clean JVM consumer gate caught the missing artifact
before this RC. Its Maven Central publication is therefore required alongside `:protocol` and
`:client-sdk` even though it has no external runtime dependencies.

Consumer-facing upgrade guidance is in [migrate-to-0.10.0.md](migrate-to-0.10.0.md), including the
breaking `authToken` provider change for applications upgrading from an earlier Central release.

## Before tag approval

1. Run the permanent gates and archive their workflow URLs/artifacts.
2. Build every row above from the exact candidate commit and record SHA-256/digests in release
   evidence (never store credentials or bearer tokens there).
3. ~~Complete clean Android and KMP/iOS consumer resolution against the staging/released
   coordinates.~~ **Reclassified 2026-08-04 to a post-publish check — this step could not be
   performed as written.** It assumed a staging window that this project does not have:
   [`release.yml`](../.github/workflows/release.yml) runs `publishAndReleaseToMavenCentral`, which
   publishes *and* releases in one irreversible step, and nothing else here produces a repository a
   consumer could resolve from. A Central Portal deployment is not resolvable until it is released,
   so "resolve from staging before approving the tag" has no artifact to point at.

   What stands in its place: the three `consumer-tests/*` fixtures pass against **Maven local** and
   run as permanent CI gates, and each was verified to fail on an unpublished version, so they are
   not vacuous. Re-run all three against the **released** `0.10.0` coordinates immediately after the
   publish, and treat a failure there as a `0.10.1` trigger. **This is a real reduction in
   assurance** — a broken POM or metadata closure would now be caught after Central has the
   artifacts rather than before — and it is accepted deliberately rather than overlooked. Closing it
   properly means publishing to a resolvable staging repository (GitHub Packages) first; that work
   is **still open** (it did not land in 0.11.0 either).

   **The post-publish re-run was done 2026-08-04 and all three fixtures pass** — see [Post-publish
   consumer resolution](#post-publish-consumer-resolution). That discharges the check for this
   release, but *not* the underlying gap: the assurance is still after-the-fact, and the staging
   repository remains open work. The same post-publish re-run is therefore required for every
   subsequent release, 0.11.0 included.
4. ~~Complete the real-radio, iOS, TLS-proxy, Ubuntu, and Pi evidence.~~ **All four rigs are run
   (25/25) as of 2026-08-03** — see [validation-plan.md](validation-plan.md). Rig D passed
   6/6 on **one amd64 Linux host** under the option-1 relaxation, *not* on the Ubuntu and Pi hosts
   this line originally named: [rig-d-evidence.md](rig-d-evidence.md), with
   [rust-agent-container.md](proposals/rust-agent-container.md) §2/§10 updated to match. **Approving
   the tag means accepting that arm64, AppArmor, SELinux-enforcing and rootless Podman are
   unvalidated and the image is labelled accordingly** — that is a decision, not a formality.
   ~~Two residuals worth closing first~~ — **one left.** Rig D's `agent-rs` fix is now **verified on
   macOS (2026-08-04)**: connect, discover and read all pass through the cached peripheral handle on
   CoreBluetooth, evidence in
   [rig-d-evidence.md](rig-d-evidence.md#run-2026-08-04--pass-and-the-recipe-above-is-wrong-in-two-ways).
   ~~The remaining residual is the multi-arch manifest, unexercised because no `v*` tag exists yet,
   so item 2's digest record and this evidence cannot yet refer to the same published artifact.~~
   **Closed 2026-08-04** — the manifest is published and its digests are recorded under [Rust OCI
   image](#rust-oci-image). The acceptance above still stands unchanged: arm64 is *built* and
   published, but it is still not *validated* on arm64 hardware.
5. ~~Complete the scan-concurrency hardware run~~ — **DONE 2026-08-03**, evidence in
   [scan-concurrency-validation.md](scan-concurrency-validation.md). Passed on the iOS agent, the
   Kotlin JVM agent and `agent-rs`, all three in agreement, no `INCONCLUSIVE` verdicts. One case
   (`SC-HW-06`, the Apple overflow-advertising wording) is still unrun for want of a second Apple
   device; it does not gate the tag, and [scanning.md](scanning.md)'s Apple paragraph therefore
   stands unchanged. **The published capability strings and the `SCAN_UNAVAILABLE` `ErrorKind` this
   introduced are now hardware-backed** — which is what made it a separate blocker from item 4,
   since Central cannot unpublish them.
6. ~~Confirm Central quota and signing credentials, then create `v0.10.0` on the approved commit.~~
   **Done 2026-08-04.** The credentials had to be recreated: the repository was deleted and
   recreated during an earlier history reorganization, which discarded its Actions secrets, and
   `release.yml` had never run — so the CI publish path was unproven rather than merely unused.
   `release-preflight.yml` exists because of that and should be run before any future Central
   publish.

This document began as an inventory and approval checklist, and the checklist above is preserved as
it stood at approval — including the reservations, which were accepted rather than resolved. What
publication produced is recorded in [Release evidence](#release-evidence--published-2026-08-04).
Release assets must include a SHA-256 sidecar or a release-evidence record that names the exact
asset and its hash.
