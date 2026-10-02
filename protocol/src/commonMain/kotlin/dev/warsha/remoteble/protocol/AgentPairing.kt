package dev.warsha.remoteble.protocol

/**
 * Everything a client needs to reach an agent, as one value an agent can show and a client can
 * take in: where the agent listens, its bearer token, and the fingerprint to pin. Not BLE pairing
 * (`Op.Pair`), which bonds a peripheral; this pairs a client with an agent.
 *
 * Written as a URI, which agents show as a QR code or print on request:
 *
 * ```
 * remoteble://<host>:<port>?token=<token>&fp=sha256:<hex>
 * ```
 *
 * An IPv6 host is bracketed, query values are percent-encoded, and `+` means itself, not a space.
 * Both parameters are optional: without `token` the agent needs none, and without `fp` it serves
 * cleartext `ws://`, which [encrypted] reports so a client can say so. Parameters a later version
 * may add are ignored; a repeated one is refused. See `docs/agent-conformance-spec.md` §3.2.
 *
 * [toString] leaves the token out, so a pairing can be logged; [toUri] carries it.
 */
class AgentPairing(
    val host: String,
    val port: Int,
    val token: String?,
    val fingerprint: AgentFingerprint?,
) {
    init {
        require(port in 1..65535) { "port must be 1-65535, got $port" }
        require(isValidHost(host)) { "not a host name or IP address: $host" }
        require(token == null || token.isNotEmpty()) { "an empty token is no token; pass null" }
    }

    /** Whether the agent serves `wss://` with the pinned [fingerprint]. */
    val encrypted: Boolean get() = fingerprint != null

    /** The agent's WebSocket endpoint: `wss://` when pinned, `ws://` otherwise. */
    val url: String get() = "${if (encrypted) "wss" else "ws"}://${authority()}$AGENT_PATH"

    /** The pairing URI, token included: treat it as the secret it carries. */
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(authority())
        val query = listOfNotNull(token?.let { "token=${percentEncode(it)}" }, fingerprint?.let { "fp=$it" })
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }

    private fun authority(): String = (if (':' in host) "[$host]" else host) + ":" + port

    override fun equals(other: Any?): Boolean =
        other is AgentPairing && host == other.host && port == other.port && token == other.token && fingerprint == other.fingerprint

    override fun hashCode(): Int = ((host.hashCode() * 31 + port) * 31 + token.hashCode()) * 31 + fingerprint.hashCode()

    override fun toString(): String =
        "AgentPairing(${authority()}, token=${if (token == null) "none" else "<redacted>"}, fp=${fingerprint ?: "none"})"

    companion object {
        const val SCHEME = "remoteble"
        private const val AGENT_PATH = "/agent"

        /** Parses a pairing URI; throws [IllegalArgumentException] naming what is wrong with it. */
        fun parse(uri: String): AgentPairing {
            val text = uri.trim()
            val prefix = "$SCHEME://"
            require(text.startsWith(prefix, ignoreCase = true)) { "a pairing starts with $prefix" }
            val rest = text.substring(prefix.length)
            require('#' !in rest) { "a pairing has no fragment" }
            val authority = rest.substringBefore('?').removeSuffix("/")
            require('/' !in authority) { "a pairing has no path" }
            require('@' !in authority) { "a pairing has no user information" }
            val (host, portText) = if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                require(close > 0 && authority.getOrNull(close + 1) == ':') { "an IPv6 host is [address]:port" }
                authority.substring(1, close) to authority.substring(close + 2)
            } else {
                require(authority.count { it == ':' } == 1) { "a pairing needs host:port" }
                authority.substringBefore(':') to authority.substringAfter(':')
            }
            require(portText.isNotEmpty() && portText.all { it in '0'..'9' } && portText.length <= 5) { "not a port: $portText" }

            val parameters = mutableMapOf<String, String>()
            rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val name = percentDecode(pair.substringBefore('='))
                require(name !in parameters) { "$name is given twice" }
                parameters[name] = percentDecode(pair.substringAfter('=', ""))
            }
            return AgentPairing(
                host = host,
                port = portText.toInt(),
                token = parameters["token"]?.takeIf { it.isNotEmpty() },
                fingerprint = parameters["fp"]?.let(AgentFingerprint::parse),
            )
        }

        /** [parse], or `null` instead of throwing. */
        fun parseOrNull(uri: String): AgentPairing? = runCatching { parse(uri) }.getOrNull()

        private fun isValidHost(host: String): Boolean = when {
            host.isEmpty() -> false
            ':' in host -> host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            else -> host.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val HEX = "0123456789ABCDEF"

        /** RFC 3986: everything but the unreserved characters, as UTF-8 bytes. */
        private fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && c in UNRESERVED) {
                    append(c)
                } else {
                    append('%').append(HEX[byte.toInt() shr 4 and 0xF]).append(HEX[byte.toInt() and 0xF])
                }
            }
        }

        private fun percentDecode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    val hi = value.getOrNull(i + 1)?.let(::hexValue)
                    val lo = value.getOrNull(i + 2)?.let(::hexValue)
                    require(hi != null && lo != null) { "a broken percent-escape at ${value.drop(i).take(3)}" }
                    bytes += ((hi shl 4) or lo).toByte()
                    i += 3
                } else {
                    c.toString().encodeToByteArray().forEach { bytes += it }
                    i++
                }
            }
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("a percent-escaped value is not UTF-8")
            }
        }

        private fun hexValue(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }
    }
}
