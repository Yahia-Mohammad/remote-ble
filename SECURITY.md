# Security Policy

## Supported versions

RemoteBLE is pre-1.0 and released from a single line. Security fixes land on the
latest published version only.

| Version                | Supported |
|------------------------|-----------|
| Latest `0.x` release   | ✅        |
| Any earlier release    | ❌        |

## Reporting a vulnerability

Please report security issues **privately** — do not open a public issue.

Use GitHub's private vulnerability reporting:
[**Report a vulnerability**](https://github.com/Yahia-Mohammad/remote-ble/security/advisories/new).

You'll get an acknowledgement as soon as the report is triaged. Once a fix is
available and released, the advisory is published with credit to the reporter
(unless you prefer to remain anonymous).

## Security posture (by design)

Understanding what RemoteBLE does and does not protect helps scope reports:

- **Transport auth is a single optional bearer token**
  (`REMOTE_BLE_TOKEN` / `WebSocketAgentTransport.authToken`), enforced at the
  WebSocket handshake (a wrong/missing token is rejected with `401` before the
  connection upgrades). On the client the token is supplied through a **suspend
  provider** invoked per connection attempt (never cached), so an embedder can back it
  with short-lived/rotating credentials that refresh on reconnect. This is deliberately
  "a hook, not a framework" — richer identity/authorization is left to the embedding
  application.
- **Encryption is the default wherever the network can reach an agent.** Every
  agent serves `wss://` with a long-lived self-signed identity that clients pin by
  fingerprint, given to them by pairing (#39); a client pinning a different key fails
  inside the TLS handshake, before its token is sent. A desktop agent bound to a
  non-loopback address refuses to start in cleartext unless `--tls` is on or
  `REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true` says otherwise, and the phone agents start
  with **Encrypt connections** on. Cleartext `ws://` remains for loopback, behind a
  tunnel or the TLS proxy recipe, and wherever an operator explicitly chooses it; there
  the bearer token crosses the network readable. The pin protects the connection, not
  the token's secrecy on the device that shows it: a pairing QR code carries the token.
- **The phone agents (`android-agent`, `ios-agent`) are dev/test tools, not
  shipping builds.** They always require a token, because they listen on open Wi-Fi,
  and serve `wss://` unless encryption is switched off. Do not treat them as a
  hardened, internet-facing service.
- **`DeviceHandle` is opaque and agent-scoped** — clients never construct or
  parse it.

Reports about these documented, intentional boundaries are welcome as hardening
suggestions, but they are known trade-offs rather than vulnerabilities. Reports
about auth bypass, unauthenticated access where a token *is* configured, a client
accepting an agent key other than the one it pinned, memory safety, or
protocol-parsing flaws are exactly what we want to hear about.
