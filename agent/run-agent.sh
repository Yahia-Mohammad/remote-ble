#!/usr/bin/env bash
#
# Build and run the RemoteBle agent on macOS with a working CoreBluetooth radio.
#
# `./gradlew :agent:jvmRun` aborts with SIGABRT: macOS TCC kills any process that
# touches CoreBluetooth without an NSBluetoothAlwaysUsageDescription in the main
# bundle, and the request is only honored when the app is launched via LaunchServices.
# This script compiles a tiny JNI launcher (agent/macos-launcher/launcher.c), wraps it
# in a signed RemoteBleAgent.app whose Info.plist carries the key, and starts it with
# `open`. The launcher also puts a menu bar status item up (agent/macos-launcher/
# MenuBar.swift) so it's visible at a glance whether the agent is running. See
# agent/macos-launcher/launcher.c for the full rationale.
#
# Usage:   agent/run-agent.sh [port]            (default 8080)
#          REMOTE_BLE_TOKEN=secret agent/run-agent.sh 8080
#
set -euo pipefail

PORT="${1:-8080}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="$HERE/macos-launcher"
OUT="$HERE/build/macos-app"
APP="$OUT/RemoteBleAgent.app"
LOG="$OUT/agent.log"

# This script is inherently macOS-only (CoreBluetooth + clang/swiftc/codesign), so
# there's no cross-platform bootstrap to do here — just fail fast with an actionable
# message instead of a cryptic error partway through the build.
if [ "$(uname -s)" != "Darwin" ]; then
  echo "agent/run-agent.sh only runs on macOS (needs CoreBluetooth, clang, swiftc, codesign)." >&2
  exit 1
fi

if ! xcode-select -p >/dev/null 2>&1; then
  echo "Xcode Command Line Tools not found (need clang/swiftc/codesign) — install with: xcode-select --install" >&2
  exit 1
fi

JAVA_HOME_17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_17}"
if [ -z "$JAVA_HOME" ]; then
  cat >&2 <<'EOF'
No JDK 17 found (checked `/usr/libexec/java_home -v 17` and $JAVA_HOME). Install
one, then re-run — for example:
  brew install --cask temurin@17
  sdk install java 17.0.13-tem        # https://sdkman.io
EOF
  exit 1
fi
LIBJVM="$JAVA_HOME/lib/server/libjvm.dylib"
[ -f "$LIBJVM" ] || { echo "libjvm.dylib not found under $JAVA_HOME — is this a JDK (not just a JRE)?" >&2; exit 1; }

echo "==> Resolving agent classpath (Gradle)…"
CP="$("$ROOT/gradlew" -q --console=plain :agent:printJvmRuntimeClasspath | tail -1)"
[ -n "$CP" ] || { echo "empty classpath from Gradle" >&2; exit 1; }

echo "==> Building RemoteBleAgent.app…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$SRC/Info.plist" "$APP/Contents/Info.plist"
# launcher.c: plain C JNI bootstrap (no Cocoa interop needed). MenuBar.swift: the menu
# bar status item, calling back into launcher.c's exported `agent_menu_run`. Compiled
# separately, then linked together with swiftc so the Swift runtime is wired in correctly.
clang -c "$SRC/launcher.c" -o "$OUT/launcher.o" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin"
swiftc -c -parse-as-library "$SRC/MenuBar.swift" -o "$OUT/MenuBar.o"
swiftc "$OUT/launcher.o" "$OUT/MenuBar.o" -o "$APP/Contents/MacOS/agent-launcher" \
  -framework Cocoa \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$SRC/Info.plist"
codesign -f -s - "$APP/Contents/MacOS/agent-launcher" >/dev/null 2>&1
codesign -f -s - "$APP" >/dev/null 2>&1

echo "==> Launching agent on ws://0.0.0.0:$PORT/agent (logs: $LOG)…"
: > "$LOG"
OPEN_ARGS=(-n "$APP" --stdout "$LOG" --stderr "$LOG"
  --env "AGENT_LIBJVM=$LIBJVM" --env "AGENT_CP=$CP")
# `open` starts the app through LaunchServices, which does NOT inherit this shell's environment —
# only what's passed with --env reaches the agent. Forward every REMOTE_BLE_* variable rather than
# an allowlist: the agent reads a growing set of them (token, liveness interval, grace windows,
# log level, REMOTE_BLE_WRITE_FAIL_FAST), and an allowlist silently drops the ones it hasn't been
# taught, which looks exactly like the setting having no effect.
while IFS='=' read -r name _; do
  OPEN_ARGS+=(--env "$name=${!name}")
done < <(env | grep '^REMOTE_BLE_[A-Z0-9_]*=' || true)
open "${OPEN_ARGS[@]}" --args dev/warsha/remoteble/agent/MainKt "$PORT"

MATCH="RemoteBleAgent.app/Contents/MacOS/agent-launcher"

# `open` hands off to LaunchServices and returns immediately, so the launcher isn't a
# child of this script — `wait` can't block on it. Poll for its PID instead, so we can
# later poll for it exiting (e.g. via the menu bar's "Quit Agent", not just our own
# Ctrl-C) rather than tailing the log forever regardless of whether the agent is alive.
LAUNCHER_PID=""
for _ in $(seq 1 20); do
  LAUNCHER_PID="$(pgrep -f "$MATCH" | head -1)"
  [ -n "$LAUNCHER_PID" ] && break
  sleep 0.25
done
[ -n "$LAUNCHER_PID" ] || { echo "agent-launcher process not found after launch" >&2; exit 1; }

tail -f "$LOG" &
TAIL_PID=$!

INTERRUPTED=0
on_signal() {
  INTERRUPTED=1
  echo
  echo "==> Stopping agent…"
  pkill -f "$MATCH" 2>/dev/null || true
}
trap on_signal INT TERM
trap 'kill "$TAIL_PID" 2>/dev/null || true; pkill -f "$MATCH" 2>/dev/null || true' EXIT

echo "==> Streaming logs (Ctrl-C to stop the agent, or choose Quit Agent from the menu bar)…"
while kill -0 "$LAUNCHER_PID" 2>/dev/null; do sleep 0.5; done
[ "$INTERRUPTED" = 1 ] || echo "==> Agent exited (e.g. via Quit Agent)."
