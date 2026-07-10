package dev.warsha.remoteble.agent

import com.juul.kable.Peripheral
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnProfile
import dev.warsha.remoteble.protocol.ErrorKind

// CoreBluetooth exposes no connection-priority/interval-request API; Apple manages link
// parameters itself, so there's nothing for this agent to call.
internal actual fun agentConnParamsSupported(): Boolean = false

internal actual suspend fun applyConnParams(
    peripheral: Peripheral,
    profile: ConnProfile,
    hint: ConnParamHint?,
): Boolean = bleError(ErrorKind.UNSUPPORTED, message = "connection parameters not supported")
