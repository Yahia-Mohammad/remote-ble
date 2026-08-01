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
3. Complete clean Android and KMP/iOS consumer resolution against the staging/released coordinates.
4. Complete PR8’s real-radio, iOS, TLS-proxy, Ubuntu, and Pi evidence.
5. Complete the scan-concurrency hardware run —
   [scan-concurrency-validation.md](scan-concurrency-validation.md). This is a **separate blocker**
   from item 4 even though it shares Rig B: the feature it validates landed after PR8's plan was
   written, it introduces published capability strings and a new `ErrorKind` that Central cannot
   unpublish, and one of its cases decides a sentence in [scanning.md](scanning.md) about Apple
   discovery completeness. An `INCONCLUSIVE` verdict there is not a pass.
6. Confirm Central quota and signing credentials, then create `v0.10.0` on the approved commit.

The tag and publication are PR9 actions. This document is an inventory and approval checklist, not
evidence that publication or hardware validation has happened. Release assets must include a
SHA-256 sidecar or a release-evidence record that names the exact asset and its hash.
