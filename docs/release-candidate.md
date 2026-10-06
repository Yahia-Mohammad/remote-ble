# Release-candidate inventory and release evidence

The **currently released line is 0.15.0** (2026-10-06). `v0.14.0` and `v0.14.1` were tagged, and
their GitHub Releases and GHCR images published, but each was withdrawn before Maven Central when a
final review found fixes; 0.14.2 replaced both. The inventory and checklist below were written for
0.10.0 and remain the procedure of record for every release since. Every version source a release
touches is checked by [`check-release-version.sh`](../scripts/check-release-version.sh) — run the
guard with the intended tag before any release workflow dispatch:

```sh
bash scripts/check-release-version.sh v0.15.0
```

Substitute the tag being cut. The published evidence for each release is recorded under
[Release evidence](#release-evidence--published-2026-08-04) below, 0.11.0's alongside 0.10.0's.

## Release evidence — published 2026-10-06 (0.15.0)

Tag `v0.15.0` is annotated object `8a6bd5a`, on commit `3be9fcc` (the merge of #70), cut after all
five workflows passed on that commit. Every hash below is the published artifact's own, read back
from the release and the registry rather than from a local build.

The tag followed a pre-tag review of `v0.14.2..main`, which found the Rust agent never reporting Tx
power on macOS: btleplug 0.11.8 hard-codes it to `None` on CoreBluetooth. #70 moved `agent-rs` to
btleplug 0.13.4 and corrected the coverage docs. On a Mac with a Pixel 8 running the test
peripheral, `agent-rs` then passed the live E2E 14 of 14, with write errors delivered as
`WRITE_FAILED` for the first time on macOS, and reported Tx power for 9 and 7 devices in two scans
where it had reported none.

### GitHub Release assets

GitHub's server-side digest for each asset matches its published `.sha256` sidecar
(`agent-artifacts.yml` run `37449110216`).

| Asset | SHA-256 |
|---|---|
| `remoteble-agent-0.15.0-all.jar` | `ded8980a8fd9138b60828db25a3a21ad5d9a1544517f4fc649499f4e8f7ff05a` |
| `remoteble-agent-rs-linux-x86_64` | `042a604b7e196c6a8c3093b23b718fe90d450c15593c4034bdf114d6afe8a213` |
| `remoteble-agent-rs-linux-aarch64` | `a6387a42128af36b0a446f23e58177027cfa9dda4fc6853ad2854e45b2fc2ae3` |
| `remoteble-agent-rs-windows-x86_64.exe` | `4be0e277692a466dc509873d5e11e1ec968f764a1ac3a6880c840349821901e9` |
| `remoteble-agent-rs-macos-universal.app.zip` | `8872c140246b89f88e0d7b1df62efa38eff3fd96972042eb21c6a26d2c32abb7` |

### Rust OCI image

`ghcr.io/yahia-mohammad/remoteble-agent-rs`, tags `0.15.0`, `0.15`, `0`, `latest` and `sha-3be9fcc`;
`0.15.0` and `latest` resolve to the same index (`agent-container.yml` run `37449110146`).

| | Digest |
|---|---|
| **Manifest list (OCI index)** | `sha256:8f88558c9e7ff3e12553dce855930a77cb611c804b17b808c497b541e367f399` |
| `linux/amd64` | `sha256:aa43bf38b04ad8d0501bc7b7fe85e572bc544b55b250e45e621706110af8142f` |
| `linux/arm64` | `sha256:0e96019782032ed74b76c52916ddce21eca3a5dd10b7241e838c04ceb1b845e1` (plus the two Buildx attestation manifests) |

### Maven Central

Published by [`release.yml`](../.github/workflows/release.yml) (run `37452817666`, dispatched by
the maintainer; it finished at 10:56:34 UTC), preceded by
[`release-preflight.yml`](../.github/workflows/release-preflight.yml) (run `37449112587`): all 18
coordinates built, **102 detached signatures** with every POM signed, and the Portal credential
check returning HTTP 200 with `{"published":false}`. A poll of `repo1.maven.org` saw all 18 POMs
12 minutes after the run finished; the `client-sdk` POM reports `Last-Modified: 11:01:28 GMT`.

### Post-publish consumer resolution — 0.15.0

Run 2026-10-06 against the **released** coordinates, all three pass:

| Fixture | Task | Result |
|---|---|---|
| `consumer-tests/jvm` | `clean check` | ✅ |
| `consumer-tests/android` | `clean checkDebugAarMetadata compileDebugKotlin`, at `compileSdk 36` | ✅ |
| `consumer-tests/kmp` | `clean compileKotlinIosArm64`, `…SimulatorArm64`, `…MacosArm64` | ✅ |

`mavenLocal()` was neutralized with `-Dmaven.repo.local` on an empty directory plus
`--refresh-dependencies`, and the directory held **0 files** afterwards, so every artifact came from
Central. The JVM closure resolves `client-sdk:0.15.0` → `client-sdk-jvm` → `protocol`/`protocol-jvm`
+ `log`/`log-jvm`, all at `0.15.0`, on `ktor-client-okhttp` 3.5.1. The released
`client-sdk-android`, `protocol-android` and `log-android` AARs each declare `minCompileSdk=36`, read
from the files downloaded from Central.

---

## Release evidence — published 2026-10-05 (0.14.2)

Tag `v0.14.2` is annotated object `8f8afe1`, on commit `bdb60e7` (the merge of #65). Every hash
below is the published artifact's own, read back from the release and the registry rather than
from a local build.

**One departure from the procedure:** GitHub never started the push workflows for `bdb60e7`. The
commit has no check suites at all, though GitHub reported no incident and the commit carries no skip
directive. Build and Rust agent cannot be dispatched, so the tag was cut on content evidence
instead: `bdb60e7`'s tree (`8f660b2`) is identical to that of `7b42ab2`, the #65 head on which all
18 checks passed, and Release gates was dispatched on `main` at `bdb60e7` (run `37345442901`): every
gate passed. Only the non-gating rapid-session-churn harness failed, as it has on every scheduled
run since 2026-08-24, including on `7662eb8` the same morning (4 of 10 iterations reproduced; 1 of
10 here).

### GitHub Release assets

GitHub's server-side digest for each asset matches its published `.sha256` sidecar
(`agent-artifacts.yml` run `37345435153`).

| Asset | SHA-256 |
|---|---|
| `remoteble-agent-0.14.2-all.jar` | `ae0811ab99c1d1d86143cf940320099d134b678130a03754f47ed9b623285f0c` |
| `remoteble-agent-rs-linux-x86_64` | `d5ede7208d0e76e14c008af6abceae4cba2238b18d8a58074e8c7b2ab28675c9` |
| `remoteble-agent-rs-linux-aarch64` | `f5b6696421a2e2648c8ee83a00b3dc9b641baffcbab0615c954b767dbeca41c0` |
| `remoteble-agent-rs-windows-x86_64.exe` | `c5324840da2572f6f4e1cc6de1c3fac96eee6cb26a7a2f07047081bdfaf6c19b` |
| `remoteble-agent-rs-macos-universal.app.zip` | `46d03aa876cf844060f84736a9f43e214a8e304ee76159035115bcf90077d3dc` |

### Rust OCI image

`ghcr.io/yahia-mohammad/remoteble-agent-rs`, tags `0.14.2`, `0.14`, `0`, `latest` and `sha-bdb60e7`;
`0.14.2` and `latest` resolve to the same index (`agent-container.yml` run `37345435162`). The `0.14`,
`0` and `latest` tags moved off the 0.14.1 image, which keeps `0.14.1` and `sha-7662eb8`.

| | Digest |
|---|---|
| **Manifest list (OCI index)** | `sha256:92e20f6b6051eecbd29724d1fad534fed35bda98e7b375762d4f6533f81bf7ee` |
| `linux/amd64` | `sha256:ccec24236e1ec1d1b7aef939c73f4308dc07a43137a3c7bb451bdc1418800134` |
| `linux/arm64` | `sha256:595f81911554386733f3560d166682621b0eade71775855b84249d88bfa4a3ab` (plus the two Buildx attestation manifests) |

### Maven Central

Published by [`release.yml`](../.github/workflows/release.yml) (run `37348578953`, dispatched by
the maintainer; it finished at 17:31:36 UTC), preceded by
[`release-preflight.yml`](../.github/workflows/release-preflight.yml) (run `37345438805`): all 18
coordinates built, **102 detached signatures** with every POM signed, and the Portal credential
check returning HTTP 200 with `{"published":false}`. A poll of `repo1.maven.org` saw all 18 POMs
six minutes after the run finished; the `client-sdk` POM reports `Last-Modified: 17:36:49 GMT`.

Neither 0.14.0 nor 0.14.1 was ever uploaded, so 0.13.0 → 0.14.2 is the whole 0.14 step on Central.

### Post-publish consumer resolution — 0.14.2

Run 2026-10-05 against the **released** coordinates, all three pass:

| Fixture | Task | Result |
|---|---|---|
| `consumer-tests/jvm` | `clean check` | ✅ |
| `consumer-tests/android` | `clean checkDebugAarMetadata compileDebugKotlin`, at `compileSdk 36` | ✅ |
| `consumer-tests/kmp` | `clean compileKotlinIosArm64`, `…SimulatorArm64`, `…MacosArm64` | ✅ |

`mavenLocal()` was neutralized with `-Dmaven.repo.local` on an empty directory plus
`--refresh-dependencies`, and the directory held **0 files** afterwards, so every artifact came from
Central. The JVM closure resolves `client-sdk:0.14.2` → `client-sdk-jvm` → `protocol`/`protocol-jvm`
+ `log`/`log-jvm`, all at `0.14.2`, and brings `ktor-client-okhttp` 3.5.1 (OkHttp 5.3.2), not CIO:
the engine change 0.14.1 made reaches Central consumers here. The released `client-sdk-android`,
`protocol-android` and `log-android` AARs each declare `minCompileSdk=36`, read from the files
downloaded from Central.

---

## Release evidence — published 2026-09-29 (0.13.0)

Tag `v0.13.0` is annotated object `abedcba`, on commit `e993f6c`, cut after all five workflows
passed on that commit. Every hash below is the published artifact's own, read back from the release
and the registry rather than from a local build.

### GitHub Release assets

GitHub's server-side digest for each asset matches its published `.sha256` sidecar. The macOS
bundle is new in this release: the first tagged run of the `agent-rs (macOS universal .app)` job
(`agent-artifacts.yml` run `36572222201`).

| Asset | SHA-256 |
|---|---|
| `remoteble-agent-0.13.0-all.jar` | `cab38bb898d25227e0762e78bb73a43db4292990f9e633d3ad4588fbb740ae6a` |
| `remoteble-agent-rs-linux-x86_64` | `cb64aa6868ae8331fc3e4e280a1544d77d46e41a2db5d61b0f15b56ec39f3ef6` |
| `remoteble-agent-rs-linux-aarch64` | `fa79647703db95aeb24cba8b752d1c967d96a5e1a267549ab96fa78681407729` |
| `remoteble-agent-rs-windows-x86_64.exe` | `a5bf635188492aef9112563f70c05e8b0e82c2ab5d2bcb16cdf274293045ae85` |
| `remoteble-agent-rs-macos-universal.app.zip` | `cbccb9a9720267a6128321aee4e9d2511c4d3ca7de23dd13f273286cab98e71d` |

### Rust OCI image

`ghcr.io/yahia-mohammad/remoteble-agent-rs`, tags `0.13.0` and `latest` resolving to the same index
(`agent-container.yml` run `36572222122`):

| | Digest |
|---|---|
| **Manifest list (OCI index)** | `sha256:bbd63594cb948f240529741b779f8c7d13e26e6c52d2223222b655b74aabb7d7` |
| `linux/amd64` | `sha256:506f7898f8dc31b94256922c821d16f45eec016e0daf0842de9b3e9d611c4877` |
| `linux/arm64` | `sha256:5a8a73047abc0ecd98d7a630c3d222594b91f7da5dbe1da99c6fbc7ef78d9e3d` (plus the two Buildx attestation manifests) |

### Maven Central

Published by [`release.yml`](../.github/workflows/release.yml) (run `36573626091`), preceded by
[`release-preflight.yml`](../.github/workflows/release-preflight.yml) (run `36572240296`) on the same
runner image: four secrets present, **all 18 coordinates** built, **102 detached signatures**, and the
Portal credential check returning HTTP 200.

18 rather than 15 because `protocol`, `log` and `client-sdk` each gained a `-macosarm64` klib. The
release runner is Linux, so those klibs are cross-compiled there, as the iOS ones always were. The
preflight's coordinate list predated them and would have passed a publish missing all three; it was
extended before the tag (#32), so this run is the first to check them.

The `client-sdk` POM on `repo1.maven.org` reports `Last-Modified: 13:21:49 GMT`, about three
minutes after the publish run finished. That is far quicker than 0.12.0's 16 minutes, but it is
read from the server rather than observed: the poll watching for it had a shell word-splitting bug
and never saw anything, so do not quote this as a typical sync time.

### Post-publish consumer resolution — 0.13.0

Run 2026-09-29 against the **released** coordinates, all three pass:

| Fixture | Task | Result |
|---|---|---|
| `consumer-tests/jvm` | `clean check` | ✅ |
| `consumer-tests/android` | `clean checkDebugAarMetadata compileDebugKotlin`, at `compileSdk 36` | ✅ |
| `consumer-tests/kmp` | `clean compileKotlinIosArm64`, `…SimulatorArm64`, `…MacosArm64` | ✅ |

`mavenLocal()` was neutralized with `-Dmaven.repo.local` on an empty directory plus
`--refresh-dependencies`, and the directory held **0 files** afterwards, so every artifact came from
Central. The Android row is the #24 acceptance against the real artifacts: at `compileSdk 36`
`checkDebugAarMetadata` passes, where 0.12.0 fails it. The released `client-sdk-android`,
`protocol-android` and `log-android` AARs each declare `minCompileSdk=36`, read from the files
downloaded from Central. The JVM closure resolves `client-sdk:0.13.0` → `client-sdk-jvm` →
`protocol`/`protocol-jvm` + `log`/`log-jvm`, all at `0.13.0`.

---

## Release evidence — published 2026-08-18 (0.12.0)

Tag `v0.12.0` is annotated object `c63a1f1`, on commit `bc4c268`, cut after all workflows passed on
that commit. Every hash below is the published artifact's own, read back from the release and the
registry rather than from a local build.

### GitHub Release assets

| Asset | SHA-256 |
|---|---|
| `remoteble-agent-0.12.0-all.jar` | `5a0bc85bbe5d39f5b8cf290bcca2343646deb2298d3546ed5b7f481bf268fb84` |
| `remoteble-agent-rs-linux-x86_64` | `d8158f5f950a122bb705c6c7fbec4d10f541896bcd91695a855de0c804bccfa8` |
| `remoteble-agent-rs-linux-aarch64` | `3e0f56f5806c5cd86d750509efb0f8bfd75f957a54e7f9d933464ded67c14ea5` |
| `remoteble-agent-rs-windows-x86_64.exe` | `e0459c4137a9b23b64ddec31812d479829d8135bb0ff256f9f909fd00e4c4731` |

### Rust OCI image

`ghcr.io/yahia-mohammad/remoteble-agent-rs`, tags `0.12.0` and `latest` resolving to the same index:

| | Digest |
|---|---|
| **Manifest list (OCI index)** | `sha256:bfb135139514fcb2f66752b1b47f1c1dbd70cab61f558f3acf12f34798f19629` |
| `linux/amd64` | `sha256:0068ca7fbd3ff1e94ad9b5c8be321a820afee8489123ced225f5a80fd1930334` |
| `linux/arm64` | `sha256:ce496944b7e88999…` (plus the two Buildx attestation manifests) |

Pushed on the first attempt; no GHCR rate-limit retry was needed, as with 0.11.0.

### Maven Central

Published by [`release.yml`](../.github/workflows/release.yml) (run `32187797842`), preceded by
[`release-preflight.yml`](../.github/workflows/release-preflight.yml) (run `32187320908`) on the same
runner image: four secrets present, every coordinate built, **84 detached signatures**, and the
Portal credential check returning HTTP 200.

All 15 coordinates became resolvable from `repo1.maven.org` **16 minutes** after the run completed —
longer than 0.10.0's ~12, which is worth knowing before concluding a publish has failed.

### Post-publish consumer resolution — 0.12.0

Run 2026-08-18 against the **released** coordinates, all three pass:

| Fixture | Task | Result |
|---|---|---|
| `consumer-tests/jvm` | `clean check` | ✅ |
| `consumer-tests/android` | `clean compileDebugKotlin` | ✅ |
| `consumer-tests/kmp` | `clean compileKotlinIosArm64`, `…SimulatorArm64` | ✅ |

`mavenLocal()` was neutralized with `-Dmaven.repo.local` on an empty directory plus
`--refresh-dependencies`. **This mattered more than usual here:** the preflight run publishes to the
operator's Maven local, so `0.12.0` was already in `~/.m2` and all three fixtures would have resolved
locally and proved nothing. The empty repository held **0 files** afterwards, which is the positive
evidence that resolution came from Central.

The closure was confirmed explicitly rather than inferred from a green build:
`dependencies --configuration runtimeClasspath` resolves `client-sdk:0.12.0` →
`client-sdk-jvm:0.12.0` → `protocol`/`protocol-jvm` + `log`/`log-jvm`, all at `0.12.0`.

The Android fixture needs `ANDROID_HOME` when run outside CI — it fails with "SDK location not
found" before reaching dependency resolution, which reads like a resolution failure and is not one.

---

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
