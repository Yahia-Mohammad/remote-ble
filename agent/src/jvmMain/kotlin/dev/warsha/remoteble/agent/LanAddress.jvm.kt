package dev.warsha.remoteble.agent

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The desktop CLI binds loopback by default and requires an explicit LAN bind; this actual exists
 * only to satisfy the `commonMain` expect for the `jvm()` target.
 */
actual fun lanIPv4Address(): String? =
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
