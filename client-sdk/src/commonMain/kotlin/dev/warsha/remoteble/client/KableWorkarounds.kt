package dev.warsha.remoteble.client

import com.juul.kable.PeripheralBuilder

/**
 * Applies the platform-specific Kable builder options a locally-radioed [com.juul.kable.Peripheral]
 * needs to behave correctly — see [peripheralFor]'s [BleMode.LOCAL] branch.
 *
 * This exists because Kable's builder options are not honoured uniformly: `PeripheralBuilder` is a
 * common `expect class`, so every option compiles on every target, but each platform's factory reads
 * back only the subset it implements and silently drops the rest. Setting an option is therefore not
 * evidence that it applies, and the ones that matter here are the ones that do.
 */
internal expect fun PeripheralBuilder.applyPlatformWorkarounds()
