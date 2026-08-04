# Rust agent container (0.10.0 PR5–PR6 delivery)

The local image is built from the repository root so it can include the release license and notice:

```sh
docker build -f agent-rs/Dockerfile -t remoteble-agent-rs:local .
agent-rs/container-smoke.sh remoteble-agent-rs:local
```

The image is a multi-stage Debian build: Cargo and compiler tooling stay in the builder; the runtime
contains only the Rust agent binary, `libdbus-1`, CA certificates, and the license metadata. It runs
as the fixed non-root `remoteble` user, defaults to `0.0.0.0:8080`, and has no baked credential.
The agent therefore fails closed if the port is exposed without `REMOTE_BLE_TOKEN` or
`REMOTE_BLE_TOKENS`.

On a supported Linux host, mount the host system D-Bus socket and supply a credential:

```sh
docker run --rm --name remoteble-agent -p 8080:8080 \
  -e REMOTE_BLE_TOKEN='replace-me' \
  -v /run/dbus/system_bus_socket:/run/dbus/system_bus_socket \
  remoteble-agent-rs:local
```

Do not add `--privileged`, host networking, HCI mounts, or a credential-bearing health check. The
image’s `--version` and fail-closed startup checks are covered by `container-smoke.sh`; D-Bus access,
read-only filesystem operation, SIGTERM, and real BLE scan/connect/read are recorded on a Linux host
in the hardware-validation bundle ([rig-d-evidence.md](rig-d-evidence.md) — **6/6, one amd64 host**).

> **The host list changed and this line used to overstate it.** The original criteria named Ubuntu
> amd64 *and* Raspberry Pi arm64. The accepted Rig D scope is now **option 1**: validate the one
> available amd64 Linux host (Fedora-based Nobara), then relax the criteria to "one amd64 Linux host
> validated; AppArmor and arm64 unvalidated" and label the image accordingly. Ubuntu was named
> *because of* AppArmor, and Fedora ships SELinux — so that run yields a third data point rather
> than a substitute. See gap 2 in
> [proposals/0.10.0-progress-status.md](proposals/0.10.0-progress-status.md).

The authoritative support contract is [the container proposal](proposals/rust-agent-container.md).

On every PR and `main`, [Rust agent container CI](../.github/workflows/agent-container.yml) builds
the amd64 image and runs that smoke script. A version tag (or explicit tag dispatch) first passes
the shared version guard, then publishes a Buildx `linux/amd64` + `linux/arm64` GHCR manifest under
`ghcr.io/<owner>/remoteble-agent-rs`. It attaches semantic version, major/minor, major, commit, and
stable `latest` tags, plus OCI source/revision/license labels, build provenance, an SBOM, and the
published digest artifact. GHCR publication and host-radio evidence are still distinct steps.
