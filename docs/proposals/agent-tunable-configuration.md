# Making agent timeouts and limits tunable without a recompile

**Status:** proposal, not started — with one exception already landed, described below. Raised
2026-07-28 during Rig A hardware validation.

> **Precedent already set.** `REMOTE_BLE_WRITE_FAIL_FAST` (Kotlin agent, default `true`) shipped
> ahead of this proposal because it gates a *workaround for a backend defect*, and shipping that
> un-switchable would have meant the agent silently special-casing a vendor bug. It follows the
> shape recommended here: an `AgentConfig` field, a `REMOTE_BLE_*` environment variable, strict
> parsing that fails startup on a bad value rather than falling back, and a startup log line so the
> running behaviour is visible. Use it as the reference implementation for the rest of category A —
> and note it is Kotlin-only, so `agent-rs` parity is outstanding.
**Scope:** the Kotlin agent (`:agent`, all four hosts) and `agent-rs`. Not the client SDK.

## Why this matters

The agents are delivered as a fat JAR, a native binary, an OCI image, and two phone apps. None of
those is something an operator rebuilds. So every value that is a compile-time constant today is,
in practice, unchangeable in the field — and the deployment convenience that makes RemoteBLE
useful for testing is exactly what a "just recompile it" answer destroys.

This surfaced concretely with `GATT_OP_TIMEOUT`. It was introduced (see
[the write-error fix](../../CHANGELOG.md) and `EngineBleBackend.gattOp`) as a hard 10-second bound
on every ATT transaction, justified as *"kept below the client SDK's 15s default op timeout"*. But
that client timeout is **per-call adjustable** — `session.request(op, timeout = …)` — so the
coupling only holds for the default. A client that raises its timeout cannot ask the agent to wait
longer, and one that lowers it makes the agent's bound moot. Two values that must move together,
where only one of them can move.

Rig C found the same shape from the other direction: a TLS-terminating reverse proxy has its own
idle timeout, and the recipe in [tls-proxy-recipe.md](../tls-proxy-recipe.md) has to warn operators
to keep the proxy's timeout above the agent's ping period — advice they cannot act on from the
other side, because the ping period is a constant.

## Current configurable surface

**Kotlin agent** — `AgentConfig` fields, populated in `Main.kt` from CLI args and environment:

| Setting | CLI | Environment |
|---|---|---|
| Bind host | `--bind` | `REMOTE_BLE_BIND` |
| Port | `--port` / positional | — |
| Bearer token | — | `REMOTE_BLE_TOKEN` |
| Named credentials | — | `REMOTE_BLE_TOKENS` |
| Operator token | — | `REMOTE_BLE_OPERATOR_TOKEN` |
| Insecure LAN override | — | `REMOTE_BLE_ALLOW_INSECURE_LAN` |
| Lease grace | — | `REMOTE_BLE_LEASE_GRACE_MS` |
| Transport grace | — | `REMOTE_BLE_TRANSPORT_GRACE_MS` |
| Liveness probe interval | — | `REMOTE_BLE_LIVENESS_PROBE_MS` |
| Simulation profile | `--simulate` | `REMOTE_BLE_SIMULATE` |
| Log level | — | `REMOTE_BLE_LOG` |
| Exclusive mode | — | `REMOTE_BLE_EXCLUSIVE` |

`AgentConfig.maxConnections` exists as a field with a default of 4 but has **no** CLI flag or
environment variable — it is settable only by a caller constructing `AgentConfig` in code.

**`agent-rs`** — clap, which already gives each option a flag *and* an environment fallback:
`--bind`, `--port`, `--token`, `--tokens`, `--allow-insecure-lan`, `--lease-grace-ms`,
`--transport-grace-ms`, `--liveness-probe-ms`, `--strict-identifiers`, `--log-level`,
`--log-format`.

**Phone agents** — nothing. `android-agent`'s `MainActivity` constructs
`AgentConfig(bindHost = "0.0.0.0")` and takes defaults for everything else; the iOS agent calls
`AgentRunner()` with no configuration at all. Neither can set even the values the desktop agents
already expose, and neither can read environment variables in any practical way. Any solution that
is env-var-only leaves both phone hosts where they are today.

## Inventory, with triage

Not everything here should become a knob. Three categories.

