package dev.warsha.remoteble.agent

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnProfile

// Kable's Android backend exposes a (synchronous, accept/reject) connection-priority request via
// the Android-only AndroidPeripheral interface — absent from the common Peripheral.
internal actual fun agentConnParamsSupported(): Boolean = true

internal actual suspend fun applyConnParams(
    peripheral: Peripheral,
    profile: ConnProfile,
    hint: ConnParamHint?,
): Boolean = (peripheral as? AndroidPeripheral)?.requestConnectionPriority(profile.toKablePriority()) ?: false

private fun ConnProfile.toKablePriority(): AndroidPeripheral.Priority = when (this) {
    ConnProfile.LOW_POWER -> AndroidPeripheral.Priority.Low
    ConnProfile.BALANCED -> AndroidPeripheral.Priority.Balanced
    ConnProfile.LOW_LATENCY -> AndroidPeripheral.Priority.High
}
