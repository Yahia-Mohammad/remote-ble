# Build from the repository root so the image carries the release LICENSE/NOTICE:
#   docker build -f agent-rs/Dockerfile -t remoteble-agent-rs:local .
# let-chains (src/ble/btleplug_impl.rs) require a newer stable compiler than the 2024
# edition's minimum of 1.85; pinned to match the toolchain CI already builds against.
FROM rust:1.97.1-bookworm AS builder

RUN apt-get update \
    && apt-get install --no-install-recommends -y libdbus-1-dev pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/agent-rs
COPY agent-rs/ /workspace/agent-rs/
RUN cargo build --release --locked

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates libdbus-1-3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 65532 remoteble \
    && useradd --uid 65532 --gid remoteble --no-create-home --shell /usr/sbin/nologin remoteble \
    && install -d -o remoteble -g remoteble -m 0700 /var/lib/remoteble

COPY --from=builder /workspace/agent-rs/target/release/agent-rs /usr/local/bin/remoteble-agent-rs
COPY LICENSE NOTICE /usr/share/doc/remoteble/

# A published port must be authenticated and encrypted: the agent rejects this non-loopback
# default unless the operator supplies REMOTE_BLE_TOKEN or REMOTE_BLE_TOKENS at runtime, and serves
# it as wss:// with a pinned identity. The identity lives on a volume, so a recreated container
# keeps the fingerprint its clients pinned; without one, every new container is a new identity.
ENV REMOTE_BLE_BIND=0.0.0.0 \
    PORT=8080 \
    REMOTE_BLE_LOG_FORMAT=json \
    REMOTE_BLE_TLS=true \
    REMOTE_BLE_IDENTITY_FILE=/var/lib/remoteble/agent-identity.pem
VOLUME /var/lib/remoteble

USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/remoteble-agent-rs"]
