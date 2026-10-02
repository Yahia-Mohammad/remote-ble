package dev.warsha.remoteble.protocol

/**
 * An agent's identity on an encrypted link: the SHA-256 of its TLS certificate's
 * SubjectPublicKeyInfo, written `sha256:<64 lowercase hex>`.
 *
 * The public key rather than the whole certificate, so an agent can reissue its certificate (new
 * validity, new serial) without breaking clients that paired with it; only an explicit identity
 * reset changes it. See `docs/proposals/agent-transport-encryption.md`.
 */
class AgentFingerprint private constructor(private val digest: ByteArray) {

    /** The 32-byte SHA-256 digest. A copy, so callers cannot mutate the identity. */
    val bytes: ByteArray get() = digest.copyOf()

    /** Whether [spkiSha256] — the SHA-256 of a presented key's SPKI — is this identity. */
    fun matches(spkiSha256: ByteArray): Boolean = digest.contentEquals(spkiSha256)

    override fun equals(other: Any?): Boolean = other is AgentFingerprint && digest.contentEquals(other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = PREFIX + digest.joinToString("") { HEX[it.toInt() shr 4 and 0xF].toString() + HEX[it.toInt() and 0xF] }

    companion object {
        private const val PREFIX = "sha256:"
        private const val HEX = "0123456789abcdef"
        private const val DIGEST_BYTES = 32

        /** Wraps a SHA-256 digest of an SPKI. */
        fun ofSpkiSha256(digest: ByteArray): AgentFingerprint {
            require(digest.size == DIGEST_BYTES) { "a SHA-256 digest is $DIGEST_BYTES bytes, got ${digest.size}" }
            return AgentFingerprint(digest.copyOf())
        }

        /**
         * Parses `sha256:<64 hex>`. Hex is accepted in either case, since people paste it from
         * tools that print upper case; [toString] always writes lower case.
         */
        fun parse(text: String): AgentFingerprint {
            val trimmed = text.trim()
            require(trimmed.startsWith(PREFIX, ignoreCase = true)) { "fingerprint must start with '$PREFIX': $text" }
            val hex = trimmed.substring(PREFIX.length)
            require(hex.length == DIGEST_BYTES * 2) { "fingerprint needs ${DIGEST_BYTES * 2} hex digits, got ${hex.length}" }
            val digest = ByteArray(DIGEST_BYTES) { i ->
                val hi = hexDigit(hex[2 * i])
                val lo = hexDigit(hex[2 * i + 1])
                require(hi != null && lo != null) { "fingerprint is not hex: $text" }
                ((hi shl 4) or lo).toByte()
            }
            return AgentFingerprint(digest)
        }

        /**
         * ASCII hex only. `digitToIntOrNull` also takes other scripts' digits and fullwidth letters,
         * so a fingerprint would have more than one accepted spelling.
         */
        private fun hexDigit(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }

        /** [parse], or `null` instead of throwing. */
        fun parseOrNull(text: String): AgentFingerprint? = runCatching { parse(text) }.getOrNull()
    }
}

/**
 * The DNS name every agent certificate carries, and the TLS server name a pinning client presents.
 *
 * Agents are reached by IP addresses that change, so their certificates cannot name them, while TLS
 * stacks (Ktor's CIO among them) verify the server name regardless of the trust decision. A fixed
 * name satisfies that check on every agent; the pin remains the actual identity check. `.invalid` is
 * reserved (RFC 2606), so it can never collide with a real host.
 */
const val AGENT_TLS_SERVER_NAME: String = "agent.remoteble.invalid"
