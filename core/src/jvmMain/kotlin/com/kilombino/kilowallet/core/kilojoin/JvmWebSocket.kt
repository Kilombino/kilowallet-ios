package com.kilombino.kilowallet.core.kilojoin

import java.net.URI
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** The JVM's WebSocket (tests, desktop preview, the interop tool): java.net.http, certificates checked as usual. */
object JvmWebSocket : WebSocketFactory {
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build() }

    override fun open(url: String, listener: WebSocketListener): WebSocket {
        val text = StringBuilder()
        val ws = client.newWebSocketBuilder().header("User-Agent", "Kilowallet")
            .buildAsync(URI(url), object : java.net.http.WebSocket.Listener {
                override fun onText(webSocket: java.net.http.WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    text.append(data)
                    if (last) { val s = text.toString(); text.setLength(0); listener.onText(s) }
                    webSocket.request(1); return null
                }
                override fun onPong(webSocket: java.net.http.WebSocket, message: ByteBuffer): CompletionStage<*>? {
                    listener.onPong(); webSocket.request(1); return null
                }
                override fun onClose(webSocket: java.net.http.WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    listener.onClosed(null); return null
                }
                override fun onError(webSocket: java.net.http.WebSocket, error: Throwable) = listener.onClosed(error.message ?: "error")
            }).get(30, TimeUnit.SECONDS)
        return object : WebSocket {
            private val lock = Any()
            override fun send(text: String): Boolean = runCatching { synchronized(lock) { ws.sendText(text, true).get(15, TimeUnit.SECONDS) }; true }.getOrDefault(false)
            override fun ping(): Boolean = runCatching { synchronized(lock) { ws.sendPing(ByteBuffer.allocate(0)).get(15, TimeUnit.SECONDS) }; true }.getOrDefault(false)
            override fun close() { runCatching { ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS) }; ws.abort() }
        }
    }
}
