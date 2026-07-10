# `:protocol` — The Wire Contract

[← back to index](README.md)

The `:protocol` module is the **single source of truth for what crosses the wire**.
It is pure `commonMain` Kotlin with one dependency family (kotlinx-serialization) and
**zero BLE or network code**. Both the client SDK and the agent compile against it;
it is the only thing they share.

Source: [`protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol)

- [`Frame.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Frame.kt) — the envelope
- [`Op.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Op.kt) — the operation set + `DeviceHandle`, `CharRef`, `ScanFilter`
- [`Results.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Results.kt) — `OpResult`, `ResultPayload`, `ServiceNode`, `CharNode`
- [`Events.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Events.kt) — `AgentEvent`, `BleConnState`, `AdvertisementDto`
- [`Errors.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Errors.kt) — `AgentError`, `ErrorKind`, `AgentException`
- [`ProtocolCodec.kt`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/ProtocolCodec.kt) — CBOR/JSON encode/decode

## The frozen wire identity

Every polymorphic type on the wire carries a `@SerialName` discriminator. **Those
strings are the wire identity and are frozen** — changing one is a breaking protocol
change, independent of Kotlin class names. The codec is configured to emit/read them
as the polymorphic class discriminator.

```kotlin
@Serializable @SerialName("cmd")          // Command
@Serializable @SerialName("connect")      // Op.Connect
@Serializable @SerialName("ok")           // OpResult.Ok
@Serializable @SerialName("notification") // AgentEvent.Notification
```

This is why the discriminators are documented below alongside each type — they are
API.

## Frames — the envelope

[`Frame`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Frame.kt)
is a sealed interface with five implementations:

| Frame | `@SerialName` | Direction | Fields | Purpose |
|---|---|---|---|---|
| `ClientHello` | `hello` | client → agent | `minVersion: Int`, `maxVersion: Int`, `capabilities: Set<String>` | First frame on every (re)connect; opens capability negotiation. |
| `ServerHello` | `server_hello` | agent → client | `version: Int`, `capabilities: Set<String>`, `agentInfo: String?` | The agent's answer — the negotiated version + capability set. |
| `Command` | `cmd` | client → agent | `cid: Long`, `op: Op` | A request. |
| `Reply` | `reply` | agent → client | `cid: Long`, `result: OpResult` | The response to the `Command` with the same `cid`. |
| `Event` | `event` | agent → client | `event: AgentEvent` | Unsolicited; routed by the id baked into the event. |

