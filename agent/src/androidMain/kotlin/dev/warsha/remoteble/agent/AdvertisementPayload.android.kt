package dev.warsha.remoteble.agent

import com.juul.kable.Advertisement
import com.juul.kable.PlatformAdvertisement

// Android hands Kable the raw scan record, advertisement and scan response together, so every
// service-data and manufacturer entry is there to read rather than only those Kable can look up.
internal actual fun advertisementPayload(advertisement: Advertisement): AdvertisementPayload =
    (advertisement as? PlatformAdvertisement)?.bytes?.let(::parseAdvertisingRecord) ?: lookedUpPayload(advertisement)
