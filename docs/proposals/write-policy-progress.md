# U7 write policy: completed

Completed on `feat/write-policy`, 2026-08-06. The per-principal write policy designed in
[`agent-write-policy.md`](agent-write-policy.md) is now enforced by both reference agents; this
note records the delivered contract rather than a session handoff.

## Delivered behavior

- `write.policy` is an agent-level capability. `POLICY_DENIED` is non-transient and emitted only
  after that capability is negotiated; clients without it receive `INVALID_REQUEST` instead.
- `REMOTE_BLE_POLICY_FILE` (and Rust's `--policy-file`) loads once before the backend or listener
  starts. An unset path remains permissive; a blank or whitespace-only path is also permissive but
  logs a startup warning. A nonblank configured file denies unlisted or empty principals.
- Both parsers reject malformed JSON, unknown fields, unsupported versions, unknown principals,
  negative bounds, and signed-32-bit overflow. `maximumBytes: null` is unlimited; `0` permits only
  an empty payload, while the normal 512-byte operation ceiling still applies.
- Characteristic rules match service/characteristic, size, and response mode. Descriptor rules
  independently match service, characteristic, **and descriptor UUID**; all fields accept only
  explicit `"*"` wildcarding and otherwise compare case-insensitively against the wire form.
- The check runs after lease authorization for writes, descriptor writes, pairing, and unpairing.
  Rust keeps explicit pairing arms: a permitted request reaches `UNSUPPORTED` until its backend
  implements pairing, while a denied request is refused first.
- `agent.status.settings.writePolicyEnforced` reports whether this process loaded a nonblank policy
  source.

## Verification

- Kotlin policy-engine, dispatch, startup-loader, and independent-client tests cover strict schema
  parsing, descriptor scope, byte bounds, capability fallback, busy-before-policy ordering, blank
  path warnings, and the shared SDK/raw-WebSocket allowed-and-denied matrix against the production
  JVM agent.
- Rust policy-engine, loader, dispatch, and real-WebSocket-handshake tests cover the same contract
  across two authenticated principals.
- The unavailable `remoteble-tools` repository is not a test dependency here. A raw WebSocket/CBOR
  client is the independent in-repo substitute; the packaged CLI matrix remains that repository's
  integration responsibility.

Final verification: `./gradlew build` (**562 tests** across the current Gradle XML results),
`cargo test` (**175 passed**), `cargo clippy --all-targets`, `cargo fmt --check`, and
`git diff --check`. Blank `REMOTE_BLE_POLICY_FILE` and Rust `--policy-file ''` were also exercised
against the Rust binary; both logged the permissive fallback before backend initialization.

## Remaining scope

Duplicate JSON-member rejection is deferred hardening. Portable policy files must use unique member
names: Kotlin can retain a duplicate's last value, Rust rejects duplicate DTO fields, and duplicate
principal-map behavior is not guaranteed. Phone-agent provisioning and live policy reload remain
deliberately deferred design work.

**Shipped in 0.11.0.** This branch was merged into `feat/cli-readiness` and landed on `main` via
PR #9; the optional `device` field on both rule kinds was added during that merge and is described
in [`cli-readiness-progress.md`](cli-readiness-progress.md). The deferrals above are unchanged.
