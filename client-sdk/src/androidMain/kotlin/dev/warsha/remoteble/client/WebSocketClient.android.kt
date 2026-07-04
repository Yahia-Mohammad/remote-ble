package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/** Android engine: Ktor OkHttp. */
actual fun defaultWebSocketHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}
