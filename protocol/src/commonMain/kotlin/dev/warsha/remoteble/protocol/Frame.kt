package dev.warsha.remoteble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level wire envelope. Three kinds: a client->agent request ([Command]),
 * an agent->client reply to a request ([Reply]), and an unsolicited
 * agent->client event ([Event]).
 *
 * The `@SerialName` discriminators on this hierarchy (and every type below it)
 * are the wire identity — treat them as frozen. Changing one is a breaking
 * protocol change.
 */
@Serializable
sealed interface Frame

/**
 * Client -> agent. [cid] is a client-assigned, monotonically increasing
 * correlation id; the matching [Reply] echoes it.
 */
@Serializable
@SerialName("cmd")
data class Command(val cid: Long, val op: Op) : Frame

/** Agent -> client, in response to a [Command] with the same [cid]. */
@Serializable
@SerialName("reply")
data class Reply(val cid: Long, val result: OpResult) : Frame

/**
 * Agent -> client, unsolicited. Routed by the id baked into the event
 * (subId for notifications, scanId for scan results).
 */
@Serializable
@SerialName("event")
data class Event(val event: AgentEvent) : Frame

/**
 * Client -> agent, the first frame on every (re)connection. Declares the protocol
 * version range the client speaks and the optional [Capabilities] it understands.
 * The agent answers with a [ServerHello]. Negotiation is *lenient*: a client may
 * begin issuing [Command]s without waiting, but should gate capability-specific ops
 * on the negotiated set in the reply.
 *
 * Note: this carries no auth credential or ownership id — those stay on the WebSocket
 * upgrade headers (`Authorization` / `CLIENT_ID_HEADER`).
 */
@Serializable
@SerialName("hello")
data class ClientHello(
    val minVersion: Int = PROTOCOL_VERSION,
    val maxVersion: Int = PROTOCOL_VERSION,
    val capabilities: Set<String> = emptySet(),
    // The format this client's local Kable `Identifier` can hold. Only meaningful when the client
    // also names [Capabilities.IDENTIFIER_TRANSLATION]; an agent that negotiated it mints handles in
    // this format. Optional/nullable (and trailing) so the frame stays wire-compatible with peers
    // that predate translation — they simply omit it and get untranslated handles.
    val identifierFormat: IdentifierFormat? = null,
) : Frame

/**
 * Agent -> client, in response to a [ClientHello]. [version] is the version the agent
 * chose to speak (within the client's range); [capabilities] is the negotiated set —
 * `clientWanted ∩ agentSupported`. [agentInfo] is an optional human-readable
 * identifier (engine/platform) for logs and the dashboard.
 */
@Serializable
@SerialName("server_hello")
data class ServerHello(
    val version: Int = PROTOCOL_VERSION,
    val capabilities: Set<String> = emptySet(),
    val agentInfo: String? = null,
) : Frame
