package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * JVM engine: Ktor OkHttp, as on Android. Not CIO, whose TLS client corrupts its own buffers and
 * fails about one fresh `wss://` connection in 150 (see client-sdk/build.gradle.kts).
 */
actual fun defaultWebSocketHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}
