package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.IdentifierFormat

// The JVM's Kable handle format is the host radio's native id: a UUID on macOS, a MAC on Windows,
// btleplug's bluez JSON on Linux. Resolve it from os.name the same way the client does.
internal actual fun agentIdentifierFormat(): IdentifierFormat {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("mac") || os.contains("darwin") -> IdentifierFormat.UUID
        os.startsWith("windows") -> IdentifierFormat.MAC_ADDRESS
        else -> IdentifierFormat.BLUEZ_JSON
    }
}
