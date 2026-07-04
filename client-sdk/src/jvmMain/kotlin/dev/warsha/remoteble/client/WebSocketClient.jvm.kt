package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** JVM engine: Ktor CIO. */
actual fun defaultWebSocketHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}
