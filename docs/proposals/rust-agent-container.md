# Rust agent container — Linux real-radio image

**Status:** implemented in 0.10.0 (Dockerfile + multi-arch workflow landed). Host validation ran
**2026-08-03 on one amd64 Linux host** under the option-1 relaxation below — see
[../rig-d-evidence.md](../rig-d-evidence.md). Publication of the image itself is still
release-gated (see [../release-candidate.md](../release-candidate.md)).

**Artifact:** `ghcr.io/yahia-mohammad/remoteble-agent-rs:<version>`

**Supported hosts:** Linux with host BlueZ and system D-Bus. **Validated: amd64 Fedora-family
(Nobara 44, BlueZ 5.86, Docker 29.7.1, SELinux disabled).** **Unvalidated: arm64 (Raspberry Pi),
AppArmor (Ubuntu), SELinux in enforcing mode, and Podman/rootless.** Label the image to match — do
not describe arm64 as supported on the strength of a build that no one has run.

## 1. Intent

Ship the existing `agent-rs` binary as a convenient, versioned OCI image. The container does not run
its own Bluetooth daemon and does not access an HCI device directly. `btleplug` talks to the host's
BlueZ service through the system D-Bus socket.

The image is another packaging of the same agent behavior, protocol, configuration, and version—not a
new agent implementation.

## 2. Honest support boundary

- Supported: native Linux Docker/Podman hosts with a working Bluetooth adapter, BlueZ daemon, and
  system D-Bus socket.
- Not supported for real radio: Docker Desktop on macOS or Windows, because the Linux VM does not
  transparently provide the host CoreBluetooth/Windows BLE stack.
- No bundled BlueZ daemon, D-Bus daemon, or `--privileged` default.
- No claim that every Linux security policy works automatically. **Relaxed 2026-08-03 (option 1):**
  Ubuntu/AppArmor and Raspberry Pi were the release-gating reference hosts; neither was available, so
  the gate is now **one validated amd64 Linux host** (Rig D, above) with AppArmor and arm64 recorded
  as unvalidated. Other distributions, arm64, SELinux-enforcing, and rootless Podman are best effort
  until someone runs them. The rejected alternatives are recorded in
  [`0.10.0-progress-status.md`](0.10.0-progress-status.md) item 2 so they are not re-litigated.
- **Rig D is not only a packaging rig.** It found a defect that no Apple-hosted rig could reach:
  `agent-rs` resolved a fresh, undiscovered `Peripheral` per GATT op, which CoreBluetooth's btleplug
  backend tolerates and BlueZ does not. Treat a new host family as correctness coverage, not as a
  repackaging check.

## 3. Image construction

Use a multi-stage Dockerfile:

1. Builder stage compiles `agent-rs --release --locked` from the tagged source.
2. Runtime stage uses a small glibc Debian base with the `libdbus-1` runtime and CA certificates.
3. Copy only the binary, licenses/notices, and minimal runtime metadata.
4. Create a fixed non-root runtime user; retain a documented root fallback only if host D-Bus policy
   prevents the validated non-root path.
5. Set `REMOTE_BLE_BIND=0.0.0.0`, `PORT=8080`, and `REMOTE_BLE_LOG_FORMAT=json` as container-oriented
   defaults. Do not set a credential or insecure-LAN override.
6. Use the agent binary as `ENTRYPOINT`; preserve CLI arguments.
7. Forward `SIGTERM` directly so the existing structured disconnect/shutdown path runs.

The runtime image must not contain Cargo, compilers, package managers beyond base-image requirements,
source code, build caches, or credentials.

## 4. Runtime contract

Example:

```sh
docker run --rm \
  --name remoteble-agent \
  -p 8080:8080 \
  -e REMOTE_BLE_TOKEN='replace-me' \
  -v /run/dbus/system_bus_socket:/run/dbus/system_bus_socket \
  ghcr.io/yahia-mohammad/remoteble-agent-rs:0.10.0
```

The bind policy already refuses an unauthenticated `0.0.0.0` listener, so publishing the container
port without `REMOTE_BLE_TOKEN`/`REMOTE_BLE_TOKENS` fails closed. Production deployments should place
the published endpoint behind the documented TLS proxy/VPN path.

Start with ordinary bridge networking and `-p 8080:8080`. Host networking is not inherently required
for BLE when the process talks to host BlueZ through D-Bus; add it to the supported invocation only if
Ubuntu/Pi testing demonstrates a concrete need.

Likewise, do not request `/dev` mounts, `NET_ADMIN`, `SYS_ADMIN`, or `--privileged` unless evidence
shows the D-Bus architecture cannot work without them. Any required capability becomes a documented
security exception and a release decision.

## 5. Configuration and secrets

All existing Rust-agent environment variables remain supported, including:

