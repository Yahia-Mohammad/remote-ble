package dev.warsha.remoteble.agent

import dev.warsha.remoteble.protocol.BleRadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// Kable's JVM (btleplug) backend exposes no adapter-state API, and btleplug's own
// `Manager`/`Adapter` surface has no power-state event either — so there is nothing to observe
// without going around Kable entirely (BlueZ D-Bus on Linux, IOBluetooth on macOS). Returning null
// keeps the desktop agent from advertising `radio.state`, which is the honest answer: a client
// learns the agent cannot tell it, rather than being told the radio is fine.
internal actual fun agentRadioStateSource(scope: CoroutineScope): StateFlow<BleRadioState>? = null
