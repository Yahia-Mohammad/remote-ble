# 0.10.0 release-candidate inventory

The release candidate is version **0.10.0** everywhere checked by
[`check-release-version.sh`](../scripts/check-release-version.sh). Run the guard with the intended
tag before any release workflow dispatch:

```sh
bash scripts/check-release-version.sh v0.10.0
```

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
   properly means publishing to a resolvable staging repository (GitHub Packages) first; that is
   0.10.1 work.
4. ~~Complete PR8’s real-radio, iOS, TLS-proxy, Ubuntu, and Pi evidence.~~ **All four rigs are run
   (25/25) as of 2026-08-03** — see [pr8-validation-plan.md](pr8-validation-plan.md). Rig D passed
   6/6 on **one amd64 Linux host** under the option-1 relaxation, *not* on the Ubuntu and Pi hosts
   this line originally named: [pr8-rig-d-evidence.md](pr8-rig-d-evidence.md), with
   [rust-agent-container.md](proposals/rust-agent-container.md) §2/§10 updated to match. **Approving
   the tag means accepting that arm64, AppArmor, SELinux-enforcing and rootless Podman are
   unvalidated and the image is labelled accordingly** — that is a decision, not a formality.
   Two residuals worth closing first, neither strictly blocking: Rig D's `agent-rs` fix is unverified
   on macOS (Rig A's evidence predates it — the re-check is short and prompt-free,
   [commands and pass criteria here](pr8-rig-d-evidence.md#the-outstanding-macos-re-check-finding-1)),
   and the multi-arch manifest is unexercised because no `v*` tag exists yet, so item 2's digest
   record and this evidence cannot yet refer to the same published artifact.
5. ~~Complete the scan-concurrency hardware run~~ — **DONE 2026-08-03**, evidence in
   [scan-concurrency-validation.md](scan-concurrency-validation.md). Passed on the iOS agent, the
   Kotlin JVM agent and `agent-rs`, all three in agreement, no `INCONCLUSIVE` verdicts. One case
   (`SC-HW-06`, the Apple overflow-advertising wording) is still unrun for want of a second Apple
   device; it does not gate the tag, and [scanning.md](scanning.md)'s Apple paragraph therefore
   stands unchanged. **The published capability strings and the `SCAN_UNAVAILABLE` `ErrorKind` this
   introduced are now hardware-backed** — which is what made it a separate blocker from item 4,
   since Central cannot unpublish them.
6. Confirm Central quota and signing credentials, then create `v0.10.0` on the approved commit.

The tag and publication are PR9 actions. This document is an inventory and approval checklist, not
evidence that publication or hardware validation has happened. Release assets must include a
SHA-256 sidecar or a release-evidence record that names the exact asset and its hash.
