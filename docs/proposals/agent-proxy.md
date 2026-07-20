# AgentProxy — transparent multi-agent aggregation

**Status:** detailed future design; explicitly out of scope for 0.10.0

**Target:** a post-0.10.0 release behind a separate approval gate

**Wire:** RemoteBLE protocol v1 on both sides; no client SDK API change

**Primary constraint:** preserve RemoteBLE's minimal-impact Kable integration

## 1. Intent

AgentProxy presents several ordinary RemoteBLE agents as one ordinary RemoteBLE endpoint. Existing
applications change only the configured endpoint and credential:

```text
Kable application
  -> existing RemoteBLE client SDK
       -> one WebSocket /agent endpoint
            -> AgentProxy
                 -> agent A -> radio A
                 -> agent B -> radio B
                 -> agent C -> radio C
```

The application continues to use the existing `RemoteScanner`, `RemotePeripheral`, and Kable
`Scanner`/`Peripheral` APIs. It does not configure an `AgentRegistry`, receive an `AgentId`, select a
route, or understand upstream topology.

AgentProxy is therefore a deployment component, not a client SDK feature. Its purpose is transparent
radio aggregation for installations that accept proxy-defined routing policy.

Naming note: [agent-conformance-spec.md](../agent-conformance-spec.md) is the existing language-neutral wire
specification for any compatible client/agent. This proposal's `AgentProxy` is one concrete future
service that implements both sides of that contract.

## 2. Philosophy and invariants

1. Existing Kable-facing application code does not change.
2. The client owns one `AgentSession` and sees one protocol-v1 agent.
3. Every proxy-issued device handle maps to exactly one upstream agent and handle generation.
4. A peripheral's route is fixed when its proxy handle is minted; operations never roam silently.
5. Upstream handles are never treated as fleet-global identities and may collide freely.
6. Duplicate sightings are not deduplicated automatically. One physical device seen through two
   radios appears as two proxy-local devices.
7. The proxy does not weaken upstream authentication, lease isolation, limits, or cleanup semantics.
8. Success is reported only for work confirmed by the selected upstream agent.
9. Unsupported topology-wide semantics are omitted rather than approximated.
10. A proxy restart invalidates its routing table; clients must rescan, matching the documented
    post-agent-restart behavior for translated identifiers.

## 3. Non-goals

- No client-visible fleet API, route selection, or agent discovery.
- No physical-device identity service or cross-agent deduplication.
- No automatic best-RSSI selection or mid-connection failover.
- No cloud relay, account system, device management, or Internet-exposure claim.
- No change to Kable, `:client-sdk`, or the protocol-v1 schema.
- No shared upstream connection across mutually untrusted downstream clients in v1.
- No topology mutation within an established downstream protocol session.

Applications that need explicit route visibility, per-agent capabilities, or application-controlled
selection need a separate orchestration API; AgentProxy deliberately does not expose those concepts.

## 4. Implementation placement

The recommended implementation is a new JVM service/module, `:agent-proxy`:

- depends on `:protocol`, `:client-sdk`, coroutines, Ktor client, and Ktor server;
- contains no BLE backend and does not depend on `:agent` or Compose;
- uses the existing client session implementation for every upstream connection;
- implements the existing WebSocket agent boundary for downstream clients;
- ships as a standalone distribution/container only after the proposal is approved.

Kotlin/JVM is preferred because the mature upstream client session already implements handshake,
timeouts, reconnect, reconciliation, readiness, and credential rotation. Implementing the proxy in
Rust would first require a second complete RemoteBLE client session and would increase parity risk.

Before creating the module, extract only the reusable downstream server framing/auth pieces that do
not pull the BLE engine into the proxy. Do not make `:agent-proxy` depend on the mobile/Kable agent
composition root.

## 5. Configuration model

Configuration is operator-owned and loaded before serving downstream clients:

```yaml
schemaVersion: 1
proxyId: lab-west
listen:
  bind: 127.0.0.1
  port: 8080
  credentialsEnv: REMOTE_BLE_PROXY_TOKENS
upstreams:
  - id: android-bench
    url: wss://android-bench.example/agent
    tokenEnv: ANDROID_BENCH_TOKEN
  - id: pi-rack
    url: wss://pi-rack.example/agent
    tokenEnv: PI_RACK_TOKEN
scanStartupPolicy: all-required
downstreamGraceMs: 10000
```

Requirements:

- upstream IDs are stable, unique, bounded local configuration keys;
- secrets are referenced from environment/files, never embedded in printable configuration state;
- every upstream has an independent endpoint, trust configuration, and credential provider;
- downstream credentials are distinct from every upstream credential;
- configuration reload creates a new topology generation for new downstream sessions only;
- existing sessions keep their original topology snapshot until they close.

