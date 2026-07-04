package dev.warsha.remoteble.agent

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The desktop CLI agent binds `0.0.0.0` and prints that literally (see `Main.kt`) rather than
 * resolving a single LAN address, so this actual exists only to satisfy the `commonMain` expect
 * for the `jvm()` target; nothing in the CLI path calls it today.
 */
actual fun lanIPv4Address(): String? =
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
