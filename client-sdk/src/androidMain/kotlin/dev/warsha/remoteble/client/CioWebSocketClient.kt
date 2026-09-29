package dev.warsha.remoteble.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * A WebSocket [HttpClient] on Ktor's CIO engine, for reaching a plain `ws://` agent from an app
 * that has not opted into cleartext traffic.
 *
 * [defaultWebSocketHttpClient] runs on OkHttp, which enforces Android's network security policy
 * and so refuses `ws://` in any app targeting API 28+ without a cleartext exception. CIO opens
 * plain sockets, which that policy does not govern. Phone agents are LAN-facing and speak `ws://`
 * by default, and listing every LAN address an agent might have in a network security config is
 * impractical, so this is usually the simpler fix.
 *
 * It bypasses the policy for this client only; the rest of the app is unaffected. `wss://` still
 * works, but CIO's own TLS stack supports TLS 1.2 only, so prefer the default client for an agent
 * behind a TLS 1.3-only proxy.
 */
public fun cioWebSocketHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}
