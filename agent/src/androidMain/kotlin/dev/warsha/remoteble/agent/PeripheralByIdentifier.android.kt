package dev.warsha.remoteble.agent

import com.juul.kable.Identifier
import com.juul.kable.Peripheral

actual fun peripheralByIdentifier(identifier: Identifier): Peripheral = Peripheral(identifier)