`all-required` is the v1 default: `scan.start` succeeds only after every topology-snapshot leg starts.
An optional operator-selected `available` policy may be explored later, but it must be visibly degraded
in proxy diagnostics and must never become an invisible client-side default.

## 6. Downstream identity and upstream session ownership

The downstream authenticated identity remains `(principal, stableClientId)`. For each such logical
client, AgentProxy owns one upstream `AgentSession` per configured upstream agent:

```text
ProxyClientContext(principal, stableClientId, topologyGeneration)
  -> upstream session A
  -> upstream session B
  -> handle routes
  -> scan routes
  -> observation routes
```

The upstream stable client ID is derived from `(proxy deployment ID, downstream principal,
downstream stable client ID, upstream ID)` using a keyed, bounded digest. This prevents accidental
collisions and avoids disclosing downstream identifiers to upstream logs. Upstream credentials still
select the authenticated upstream principal.

Per-downstream-client upstream sessions are intentional:

- upstream agents remain the authoritative lease/security boundary;
- two downstream clients cannot become one invisible upstream principal participant;
- the proxy does not need to reimplement peripheral lease arbitration;
- reconnect and reconciliation remain isolated per downstream client.

The cost is bounded `clients × upstreams` connections. Configuration must set hard ceilings for both
dimensions and reject excess downstream sessions before allocating upstream graphs.

On downstream transport loss, the `ProxyClientContext` and its upstream sessions survive only for the
configured grace window. A matching principal/stable ID may resume it. Explicit downstream shutdown,
grace expiry, credential revocation, or proxy shutdown closes every owned upstream session and joins
all work before releasing the context.

## 7. Collision-safe handle routing

AgentProxy mints a downstream handle for each `(upstream ID, upstream generation, upstream handle)`.
The client never sees the upstream handle directly.

```text
proxyHandle -> Route {
  topologyGeneration,
  upstreamId,
  upstreamSession,
  upstreamDeviceHandle
}
```

The minted value must satisfy the downstream client's negotiated identifier format. Reuse the
identifier-translation rules, but derive the proxy namespace from the full route key so identical
MAC/UUID/string handles from different agents cannot collide.

Mappings are stable for the lifetime of a resumable `ProxyClientContext`. They are evicted only after:

- the client context retires;
- the topology generation retires; or
- a bounded least-recently-used policy removes an unleased/unreferenced scan-only route.

Connected, observed, grace-held, or reconciliation-tracked routes are pinned. Eviction must never make
an active peripheral route to a different upstream.

The proxy does not merge duplicate physical sightings. Two routes may carry identical advertisement
content while retaining distinct proxy handles.

## 8. Protocol routing matrix

| Traffic | Proxy behavior |
|---|---|
| `ClientHello` | Negotiate once against the topology snapshot and proxy's conservative capability contract. |
| `ScanStart` | Allocate one upstream scan per eligible agent, forward identical filters, and fairly merge translated advertisements. |
| `ScanStop` | Stop and join every leg belonging to the downstream scan ID. |
| Device-bearing command | Resolve proxy handle, translate to the fixed upstream handle, execute on that upstream session, translate reply payload if required. |
| `ObserveStart` | Resolve the device route and record downstream `subId -> upstream session/subscription`. |
| `ObserveStop` | Use the subscription route table; reject unknown/replaced IDs consistently. |
| Device-bearing event | Translate its upstream device handle back to the pinned proxy handle. |
| Scan/batch event | Translate every advertisement route before emitting downstream. |
| Notification event | Translate upstream subscription ID to the owning downstream subscription ID. |
| `SlotState` | Not forwarded in v1; topology-wide slot aggregation is not a truthful per-agent capability. |

All correlation, scan, and subscription IDs are scoped independently on each upstream session. The
proxy never forwards a downstream numeric ID blindly where it could collide with another leg.

## 9. Capability and handshake policy

Protocol v1 negotiates capabilities per connection, not per device. A transparent proxy therefore
cannot expose a union of heterogeneous upstream capabilities truthfully.

AgentProxy advertises:

1. proxy-implemented capabilities such as downstream identifier translation; plus
2. the intersection of route-level capabilities that the proxy can preserve across every configured
   eligible upstream in the topology snapshot.

Agent-wide capabilities that cannot be attributed or aggregated truthfully—initially `slots`—are
omitted even if every upstream advertises them. A capability contract may be declared in configuration
to avoid blocking startup on an unavailable agent, but every connected upstream must be checked
against it; a mismatch makes that upstream ineligible and fails `all-required` startup.

Capabilities remain frozen for the downstream session because protocol v1 has no renegotiation. New
or replaced upstreams join only new downstream topology generations.

## 10. Scan, degradation, and failure semantics

