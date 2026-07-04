package dev.warsha.remoteble.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address

actual fun lanIPv4Address(): String? {
    val context = androidAgentContext ?: return null
    linkPropertiesIPv4(context)?.let { return it }
    return wifiManagerIPv4(context)
}

private fun linkPropertiesIPv4(context: Context): String? {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = connectivityManager.activeNetwork ?: return null
    val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
    return linkProperties.linkAddresses
        .mapNotNull { it: LinkAddress -> it.address as? Inet4Address }
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}

@Suppress("DEPRECATION")
private fun wifiManagerIPv4(context: Context): String? {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val ipAddress = wifiManager.connectionInfo?.ipAddress ?: return null
    if (ipAddress == 0) return null
    return Formatter.formatIpAddress(ipAddress)
}
