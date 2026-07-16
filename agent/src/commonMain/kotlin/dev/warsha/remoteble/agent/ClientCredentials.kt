package dev.warsha.remoteble.agent

/**
 * Named client credentials for the WebSocket upgrade boundary. The credential name is an internal
 * principal label; only the bearer secret travels on the wire.
 */
class ClientCredentials private constructor(private val byName: Map<String, String>) {
    val required: Boolean get() = byName.isNotEmpty()

    /** Returns the authenticated principal name, or null when [bearer] is absent/invalid. */
    fun authenticate(bearer: String?): String? {
        if (!required) return ANONYMOUS_PRINCIPAL
        val candidate = bearer?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return null
        return byName.entries.firstOrNull { (_, secret) -> constantTimeEquals(secret, candidate) }?.key
    }

    companion object {
        const val DEFAULT_PRINCIPAL = "default"
        const val ANONYMOUS_PRINCIPAL = "anonymous"

        fun of(credentials: Map<String, String>): ClientCredentials {
            require(credentials.keys.all {
                it.isNotBlank() && '\u0000' !in it && it.encodeToByteArray().size <= 128
            }) {
                "credential names must be non-empty and at most 128 UTF-8 bytes"
            }
            require(credentials.values.all { it.isNotBlank() && it.encodeToByteArray().size <= 512 }) {
                "credential secrets must be non-empty and at most 512 UTF-8 bytes"
            }
            require(credentials.values.toSet().size == credentials.size) {
                "credential secrets must be unique"
            }
            return ClientCredentials(credentials.toMap())
        }

        fun legacy(token: String?): ClientCredentials =
            of(token?.takeIf { it.isNotBlank() }?.let { mapOf(DEFAULT_PRINCIPAL to it) } ?: emptyMap())

        /** Stable, unambiguous ownership key: a client id cannot cross a principal boundary. */
        fun sessionKey(principal: String, clientId: String): String {
            require(clientId.isNotBlank() && '\u0000' !in clientId && clientId.encodeToByteArray().size <= 128) {
                "client identity must be non-empty and at most 128 UTF-8 bytes"
            }
            return "$principal\u0000$clientId"
        }

        // Compare all available bytes and length so a mismatch does not exit early.
        private fun constantTimeEquals(expected: String, actual: String): Boolean {
            val a = expected.encodeToByteArray()
            val b = actual.encodeToByteArray()
            var difference = a.size xor b.size
            val max = maxOf(a.size, b.size)
            repeat(max) { index ->
                val left = if (index < a.size) a[index].toInt() else 0
                val right = if (index < b.size) b[index].toInt() else 0
                difference = difference or (left xor right)
            }
            return difference == 0
        }
    }
}
