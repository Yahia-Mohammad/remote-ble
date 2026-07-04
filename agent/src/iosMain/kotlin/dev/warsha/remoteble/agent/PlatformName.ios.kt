package dev.warsha.remoteble.agent

import platform.UIKit.UIDevice

actual fun platformName(): String {
    val device = UIDevice.currentDevice
    return "ios/${device.model} ${device.systemName} ${device.systemVersion}"
}
