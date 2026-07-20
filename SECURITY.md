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
- **The phone agents (`android-agent`, `ios-agent`) are dev/test tools, not
  shipping builds.** They serve `ws://` in cleartext and always require a token
  (auto-generated if left blank) because they listen on open Wi-Fi. Do not treat
  them as a hardened, internet-facing service.
- **`DeviceHandle` is opaque and agent-scoped** — clients never construct or
  parse it.

Reports about these documented, intentional boundaries are welcome as hardening
suggestions, but they are known trade-offs rather than vulnerabilities. Reports
about auth bypass, unauthenticated access where a token *is* configured, memory
safety, or protocol-parsing flaws are exactly what we want to hear about.
