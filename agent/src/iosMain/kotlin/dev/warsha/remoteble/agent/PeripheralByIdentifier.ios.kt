package dev.warsha.remoteble.agent

import com.juul.kable.Identifier
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral

/**
 * Apple's CoreBluetooth can hand back a different `CBCharacteristic` instance than the one an
 * operation was issued against. Kable matches a completion to its pending operation by comparing
 * characteristics **by reference** unless told otherwise, so such a completion is never matched and
 * the operation suspends forever — exactly what Rig B measured: a read that reaches the peripheral,
 * is answered there, and never resumes, while writes on the same link complete in ~100ms.
 * `forceCharacteristicEqualityByUuid` switches that comparison to UUID equality.
 */
@OptIn(ObsoleteKableApi::class)
actual fun peripheralByIdentifier(identifier: Identifier): Peripheral = Peripheral(identifier) {
    forceCharacteristicEqualityByUuid = true
}
