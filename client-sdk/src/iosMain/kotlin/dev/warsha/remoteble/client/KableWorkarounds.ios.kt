package dev.warsha.remoteble.client

import com.juul.kable.ObsoleteKableApi
import com.juul.kable.PeripheralBuilder

/**
 * Apple's CoreBluetooth can hand back a different `CBCharacteristic` instance than the one an
 * operation was issued against. Kable matches a completion to its pending operation by comparing
 * characteristics **by reference** unless told otherwise, so such a completion is never matched and
 * the operation suspends forever: a read that reaches the peripheral, is answered there, and never
 * resumes, while writes on the same link complete in ~100ms.
 *
 * Rig B measured exactly that against the iOS *agent* and fixed it there
 * (`agent/.../PeripheralByIdentifier.ios.kt`). The same defect is reachable from this module, whose
 * [BleMode.LOCAL] path builds an ordinary Kable peripheral against the client's own radio — an iOS
 * app using LOCAL mode hits it with no agent involved. Apple is the only target where the option is
 * read back at all: the Android and JVM factories drop it and hardcode reference equality.
 */
@OptIn(ObsoleteKableApi::class)
internal actual fun PeripheralBuilder.applyPlatformWorkarounds() {
    forceCharacteristicEqualityByUuid = true
}
