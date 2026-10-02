@file:OptIn(ExperimentalAtomicApi::class)

package dev.warsha.remoteble.client

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Identity mismatches awaiting the request failure each one caused, by origin. For an engine that
 * reports a refused certificate only as a generic failure (NSURLSession's cancelled challenge): the
 * refusal is recorded where the certificate is seen, and taken back when a request to the same
 * origin fails. Keyed so that a request to another address, failing for its own reason at the same
 * moment, is never reported as this mismatch.
 */
internal class PendingRefusals {
    private val pending = AtomicReference(emptyMap<String, AgentIdentityMismatchException>())

    fun record(origin: String, refusal: AgentIdentityMismatchException) {
        while (true) {
            val current = pending.load()
            if (pending.compareAndSet(current, current + (origin to refusal))) return
        }
    }

    /** The refusal recorded for [origin], removed; `null` if there is none. */
    fun take(origin: String): AgentIdentityMismatchException? {
        while (true) {
            val current = pending.load()
            val refusal = current[origin] ?: return null
            if (pending.compareAndSet(current, current - origin)) return refusal
        }
    }

    companion object {
        /** `host:port`. NSURLProtectionSpace gives an IPv6 host without brackets; either spelling matches. */
        fun origin(host: String, port: Number): String =
            "${host.removePrefix("[").removeSuffix("]").lowercase()}:${port.toLong()}"
    }
}
