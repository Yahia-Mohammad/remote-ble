package dev.warsha.remoteble.agent

/**
 * This device's LAN-reachable IPv4 address (e.g. `"192.168.1.23"`), or `null` if there is none
 * (no Wi-Fi/LAN interface up). Used to build the `ws://<addr>:<port>/agent` string shown by the
 * mobile status UI — see [dev.warsha.remoteble.agent.ui.AgentApp]'s `addressLabel`.
 */
expect fun lanIPv4Address(): String?
