package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.AgentFingerprint

/** Where a phone agent's TLS identity comes from: Android Keystore, or the iOS Keychain. */
interface AgentTlsProvider {
    /**
     * Loads the identity, creating it on first use. [reset] replaces it first, which makes every
     * paired client fail with an identity error until it pairs again. May block on the platform's
     * key store; callers run it off the main thread.
     */
    suspend fun load(reset: Boolean = false): AgentTls
}

/** A loaded identity: the [fingerprint] clients pin, and the [front] that serves it. */
class AgentTls(val fingerprint: AgentFingerprint, val front: TlsFront.Factory)
