package dev.warsha.remoteble.client

import io.ktor.client.HttpClient

/**
 * A platform-default [HttpClient] with WebSocket support, ready for
 * [WebSocketAgentTransport]. Each target binds the conventional Ktor engine
 * (JVM: CIO, Android: OkHttp, iOS: Darwin). Apps that need custom engine config
 * (proxies, TLS pinning, timeouts) can build their own `HttpClient { WebSockets }`
 * and hand it to the transport instead.
 */
expect fun defaultWebSocketHttpClient(): HttpClient
