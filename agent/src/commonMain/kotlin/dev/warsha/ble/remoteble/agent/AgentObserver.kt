package dev.warsha.ble.remoteble.agent

/**
 * Lifecycle hooks the [BleAgent] reports so an outside observer (the JVM status
 * dashboard) can show what's happening, without the agent depending on any UI or
 * platform code. All methods default to no-ops; [None] is the inert implementation
 * used when nobody is watching.
 */
interface AgentObserver {
    /** A free-text activity line attributed to a client connection. */
    fun onClientLog(clientId: Long, message: String) {}

    /** A device became connected through [clientId]'s session. */
    fun onDeviceConnected(clientId: Long, handle: String) {}

    /** A device connected through [clientId]'s session was disconnected. */
    fun onDeviceDisconnected(clientId: Long, handle: String) {}

    /** A scan surfaced [handle] (optionally with a [name]); used to label hardware. */
    fun onDeviceSeen(handle: String, name: String?) {}

    companion object {
        val None: AgentObserver = object : AgentObserver {}
    }
}
