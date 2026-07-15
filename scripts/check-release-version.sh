#!/usr/bin/env bash
# Validates every version source that is shipped or shown to users. Run with an optional v-prefixed
# tag to also verify the release ref, for example: bash scripts/check-release-version.sh v0.9.0.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

gradle_version="$(sed -nE 's/^VERSION_NAME=([0-9]+\.[0-9]+\.[0-9]+)$/\1/p' gradle.properties)"
cargo_version="$(sed -nE 's/^version = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p' agent-rs/Cargo.toml | head -n1)"
plist_versions="$(sed -nE '/CFBundle(ShortVersionString|Version)/{n;s/.*<string>([0-9]+\.[0-9]+\.[0-9]+)<\/string>.*/\1/p;}' agent-rs/macos/Info.plist | sort -u)"
readme_version="$(sed -nE 's/.*dev\.warsha\.remoteble:client-sdk:([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' README.md | head -n1)"
kotlin_agent_version="$(sed -nE 's/.*RemoteBLE Agent ([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/di/AgentModule.kt | head -n1)"

test -n "$gradle_version" && test -n "$cargo_version" && test -n "$plist_versions" && test -n "$readme_version" && test -n "$kotlin_agent_version"
test "$gradle_version" = "$cargo_version"
test "$gradle_version" = "$plist_versions"
test "$gradle_version" = "$readme_version"
test "$gradle_version" = "$kotlin_agent_version"

if [[ $# -gt 0 ]]; then
  test "$1" = "v$gradle_version"
fi

printf 'Release version verified: %s\n' "$gradle_version"
