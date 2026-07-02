#!/usr/bin/env bash
#
# Build and run the native Rust RemoteBle agent (agent-rs).
#
# Self-bootstrapping: detects the host OS and installs whatever's missing — the Rust
# toolchain (via rustup) and OS-level build prerequisites — before building, so a bare
# checkout with nothing preinstalled still works from a single invocation.
#
# On macOS a bare `cargo run --bin agent-rs` aborts with SIGABRT: macOS TCC kills any
# process that touches CoreBluetooth without an NSBluetoothAlwaysUsageDescription in
# its main bundle, and the permission is only honored when the app is launched via
# LaunchServices. On macOS this script therefore wraps the compiled binary in a signed
# RemoteBleAgentRs.app whose Info.plist carries the key, and starts it with `open`.
# The FIRST launch shows a one-time "RemoteBleAgentRs would like to use Bluetooth"
# prompt — approve it (and re-run if needed).
#
# On Linux (and other POSIX systems) there's no TCC dance: the binary is built and
# run directly, talking to BlueZ over D-Bus.
#
# Usage:   agent-rs/run-agent-rs.sh [port]            (default 8080)
#          REMOTE_BLE_TOKEN=secret agent-rs/run-agent-rs.sh 8080
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${1:-8080}"
OS="$(uname -s)"

log() { echo "==> $*"; }

# ---------------------------------------------------------------------------
# 1. Rust toolchain — install via rustup if `cargo` isn't on PATH or in one of
#    the usual install locations.
# ---------------------------------------------------------------------------
find_cargo() {
  command -v cargo >/dev/null 2>&1 && return 0
  local candidate
  for candidate in "$HOME/.cargo/bin" /usr/local/bin /opt/homebrew/bin; do
    if [ -x "$candidate/cargo" ]; then
      export PATH="$candidate:$PATH"
      return 0
    fi
  done
  return 1
}

if ! find_cargo; then
  log "cargo not found — installing Rust via rustup (https://rustup.rs)…"
  if command -v curl >/dev/null 2>&1; then
    FETCH=(curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs)
  elif command -v wget >/dev/null 2>&1; then
    FETCH=(wget -qO- https://sh.rustup.rs)
  else
    echo "Neither curl nor wget is available — install one, or install Rust manually from https://rustup.rs" >&2
    exit 1
  fi
  "${FETCH[@]}" | sh -s -- -y --profile minimal --default-toolchain stable
  # shellcheck disable=SC1091
  source "$HOME/.cargo/env"
  find_cargo || { echo "rustup install completed but cargo is still not on PATH" >&2; exit 1; }
fi
log "Using $(cargo --version)"

# ---------------------------------------------------------------------------
# 2. OS-level build prerequisites
# ---------------------------------------------------------------------------
case "$OS" in
  Darwin)
    # cc/ld for the Rust build come from the Xcode Command Line Tools. Apple only
    # lets these be installed via a GUI confirmation, so we can kick it off but
    # can't finish it unattended.
    if ! xcode-select -p >/dev/null 2>&1; then
      log "Xcode Command Line Tools not found — requesting install…"
      xcode-select --install >/dev/null 2>&1 || true
      cat >&2 <<'EOF'
