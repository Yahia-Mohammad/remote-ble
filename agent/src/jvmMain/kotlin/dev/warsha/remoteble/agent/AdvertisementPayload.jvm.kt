package dev.warsha.remoteble.agent

import com.juul.kable.Advertisement

// Kable's btleplug backend keeps its service-data map internal and offers only lookups by UUID, so
// service data that isn't for an advertised service UUID can't be found.
internal actual fun advertisementPayload(advertisement: Advertisement): AdvertisementPayload = lookedUpPayload(advertisement)