- `REMOTE_BLE_TOKEN` / `REMOTE_BLE_TOKENS`
- `REMOTE_BLE_BIND`
- `PORT`
- `REMOTE_BLE_LEASE_GRACE_MS`
- `REMOTE_BLE_TRANSPORT_GRACE_MS`
- `REMOTE_BLE_LIVENESS_PROBE_MS`
- `REMOTE_BLE_STRICT_IDENTIFIERS`
- `REMOTE_BLE_LOG` / `REMOTE_BLE_LOG_FORMAT`

Examples may use `-e` for clarity, but production guidance should prefer Docker/Compose secrets or an
operator-managed environment file with restricted permissions. Secrets must not be baked into image
layers, labels, default environment, health checks, or workflow logs.

## 6. Runtime hardening target

Validate the following settings before documenting them as defaults:

- non-root user with access to the mounted D-Bus socket;
- read-only root filesystem;
- writable `tmpfs` only if a dependency requires it;
- `no-new-privileges`;
- dropped Linux capabilities;
- explicit memory/PID limits in deployment examples;
- clean shutdown within the container stop timeout;
- no credential or device identifiers in image metadata.

Do not add a credential-dependent Docker `HEALTHCHECK`. Until the server exposes a safe authenticated
readiness probe, rely on process health plus an external WebSocket/authenticated smoke test in CI.

## 7. Multi-architecture publishing

The release workflow builds one manifest list for:

- `linux/amd64`
- `linux/arm64`

Publish immutable and convenience tags:

- `0.10.0`
- `0.10`
- `0`
- `latest` only for the newest stable release
- optional `sha-<commit>` for traceability

OCI labels include source repository, revision, semantic version, licenses, creation time, and
description. Builds must use the tag's source and `Cargo.lock`. The workflow produces an image digest,
SBOM, and provenance/artifact record tied to the release evidence.

Prefer one Docker Buildx multi-platform build from source for simplicity and reproducibility. Reusing
the separately cross-compiled release binaries is acceptable only if checksums prove that the image
contains the intended tag artifact and the workflow remains easier to audit.

## 8. CI and release validation

### Image-level tests

- image starts with `--version` and reports the release version;
- unauthenticated non-loopback startup fails;
- authenticated startup remains running and handles `SIGTERM` cleanly;
- image contains no compiler/build tree and runs with the targeted hardening flags;
- amd64 and arm64 manifests resolve to the correct architecture.

### Real-host smoke tests

On Ubuntu amd64 and Raspberry Pi/Debian arm64:

1. confirm host BlueZ can see the adapter;
2. mount the system D-Bus socket and start the authenticated image;
3. connect an ordinary RemoteBLE client through the published port;
4. scan, connect, discover, read, and disconnect a real peripheral;
5. restart the container and repeat;
6. capture host OS, Docker version, BlueZ version, adapter, image digest, command, and redacted logs.

The image is not called supported until both reference-host runs pass. Automated build checks may run
on hosted CI, but real-radio evidence belongs to the 0.10.0 hardware gate.

## 9. Implementation sequence

| Phase | Deliverable | Exit gate |
|---|---|---|
| C1 | Dockerfile, ignore file, local amd64 build | Secure startup/version/signal tests pass |
| C2 | Compose/example documentation and hardening exploration | Credentialed bridge-network invocation works without privilege on Ubuntu |
| C3 | Buildx GHCR workflow with version tags, labels, digest, SBOM | amd64/arm64 manifest is reproducible from the release commit |
| C4 | Ubuntu and Pi real-radio validation | Scan/connect/read passes and evidence is archived |
| C5 | Release integration and post-publish pull test | Public digest matches recorded release evidence |

## 10. Acceptance criteria

- One documented `docker run` command starts the authenticated real-radio Rust agent on supported
  Linux hosts.
- The image uses the same versioned binary behavior as direct `agent-rs` distribution.
- The supported path does not require `--privileged`, host networking, or HCI device passthrough unless
  the release evidence explicitly proves and documents an unavoidable exception.
- The listener fails closed without credentials.
- `SIGTERM` executes structured agent shutdown.
- Linux amd64/arm64 images, digest, SBOM, and source revision are published together.
  **Open** — no `v*` tag exists yet, so the publish job has never run.
- ~~Ubuntu amd64 and Pi arm64 hardware smoke evidence passes before 0.10.0 publication.~~
  **Relaxed 2026-08-03 (option 1) to: one amd64 Linux host passes all six Rig D cases on a real
  radio, with AppArmor and arm64 recorded as unvalidated in this document and on the image label.**
  **Met** — Nobara 44 amd64, 6/6, [../rig-d-evidence.md](../rig-d-evidence.md). The original
  two-reference-host bar is deferred, not satisfied; restoring it needs an Ubuntu host and a Pi.
