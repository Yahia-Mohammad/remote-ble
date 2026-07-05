package dev.warsha.remoteble.client

import com.juul.kable.Identifier
import com.juul.kable.toIdentifier

// JVM's Identifier wraps the host radio's native id, constructed by a per-host-OS native parser
// (UUID on macOS, MAC on Windows, a bluez id on Linux). A handle in a *different* format — e.g. a
// macOS agent's UUID handle read on a Linux host — makes that parser fail with an opaque Kable FFI
// error, so surface a clear, catchable exception instead. Removed by agent-side handle translation
// in a future release.
internal actual fun deviceHandleToIdentifier(value: String): Identifier =
    try {
        value.toIdentifier()
    } catch (cause: Exception) {
        throw RemoteIdentifierUnavailableException(value, cause)
    }
