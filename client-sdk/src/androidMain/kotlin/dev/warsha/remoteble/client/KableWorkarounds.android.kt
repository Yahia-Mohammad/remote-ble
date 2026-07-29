package dev.warsha.remoteble.client

import com.juul.kable.PeripheralBuilder

/**
 * Nothing to apply: Android's factory honours most of the builder (transport, PHY, threading
 * strategy, auto-connect, `disconnectTimeout`, `observationExceptionHandler`), and every one of
 * those defaults is the right choice for this SDK — direct connect rather than auto-connect, LE
 * transport, 1M PHY. `forceCharacteristicEqualityByUuid` is the one option Android ignores: it
 * hardcodes reference equality, which is correct there because Android's GATT callbacks return the
 * same characteristic instances the operation was issued against.
 */
internal actual fun PeripheralBuilder.applyPlatformWorkarounds() {
    // No-op by design; see the KDoc above.
}
