package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.Capabilities

/**
 * The scan-isolation policy advertised by an agent in its handshake.
 *
 * The three concrete values are mutually exclusive. A missing, malformed, or contradictory
 * capability set is intentionally treated as [LEGACY_OR_UNKNOWN], because clients must never infer
 * a safety property from an old agent that did not negotiate it.
 */
public enum class ScanConcurrencyMode {
    MULTIPLEXED,
    SINGLE,
    UNCONTROLLED,
    LEGACY_OR_UNKNOWN,
    ;

    public companion object {
        public fun fromCapabilities(capabilities: Set<String>?): ScanConcurrencyMode {
            val advertised = capabilities.orEmpty().intersect(
                setOf(
                    Capabilities.SCAN_CONCURRENCY_MULTIPLEXED,
                    Capabilities.SCAN_CONCURRENCY_SINGLE,
                    Capabilities.SCAN_CONCURRENCY_UNCONTROLLED,
                ),
            )
            return when (advertised.singleOrNull()) {
                Capabilities.SCAN_CONCURRENCY_MULTIPLEXED -> MULTIPLEXED
                Capabilities.SCAN_CONCURRENCY_SINGLE -> SINGLE
                Capabilities.SCAN_CONCURRENCY_UNCONTROLLED -> UNCONTROLLED
                null -> LEGACY_OR_UNKNOWN
                else -> LEGACY_OR_UNKNOWN
            }
        }
    }
}

/** Resolves the current handshake's scan concurrency policy, waiting for the hello if necessary. */
public suspend fun AgentSession.awaitScanConcurrencyMode(): ScanConcurrencyMode =
    ScanConcurrencyMode.fromCapabilities(awaitCapabilities())
