package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.Capabilities

/** The agent-side scan concurrency policy, fixed for one process lifetime. */
enum class ScanConcurrencyMode(val capability: String) {
    MULTIPLEXED(Capabilities.SCAN_CONCURRENCY_MULTIPLEXED),
    SINGLE(Capabilities.SCAN_CONCURRENCY_SINGLE),
    UNCONTROLLED(Capabilities.SCAN_CONCURRENCY_UNCONTROLLED),
    ;

    companion object {
        fun parse(value: String): ScanConcurrencyMode = when (value.lowercase()) {
            "multiplexed" -> MULTIPLEXED
            "single" -> SINGLE
            "uncontrolled" -> UNCONTROLLED
            else -> error("REMOTE_BLE_SCAN_CONCURRENCY must be 'multiplexed', 'single', or 'uncontrolled', got '$value'")
        }
    }
}
