package dev.warsha.remoteble.agent

import com.juul.kable.Peripheral
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.ErrorKind

// Kable's JVM (btleplug) backend exposes no interval/priority control at all — no equivalent of
// Android's requestConnectionPriority — so don't advertise the capability.
internal actual fun agentConnParamsSupported(): Boolean = false

internal actual suspend fun applyConnParams(
    peripheral: Peripheral,
    profile: ConnProfile,
    hint: ConnParamHint?,
): Boolean = bleError(ErrorKind.UNSUPPORTED, message = "connection parameters not supported")