### A. Should be tunable — deployment-dependent behaviour

| Value | Kotlin | Rust | Default | Why it needs to move |
|---|---|---|---|---|
| GATT op bound | `EngineBleBackend.GATT_OP_TIMEOUT` | `btleplug_impl::GATT_OP_TIMEOUT` | 10 s | Must track the client's op timeout, which is per-call adjustable. Slow peripherals and long connection intervals need headroom |
| Deep liveness probe bound | `EngineBleBackend.LIVENESS_PROBE_TIMEOUT` | `btleplug_impl::LIVENESS_PROBE_TIMEOUT` | 5 s | A slow peripheral fails a healthy-link probe and gets retired |
| WebSocket ping period | `AgentWebSocketServer.DEFAULT_PING_PERIOD` | `server::PING_PERIOD` | 15 s | Must sit under the idle timeout of any proxy or NAT in front of the agent |
| WebSocket pong/liveness timeout | `AgentWebSocketServer.DEFAULT_PONG_TIMEOUT` | `server::LIVENESS_TIMEOUT` | 40 s | High-latency or metered links need more tolerance |
| Notification delivery bound | `BleAgent.NOTIFICATION_DELIVERY_TIMEOUT` | — | 5 s | Bursty peripherals; also asymmetric today (no Rust counterpart — confirm) |
| Max connections | `AgentConfig.maxConnections` | — | 4 | Already a config field; just never exposed. A lab rig drives more than four peripherals |
| Max in-flight ops | `BleAgent.DEFAULT_MAX_INFLIGHT_COMMANDS` | `server::MAX_INFLIGHT_OPS` | 64 | Throughput ceiling for busy rigs |
| Active scans / observations | `MAX_ACTIVE_SCANS` (16), `MAX_ACTIVE_OBSERVATIONS` (128) | same | — | Multi-client rigs |
| Outbound channel capacities | — | `FRAME_CHANNEL_CAP` (512), `EVENT_CHANNEL_CAP` (128) | — | Memory-vs-shedding trade-off; interacts with `LIMIT-SLOW-01` |
| Scan batch window / size | `DEFAULT_SCAN_BATCH_WINDOW` (100 ms), `DEFAULT_SCAN_BATCH_MAX_SIZE` (16) | — | — | Latency-vs-chattiness on constrained links |
| Handle-translation cache | `HandleTranslator.MAX_ENTRIES` | `translate::MAX_ENTRIES` | 4096 | Memory bound on long-lived agents |

### B. Tunable, but only with an explicit warning — security posture

`FailedAuthLimiter.MAX_FAILURES_PER_PEER` (5), `MAX_FAILURES_GLOBAL` (64), `MAX_TRACKED_PEERS`
(256), and `agent-rs`'s `AUTH_FAILURE_WINDOW` (60 s). Raising these weakens brute-force resistance.
If exposed at all, they should be documented as a security control, and loosening them should log a
warning the way `REMOTE_BLE_ALLOW_INSECURE_LAN` already does.

### C. Must NOT be independently tunable — wire contract

These are asserted by the conformance suite and by *both* agents. Changing one on one side breaks
interoperability silently, which is worse than the inconvenience of a rebuild:

- `MAX_FRAME_BYTES` (1 MiB) — `LIMIT-FRAME-01`
- `MAX_WRITE_BYTES` (512), `MAX_SCAN_FILTERS` (64), `MIN_MTU` (23) / `MAX_MTU` (517) —
  `LIMIT-OP-01`, whose published operation limits the scenario table names explicitly
- `PROTOCOL_VERSION`, the close-reason strings
- `DEFAULT_ATT_MTU` (23) — a Bluetooth spec constant, not a policy
- The `SimulationProfile` schema bounds (`MAX_PERIPHERALS`, `MAX_VALUE_BYTES`, …) — input
  validation on an untrusted file, not deployment tuning
- `AgentMonitor.MAX_LOGS` (500) — dashboard ring buffer; no operational value in tuning

If any of these ever must change, it is a protocol version decision, not a configuration one.

## Delivery options

### 1. Environment variables

Already the dominant pattern in the Kotlin agent, and `agent-rs` gets them free from clap.

