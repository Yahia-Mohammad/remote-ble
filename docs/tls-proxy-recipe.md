# TLS-terminating reverse proxy — supported recipe (`TLS-PROXY-01`)

The agent speaks plain WebSocket. To expose it over `wss://`, put a TLS-terminating reverse proxy
in front of a loopback-bound agent. This is the recipe that
[`0.9.1-scenarios.md`](conformance/0.9.1-scenarios.md)'s `TLS-PROXY-01` requires, and the concrete
procedure behind [pr8-validation-plan.md](pr8-validation-plan.md)'s Rig C.

**Two rules that make or break the evidence:**

1. **Never disable certificate validation** in the test client. A client that skips validation
   passes case 3 without proving anything. Point the JVM at a truststore holding the proxy's CA
   instead — that is what the steps below do.
2. **Bind the agent to loopback.** The proxy is the only thing that should be reachable off-host;
   the agent behind it must not be independently reachable.

Prerequisites: `caddy` (or nginx), plus `openssl` and `keytool`, both already present on macOS and
most Linux hosts. Nothing is installed into a system or JDK trust store.

---

## 1 — Certificates

```bash
scripts/tls-proxy-certs.sh /tmp/remoteble-tls
```

This issues a throwaway CA and three leaves: a good one for `localhost`, one signed by a second
untrusted CA, and one valid for `other.example` — the last two exist so case 3 can prove the client
*rejects* what it should. It also writes `truststore.p12` containing only the trusted CA.

Keep the output directory out of the repository. It contains private keys.

> Deliberately **not** `mkcert -install`: that modifies the operator's system and JDK trust stores.
> A purpose-built truststore proves more and changes nothing outside the test.

## 2 — Proxy

```caddyfile
{
	auto_https off
	admin off
}

localhost:8443 {                          # good path — cases 1, 2, 4, 5
	tls /tmp/remoteble-tls/proxy.crt /tmp/remoteble-tls/proxy.key
	reverse_proxy 127.0.0.1:8080
}

localhost:8444 {                          # case 3a — untrusted CA
	tls /tmp/remoteble-tls/rogue-proxy.crt /tmp/remoteble-tls/rogue-proxy.key
	reverse_proxy 127.0.0.1:8080
}

localhost:8445 {                          # case 3b — hostname mismatch
	tls /tmp/remoteble-tls/mismatch.crt /tmp/remoteble-tls/mismatch.key
	reverse_proxy 127.0.0.1:8080
}
```

Caddy 2's `reverse_proxy` handles the WebSocket `Upgrade`/`Connection` dance natively and forwards
request headers — including `Authorization` — upstream unchanged. No extra directives are needed
for either; cases 1 and 2 are what verify that claim rather than assuming it.

> **Do not add `local_certs`.** Every site above supplies an explicit `tls` pair, so Caddy's
> internal CA is never needed — and enabling it makes Caddy try to install its own root into the
> system trust store (a sudo prompt on macOS).

```bash
caddy run --config Caddyfile
```

## 3 — Agent, loopback-bound

Run it with the simulated backend so the whole recipe needs no radio:

```bash
REMOTE_BLE_TOKEN=<secret> java -jar agent/build/libs/remoteble-agent-<version>-all.jar \
  8080 --bind 127.0.0.1 --simulate agent/simulation/sim-hrm.json
```

`--simulate` never constructs `EngineBleBackend`, so no CoreBluetooth call is made and the macOS
TCC workaround in [bringup.md](bringup.md) does not apply — a plain JVM is fine here.
For the real-radio re-confirmation, drop `--simulate` and use `agent/run-agent.sh` instead.

## 4 — Cases 1–3: upgrade, bearer forwarding, CA trust

```bash
TLS=/tmp/remoteble-tls
hs() { curl -sS --http1.1 -o /dev/null -w "%{http_code}\n" --cacert "$TLS/test-ca.crt" -m 8 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" "$@"; }

hs -H "Authorization: Bearer <secret>" https://localhost:8443/agent   # 101 — case 1 + 2
hs https://localhost:8443/agent                                       # 401 — case 2
hs -H "Authorization: Bearer wrong" https://localhost:8443/agent       # 401 — case 2

curl -sS --http1.1 -m 6 --cacert "$TLS/test-ca.crt"  https://localhost:8444/agent  # case 3a — must fail
curl -sS --http1.1 -m 6 --cacert "$TLS/test-ca.crt"  https://localhost:8445/agent  # case 3b — must fail
curl -sS --http1.1 -m 6 --cacert "$TLS/rogue-ca.crt" https://localhost:8443/agent  # control — must fail
```

