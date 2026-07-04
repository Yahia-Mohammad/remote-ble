package dev.warsha.remoteble.agent

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

/**
 * The Wi-Fi interface's (`en0`) IPv4 address via the BSD `getifaddrs` walk (declared under
 * `platform.darwin`, not `platform.posix`, on Apple targets) — CoreBluetooth/Network.framework
 * expose no higher-level "my LAN IP" API, so this is the standard Kotlin/Native cinterop
 * approach. `arpa/inet.h` (`inet_ntop`) isn't bound for iOS, so the dotted-quad string is built
 * by hand from the raw address bytes instead.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun lanIPv4Address(): String? = memScoped {
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return@memScoped null
    try {
        var current = ifap.value
        while (current != null) {
            val entry = current.pointed
            val name = entry.ifa_name?.toKString()
            val addr = entry.ifa_addr
            if (name == "en0" && addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                val sinAddrBytes = addr.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>()
                return@memScoped (0..3).joinToString(".") { i -> (sinAddrBytes[i].toInt() and 0xFF).toString() }
            }
            current = entry.ifa_next
        }
        null
    } finally {
        freeifaddrs(ifap.value)
    }
}
