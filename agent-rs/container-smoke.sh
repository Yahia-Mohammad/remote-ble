#!/usr/bin/env sh
# Minimal local PR5 smoke test. Requires Docker and an image built from agent-rs/Dockerfile.
# The real-radio D-Bus scan/connect/read evidence remains a PR8 hardware gate.
set -eu

image="${1:-remoteble-agent-rs:local}"

version="$(docker run --rm "$image" --version)"
case "$version" in
  *"agent-rs "*) ;;
  *) echo "image did not report agent-rs version: $version" >&2; exit 1 ;;
esac

# The image defaults to 0.0.0.0 but intentionally carries no credential. Bind validation must
# fail before attempting BlueZ/D-Bus initialization; a successful process here would be insecure.
if docker run --rm "$image"; then
  echo "unauthenticated non-loopback image startup unexpectedly succeeded" >&2
  exit 1
fi

echo "container smoke passed for $image"
