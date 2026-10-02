package dev.warsha.remoteble.agent

/**
 * This device's LAN-reachable IPv4 address (e.g. `"192.168.1.23"`), or `null` if there is none
 * (no Wi-Fi/LAN interface up). Used to build the `ws://<addr>:<port>/agent` string shown by the
 * mobile status UI — see [dev.warsha.remoteble.agent.ui.AgentApp]'s `addressLabel`.
 */
expect fun lanIPv4Address(): String?

/**
 * The address a client should use to reach an agent bound to [bindHost]: that address, or for a
 * wildcard bind [lan]'s. `null` for a wildcard bind with no LAN address to name, when only an explicit
 * bind can say. Used for every pairing the Kotlin agents show or print; the Rust agent's
 * `pairing_host` makes the same choice.
 */
internal fun pairingHost(bindHost: String, lan: () -> String? = ::lanIPv4Address): String? =
    if (bindHost in WILDCARD_HOSTS) lan() else bindHost

private val WILDCARD_HOSTS = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")
