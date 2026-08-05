package dev.warsha.remoteble.agent

/**
 * Turns a lease owner's session key into a message another client is allowed to read.
 *
 * A `PERIPHERAL_BUSY` reply that says only "peripheral in use" leaves the caller with no next
 * action: the registry knows who holds the lease, and the client that has just been refused has to
 * ask a human, read agent logs, or guess. Naming the holder makes contention diagnosable — but the
 * holder's identity is another tenant's, so what may be disclosed depends on who is asking.
 *
 * ### Disclosure policy
 * A session key is `principal` + NUL + `clientId` (see `ClientCredentials.sessionKey`). The principal is
 * an operator-assigned credential name; the client id is chosen by the client itself.
 *
 *  - **Same principal** — the caller already holds this credential, so it may see the client id.
 *    This is the case that matters most in practice: two shells, two checkouts, or a stale process
 *    belonging to the same person or team, where "which of my own clients is holding this" is the
 *    whole question.
 *  - **Different principal** — only the principal name is disclosed. It is operator-assigned and
 *    already shared context between tenants of one agent, whereas another tenant's client id can
 *    carry a hostname or username it never intended to publish.
 *
 * ### The holder's identity is untrusted text
 * Both halves cross the wire from the holder, and this message is rendered by whatever refused
 * client receives it — a terminal, a log, a coding agent's context. It is therefore length-bounded
 * and control-character escaped here, at the point of disclosure, rather than trusting the ingress
 * validation that only rejects NUL and caps the raw byte length.
 */
internal object LeaseDisclosure {

    /** Longest identity fragment rendered into a message; the rest becomes an ellipsis. */
    const val MAX_FRAGMENT_LENGTH: Int = 48

    /** Separates the principal from the client id in a session key. */
    private const val SESSION_KEY_SEPARATOR: Char = '\u0000'

    /**
     * A `PERIPHERAL_BUSY` message describing who holds the peripheral, addressed to [requesterKey].
     * Both arguments are session keys; a key with no separator is treated as a bare principal.
     */
    fun busyMessage(ownerKey: String, requesterKey: String): String {
        val (ownerPrincipal, ownerClientId) = split(ownerKey)
        val (requesterPrincipal, _) = split(requesterKey)
        val principal = sanitize(ownerPrincipal)
        return when {
            ownerClientId == null || ownerPrincipal != requesterPrincipal ->
                "peripheral in use by principal '$principal'"
            else ->
                "peripheral in use by principal '$principal', client '${sanitize(ownerClientId)}'"
        }
    }

    /**
     * A holder label for an `agent.status` lease row, addressed to [requesterKey].
     *
     * The same policy [busyMessage] applies, with one addition: [operatorScope] — the caller
     * presented the agent's operator credential on the upgrade, which is the management plane the
     * dashboard already discloses holders on. Nothing here is reachable with a client bearer token
     * alone, because the agent requires the operator secret to be distinct from every client
     * credential.
     *
     *  - **Own or same-principal lease** — `principal/clientId`.
     *  - **Another principal, operator scope** — `principal/clientId`.
     *  - **Another principal, no operator scope** — `principal` alone.
     *
     * Sanitized identically to [busyMessage]: both halves are text the *holder* chose, and this
     * lands in a terminal, a log, or a coding agent's context just the same.
     */
    fun holderLabel(ownerKey: String, requesterKey: String, operatorScope: Boolean): String {
        val (ownerPrincipal, ownerClientId) = split(ownerKey)
        val (requesterPrincipal, _) = split(requesterKey)
        val principal = sanitize(ownerPrincipal)
        val maySeeClientId = ownerClientId != null && (operatorScope || ownerPrincipal == requesterPrincipal)
        return if (maySeeClientId) "$principal/${sanitize(ownerClientId!!)}" else principal
    }

    private fun split(key: String): Pair<String, String?> {
        val separator = key.indexOf(SESSION_KEY_SEPARATOR)
        return if (separator < 0) key to null else key.substring(0, separator) to key.substring(separator + 1)
    }

    /**
     * Escapes anything that could reformat the line it lands on and bounds the result. Printable
     * ASCII passes through; everything else — control characters, but also the bidirectional
     * overrides and line separators that survive an ordinary `isISOControl` check — is rendered as
     * an escape so a holder cannot forge the rest of the message.
     */
    private fun sanitize(value: String): String {
        if (value.isEmpty()) return "<unnamed>"
        val escaped = buildString {
            for (character in value) {
                when {
                    length >= MAX_FRAGMENT_LENGTH -> {
                        append('…')
                        return@buildString
                    }
                    character in ' '..'~' && character != '\'' -> append(character)
                    else -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                }
            }
        }
        return escaped
    }
}
