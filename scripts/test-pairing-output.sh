#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPECTED='Pairing: remoteble://127.0.0.1:8080?token=private-test-secret'
observed="$(
  source "$HERE/pairing-output.sh"
  unset REMOTE_BLE_PAIRING_OUTPUT
  trap cleanup_pairing_output EXIT
  prepare_pairing_output --tls
  [ -z "$PAIRING_OUTPUT_DIR" ]
  [ -z "${REMOTE_BLE_PAIRING_OUTPUT:-}" ]
  prepare_pairing_output --tls --print-pairing
  [ -p "$REMOTE_BLE_PAIRING_OUTPUT" ]
  dir="$PAIRING_OUTPUT_DIR"
  # Simulates the dedicated agent writer; stdout/stderr can still point to agent.log.
  printf '%s\n' "$EXPECTED" > "$REMOTE_BLE_PAIRING_OUTPUT"
  wait "$PAIRING_OUTPUT_PID"
  cleanup_pairing_output
  [ ! -e "$dir" ]
)"
[ "$observed" = "$EXPECTED" ]
# A failed launch never opens the writer. Cleanup must still retire the blocked reader.
(
  source "$HERE/pairing-output.sh"
  trap cleanup_pairing_output EXIT
  prepare_pairing_output --print-pairing
  dir="$PAIRING_OUTPUT_DIR"
  cleanup_pairing_output
  [ ! -e "$dir" ]
)
echo 'Pairing pipe tests passed (output delivered, no persistent URI, failed-launch cleanup).'
