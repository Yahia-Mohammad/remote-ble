package dev.warsha.remoteble.client

import com.juul.kable.Identifier
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Apple's Identifier is a `Uuid`. The agent mints CoreBluetooth UUID handles, so they
// parse cleanly (this matches Kable's own `toIdentifier()` = Uuid.parse on Apple).
@OptIn(ExperimentalUuidApi::class)
internal actual fun deviceHandleToIdentifier(value: String): Identifier = Uuid.parse(value)
