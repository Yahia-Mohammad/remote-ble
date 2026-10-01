# Encrypted LAN transport with a pinned agent identity

Decision record for [#39](https://github.com/Yahia-Mohammad/remote-ble/issues/39). **Accepted
2026-10-01: option A, TLS with a pinned self-signed certificate.** The phases in [§8](#8-phases) are
the plan, and each one updates this record as it lands. **Phase 1 complete (2026-10-01)**: the identity,
the JVM agent's TLS front with the peer registry, the Rust agent's rustls listener, and the SDK's
pinned JVM client, all behind `--tls`, with the binding and its scenarios in the conformance spec
([§10](#10-progress)).

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
| The CIO **client** speaks TLS ≤ 1.2, with ECDHE-ECDSA/RSA AES-GCM suites, and takes a custom `X509TrustManager`. | Every agent MUST offer TLS 1.2 with an ECDHE-ECDSA-AES-GCM suite, or the JVM SDK cannot connect, and 1.3 alongside where the platform has it (Android before 10 has no 1.3 server). |
| The CIO **client** verifies the TLS server name against the certificate even when a custom trust manager accepts it (`TLSClientHandshake` calls `verifyHostnameInCertificate` whenever `serverName` is set, and the engine defaults it to the URL host). | Agents are reached by changing IP addresses their certificates cannot name, so every agent certificate carries the fixed name `agent.remoteble.invalid` and pinning clients present it ([§5.1](#51-identity)). |
| `FailedAuthLimiter` keys on `origin.remoteHost`, and the dashboard's own-device gate (`Dashboard.kt`) is sound only because that is the real TCP peer. | A TLS front that relays to a loopback listener MUST carry the real peer through ([§5.2](#52-kotlin-agents-jvm-android-ios)), or the dashboard opens to the whole network and all clients share one rate-limit bucket. |

## 5. Design

### 5.1 Identity

- **Key:** ECDSA P-256. Every party supports it: the JDK, Android Keystore, iOS `SecKey`, rustls, and
  the CIO client's ECDHE-ECDSA suites. Ed25519 would not reach the CIO client.
- **Certificate:** self-signed, created with the key on first start, with no well-defined expiry
  (`99991231235959Z`, RFC 5280) since clients pin the key and ignore validity. It carries a SAN of
  `agent.remoteble.invalid` (reserved TLD, so never a real host) for TLS stacks that verify the
  server name regardless of the trust decision, plus critical `CA:FALSE`, `digitalSignature` and
  `serverAuth`. Built by a common DER writer (`SelfSignedCertificate`), since neither the JDK nor
  iOS can create a certificate.
- **Storage**, beside each agent's existing secrets:

  | Agent | Where |
  |---|---|
  | Kotlin JVM | `agent-identity.pem` (PKCS#8 key + certificate, owner-only permissions) under the per-user config directory (`$XDG_CONFIG_HOME/remoteble/`, `~/Library/Application Support/RemoteBLE/`, `%APPDATA%\RemoteBLE\`), overridable with `REMOTE_BLE_IDENTITY_FILE`. PEM rather than PKCS#12, so no keystore password is needed and Rust reads it natively. New: the desktop agents persisted nothing before. |
  | Rust | The same file, format and variable, so the two desktop agents on one host present one identity. |
  | Android | Android Keystore; the private key never leaves it. The keystore's generator issues a self-signed certificate, but one without the SAN, so the agent builds its own with `SelfSignedCertificate`, signs it with the keystore key, and stores it over the generated one. |
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

Added to [`agent-conformance-spec.md`](../agent-conformance-spec.md) §3.1, which names each agent's
adapter; run against every agent:

| Id | Scenario |
|---|---|
| `TLS-PIN-01` | A client pinning the agent's fingerprint connects, handshakes and runs an op over `wss://`. |
| `TLS-PIN-02` | A client pinning a different fingerprint fails with the identity error, and the agent never receives the upgrade request (so never sees the token). |
| `TLS-PIN-03` | The fingerprint is stable across a restart and changes after a reset. |
| `TLS-PIN-04` | Reconnect and lease resume work over `wss://` as over `ws://`. |
| `TLS-PIN-05` | A non-loopback cleartext bind is refused without the explicit opt-in; loopback `ws://` still works. Lands with the gate itself, in phase 5. |
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
   `TLS-PIN-01`–`04`, `06` and `07` against both desktop agents (`05` needs the phase 5 gate).
2. **Android agent and the OkHttp client.** Validated on Rig B's Pixel (Android 17).
3. **iOS agent and the Darwin client.** The largest piece: certificate construction, the Keychain
   identity and the `NWListener` front.
4. **Pairing:** URI, QR codes, `AgentPairing`, and scan-or-paste in `client-ui`.
5. **Defaults:** phones on TLS, the cleartext gate, and the documents in [§7](#7-documents-this-changes).
   Shipped with a migration guide in the next minor release.

## 9. Open questions

- ~~**Android Keystore keys for a server-side `SSLServerSocket`.**~~ Settled in phase 2: they work,
  verified on API 24, 30, 36 and 37 ([§10](#10-progress)). The front hands Conscrypt the keystore key
  through its own `X509ExtendedKeyManager`, since a keystore key has no encoding to put in an
  in-memory keystore, and the key allows the `NONE` digest, because Conscrypt hashes the handshake
  itself and asks the keystore to sign the digest raw.
- **The iOS certificate.** It needs a minimal DER writer, and an identity assembled from a Keychain
  key plus certificate rather than a PKCS#12 import. Prototype it first, because phase 3 sizing
  depends on it.
- ~~**Desktop identity location.**~~ Settled in phase 1: one PEM file, one default path, shared by
  the JVM and Rust agents. Two agents on one host are one machine, so one identity is the honest
  answer; `REMOTE_BLE_IDENTITY_FILE` separates them when that is really wanted.

## 10. Progress

**Phase 1, Kotlin half (2026-10-01).** `AgentFingerprint` and `AGENT_TLS_SERVER_NAME` in
`:protocol`; `SelfSignedCertificate` (common), `AgentIdentityStore` and `JsseTlsFront` (JVM) in
`:agent`, with the peer registry wired through `ApplicationCall.peer`; `pinnedWebSocketHttpClient`
(JVM) and `AgentIdentityMismatchException` in `:client-sdk`, the mismatch treated as terminal like a
cleartext refusal. The JVM agent serves `wss://` with `--tls`.

Evidence: `TlsPinningEndToEndTest` covers `TLS-PIN-01`, `02`, `03`, `06` and `07` against the real
front with the CIO client. `07` was mutation-checked: with the registry lookup removed, a LAN request
reached the dashboard (200 instead of 404) and the monitor recorded the relay's address. The
certificate parses and verifies under the JDK and OpenSSL, whose SPKI digest matches the agent's
fingerprint; `openssl s_client` negotiates ECDHE-ECDSA AES-GCM on both TLS 1.2 and 1.3; `curl
--pinnedpubkey` gets 401 without the token and 101 with it, and aborts on a wrong pin.

**Phase 1, Rust half (2026-10-01).** `transport/identity.rs` creates and loads the same PEM file at
the same default path, with `rcgen`; the fingerprint is taken from the certificate's SPKI, and a file
whose key does not belong to its certificate is refused. `run_on` performs the TLS handshake on each
connection's own task, bounded at 10 s, before the unchanged upgrade path. Every TLS crate is held to
`ring`, because rustls's default `aws-lc-rs` links `aws-lc-sys`, whose OpenSSL licence `deny.toml`
refuses.

Evidence: `tls_pin_01_06_*` (a pinning rustls client upgrades over 1.3, and over 1.2 alone), `tls_pin_02_*`
(a wrong pin fails before the agent completes the handshake), and `tls_accept_loop_*` (the real accept
loop over TCP keeps serving pinned clients after refusals, and gives cleartext no upgrade on the TLS
port). `cargo deny check advisories licenses sources` passes.

**The cross-agent check found a real defect.** A file written by either desktop agent must load in the
other. It did not: the JDK encodes an EC PKCS#8 key without RFC 5915's optional public key, and `ring`
refuses such a key, so a JVM-created identity would have stopped the Rust agent's TLS. The JVM agent now
writes the public key. Rechecked both ways, with OpenSSL agreeing on both fingerprints.

**Phase 1 closed (2026-10-01).** `TLS-PIN-04` has adapters on both agents: on Kotlin, a same-port
restart where the pinned client reconnects and its subscription resumes, plus a lease held through a
transport drop that the same principal and client id resume while another principal is refused; on
Rust, the same lease case over pinned TLS. The binding's normative rules and the `TLS-PIN-*` table,
with each agent's adapter, are in the conformance spec §3.1. `TLS-PIN-05` stays pending with the
phase 5 gate.

Next is phase 2: the Android agent and the OkHttp client, starting with the Android Keystore question
in [§9](#9-open-questions).

**Phase 2, Android agent (2026-10-01).** `JsseTlsFront` and `AgentTlsIdentity` moved to a `jsseMain`
source set shared by the JVM and Android, and the front now offers the identity through its own key
manager rather than an in-memory PKCS#12 store. `AndroidAgentIdentityStore` generates the P-256 key in
Android Keystore and stores the agent's own certificate over the generated one. The app's **Encrypt
connections (wss://)** switch, off by default until phase 5, shows the fingerprint and offers a confirmed
**New identity**. `:e2e-runner:pinRun` is the hardware check: the SDK's pinned client connects and scans
through a live agent, then a wrong pin must be refused at once.

Evidence, with `pinRun`, OpenSSL and the app's own screens:

| Device | TLS | `pinRun` | Also |
|---|---|---|---|
| Emulator, API 24 (Android 7.0) | 1.2 ECDHE-ECDSA AES-GCM; 1.3 refused (no platform support) | Pass | Keystore kept the agent certificate over the generated one |
| Emulator, API 30 | 1.3 and 1.2 | Pass, 21 advertisements | |
| Emulator, API 36 | 1.3 and 1.2; SPKI digest matches the screen | Pass | Same fingerprint after an app restart; **New identity** changed it, the old pin was refused and the new one passed |
| Pixel 8, Android 17 (API 37), over Wi-Fi | 1.3 and 1.2 | Pass, 62 advertisements | The agent logged the Mac's LAN address, not loopback (`TLS-PIN-07`) |

**The device run found a relay defect.** The front connected upstream to
`InetAddress.getLoopbackAddress()`, which is `127.0.0.1` on the JDK but `::1` on Android, where CIO's
IPv4-only listener refuses it. Every connection completed TLS and then closed without a response. The
JDK returns `::1` too under `java.net.preferIPv6Addresses=true`, so the JVM agent was exposed as well.
The front now dials `127.0.0.1`, the address `TlsFront`'s contract names.
