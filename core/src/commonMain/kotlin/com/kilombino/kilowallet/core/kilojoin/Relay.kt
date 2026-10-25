package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.platform.ioDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One open WebSocket (wss only), as the platform provides it: URLSessionWebSocketTask on iOS. */
interface WebSocket {
    /** Queue a text frame; false when the socket is gone. */
    fun send(text: String): Boolean
    /** A WebSocket ping; the pong comes back through [WebSocketListener.onPong]. False when the socket is gone. */
    fun ping(): Boolean
    fun close()
}

/** Called on any thread. */
interface WebSocketListener {
    fun onText(text: String)
    fun onPong()
    fun onClosed(error: String?)
}

fun interface WebSocketFactory {
    /** Open [url] and return once the handshake is done; throws when it fails. Blocking. */
    fun open(url: String, listener: WebSocketListener): WebSocket
}

/**
 * A minimal nostr relay client, the Android app's `coinjoin/RelayClient.kt` over the platform's
 * WebSocket: the NIP-01 REQ / EVENT / CLOSE verbs, an OK awaited per published event, EOSE per
 * subscription. [onEvent] gets every verified event of every open subscription, on the relay's
 * own coroutine.
 */
class RelayClient(
    private val url: String,
    private val sockets: WebSocketFactory,
    private val onEvent: suspend (subId: String, event: NostrEvent) -> Unit,
    private val onClosed: (String?) -> Unit = {},
) {
    private var socket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    /** Frames as they come; null when the socket closed. */
    private val inbox = Channel<String?>(Channel.UNLIMITED)
    private val lock = Mutex()
    private val pendingOk = HashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    private val pendingEose = HashMap<String, CompletableDeferred<Unit>>()

    var connected = false; private set
    /** When the relay last sent us anything (a frame of any kind, pongs included), in ms. */
    var lastHeard = 0L; private set

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** Blocking: opens the WebSocket. */
    fun connect() {
        require(url.startsWith("wss://")) { "only wss:// relays" }
        val listener = object : WebSocketListener {
            override fun onText(text: String) { lastHeard = nowMs(); inbox.trySend(text) }
            override fun onPong() { lastHeard = nowMs() }
            override fun onClosed(error: String?) {
                if (!connected) return
                connected = false
                inbox.trySend(null)
                onClosed(error)
            }
        }
        socket = sockets.open(url, listener)
        connected = true; lastHeard = nowMs()
        scope.launch {
            for (text in inbox) handle(text)
            scope.cancel()
        }
    }

    private suspend fun handle(text: String?) {
        if (text == null) {
            lock.withLock {
                pendingOk.values.forEach { it.complete(false to "disconnected") }; pendingOk.clear()
                pendingEose.values.forEach { it.complete(Unit) }; pendingEose.clear()
            }
            return
        }
        val a = runCatching { J.parseArray(text) }.getOrNull() ?: return
        fun s(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.content ?: ""
        when (s(0)) {
            "EVENT" -> {
                val ev = runCatching { NostrEvent.parse(a[2].jsonObject) }.getOrNull() ?: return
                if (ev.verify()) runCatching { onEvent(s(1), ev) }
            }
            "EOSE", "CLOSED" -> lock.withLock { pendingEose.remove(s(1)) }?.complete(Unit)
            "OK" -> lock.withLock { pendingOk.remove(s(1)) }?.complete((s(2) == "true") to s(3))
        }
    }

    private fun sendText(s: String): Boolean = socket?.send(s) ?: false

    /** Publishes [ev] and waits for the relay's OK. Returns (accepted, message). */
    suspend fun publish(ev: NostrEvent, timeoutSec: Long = 15): Pair<Boolean, String> {
        val f = CompletableDeferred<Pair<Boolean, String>>()
        lock.withLock { pendingOk[ev.id] = f }
        if (!sendText(J.arr(listOf("EVENT", ev.toJson())).toString())) {
            lock.withLock { pendingOk.remove(ev.id) }
            return false to "not connected"
        }
        return withTimeoutOrNull(timeoutSec * 1000) { f.await() } ?: run { lock.withLock { pendingOk.remove(ev.id) }; false to "timeout" }
    }

    /** Opens a subscription; with [waitEose] it returns once the stored events are delivered. */
    suspend fun subscribe(subId: String, filters: List<JsonObject>, waitEose: Boolean = true, timeoutSec: Long = 15) {
        val f = CompletableDeferred<Unit>()
        if (waitEose) lock.withLock { pendingEose[subId] = f }
        sendText(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subId)) + filters).toString())
        if (waitEose) withTimeoutOrNull(timeoutSec * 1000) { f.await() } ?: lock.withLock { pendingEose.remove(subId) }
    }

    /** A WebSocket ping; [lastHeard] moves when the pong comes back. */
    fun ping(): Boolean = socket?.ping() ?: false

    fun unsubscribe(subId: String) { sendText(J.arr(listOf("CLOSE", subId)).toString()) }

    fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        inbox.trySend(null)
        inbox.close()
    }
}

/** The open pools' announcements since [since] (seconds). */
fun poolsFilter(since: Long): JsonObject = filter(listOf(Protocol.KIND_POOL), t = Protocol.TAG, since = since)

/** A NIP-01 filter. */
internal fun filter(kinds: List<Int>, p: String? = null, authors: String? = null, d: String? = null, t: String? = null,
                    since: Long? = null): JsonObject = J.obj(
    "kinds" to kinds, "#p" to p?.let { listOf(it) }, "authors" to authors?.let { listOf(it) },
    "#d" to d?.let { listOf(it) }, "#t" to t?.let { listOf(it) }, "since" to since,
)
