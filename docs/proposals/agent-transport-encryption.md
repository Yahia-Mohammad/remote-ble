# Encrypted LAN transport with a pinned agent identity

Decision record for [#39](https://github.com/Yahia-Mohammad/remote-ble/issues/39). **Accepted
2026-10-01: option A, TLS with a pinned self-signed certificate.** Nothing is implemented yet; the
phases in [§8](#8-phases) are the plan, and each one updates this record as it lands.

## 1. The problem

Both agents serve `ws://` in cleartext. The bearer token decides who may connect, but anyone on the
same network can read the token, every advertisement and every GATT value. The only encrypted setup
today is the [TLS proxy recipe](../tls-proxy-recipe.md), which needs a CA, a proxy and a
loopback-bound agent, none of which fits a phone agent listening on all interfaces.

The goal: a client reaches an agent on the LAN over an encrypted, authenticated channel with no CA,
domain or proxy, and without touching any trust store. The agent's identity is established once, at
pairing, and verified on every connection after that.

## 2. Decision

**`wss://` served by the agent itself, with a long-lived self-signed certificate whose public key the
client pins.** The agent's identity is the SHA-256 of the certificate's SubjectPublicKeyInfo (SPKI),
written `sha256:<64 lowercase hex>`. Pinning the key rather than the certificate lets an agent
reissue its certificate (new validity, new serial) without breaking existing pairings; only an
explicit identity reset changes the fingerprint.

The wire protocol does not change: same `/agent` endpoint, same frames, same protocol version, same
bearer token in the upgrade request. TLS sits underneath all of it.

## 3. Why not a Noise handshake (option B)

The full comparison is in the issue thread; the points that decided it:

- **The dashboard can only be protected by TLS.** It is an HTML page plus `/api/state`, loaded by a
  browser that cannot run a Noise handshake for the page itself. Under B the page and the operator
  token stay cleartext, which fails the issue's item 5.
- **B changes the protocol for everyone.** A version bump, a transition period where agents speak
  both modes, and an exact pattern/prologue/framing spec that every third-party client must
  implement. A needs only a WebSocket library with TLS and a pin hook, which every mainstream one
  has (`curl --pinnedpubkey` included).
- **B's costs were smaller than first stated, and that is recorded here so it is not re-argued.** The
  primitives exist for every Kotlin target: `cryptography-kotlin` 0.6.0 implements X25519 and
  ChaCha20-Poly1305 on the JDK, on CryptoKit (through its own Swift bridge) and on OpenSSL3. A JVM
  Noise implementation exists too (`org.signal.forks:noise-java`, last released 2024-02), though
  nothing for Kotlin/Native. B's real strengths were code sharing (one core in `commonMain` for agent
  and SDK) and, with `XXpsk3`, a token that is never transmitted even to an impostor. The second is
  why pinning is mandatory in the SDK under A ([§5.4](#54-client-sdk)).

## 4. Verified constraints

Checked against the libraries this build resolves (Ktor 3.5.1), not assumed:

| Fact | Consequence |
|---|---|
| Ktor's CIO **server** has no TLS on any platform, the JVM included: no TLS classes in the jar, and `startConnector` distinguishes only a Unix socket from plain TCP. | The Kotlin agents cannot get TLS from their engine. |
| `ktor-network-tls` implements only the **client** handshake (`TLSClientHandshake`; no server side). | It cannot wrap the agent's listening socket either. |
| The CIO **client** speaks TLS ≤ 1.2, with ECDHE-ECDSA/RSA AES-GCM suites, and takes a custom `X509TrustManager`. | Every agent MUST offer TLS 1.2 with an ECDHE-ECDSA-AES-GCM suite, alongside 1.3, or the JVM SDK cannot connect. |
| `FailedAuthLimiter` keys on `origin.remoteHost`, and the dashboard's own-device gate (`Dashboard.kt`) is sound only because that is the real TCP peer. | A TLS front that relays to a loopback listener MUST carry the real peer through ([§5.2](#52-kotlin-agents-jvm-android-ios)), or the dashboard opens to the whole network and all clients share one rate-limit bucket. |

## 5. Design

### 5.1 Identity

- **Key:** ECDSA P-256. Every party supports it: the JDK, Android Keystore, iOS `SecKey`, rustls, and
  the CIO client's ECDHE-ECDSA suites. Ed25519 would not reach the CIO client.
- **Certificate:** self-signed, created with the key on first start, validity far enough ahead that
  it never matters (clients pin the key and ignore validity and host name).
- **Storage**, beside each agent's existing secrets:

  | Agent | Where |
  |---|---|
  | Kotlin JVM | A PKCS#12 file under the per-user config directory (`$XDG_CONFIG_HOME/remoteble/`, `~/Library/Application Support/RemoteBLE/`, `%APPDATA%\RemoteBLE\`), overridable with `REMOTE_BLE_IDENTITY_FILE`. New: the desktop agents persist nothing today. |
  | Rust | The same file and variable, so the two desktop agents agree. |
  | Android | Android Keystore. Its key generator issues the self-signed certificate itself, and the private key never leaves the keystore. |
  | iOS | Keychain. iOS has no API to create a certificate, so the agent builds the DER and signs it with the key. |

- **Reset:** an explicit control on every agent (`--reset-identity` / `REMOTE_BLE_RESET_IDENTITY` on
  the desktop, a button in the phone apps) that discards the key. Every paired client then fails with
  an identity error, which is the point.

### 5.2 Kotlin agents (JVM, Android, iOS)

**An in-process TLS front relaying to CIO on loopback.** The agent keeps CIO and all of its routing;
CIO binds an ephemeral port on `127.0.0.1`, and the front owns the public port:

- **JVM and Android:** `SSLServerSocket` from the platform JSSE (Conscrypt on Android), relaying each
  accepted connection to CIO with two coroutines.
- **iOS:** a Network.framework `NWListener` with TLS options carrying the Keychain identity, through
  cinterop, relaying the same way.

**The real peer is carried by a registry, not a header.** For each relayed connection the front
records *its own outbound local port → the real peer address*. CIO's `origin.remotePort` for that
request is exactly that port, so one accessor resolves the real peer, and the limiter, the dashboard
gate and the monitor's client list all go through it. A port the registry does not know is a process
on this device connecting to the loopback listener directly. That is treated as local, exactly as
loopback is today. Nothing a remote party sends can influence the lookup, which is why this is
preferred over `X-Forwarded-For`-style headers.

**Rejected: swapping the JVM and Android engines to Netty**, which serves TLS natively with real peer
addresses. iOS needs the front regardless (no Netty on Kotlin/Native), so Netty would mean two TLS
designs to keep in parity, plus several MB on Android.

### 5.3 Rust agent

`tokio-rustls` in front of the existing `accept_hdr_async` in `transport/server.rs`, with `rcgen`
creating the certificate. The peer address is unaffected. TLS 1.2 and 1.3 both enabled
([§4](#4-verified-constraints)).

### 5.4 Client SDK

- **`AgentFingerprint`**, parsed from `sha256:<hex>`, and a per-platform
  **`pinnedWebSocketHttpClient(fingerprint)`** beside `defaultWebSocketHttpClient()`:
  - JVM: CIO with a trust manager that accepts exactly the pinned SPKI.
  - Android: OkHttp with an equivalent trust manager and a host-name verifier that defers to the pin.
    Agents are reached by IP address, so the pin is the identity.
  - Apple: Darwin's `handleChallenge`, evaluating the server trust against the pin.
- **`AgentIdentityMismatchException(expected, presented)`**: the pin check fails inside the TLS
  handshake, before any HTTP request, so the token is never sent to an impostor. The transport treats
  it like a cleartext refusal: `GAVE_UP` at once, no retry, the reason logged at ERROR.
- **Pinning is mandatory for a paired agent.** The pinned client is the only way the SDK talks to an
  agent whose pairing carried a fingerprint. An unpinned `wss://` stays available for the TLS proxy
  recipe, where a real CA vouches for the proxy.
- **`AgentPairing.parse(uri)`** → URL, token provider, fingerprint.

### 5.5 Pairing payload

From the issue:

```
remoteble://<host>:<port>?token=<token>&fp=sha256:<hex>
```

Query values percent-encoded. Shown as a QR code in the phone agents and on the dashboard. The
desktop agents print the **fingerprint** at startup, but print the full URI only on request
(`--print-pairing`), so the token does not land in logs.

### 5.6 Cleartext by explicit choice

- Loopback binds keep serving `ws://`, so `adb forward`, `iproxy` and the TLS proxy recipe are
  unchanged.
- A **non-loopback cleartext** bind fails to start unless `REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true`, the
  same shape as `REMOTE_BLE_ALLOW_INSECURE_LAN` for a missing token. This breaks existing LAN `ws://`
  setups, so it ships in a minor release with a migration guide.
- The phone agents default to TLS, with cleartext as an explicit, visible toggle.

### 5.7 Dashboard

Served on the same TLS port, as `https://`. Browsers cannot pin a self-signed certificate, so the
first visit shows a warning that the operator accepts. The agent UI therefore shows both the SPKI
fingerprint and the **certificate's** SHA-256, which is what browsers display. Remote dashboard access
stays off by default; when it is on, the operator token now travels encrypted.

## 6. Conformance additions

New scenarios for [`agent-conformance-spec.md`](../agent-conformance-spec.md), run against every
agent:

| Id | Scenario |
|---|---|
| `TLS-PIN-01` | A client pinning the agent's fingerprint connects, handshakes and runs an op over `wss://`. |
| `TLS-PIN-02` | A client pinning a different fingerprint fails with the identity error, and the agent never receives the upgrade request (so never sees the token). |
| `TLS-PIN-03` | The fingerprint is stable across a restart and changes after a reset. |
| `TLS-PIN-04` | Reconnect and lease resume work over `wss://` as over `ws://`. |
| `TLS-PIN-05` | A non-loopback cleartext bind is refused without the explicit opt-in; loopback `ws://` still works. |
| `TLS-PIN-06` | The CIO client (TLS 1.2) connects, proving the 1.2 suite requirement. |
| `TLS-PIN-07` | Kotlin agents: behind the front, the rate limiter and the dashboard's own-device gate see the real peer, not loopback. |

Hardware acceptance, per agent on its rig: a client on the same Wi-Fi pairs by scanning or pasting,
and a packet capture of a session shows no token, advertisement or GATT payload in the clear.

## 7. Documents this changes

[`protocol.md`](../protocol.md) and the conformance spec §3 (the TLS binding), `SECURITY.md`'s
"Security posture" (no longer "cleartext by design"), the
[parity table](../agent-parity-verification.md) (identity, TLS and pairing per agent), and the TLS
proxy recipe, which stays valid but is no longer the only encrypted option.

## 8. Phases

Each phase is independently mergeable and testable.

1. **Identity, Rust agent, Kotlin JVM agent and JVM client.** All CI-testable, including
   `TLS-PIN-01`–`07` against both desktop agents.
2. **Android agent and the OkHttp client.** Validated on Rig B's Pixel (Android 17).
3. **iOS agent and the Darwin client.** The largest piece: certificate construction, the Keychain
   identity and the `NWListener` front.
4. **Pairing:** URI, QR codes, `AgentPairing`, and scan-or-paste in `client-ui`.
5. **Defaults:** phones on TLS, the cleartext gate, and the documents in [§7](#7-documents-this-changes).
   Shipped with a migration guide in the next minor release.

## 9. Open questions

- **Android Keystore keys for a server-side `SSLServerSocket`.** A `KeyManager` over an
  `AndroidKeyStore` entry should work, since JSSE uses such keys for client certificates. Verify it on
  API 24 and 37 before phase 2 depends on it.
- **The iOS certificate.** It needs a minimal DER writer, and an identity assembled from a Keychain
  key plus certificate rather than a PKCS#12 import. Prototype it first, because phase 3 sizing
  depends on it.
- **Desktop identity location.** Whether the JVM and Rust agents should share one file by default,
  or keep separate files so the two can run side by side with distinct identities.
