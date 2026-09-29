package dev.warsha.remoteble.client

import com.juul.kable.Identifier
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Apple's Identifier is a `Uuid`. A macOS/iOS agent mints CoreBluetooth UUID handles, which parse
// cleanly; a non-UUID handle (e.g. a MAC from an Android agent) can't be represented, so surface a
// clear, catchable exception instead of a raw parse failure. Removed by agent-side handle
// translation in a future release.
@OptIn(ExperimentalUuidApi::class)
internal actual fun deviceHandleToIdentifier(value: String): Identifier =
    try {
        Uuid.parse(value)
    } catch (cause: IllegalArgumentException) {
        throw RemoteIdentifierUnavailableException(value, cause)
    }
