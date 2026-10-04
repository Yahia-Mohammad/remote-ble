# Encrypted LAN transport with a pinned agent identity

Decision record for [#39](https://github.com/Yahia-Mohammad/remote-ble/issues/39). **Accepted
2026-10-01: option A, TLS with a pinned self-signed certificate.** The phases in [§8](#8-phases) are
the plan, and each one updates this record as it lands. **All five phases are implemented
(2026-10-02)** and ship in 0.14.0: every agent serves `wss://` with a pinned identity (desktop JVM,
Android, iOS, Rust), every SDK target pins, pairing hands clients the address, token and fingerprint
as one URI or QR code, and an agent reachable from the network encrypts unless its operator
explicitly chooses cleartext. [§10](#10-progress) records each phase and its evidence.

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
- **iOS:** a Network.framework `NWListener` with TLS options carrying the Keychain identity, relaying
  over a second, plain `NWConnection`. Its byte pump is Objective-C, compiled through cinterop, for a
  Kotlin/Native reason recorded in [§10](#10-progress).

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
  - JVM: CIO with a trust manager that accepts exactly the pinned SPKI. (Since 0.14.1, OkHttp, as on
    Android: CIO's TLS client corrupted its own buffers on about one fresh connection in 150.)
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
| `TLS-PIN-06` | A TLS 1.2-only client connects, proving the 1.2 suite requirement (first the CIO client; since 0.14.1 a 1.2-only handshake in `JsseTlsFrontTest`). |
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
- ~~**The iOS certificate.**~~ Settled in phase 3: the agent signs `SelfSignedCertificate`'s DER with
  a Keychain key (`SecKeyCreateSignature`), stores the certificate beside the key, and an identity
  query returns the pair, which the Keychain matches by public key. No PKCS#12 is involved.
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

**Phase 2, Android client (2026-10-01).** `PinningTrustManager` moved to a `jsseMain` source set
shared by the JVM and Android clients, selected by platform type because `withAndroidTarget()` does
not match the AGP KMP library target. Android's `pinnedWebSocketHttpClient(fingerprint)` is OkHttp with
that trust manager, and a host-name verifier that checks the session's certificate against the pin:
agents are reached by IP, which OkHttp's own check would refuse, and a resumed session skips the trust
manager, so passing everything would not do.

Evidence: `OkHttpPinningTest` (Android host test) runs `TLS-PIN-01` and `02` against the real front,
with identities from `okhttp-tls`, so no key is committed. With OkHttp's default host-name verifier
restored, `01` fails. On the API 36 emulator, a client app built to use the pinned client connected
through Conscrypt to the Android agent and scanned. With a wrong pin it never reached the agent, which
logged no client, and it made no second attempt.

**Phase 2 is complete.** Next is phase 3, the iOS agent and the Darwin client, starting with the
certificate and Keychain prototype in [§9](#9-open-questions).

**Phase 3, iOS agent (2026-10-01).** `IosAgentIdentityStore` keeps the identity in the Keychain, and
`NetworkTlsFront` serves it from an `NWListener`, relaying to CIO on `127.0.0.1` with the same peer
registry as the JSSE front. The app shows the same switch, fingerprint and **New identity** as Android.

Evidence, on the iPhone 17 Pro simulator (iOS 26.5), whose network is the Mac's: OpenSSL negotiates TLS
1.3, and TLS 1.2 with ECDHE-ECDSA AES-GCM, and the SPKI digest matches the screen; `pinRun` passes (the
CIO client over TLS 1.2, a scan, and a wrong pin refused at once); an upgrade without the token gets 401
through the relay; the fingerprint survived every relaunch, and **New identity** changed
it, after which the old pin was refused and the new one passed. A client that opens TCP and never
speaks TLS is cut off by a 10 s timer, though Network.framework releases the socket 15–20 s in; a
silent peer delays no other client's handshake.

The real peer address on iOS (`TLS-PIN-07`) was first left for a physical iPhone, on the reasoning
that the simulator shares the Mac's loopback. It need not wait: a request to the Mac's LAN address
reaches the simulator's agent from a non-loopback peer, which the dashboard's own-device gate refused
(404) while the same request over loopback was served (200). See the second review below.

**Kotlin/Native broke three things in Network.framework, each found on the simulator:**

- `NW_PARAMETERS_DISABLE_PROTOCOL` and `NW_PARAMETERS_DEFAULT_CONFIGURATION` are sentinel blocks
  recognised by identity, and Kotlin/Native passes a re-wrapped block. "No TLS" became "TLS with
  defaults", and the plain upstream tried a TLS handshake with CIO (`-9836`), so no request ever
  arrived. Plain TCP is now built from the protocol stack.
- Network.framework's static content contexts are mistaken for blocks, and converting one terminates
  the process ("Converting Obj-C blocks with non-reference-typed return value to kotlin.Any is not
  supported"). Reading `NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT` did it, and so does every receive
  callback at end of stream, before any Kotlin code runs. So the pump is Objective-C
  (`agent/src/nativeInterop/cinterop/tlsrelay.def`), and Kotlin hears only that a direction ended.
- A relay whose upstream sat in *waiting* was never closed, since the handshake timer only checked
  that an upstream existed. Waiting now counts as failure, and the timer checks that relaying began.

**Phase 3, Darwin client (2026-10-01).** `pinnedWebSocketHttpClient(fingerprint)` on iOS and macOS is
Ktor's Darwin engine with a challenge handler that accepts the server trust only when the leaf
certificate's SPKI hashes to the pin. Apple exports only a bare key, so the SPKI is read from the
certificate's DER by `certificateSpki`, in common code, where `CertificateSpkiTest` checks it against the
JDK on real agent certificates; that test caught a length that could overflow the bounds check.
NSURLSession reports a refused challenge as a plain cancellation, so the handler records the mismatch
and an `HttpSend` interceptor raises `AgentIdentityMismatchException` in its place.

Evidence, on the iOS simulator, with a client app built to use the pinned client: pinned to the JVM
agent serving `wss://`, it connected and its scan found the simulated peripheral. With a wrong pin it
made one TLS attempt in 29 s, which the agent saw reset, and no handshake, so the transport gave up
rather than retrying. No CI test runs the Darwin client end to end, since that needs a TLS server
holding a Keychain identity inside the test process.

**Phase 3 is complete.** It has not been run on a physical iPhone, and nothing remaining needs one. Next
is phase 4, pairing.

**Review hardening (2026-10-02).** A review of phases 1–3 found the JSSE front, which the JVM and Android
agents share, could be held by any LAN peer without the token. Its 10 s bound was a socket read timeout,
which bounds each read: a peer trickling one byte every 7 s kept a handshake open past 49 s. And its
blocking pumps ran on the shared `Dispatchers.IO`, so 80 silent connections held an honest client's
handshake for 9 s. A transient accept error also ended its accept loop, leaving an agent that looked up
and served nothing. The front now has a real deadline, threads of its own, limits of 16 connections per
host and 128 in all, and an accept loop that backs off and retries; `JsseTlsFrontTest` reproduces each,
and each test fails against the old behaviour. On a live agent the trickled handshake now closes at
10.0 s. The Rust and iOS fronts are asynchronous and already had real deadlines.

**Second review (2026-10-02).** Each item below was reproduced or confirmed first, and its test fails
against the old code:

- **A request relayed before `start()` returned took the relay's address.** The front accepts from the
  moment it binds, but the server learned of it only when `start()` resumed, which a phone's busy main
  thread can hold up; a request in between was seen as loopback, against §3.1's peer rule. Behind TLS,
  peer resolution now waits for the front.
- **The Rust agent never timed out the upgrade request.** A peer that finished the TLS handshake, or
  opened cleartext TCP, and then went silent held its connection and descriptor forever. It now has
  10 s; the Kotlin agents already closed such a peer at CIO's 45 s idle timeout.
- **The iOS listener's start waited for readiness without bound**, so one left waiting for a usable
  network would hold Start forever. It now fails after the bind timeout and cancels the listener.
- **Platform TLS 1.2 defaults included CBC suites** on the JSSE and iOS fronts (OpenSSL negotiated
  `ECDHE-ECDSA-AES128-SHA` with the iOS agent). Every agent now offers only AEAD suites, checked on the
  iOS simulator and on Android API 24 and 36.
- **A reinstalled iOS agent kept its Keychain identity** while losing its tokens and settings. The
  first run of an installation now discards it, as Android's Keystore does.
- **Smaller:** the JVM agent loaded a key not matching its certificate (Rust refused it); neither
  desktop agent warned about a world-readable identity file; the Rust temp file was named by PID, which
  collides between containers; iOS `stop()` read relay state off its queue; the Darwin client could
  attribute an identity mismatch to another origin's failure; a failed **New identity** left the
  deleted identity on screen; fingerprints parsed other scripts' digits as hex; and random serial bytes
  could, at odds of 2⁻¹²⁸, make a zero serial, which RFC 5280 forbids.
- **Considered and kept:** the JSSE front limits connections per address, not per IPv6 /64. Every
  device on a home LAN shares one /64, so grouping would let one device fill the slot all of them need;
  a host using many addresses still meets the total of 128.

**Phase 4, pairing (2026-10-02).** The URI is `AgentPairing` in `:protocol`, a strict parser and
writer, now specified in the conformance spec §3.2; the Rust agent writes it in
`transport::pairing`, and both are tested against one shared example. `pairingWebSocketHttpClient`
in the SDK pins whenever a pairing carries a fingerprint. The desktop agents print it with
`--print-pairing`, to standard output only. The phone agents show it as a QR code with a copyable
link behind **Show pairing code**, and the JVM agent's dashboard serves the same code as SVG from
`/api/pairing`, fetched only on request. QR codes come from `qrcode-kotlin` (MIT, no dependencies),
whose module matrix one function turns into a Compose canvas and an SVG.

The client apps take a pairing by paste, or by registering `remoteble://`, so the phone's own camera
opens the agent's QR code in them. That was chosen over an in-app scanner, which would have needed a
camera permission, a dependency on Android and physical phones to test. Since any app or page can
open a link, the client shows where a pairing points, and whether it is encrypted, before applying it.
A pinned pairing reads "Paired · encrypted"; editing the address by hand drops the pin.

Evidence: macOS Vision decoded every code exactly, the token's `+ & = % / é` and an IPv6 host
included: rendered from the matrix, from the dashboard's SVG, and from screenshots of the iOS and
Android agents. On the simulator, `simctl openurl` with the JVM agent's printed pairing opened the iOS
client on a cold launch; it asked, paired, connected pinned over `wss://` and scanned the simulated
peripheral. The Android client on the emulator did the same through `am start`. `pinRun` takes a
pairing URI too, and passed against the printed one.

Not in this phase: the dashboard still shows no certificate SHA-256 for a browser's warning (§5.7);
it belongs with phase 5, when the dashboard is `https://` by default. Client apps keep the pairing in
memory, as they do the address and token.

**Phase 5, encrypted by default (2026-10-02).** A listener the network can reach now serves `wss://`
unless the operator says otherwise. The desktop agents refuse a non-loopback bind without `--tls`,
naming `--tls` and `REMOTE_BLE_ALLOW_CLEARTEXT_LAN=true` as the two ways out (TLS-PIN-05, now tested
on both: `MainTest.tlsPin05…` and `tls_pin_05_…`); loopback keeps `ws://` for tunnels and the TLS proxy
recipe. The gate is separate from the token rule, so each refusal names its own opt-in, and in
`agent-rs` it runs before Bluetooth starts. The phone agents start with **Encrypt connections** on;
switching it off is remembered and shown as a warning. The `agent-rs` container sets `REMOTE_BLE_TLS`
and keeps its identity on a volume, and its smoke test checks cleartext is refused. §5.7 is done: the
Kotlin agents show the certificate's SHA-256 as browsers display it, for the `https://` dashboard's
first-visit warning. The conformance spec makes the encrypted binding a MUST for a non-loopback
listener (§3, item 6), `SECURITY.md` no longer calls cleartext the design, and
[migrate-to-0.14.0.md](../migrate-to-0.14.0.md) covers each change. It ships in 0.14.0.

**Acceptance: nothing readable on the wire (2026-10-02).** #39 asks that a capture of a session show
no token, advertisement or GATT payload in the clear. A relay recording every byte between client and
agent, which is what a packet capture of the session carries, sat between `pinRun` and the JVM agent
serving `wss://` (simulated heart-rate profile, token `capture-token-7Qz`): 1,062 bytes to the agent and
11,816 back, including 48 advertisements, and no occurrence of the token, `Authorization`, the device
name `Warsha HRM`, the service UUID or the op name `scan`. The control, the same agent in cleartext
with `scanRun` through the same relay, carried the token and `Authorization` to the agent and the
device name back, so the search would have found them.

**Release checks on hardware (2026-10-03).** Three things the earlier phases had only covered with
simulators, unit tests or a single run:

- **A phone's own camera.** The JVM agent served `wss://` on the LAN, and its dashboard's pairing
  code (`/api/pairing`) was shown on the Mac's screen. The Pixel 8's Camera app (Android 17)
  recognised it, offered the `remoteble://` link, and opened the Android client with it (the
  activity record shows it launched from `com.google.android.GoogleCamera`); the client asked, and
  paired. Connecting then failed silently: the client lacked API 37's `ACCESS_LOCAL_NETWORK`, which
  the agent had gained in #36 and the emulator, reaching the host through `10.0.2.2`, never needs.
  With that fixed (#58), the client connected from the phone's LAN address over pinned `wss://` and
  scanned. The iPhone's camera was not tried in this round.
- **The Rust agent's `--print-pairing`, live.** `agent-rs` on macOS (through `run-agent-rs.sh`, so
  Bluetooth is granted) with `--tls --print-pairing` on `0.0.0.0` printed the Mac's LAN address, the
  configured token, and the fingerprint of the identity file the JVM agent also uses. `pinRun`
  given that URI connected pinned, scanned 67 real advertisements, and refused a wrong pin with
  `GAVE_UP`.
- **The TLS-PIN-02 flake.** On #55, `aDifferentIdentityFailsBeforeTheAgentSeesAnyRequest` once saw
  `connect()` return instead of failing: the first attempt failed with something other than the
  identity error and the transport began retrying. It never recurred: 40 runs of the class and 12
  with the agent's suite alongside on a hosted runner, and a probe repeating the scenario with fresh
  identities on both sides, recording the cause chain of any other outcome, 9,000 times on a hosted
  runner and 1,500 locally, found nothing. A mutation (the probe pinning the right identity) was
  flagged on every attempt. The test now reports the transport state and the log if it fails again.
