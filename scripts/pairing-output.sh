#!/usr/bin/env bash
# Sourced by the macOS launchers: pairing URIs carry credentials and must bypass agent.log.
# A private FIFO works for terminals, redirected output, and pipelines without persisting secrets.
PAIRING_OUTPUT_DIR=""
PAIRING_OUTPUT_PID=""
prepare_pairing_output() {
  local arg
  for arg in "$@"; do
    if [ "$arg" = "--print-pairing" ]; then
      PAIRING_OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/remoteble-pairing.XXXXXX")"
      mkfifo -m 600 "$PAIRING_OUTPUT_DIR/output"
      export REMOTE_BLE_PAIRING_OUTPUT="$PAIRING_OUTPUT_DIR/output"
      cat "$REMOTE_BLE_PAIRING_OUTPUT" &
      PAIRING_OUTPUT_PID=$!
      return
    fi
  done
}
cleanup_pairing_output() {
  if [ -n "$PAIRING_OUTPUT_PID" ]; then
    # The reader may have finished hours ago; never signal a reused PID.
    if jobs -pr | grep -qx "$PAIRING_OUTPUT_PID"; then
      kill "$PAIRING_OUTPUT_PID" 2>/dev/null || true
    fi
    wait "$PAIRING_OUTPUT_PID" 2>/dev/null || true
  fi
  if [ -n "$PAIRING_OUTPUT_DIR" ]; then
    rm -rf "$PAIRING_OUTPUT_DIR"
  fi
}
