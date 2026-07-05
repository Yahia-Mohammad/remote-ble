package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.IdentifierFormat

// The JVM's Kable Identifier wraps the host radio's native id, parsed by a per-host-OS native
// parser: a UUID on macOS, a MAC on Windows, a bluez id on Linux. Resolve from os.name.
internal actual fun currentIdentifierFormat(): IdentifierFormat {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("mac") || os.contains("darwin") -> IdentifierFormat.UUID
        os.startsWith("windows") -> IdentifierFormat.MAC_ADDRESS
        else -> IdentifierFormat.BLUEZ_JSON
    }
}
