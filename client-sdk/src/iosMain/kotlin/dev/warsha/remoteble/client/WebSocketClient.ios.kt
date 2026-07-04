package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/** iOS engine: Ktor Darwin (NSURLSession). */
actual fun defaultWebSocketHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}