`cid` is **client-assigned and monotonically increasing**; the agent never invents
one. Replies are matched to requests purely by `cid` (see
[client-sdk.md](client-sdk.md#defaultagentsession)). Events carry no `cid` — they are
routed by `subId`/`scanId` inside the `AgentEvent`.

## Handshake & capability negotiation

[`Capabilities`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Capabilities.kt)
+ the two `Hello` frames let the two ends agree on an optional feature set on top of
the `PROTOCOL_VERSION` (currently **`1`**) baseline.

On every (re)connection the client sends a `ClientHello` declaring the version range it
speaks and the capability strings it understands. The agent replies with a `ServerHello`
carrying the version it chose and the **negotiated capability set** —
`clientWanted ∩ agentSupported`. Subsequent ops outside that set are answered with
`ErrorKind.UNSUPPORTED`, so a client should gate capability-specific ops on the reply.

```kotlin
object Capabilities {
    const val DESCRIPTORS      = "descriptors"   // Op.ReadDescriptor / Op.WriteDescriptor (backend-level)
    const val PAIRING          = "pairing"       // Op.Pair / Op.Unpair + bond-state events (backend-level)
    const val CONNECTION_SLOTS = "slots"         // AgentEvent.SlotState free/total slots (agent-level)
    const val CONN_PRIORITY    = "conn.priority" // Op.RequestConnectionPriority (backend-level, Android-only)
    const val RSSI             = "rssi"          // Op.ReadRssi connected read (backend-level, Android/Apple)
    const val CONN_PARAMS      = "conn.params"   // Op.SetConnParams (backend-level, Android-only); ⊃ conn.priority
    const val SCAN_BATCH       = "scan.batch"    // AgentEvent.ScanResultBatch coalescing (agent-level)
}
```

Capabilities are either **backend-level** (depend on the radio engine — the backend declares
them via `BleBackend.capabilities`) or **agent-level** (radio-independent, implemented by the
agent itself — `BleAgent.AGENT_CAPABILITIES`). The advertised set is the union of the two.

Two deliberate properties:

- **Capabilities are `Set<String>`, not an enum.** An unknown capability string decodes
  harmlessly to "not understood" rather than failing CBOR, so an old peer and a new peer
  always agree on the intersection of what they both name. This is the forward-compat
  mechanism — new optional features ship without a version bump.
- **Negotiation is lenient, not a gate.** The client may begin issuing `Command`s without
  waiting for the `ServerHello`; the negotiated set lands in
  [`AgentSession.capabilities`](client-sdk.md) (a `StateFlow`, `null` until the reply,
  reset on each reconnect) for callers that need to gate. This keeps the session/transport
  state machines unchanged and avoids a handshake deadlock.

The `Hello` exchange carries **no auth credential or ownership id** — those stay on the
WebSocket upgrade headers (`Authorization: Bearer …` and `CLIENT_ID_HEADER`).

## Operations — `Op`

[`Op`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Op.kt) is a
sealed interface; each variant is the payload of a `Command`. The set mirrors the
GATT / scanning surface 1:1.

| Op | `@SerialName` | Fields | Reply payload (on `Ok`) |
|---|---|---|---|
| `ScanStart` | `scan.start` | `scanId: Long`, `filters: List<ScanFilter>` | none — results arrive as `ScanResult` events |
| `ScanStop` | `scan.stop` | `scanId: Long` | none |
| `Connect` | `connect` | `device: DeviceHandle` | none (+ a `ConnectionState` event) |
| `Disconnect` | `disconnect` | `device: DeviceHandle` | none (+ a `ConnectionState` event) |
| `Discover` | `discover` | `device: DeviceHandle` | `ResultPayload.Services` |
| `Read` | `read` | `device: DeviceHandle`, `char: CharRef` | `ResultPayload.Bytes` |
| `Write` | `write` | `device`, `char`, `value: ByteArray`, `withResponse: Boolean` | none |
| `ObserveStart` | `observe.start` | `subId: Long`, `device`, `char` | none — notifications arrive as `Notification` events |
| `ObserveStop` | `observe.stop` | `subId: Long` | none |
| `RequestMtu` | `mtu` | `device: DeviceHandle`, `mtu: Int` | `ResultPayload.Mtu` (the *negotiated* value) |
| `ReadDescriptor` | `desc.read` | `device: DeviceHandle`, `desc: DescRef` | `ResultPayload.Bytes` — **capability: `descriptors`** |
| `WriteDescriptor` | `desc.write` | `device`, `desc: DescRef`, `value: ByteArray` | none — **capability: `descriptors`** |
| `Pair` | `pair` | `device: DeviceHandle` | `ResultPayload.Bond` (+ a `BondState` event) — **capability: `pairing`** |
| `Unpair` | `unpair` | `device: DeviceHandle` | none (+ a `BondState` event) — **capability: `pairing`** |
| `RequestConnectionPriority` | `conn.priority` | `device: DeviceHandle`, `priority: ConnPriority` | none — **capability: `conn.priority`** |
| `ReadRssi` | `rssi` | `device: DeviceHandle` | `ResultPayload.Rssi` (dBm, connected) — **capability: `rssi`** |
| `SetConnParams` | `conn.params` | `device: DeviceHandle`, `profile: ConnProfile`, `hint: ConnParamHint?` | none — **capability: `conn.params`** |

The descriptor, pairing, RSSI, and connection-parameter ops are gated behind their capabilities (see
[Handshake & capability negotiation](#handshake--capability-negotiation)); an agent that
doesn't advertise one answers the corresponding ops with `ErrorKind.UNSUPPORTED`. A backend
declares what it can service via `BleBackend.capabilities`, and the agent advertises exactly
that — the set can't drift from the implementation.

Note the reference agent does **not** advertise `pairing`: its Kable `btleplug` backend
exposes no bonding control (and CoreBluetooth bonds implicitly anyway), so `EngineBleBackend`
leaves the `pair`/`unpair` ops on the `BleBackend` defaults (`UNSUPPORTED`). A backend with
real bonding control would override them and advertise the capability; `Pair` would then return
`BONDING` (initiated) rather than a confirmed `BONDED` unless the backend also surfaces a
bond-state flow.

`SetConnParams` is the superset of `RequestConnectionPriority`: `conn.params` carries a portable
`ConnProfile` (`LOW_LATENCY` / `BALANCED` / `LOW_POWER`) plus a reserved, currently-unused
`ConnParamHint` for a future fine-grained engine, and an agent advertising it implies the coarse
`conn.priority` behavior. In the reference agent both map to the same Android binding
(`AndroidPeripheral.requestConnectionPriority`) and are advertised together — Android only. iOS and
the JVM/`btleplug` backend expose no connection-parameter control and advertise neither, answering
`UNSUPPORTED`. Likewise `rssi` is a *connected* read only on Kable's Android/Apple backends; the
JVM/`btleplug` backend has only advertisement RSSI and doesn't advertise it (`agent-rs` mirrors all
three ops' codecs for byte parity but, being `btleplug`, advertises none of these capabilities).

Stream-opening ops (`ScanStart`, `ObserveStart`) carry the stream id (`scanId` /
`subId`) **in the request**, so the agent tags subsequent events with it and the
client can demultiplex. The matching stop op carries the same id.

### Addressing types

```kotlin
@Serializable data class DeviceHandle(val value: String)
@Serializable data class CharRef(val service: String, val characteristic: String, val instance: Int = 0)
@Serializable data class DescRef(val service: String, val characteristic: String, val descriptor: String, val instance: Int = 0)
@Serializable data class ScanFilter(val service: String? = null, val name: String? = null)
```

- **`DeviceHandle`** — opaque and **agent-scoped**. On Android/Linux it may be a MAC;
  on iOS/macOS a `CBPeripheral` UUID. The client treats it as a token and never
  parses it. The agent mints handles from its *own* scan results (see
  `EngineBleBackend.scan` in [agent.md](agent.md)); the client never constructs one
  except by echoing what it received in an advertisement.
- **`CharRef`** — a characteristic by service + characteristic UUID. `instance`
  disambiguates the rare duplicate-UUID case. Resolution to a concrete handle happens
  on the agent.

## Results — `OpResult` and `ResultPayload`

[`OpResult`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Results.kt)
is the body of a `Reply`:

```kotlin
sealed interface OpResult {
    @SerialName("ok")  data class Ok(val payload: ResultPayload? = null) : OpResult
    @SerialName("err") data class Err(val error: AgentError) : OpResult
}
```

Most ops succeed with `Ok(payload = null)`. The three ops that return data use
`ResultPayload`:

| `ResultPayload` | `@SerialName` | Carries | Produced by |
|---|---|---|---|
| `Bytes` | `bytes` | `value: ByteArray` | `Read`, `ReadDescriptor` |
| `Services` | `services` | `services: List<ServiceNode>` | `Discover` |
| `Mtu` | `mtu` | `mtu: Int` | `RequestMtu` (and conceptually `Connect`) |
| `Bond` | `bond` | `state: BleBondState` | `Pair` |

The GATT table shape returned by `Discover`:

```kotlin
@Serializable data class ServiceNode(val uuid: String, val characteristics: List<CharNode>)
@Serializable data class CharNode(
    val uuid: String,
    val properties: Int,                       // GATT property bitmask (read/write/notify/…)
    val descriptors: List<String> = emptyList(),
)
```

`properties` is the raw GATT property bitmask; the client maps it into Kable's
`Characteristic.Properties` (see [client-sdk.md](client-sdk.md#uuid--discovery-mapping-helpers)).

### Reading a payload safely

The client side never blind-casts a payload. Two helpers in the protocol/client
enforce the contract:

- `OpResult.orThrow(): ResultPayload?` ([Errors.kt](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Errors.kt)) —
  returns the success payload or throws `AgentException(error)` on `Err`.
- `OpResult.payloadAs<T>()` ([RemoteGattClient.kt](../client-sdk/src/commonMain/kotlin/dev/warsha/remoteble/client/RemoteGattClient.kt)) —
  `orThrow()` then casts to the expected payload type, throwing `UNSUPPORTED` if the
  agent returned the wrong shape.

## Events — `AgentEvent`

[`AgentEvent`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Events.kt)
is the body of an unsolicited `Event` frame:

| Event | `@SerialName` | Fields | Routed by |
|---|---|---|---|
| `ScanResult` | `scan.result` | `scanId: Long`, `advertisement: AdvertisementDto` | `scanId` |
| `ScanResultBatch` | `scan.batch` | `scanId: Long`, `advertisements: List<AdvertisementDto>` | `scanId` — **capability: `scan.batch`** |
| `Notification` | `notification` | `subId: Long`, `value: ByteArray` | `subId` |
| `ConnectionState` | `conn.state` | `device: DeviceHandle`, `state: BleConnState`, `reason: AgentError?` | `device` |
| `BondState` | `bond.state` | `device: DeviceHandle`, `state: BleBondState`, `reason: AgentError?` | `device` — **capability: `pairing`** |
| `SlotState` | `conn.slots` | `free: Int`, `total: Int` | session-global — **capability: `slots`** |

`ConnectionState` reports the **physical BLE link** state and is explicitly distinct
from the IP transport state (see [the two state machines](README.md#the-two-state-machines-do-not-conflate-them)).

```kotlin
@Serializable enum class BleConnState { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }
@Serializable enum class BleBondState { NONE, BONDING, BONDED }
```

The advertisement DTO carried by scan results:

```kotlin
@Serializable class AdvertisementDto(
    val device: DeviceHandle,                       // the handle to use for Connect()
    val name: String? = null,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
)
```

The client wraps this in a Kable `Advertisement` (`RemoteAdvertisement`) and pulls
`device` out as the handle for connecting.

## Errors — `AgentError` / `ErrorKind`

[`AgentError`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/Errors.kt):

```kotlin
@Serializable data class AgentError(
    val kind: ErrorKind,
    val gattStatus: Int? = null,   // raw status when the radio actually answered
    val message: String? = null,
)
class AgentException(val error: AgentError) : Exception(error.message ?: error.kind.name)
```

The key design property of `ErrorKind` is that it encodes **where the failure
happened** — "reached the radio and the radio said no" versus "never reached the
radio (agent- or transport-level)". That distinction is what lets a caller decide
whether a retry could possibly help.

Each kind also carries a `transient: Boolean` (a pure client-side annotation — the wire
form is unchanged, since the enum serializes by name). **Transient** = a passing condition
an identical retry could plausibly clear; **permanent** = a stable fact a retry can't
change. This is the *error* half of the retry decision — the *operation* half is
`Op.isIdempotent` (below).

| Reached the radio (radio said no) | | Never reached the radio | |
|---|---|---|---|
| `CONNECTION_FAILED` | transient | `UNKNOWN_DEVICE` | permanent |
| `DISCONNECTED` | transient | `NO_CONNECTION_SLOT` | transient |
| `GATT_ERROR` | permanent | `PERIPHERAL_BUSY` | transient |
| `READ_FAILED` | transient | `AGENT_BUSY` | transient |
| `WRITE_FAILED` | transient | `UNSUPPORTED` | permanent |
| `CHARACTERISTIC_NOT_FOUND` | permanent | `TIMEOUT` | transient |
| `NOT_CONNECTED` | transient | `TRANSPORT_LOST` | transient |

`gattStatus` carries the raw BLE-stack status when the radio answered. `TIMEOUT` and
`TRANSPORT_LOST` are minted **client-side** by the session (the agent never sends
them — by definition the agent was unreachable); everything else originates at the
agent. See the full taxonomy discussion in
[design-decisions.md](design-decisions.md#the-error-taxonomy-where-not-just-what).

### Retryability: `transient` × `isIdempotent`

`ErrorKind.transient` says a retry *could* help; `Op.isIdempotent` says a retry is *safe*.
Automatic retry needs **both**. The hazard is a mutation that reached the radio and took
effect but whose reply was lost (`TIMEOUT`/`TRANSPORT_LOST`): a blind retry would apply it
twice. So **all writes and pairing are non-idempotent** — `Op.Write`, `Op.WriteDescriptor`,
`Op.Pair` — while reads, discovery, connect/disconnect, scan/observe control, MTU, RSSI reads, and
connection-priority / connection-parameter requests are convergent or effect-free and safe to
repeat. The client SDK
turns this into the *default* retry policy per op — non-idempotent ops default to no retry — which
a caller can still override per call (see [client-sdk.md](client-sdk.md#error-and-retry-policies)).

## The codec

[`ProtocolCodec`](../protocol/src/commonMain/kotlin/dev/warsha/remoteble/protocol/ProtocolCodec.kt)
is a two-method interface so the session and transport stay codec-agnostic:

```kotlin
interface ProtocolCodec {
    fun encode(frame: Frame): ByteArray
    fun decode(bytes: ByteArray): Frame
}
```

| Impl | Format | Use |
|---|---|---|
| `CborProtocolCodec` | CBOR (binary, self-describing) | **production** |
| `JsonProtocolCodec` | JSON over UTF-8 | debugging only |

CBOR is the default because reads/writes/notifications carry `ByteArray` payloads — a
binary format is the natural fit and avoids base64 bloat. The CBOR experimental
opt-in is kept *off* the public API (annotated internally) so consumers don't inherit
it.

## Serialization rules for `ByteArray`-bearing types

Kotlin's `data class` gives identity-based `equals`/`hashCode` for array fields,
which would make round-trip tests and event de-duplication wrong. Every wire type
that holds a `ByteArray` (or a `Map<_, ByteArray>`) therefore **hand-implements
content-based `equals`/`hashCode`/`toString`** and is a plain `class`, not a `data
class`:

- `Op.Write` (`value`)
- `Op.WriteDescriptor` (`value`)
- `ResultPayload.Bytes` (`value`)
- `AgentEvent.Notification` (`value`)
- `AdvertisementDto` (`manufacturerData`)

`toString` deliberately prints byte *sizes*, not contents, to keep logs readable and
avoid dumping payloads.

The round-trip suite
[`ProtocolCodecTest`](../protocol/src/commonTest/kotlin/dev/warsha/remoteble/protocol/ProtocolCodecTest.kt)
(27 tests) encodes and decodes every variant and asserts structural equality —
which is exactly why the content-based equality matters.

### On-the-wire CBOR form (for alternative agents)

`kotlinx.serialization`'s CBOR (`Cbor.Default`) is the authoritative encoding. A
non-Kotlin agent (e.g. the native Rust [`agent-rs`](../agent-rs)) must match it
exactly. The non-obvious bits, each pinned by a cross-language interop test:

- **Polymorphic frames/ops/results/events** encode as a 2-element **array**
  `[serialName, payloadMap]` — *not* a `{"type":…,"value":…}` map. The `serialName`
  is the `@SerialName` discriminator (`"cmd"`, `"scan.start"`, `"ok"`, …).
- **Enums** encode as their **name string** (`"HIGH"`, `"CONNECTED"`,
  `"GATT_ERROR"`), not an ordinal int.
- **`ByteArray`** encodes as a CBOR **array of signed bytes** (one integer per byte
  in the `i8` range: `0xFF → -1`, `0x80 → -128`) — *not* a CBOR byte string. This
  applies to every field listed above, including `manufacturerData` map values.
- Field names are the Kotlin property names verbatim (camelCase): `gattStatus`,
  `withResponse`, `scanId`, `subId`, `serviceUuids`, `manufacturerData`, `agentInfo`.
- kotlinx writes **indefinite-length** maps/arrays; a conforming agent may emit
  **definite-length** instead — both decode. Defaulted fields are omitted on encode
  (`encodeDefaults = false`) and must be optional/defaulted on decode.

These are verified both ways: Rust decodes the Kotlin codec's bytes
([`agent-rs/src/protocol/interop_tests.rs`](../agent-rs/src/protocol/interop_tests.rs))
and Kotlin decodes the Rust agent's bytes
([`RustAgentInteropTest`](../protocol/src/commonTest/kotlin/dev/warsha/remoteble/protocol/RustAgentInteropTest.kt)).