- A downstream scan collection snapshots the context's upstream sessions.
- Merge queues are bounded per leg and drained fairly; a noisy radio cannot starve another.
- Advertisement loss/coalescing follows the existing published overload policy.
- Initial `all-required` startup failure stops already-started legs and returns one safe error.
- A leg lost after startup stops contributing advertisements. The proxy continues other legs because
  protocol v1 has no route-failure event; the degradation is exposed in operator logs/metrics.
- If all scan legs terminate, the proxy stops the logical scan and records a terminal diagnostic.
- No failure message includes URLs containing credentials, tokens, or raw downstream identity.

The inability to report per-leg runtime failure to an unchanged client is a deliberate transparency
tradeoff and must be prominent in user documentation. Adding route diagnostics to the client would be
a separate protocol/API proposal.

## 11. Reconnect and lifecycle behavior

- Downstream reconnect within grace reuses the same `ProxyClientContext`, routes, and upstream
  sessions; client reconciliation sees stable proxy handles.
- Upstream reconnect is handled by its existing `AgentSession` and reconciliation graph.
- A failed upstream prerequisite only degrades operations routed to that upstream.
- The proxy never moves an existing peripheral to another agent after failure.
- Upstream agent restart may invalidate that route's translated handle; it surfaces as the existing
  rescan-required boundary.
- Proxy restart loses all routing state; all downstream clients reconnect and rescan.
- Shutdown is structured: stop accepting downstream sessions, retire contexts, close upstream
  sessions concurrently under a bound, join writers/readers, then exit.

## 12. Security and resource boundaries

- Safe-bind defaults and non-loopback credential requirements match reference agents.
- TLS is terminated by the documented reverse-proxy/VPN deployment unless native server TLS is added
  separately.
- Downstream and upstream failed-auth limiters have independent bounded state.
- Hard limits cover downstream clients, upstreams per context, concurrent scans/observations,
  pending commands, route mappings, frame size, merge queues, events, and grace-held contexts.
- Proxy-generated stable IDs and handles use keyed derivation so local labels/identities are not
  reversible from upstream logs.
- Operator diagnostics are separately authenticated and never expose credentials.
- One downstream client cannot stop another client's scans, observations, leases, or context.

## 13. Implementation sequence

| Phase | Deliverable | Exit gate |
|---|---|---|
| P0 | Protocol routing matrix, topology/capability ADR, compile spike | Prove unchanged client SDK can execute the full supported surface through one proxy endpoint |
| P1 | New JVM module, config parser, upstream connector, safe startup | Two fake upstreams connect with independent credentials and bounded ownership |
| P2 | Downstream auth, client contexts, stable identity derivation, grace lifecycle | Two downstream clients remain isolated; reconnect/expiry/close leaves no session work |
| P3 | Scan fan-out, fair bounded merge, proxy-handle namespace | Identical upstream handles remain distinct; filters/cancellation/flood tests pass |
| P4 | Device operations, observations, event/reply translation | Full GATT surface routes only to the selected upstream; ID collisions are harmless |
| P5 | Capability contract, degradation, metrics/logs, hostile-input limits | Heterogeneous/unavailable/malicious upstream matrix is truthful and bounded |
| P6 | Real-socket Kotlin/Rust E2E, packaging, docs, hardware exercise | Unchanged sample Kable app switches only its endpoint and passes through both agents |

Each behavior phase requires cancellation, cleanup, principal-isolation, and resource-limit tests.
The proxy must run the same protocol conformance scenarios applicable to a reference agent.

## 14. Acceptance criteria

- The documented Kable sample changes only URL/credential when moving from one agent to AgentProxy.
- No public `:client-sdk` type or method is required for proxy use.
- Identical handles from two upstream agents never collide or cross-route.
- Every device operation and event remains bound to its originating upstream route.
- Two downstream principals cannot share or interfere with upstream leases.
- Downstream reconnect within grace preserves routes; proxy restart requires rescan.
- Capability claims are conservative and never a topology union.
- Scan merge and all mapping/session/task state remain bounded under flood and churn.
- Kotlin and Rust reference agents both pass as upstreams in the same proxy run.
- Secrets and full credential-bearing URLs are absent from logs, errors, metrics, and artifacts.

## 15. Decision gate before implementation

AgentProxy should be implemented only after a concrete deployment needs transparent multi-radio
aggregation and accepts these tradeoffs:

- duplicate advertisements remain visible;
- route choice is proxy-owned and not application-controlled;
- per-route capabilities and failures are not visible through unchanged protocol-v1 APIs;
- proxy restart requires rescan; and
- operational complexity moves from the Kable application into a stateful service.

Until that need exists, RemoteBLE remains intentionally one client session to one agent endpoint.
