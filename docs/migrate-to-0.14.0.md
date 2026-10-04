# Migrate to RemoteBLE 0.14

> **Use 0.14.1.** 0.14.0 was tagged, and its GitHub Release and container image published, but it
> was never published to Maven Central: a final review found fixes worth having first. Everything
> below applies to 0.14.1 unchanged.

0.14 encrypts every agent reachable from the network ([#39](https://github.com/Yahia-Mohammad/remote-ble/issues/39)).
An agent serves `wss://` with a self-signed identity, clients pin its fingerprint, and a pairing
link or QR code hands a client the address, token and fingerprint together. Update the dependency
version:

```kotlin
dependencies {
    implementation("dev.warsha.remoteble:client-sdk:0.14.1")
}
```

**The wire protocol does not change**, and the SDK changes are additions. What can break is a
setup that serves cleartext `ws://` to the network: that now takes an explicit choice. Coming from a
release older than 0.13.0? Read [migrate-to-0.13.0.md](migrate-to-0.13.0.md) first, and the guides
it links.

## Desktop agents on a LAN address refuse cleartext

A JVM agent or `agent-rs` bound to a non-loopback address (`--bind`, `REMOTE_BLE_BIND`, `0.0.0.0`)
without `--tls` now refuses to start:

```
a non-loopback bind (0.0.0.0) would serve cleartext ws://: pass --tls (REMOTE_BLE_TLS=true), or set
REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true to keep cleartext
```

Pick one:

- **Encrypt it** (recommended): add `--tls` or `REMOTE_BLE_TLS=true`, then pair each client. The agent
  creates its identity on first start, logs its fingerprint, and prints the pairing link with
  `--print-pairing`. Both desktop agents share one identity file, so switching between them keeps
  the pin. See [agent.md](agent.md#built-in-tls-with-a-pinned-identity-all-agents).
- **Keep cleartext** on a network you trust: `REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true`. The agent starts
  and warns that the token and BLE traffic cross the network readable.

Loopback is unchanged: an agent on `127.0.0.1` still serves `ws://`, so `adb forward`, `iproxy` and
the [TLS proxy recipe](tls-proxy-recipe.md) keep working as they are.

## The `agent-rs` container serves `wss://`

The image sets `REMOTE_BLE_TLS=true` and keeps its identity at
`/var/lib/remoteble/agent-identity.pem`, a volume. Mount one so a recreated container keeps the
fingerprint its clients pinned:

```bash
docker run -v remoteble-identity:/var/lib/remoteble -e REMOTE_BLE_TOKEN=… -p 8080:8080 \
  ghcr.io/yahia-mohammad/remoteble-agent-rs:0.14.1 --print-pairing
```

Without a volume, every new container is a new identity and every client must pair again. To keep
the previous cleartext behaviour, set `REMOTE_BLE_TLS=false` and `REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true`.
`--print-pairing` names the container's own address; behind a published port, give clients the
host's address with the same token and fingerprint.

## Phone agents start encrypted

The Android and iOS agent apps now start with **Encrypt connections** on. Clients need the agent's
fingerprint, which **Show pairing code** hands them: scan the QR code with the client phone's camera
or copy the link. Switching encryption off is remembered, and the app says the connection is then
readable on the network.

Reinstalling an agent app now gives it a new identity, as Android always did: pair clients again
after a reinstall.

## Clients: connect to an encrypted agent

A `wss://` agent's certificate is self-signed, so a client trusts it by pin, not by a certificate
authority. From a pairing link:

```kotlin
val pairing = AgentPairing.parse(link)              // remoteble://host:port?token=…&fp=sha256:…
val transport = WebSocketAgentTransport(
    pairing.url,                                    // wss://host:port/agent
    scope,
    pairingWebSocketHttpClient(pairing),            // pinned to pairing.fingerprint
    authToken = { pairing.token },
)
```

Or with a fingerprint you already have: `pinnedWebSocketHttpClient(AgentFingerprint.parse("sha256:…"))`.
A different key fails with `AgentIdentityMismatchException` inside the TLS handshake, before the
token is sent, and the transport gives up instead of retrying: treat it as "the agent changed
identity, pair again", not as "unreachable". See [client-sdk.md](client-sdk.md).

On Android, `wss://` needs no cleartext network security config, so the
`CleartextTrafficNotPermittedException` of 0.13.0 goes away for an encrypted agent. An app targeting
API 37 needs the runtime `ACCESS_LOCAL_NETWORK` permission to reach an agent on the Wi-Fi at all;
without it the connection just times out. See [client-sdk.md](client-sdk.md).

## The dashboard is `https://`

An encrypted agent serves its dashboard as `https://` on the same port. A browser cannot pin, so the
first visit warns about a self-signed certificate. Compare the SHA-256 the warning shows with the
one the agent gives (`Certificate SHA-256 (as browsers show it)` in the desktop log, under the
fingerprint in the phone apps) before accepting it. Remote dashboard access stays off by default.
