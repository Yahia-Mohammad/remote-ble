package dev.warsha.remoteble.protocol

/**
 * HTTP header carrying the client's **stable session id** on the WebSocket handshake.
 *
 * Unlike the per-connection id the agent mints for monitoring, this value is generated once
 * per client transport and re-sent on every reconnect, so the agent can recognise a returning
 * client after a brief link drop and let it **resume** its peripheral ownership (rather than
 * treating the new socket as a different client). It is not an auth credential — that is the
 * separate bearer token.
 */
const val CLIENT_ID_HEADER: String = "X-RemoteBle-Client"

/**
 * HTTP header carrying an **operator** credential on the WebSocket handshake, as `Bearer <secret>`.
 *
 * Optional, and deliberately separate from the `Authorization` bearer that admits a client at all.
 * The client credential says *which principal* is connecting; this says the caller also holds the
 * agent's operator secret, and so may see the management plane — today, every lease's holder in an
 * `agent.status` reply (capability [Capabilities.AGENT_STATUS]) rather than only its own. The agent
 * already requires the operator secret to be distinct from every client credential, so a normal
 * bearer token cannot silently acquire operator reach.
 *
 * An absent or wrong value is **not** a connection failure: the session proceeds at normal scope and
 * says so in [AgentStatusDto.operatorScope]. A client that asked for operator-only fields without
 * the credential should be able to tell that apart from an agent that is unreachable, or one too old
 * to know the capability at all.
 */
const val OPERATOR_HEADER: String = "X-RemoteBle-Operator"
