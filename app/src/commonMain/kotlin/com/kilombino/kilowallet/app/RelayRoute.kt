package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.WebSocket
import com.kilombino.kilowallet.core.kilojoin.WebSocketFactory
import com.kilombino.kilowallet.core.kilojoin.WebSocketListener
import kotlin.concurrent.Volatile

/**
 * The way to the pools' relay (as Android does it): with the app's Tor running, its onion first
 * ([Protocol.RELAY_ONION], plain ws through Tor), then wss://relay.kilombino.com through Tor; the
 * error only when both fail. Without Tor, directly as before. Every way keeps the connection's own
 * Tor circuit ([WebSocketFactory.openIsolated]). Only the transport: nothing that goes on the wire
 * names the onion.
 */
class RelayRoute(
    /** The Tor SOCKS port when Tor runs, else null. */
    private val torPort: () -> Int?,
    /** Opens [url] directly (no Tor). */
    private val direct: (url: String, listener: WebSocketListener) -> WebSocket,
    /** Opens [url] through Tor's SOCKS [port] on the circuit [isolation]. */
    private val viaTor: (url: String, listener: WebSocketListener, port: Int, isolation: String?) -> WebSocket,
) : WebSocketFactory {
    /** How the last connection went: "onion", "wss over Tor" or "direct" (shown in the CI log). */
    @Volatile var last: String = ""
        private set

    override fun open(url: String, listener: WebSocketListener): WebSocket = openIsolated(url, listener, null)

    override fun openIsolated(url: String, listener: WebSocketListener, isolation: String?): WebSocket {
        val port = torPort() ?: return direct(url, listener).also { last = "direct" }
        if (url == Protocol.RELAY) {
            val onion = runCatching { viaTor(Protocol.RELAY_ONION, listener, port, isolation) }
            onion.getOrNull()?.let { last = "onion"; return it }
            val clear = runCatching { viaTor(url, listener, port, isolation) }
            clear.getOrNull()?.let { last = "wss over Tor"; return it }
            throw IllegalStateException("Could not reach the relay through Tor: onion: " +
                (onion.exceptionOrNull()?.message ?: "?") + "; " + url + ": " + (clear.exceptionOrNull()?.message ?: "?"))
        }
        return viaTor(url, listener, port, isolation).also { last = "wss over Tor" }
    }
}