Good: zero new machinery; natural for the OCI image (`docker run -e …`), which is how Rig D
deploys; no file to mount or manage; trivially scriptable in CI.

Bad: no discoverability (nothing lists them but the source), no validation beyond what each parse
site does, unusable on the phone agents, and the Kotlin agent's current parsing is silently
lenient — `?.toLongOrNull() ?: default` means a typo'd value falls back to the default without
complaint.

### 2. CLI flags

`agent-rs` already does this properly: clap declares flag *and* env in one place, so `--help` is
the documentation and both surfaces stay in sync automatically.

Good: self-documenting; validation and typing for free; the obvious home for the Rust agent.

Bad: the Kotlin agent's hand-rolled `parseCli` would need a real parser or a lot of boilerplate to
match; still nothing for the phone agents; a long flag list gets unwieldy past ~15 options.

### 3. Config file (TOML/JSON/properties)

Good: scales past the point where flags and env vars get unwieldy; reviewable and version-
controllable, which matters for a lab rig you want reproducible; mounts cleanly into the container;
one obvious place to document every knob with its default; and it is the only mechanism that can
plausibly be shared verbatim between the Kotlin and Rust agents, which is worth something given
they must stay in parity.

Bad: a new format to define, parse, validate, and version in two languages; another file to ship
and locate; overkill if only a handful of values are ever exposed.

### 4. Dashboard UI

Both agents already serve a status dashboard behind a separate operator credential, and the phone
agents have a native Compose UI — the only surface a phone operator actually has.

Good: the sole realistic answer for `android-agent` / `ios-agent`; the operator credential already
exists as the authorization boundary.

Bad: turns a read-only observability surface into a control plane, which is a meaningful security
change; mutating a live agent raises restart-vs-apply semantics (a changed ping period mid-session
is not obviously safe); and it cannot help a headless container, so it can never be the *only*
mechanism.

### 5. Protocol negotiation (client asks the agent)

**Rejected.** A client must not set server-side resource bounds — that is a denial-of-service
lever, and it conflicts with the agent's role as the arbiter between multiple clients. The one
value with a genuine client relationship (`GATT_OP_TIMEOUT` vs the client's op timeout) is better
addressed by documenting the relationship and letting the operator set both.

## Recommendation

**Do the cheap, high-value thing first, and keep the two agents in lockstep.**

1. **Expose category A through the mechanism each agent already has** — env vars plus CLI flags,
   one canonical name per knob shared by both agents (`REMOTE_BLE_GATT_OP_MS`,
   `REMOTE_BLE_PING_PERIOD_MS`, …). For `agent-rs` this is a few clap fields. For the Kotlin agent
   it is `AgentConfig` fields plus `Main.kt` parsing, threaded into `EngineBleBackend` and
   `AgentWebSocketServer` — which currently read their own companion constants directly, so this is
   the real work: the values must move from constants into injected configuration.
2. **Fix the silent-fallback parsing** while touching it: an unparseable `REMOTE_BLE_*_MS` should
   fail startup with a clear message, not quietly use the default. Same posture as the bind-policy
   check.
3. **Give the phone agents a settings screen** covering the same knobs, since it is the only
   surface they have. This can follow later, but should not be forgotten — they are currently the
   least configurable hosts and the hardest to redeploy.
4. **Defer the config file** until the flag list actually becomes unwieldy. Revisit if category A
   grows past roughly a dozen exposed values, or if the container deployment (Rig D) turns out to
   want a mounted profile.
5. **Leave category C alone**, and add a comment at each of those constants saying why it is not
   configurable, so this proposal does not get re-litigated per-constant.

Whatever is chosen, document the defaults and their interactions in one place — particularly the
`GATT_OP_TIMEOUT` / client-op-timeout and ping-period / proxy-idle-timeout relationships, which are
the two that have already bitten.

## Open questions

- Should `NOTIFICATION_DELIVERY_TIMEOUT` have an `agent-rs` counterpart? It appears only on the
  Kotlin side; confirm whether that is a genuine parity gap or a difference in delivery design.
- Do any category A values need to be changeable *at runtime* rather than at startup? Startup-only
  is far simpler and probably sufficient; confirm before designing anything live-reloadable.
- `maxConnections` already exists as a config field but is unexposed — is 4 a deliberate policy
  default or an untuned placeholder?
