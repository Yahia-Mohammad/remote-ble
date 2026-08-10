package dev.warsha.remoteble.agent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Named client credentials for the WebSocket upgrade boundary. The credential name is an internal
 * principal label; only the bearer secret travels on the wire.
 */
class ClientCredentials private constructor(private val byName: Map<String, String>) {
    private val lock = SynchronizedObject()
    private val revoked = mutableSetOf<String>()

    val required: Boolean get() = byName.isNotEmpty()

    /**
     * Returns the authenticated principal name, or null when [bearer] is absent/invalid/revoked.
     * Every connection attempt — a fresh handshake or a resume reconnect within a lease's
     * transport grace (see [PeripheralRegistry.onTransportDropped]) — re-authenticates from
     * scratch here, so a principal [revoke]d mid-grace cannot resume: its next reconnect fails
     * this check before the registry is ever consulted (AUTH-REVOKE-01).
     */
    fun authenticate(bearer: String?): String? {
        if (!required) return ANONYMOUS_PRINCIPAL
        val candidate = bearer?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return null
        val principal = byName.entries.firstOrNull { (_, secret) -> constantTimeEquals(secret, candidate) }?.key
            ?: return null
        return principal.takeUnless { synchronized(lock) { it in revoked } }
    }

    /** Revokes [name] at runtime: every future [authenticate] call for it fails until [unrevoke]. */
    fun revoke(name: String) {
        require(name in byName) { "unknown credential principal: $name" }
        synchronized(lock) { revoked += name }
    }

    /** Restores a previously [revoke]d principal. */
    fun unrevoke(name: String): Unit = synchronized(lock) { revoked -= name }

    fun isRevoked(name: String): Boolean = synchronized(lock) { name in revoked }

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

        /**
         * The principal half of a [sessionKey] — the whole string if it carries no separator,
         * which is what an unauthenticated connection's bare client id looks like. Used wherever a
         * component needs the principal alone rather than the full ownership key, e.g. `WritePolicy`.
         */
        fun principalOf(sessionKey: String): String {
            val separator = sessionKey.indexOf('\u0000')
            return if (separator < 0) sessionKey else sessionKey.substring(0, separator)
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
