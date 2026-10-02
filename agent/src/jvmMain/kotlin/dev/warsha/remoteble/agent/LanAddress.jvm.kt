package dev.warsha.remoteble.agent

import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The address of the interface carrying the default route: the one a LAN client most likely reaches
 * this host on, where the first interface listed could as well be a VPN or a container bridge.
 * Connecting a UDP socket picks it without sending anything; `null` with no route (offline). Matches
 * the Rust agent's `routed_ipv4`.
 */
actual fun lanIPv4Address(): String? = runCatching {
    DatagramSocket().use { socket ->
        // TEST-NET-1 (RFC 5737): never answered, and UDP connect sends no packet anyway.
        socket.connect(InetAddress.getByName("192.0.2.1"), 9)
        socket.localAddress.takeUnless { it.isAnyLocalAddress }?.hostAddress
    }
}.getOrNull()
