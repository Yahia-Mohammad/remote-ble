# Build from the repository root so the image carries the release LICENSE/NOTICE:
#   docker build -f agent-rs/Dockerfile -t remoteble-agent-rs:local .
# Rust 1.85 is the first stable compiler for the Cargo edition used by this crate.
FROM rust:1.85-bookworm AS builder

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
    && useradd --uid 65532 --gid remoteble --no-create-home --shell /usr/sbin/nologin remoteble

COPY --from=builder /workspace/agent-rs/target/release/agent-rs /usr/local/bin/remoteble-agent-rs
COPY LICENSE NOTICE /usr/share/doc/remoteble/

# A published port must be authenticated: the agent itself rejects this non-loopback default
# unless the operator supplies REMOTE_BLE_TOKEN or REMOTE_BLE_TOKENS at runtime.
ENV REMOTE_BLE_BIND=0.0.0.0 \
    PORT=8080 \
    REMOTE_BLE_LOG_FORMAT=json

USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/remoteble-agent-rs"]