A "Install Command Line Tools" dialog should have opened. Finish that install,
then re-run this script.
EOF
      exit 1
    fi
    ;;
  Linux)
    # btleplug's Linux backend talks to BlueZ over D-Bus, so the build needs a C
    # toolchain, pkg-config, and the D-Bus development headers.
    need_cc=0; need_pkgconfig=0; need_dbus=0
    command -v cc >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1 || command -v clang >/dev/null 2>&1 || need_cc=1
    command -v pkg-config >/dev/null 2>&1 || need_pkgconfig=1
    if command -v pkg-config >/dev/null 2>&1; then
      pkg-config --exists dbus-1 || need_dbus=1
    else
      need_dbus=1
    fi
    if [ "$need_cc" = 1 ] || [ "$need_pkgconfig" = 1 ] || [ "$need_dbus" = 1 ]; then
      log "Installing Linux build prerequisites (C toolchain, pkg-config, D-Bus dev headers)…"
      SUDO=""
      [ "$(id -u)" -eq 0 ] || SUDO="sudo"
      if command -v apt-get >/dev/null 2>&1; then
        $SUDO apt-get update -y
        $SUDO apt-get install -y build-essential pkg-config libdbus-1-dev
      elif command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y gcc gcc-c++ make pkgconf-pkg-config dbus-devel
      elif command -v yum >/dev/null 2>&1; then
        $SUDO yum install -y gcc gcc-c++ make pkgconfig dbus-devel
      elif command -v pacman >/dev/null 2>&1; then
        $SUDO pacman -Sy --noconfirm base-devel pkgconf dbus
      elif command -v zypper >/dev/null 2>&1; then
        $SUDO zypper install -y gcc make pkg-config dbus-1-devel
      elif command -v apk >/dev/null 2>&1; then
        $SUDO apk add build-base pkgconf dbus-dev
      else
        echo "Unrecognized package manager — install a C toolchain, pkg-config, and D-Bus dev headers (e.g. libdbus-1-dev) manually, then re-run." >&2
        exit 1
      fi
    fi
    ;;
  *)
    log "Unrecognized OS '$OS' — attempting the build as-is; install a C toolchain and D-Bus dev headers manually if it fails."
    ;;
esac

# ---------------------------------------------------------------------------
# 3. Build
# ---------------------------------------------------------------------------
log "Building agent-rs (release)…"
( cd "$HERE" && cargo build --release --bin agent-rs )
BIN="$HERE/target/release/agent-rs"
[ -x "$BIN" ] || { echo "agent-rs binary not found at $BIN" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 4. Run
# ---------------------------------------------------------------------------
if [ "$OS" = "Darwin" ]; then
  OUT="$HERE/target/macos-app"
  APP="$OUT/RemoteBleAgentRs.app"
  LOG="$OUT/agent.log"

  log "Assembling RemoteBleAgentRs.app…"
  rm -rf "$APP"
  mkdir -p "$APP/Contents/MacOS"
  cp "$HERE/macos/Info.plist" "$APP/Contents/Info.plist"
  cp "$BIN" "$APP/Contents/MacOS/agent-rs"
  # Embed the Info.plist into the Mach-O too, so TCC sees it however it resolves the
  # responsible binary, then ad-hoc sign (TCC keys a permission grant to the signature).
  codesign -f -s - \
    --identifier com.warsha.remoteble.agent-rs \
    "$APP/Contents/MacOS/agent-rs" >/dev/null 2>&1 || true
  codesign -f -s - "$APP" >/dev/null 2>&1 || true

  log "Launching agent on ws://0.0.0.0:$PORT/agent (logs: $LOG)…"
  : > "$LOG"
  OPEN_ARGS=(-n "$APP" --stdout "$LOG" --stderr "$LOG"
    --env "RUST_LOG=${RUST_LOG:-agent_rs=debug,info}")
  [ -n "${REMOTE_BLE_TOKEN:-}" ] && OPEN_ARGS+=(--env "REMOTE_BLE_TOKEN=$REMOTE_BLE_TOKEN")
  open "${OPEN_ARGS[@]}" --args --port "$PORT"

  cleanup() { echo; echo "==> Stopping agent…"; pkill -f "RemoteBleAgentRs.app/Contents/MacOS/agent-rs" 2>/dev/null || true; }
  trap cleanup INT TERM EXIT
  log "Streaming logs (Ctrl-C to stop the agent)…"
  tail -f "$LOG"
else
  log "Launching agent on ws://0.0.0.0:$PORT/agent…"
  export RUST_LOG="${RUST_LOG:-agent_rs=debug,info}"
  exec "$BIN" --port "$PORT"
fi
