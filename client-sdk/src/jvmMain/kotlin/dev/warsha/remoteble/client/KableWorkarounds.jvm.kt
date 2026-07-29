package dev.warsha.remoteble.client

import com.juul.kable.PeripheralBuilder

/**
 * Nothing to apply: Kable's JVM/btleplug factory reads only `logging` and `onServicesDiscovered` off
 * the builder, so `disconnectTimeout`, `observationExceptionHandler` and
 * `forceCharacteristicEqualityByUuid` are accepted and discarded here. Setting any of them would
 * read as configuration while changing nothing.
 */
internal actual fun PeripheralBuilder.applyPlatformWorkarounds() {
    // No-op by design; see the KDoc above.
}
