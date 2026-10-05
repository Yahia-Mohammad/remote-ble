package dev.warsha.remoteble.agent

import com.juul.kable.Advertisement

// Kable keeps CoreBluetooth's advertisement dictionary private and offers service data only by
// UUID, so service data that isn't for an advertised service UUID can't be found.
internal actual fun advertisementPayload(advertisement: Advertisement): AdvertisementPayload = lookedUpPayload(advertisement)