> **`--http1.1` is required.** Without it curl negotiates HTTP/2 via ALPN, where the classic
> `Connection: Upgrade` handshake is invalid and the request fails with `400` for a reason that has
> nothing to do with the proxy. Ktor's CIO engine is HTTP/1.1, so the SDK itself is unaffected.

Case 2's evidence is the *difference* between the responses: if the proxy stripped `Authorization`,
the authenticated request would also return 401 instead of upgrading.

Case 3 must produce three failures. Distinct reasons matter — unknown issuer for 3a, subject-name
mismatch for 3b — and the control proves the trust anchor is what decides, not the hostname alone.

## 5 — Cases 4–5: reconnect and notification delivery

```bash
REMOTE_BLE_TOKEN=<secret> ./gradlew :e2e-runner:tlsProxyRun \
  -PtrustStore=/tmp/remoteble-tls/truststore.p12 -PtrustStorePassword=changeit \
  --args "wss://localhost:8443/agent \"Warsha HRM (sim)\" 30"
```

Restart the proxy once during the window. The runner reports per-notification inter-arrival gaps
and every transport transition, which is what separates a healthy long-lived stream from a proxy
that buffers or stalls it.

A pass looks like: gaps at the profile's notify interval, one outage, a backoff ladder capping at
2 s, a recovery, and the stream resuming within tens of milliseconds **without re-subscribing** —
that last part is reconcile-on-reconnect replaying the subscription through `wss://`.

## Recorded evidence

First full run — 2026-07-27, `7e044af`, macOS 26.5.2 / arm64, Caddy 2.11.4, JDK 17.0.14, agent
0.10.0 against the canonical `agent/simulation/sim-hrm.json`.

| Case | Result | Evidence |
|---|---|---|
| 1 — upgrade | pass | `101 Switching Protocols`; stock `ScanMain` connected and scanned over `wss://` unmodified |
| 2 — bearer forwarding | pass | valid → 101, absent → 401, wrong → 401, with the 401s logged agent-side |
| 3 — CA trust | pass | untrusted issuer, SAN mismatch, and wrong-anchor control all rejected; validation never disabled |
| 4 — reconnect | pass | drop at 18.2 s → backoff ladder capped at 2 s → recovery at 27.4 s → notification 42 ms later, no re-subscribe; payload sequence continued without reset or duplicate |
| 5 — notifications | pass | 8 notifications / 8 s at `min 1002 ms / max 1008 ms / mean 1005 ms` against a 1000 ms profile interval — no batching, stalls, or drops. A longer 30 s run with the case-4 outage folded in gave the same steady cadence either side of the gap |

Cases 1–4 are proxy properties and independent of the backend. Case 5 ran against the simulated
backend; re-confirm it against the live peripheral during Rig A so the notification evidence does
not rest on simulation alone.

> **Aside, if you also drive this rig with `ScanMain`:** the canonical `sim-hrm.json` uses handle id
> `sim-hrm-1`, which no host can parse as a Kable `Identifier`, so any client reading
> `RemoteAdvertisement.identifier` fails against it — substitute a UUID id for that. It does not
> affect this recipe: `tlsProxyRun` resolves the peripheral by advertised name and never touches
> `.identifier`. Tracked separately as a simulation-profile defect, not a proxy one.

## Production notes

- Terminate TLS at the proxy and keep the agent on loopback. The bearer token is still required:
  TLS protects the channel, it does not authenticate the client.
- Anything terminating TLS in front of the agent must forward `Upgrade`, `Connection`, and
  `Authorization`. nginx needs these set explicitly, unlike Caddy.
- Long-lived WebSocket frames are what misconfigured proxies break. Check proxy read/idle timeouts
  exceed the agent's liveness ping interval, or sessions will be reaped mid-stream.
