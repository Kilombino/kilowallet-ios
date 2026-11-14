package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class DirectoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The platform's way to a directory: a line stream (`\n`-split, written lines get `\n`) to
 * [host]:[port]. On iOS, Network.framework through the app's Tor SOCKS port, the name resolved by
 * Tor; blocking. Throws when no connection can be made.
 */
fun interface DirectoryTransport {
    fun open(host: String, port: Int): LineTransport
}

/**
 * One connection to a JoinMarket directory, as a taker that never listens
 * (`jmcore/directory_client.py`, `jmcore/network.py`). Kilowallet for Android's DirectoryClient
 * over a platform [DirectoryTransport] instead of a java.net.Socket.
 *
 * [connect] opens the stream ([viaTor]: through Tor, so a `.onion` works), sends the HANDSHAKE
 * with `NOT-SERVING-ONION`, checks the DN_HANDSHAKE reply and runs JMP-0005 nick authentication
 * against [directoryId]. A reader coroutine answers every PING with a PONG, keeps [peers] from
 * PEERLIST frames and queues every other frame for [receive]. Privmsgs are signed with
 * [identity] ([sendPrivate]).
 *
 * Limits are Python's: 2 MiB per received line, 1,024 queued frames / 8 MiB, five consecutive
 * unreadable frames, 64-byte nicks and 300-byte locations in peerlists, 20,000 peers. A nick-auth
 * frame after the handshake is a protocol violation and closes the connection. DISCONNECT (801)
 * is never sent: [close] just closes the stream.
 */
@OptIn(ExperimentalAtomicApi::class)
class DirectoryClient(
    val host: String,
    val port: Int,
    val identity: NickIdentity,
    /** The wire network: `xbt` on mainnet, `xbt-regtest` in the lab. */
    val network: String = "xbt",
    /**
     * The nick-auth identity expected for this endpoint (for an onion, its lowercase `host:port`).
     * Null means no `nick_auth` feature is offered, which [requireVerified] refuses.
     */
    val directoryId: String? = null,
    /** `require_verified`: the directory must advertise and complete nick authentication. */
    val requireVerified: Boolean = true,
    /** How to open the stream; null: none (a test of the refusals). */
    private val transport: DirectoryTransport? = null,
    /** [transport] goes through Tor. Required for a `.onion`. */
    private val viaTor: Boolean = false,
    /** Non-onion directories only on regtest unless explicitly allowed (`allow_clearnet_connections`). */
    private val allowClearnet: Boolean = network == "xbt-regtest",
    /** `connection_timeout`: TCP + SOCKS + circuit, and the wait for the handshake reply. */
    private val connectTimeoutMs: Int = 120_000,
    /** Wait for the first PEERLIST chunk on a directory with `peerlist_features`. */
    private val peerlistTimeoutMs: Long = 60_000,
    /** A peerlist answer is complete after this long without a new chunk. */
    private val peerlistQuietMs: Long = 5_000,
) {
    /** A peer as the directory lists it: `nick;location[;D][;F:feat+feat]`. */
    data class Peer(val nick: String, val location: String, val features: Set<String>)

    val nick: String get() = identity.nick

    /** True once the directory accepted our nick proof (807 `ok`). */
    @Volatile var authenticated = false
        private set
    @Volatile var motd: String? = null
        private set
    @Volatile var directoryNick: String? = null
        private set
    @Volatile var directoryFeatures: Set<String> = emptySet()
        private set

    private val peerMap = AtomicReference<Map<String, Peer>>(emptyMap())
    /** The peers currently listed (nicks that left with `;D` are removed). */
    val peers: Map<String, Peer> get() = HashMap(peerMap.load())

    @Volatile private var conn: LineTransport? = null
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    /** Frames for [receive] (and, during the handshake, its replies). */
    private val frames = Channel<Wire.Frame>(Channel.UNLIMITED)
    private val queuedFrames = AtomicInt(0)
    private val queuedBytes = AtomicLong(0L)
    @Volatile private var peerlistSink: Channel<List<Peer>>? = null
    @Volatile private var lastPeerlistRequest = Long.MIN_VALUE / 2
    @Volatile private var closed = false
    /** Until the handshake is over, the reader hands handshake and nick-auth frames to [connect]. */
    @Volatile private var inHandshake = true

    companion object {
        const val MAX_RECEIVED_LINE = 2 * 1024 * 1024
        /** The directory drops any line longer than its `max_line_length`; refused here first. */
        const val MAX_SENT_LINE = 64 * 1024
        const val MAX_QUEUED_FRAMES = 1_024
        const val MAX_QUEUED_BYTES = 8L * 1024 * 1024
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val MAX_PEERS = 20_000
        const val MAX_NICK_BYTES = 64
        const val MAX_LOCATION_BYTES = 300
        const val MAX_FEATURES = 64
        const val NICK_AUTH_STEP_MS = 30_000
        const val PEERLIST_MIN_INTERVAL_MS = 60_000L
        const val NOT_SERVING_ONION = "NOT-SERVING-ONION"

        fun isOnion(host: String) = host.trim().lowercase().trimEnd('.').endsWith(".onion")

        private val FIELD_DELIMITERS = setOf(',', ';', '!')

        private fun safeField(s: String, delimiters: Set<Char>) =
            s.isNotEmpty() && s.all { it !in delimiters && !it.isWhitespace() && !it.isISOControl() }

        /**
         * `parse_peerlist_entry` for every entry of one PEERLIST line: (peer, left) pairs. Entries
         * without `;` (reference-implementation metadata) and malformed ones are skipped.
         */
        fun parsePeerlist(line: String): List<Pair<Peer, Boolean>> = line.split(",").mapNotNull { entry ->
            if (entry.isBlank() || ';' !in entry) return@mapNotNull null
            val parts = entry.split(";")
            val (nick, location) = parts[0] to parts[1]
            if (!safeField(nick, FIELD_DELIMITERS) || !safeField(location, FIELD_DELIMITERS)) return@mapNotNull null
            if (nick.encodeToByteArray().size > MAX_NICK_BYTES || location.encodeToByteArray().size > MAX_LOCATION_BYTES) return@mapNotNull null
            var left = false
            var features = emptySet<String>()
            for (p in parts.drop(2)) when {
                p == "D" -> left = true
                p.startsWith("F:") -> {
                    val s = p.substring(2)
                    if (',' in s) return@mapNotNull null
                    features = s.split('+').filter { it.isNotEmpty() }.toSet()
                    if (features.size > MAX_FEATURES || features.any { !safeField(it, FIELD_DELIMITERS + '+') }) return@mapNotNull null
                }
            }
            Peer(nick, location, features) to left
        }
    }

    /**
     * Connect and handshake (`DirectoryClient.connect` / `_handshake` / `_authenticate_nick`).
     * Throws [DirectoryException] on any refusal; the stream is closed then. Cancelling the
     * caller closes the stream (a connection still opening is closed once it opens).
     */
    suspend fun connect() {
        check(conn == null && !closed) { "connect() once per client" }
        if (requireVerified && directoryId == null) {
            throw DirectoryException("No expected nick authentication directory identity configured for $host:$port")
        }
        try {
            open()
            handshake()
        } catch (e: CancellationException) {
            closeQuietly(); throw e
        } catch (e: Exception) {
            closeQuietly()
            throw if (e is DirectoryException) e else DirectoryException("Connection failed: ${e.message}", e)
        }
    }

    private suspend fun open() {
        val onion = isOnion(host)
        if (!onion && !allowClearnet) {
            throw DirectoryException("Refusing direct TCP connection to non-onion directory $host:$port")
        }
        if (onion && !viaTor) throw DirectoryException("$host is a .onion: it needs Tor")
        val t = transport ?: throw DirectoryException("No way to reach $host:$port")
        val opening = scope.async { t.open(host, port) }
        val c = try {
            withTimeoutOrNull(connectTimeoutMs.toLong()) { opening.await() }
        } catch (e: CancellationException) {
            opening.invokeOnCompletion { if (it == null) runCatching { @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) opening.getCompleted().close() } }
            throw e
        } ?: run {
            opening.invokeOnCompletion { if (it == null) runCatching { @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) opening.getCompleted().close() } }
            throw DirectoryException("Timed out connecting to $host:$port")
        }
        if (closed) { c.close(); throw DirectoryException("Connection closed") }
        conn = c
        scope.launch { readLoop(c) }
    }

    /** The next handshake-phase frame within [timeoutMs], or null on timeout. */
    private suspend fun handshakeFrame(timeoutMs: Long): Wire.Frame? = try {
        withTimeoutOrNull(timeoutMs) { frames.receive() }
    } catch (e: ClosedReceiveChannelException) {
        throw DirectoryException("Connection closed by the directory")
    }

    private suspend fun handshake() {
        val line = NickAuth.handshakeLine(nick, network, nickAuth = directoryId != null)
        sendRaw(Wire.envelope(Wire.HANDSHAKE, line))
        val reply = handshakeFrame(connectTimeoutMs.toLong()) ?: throw DirectoryException("No handshake reply")
        NickAuth.checkDirectoryReply(reply, network, requireVerified)?.let { throw DirectoryException(it) }
        val o = JbJson.obj(reply.line)
        motd = JbJson.prim(o, "motd")?.content
        directoryNick = JbJson.prim(o, "nick")?.content
        directoryFeatures = (o["features"] as? kotlinx.serialization.json.JsonObject)?.filter { JbJson.isTrue(it.value) }?.keys.orEmpty()
        if (directoryId != null && "nick_auth" in directoryFeatures) authenticate(line, directoryId)
        inHandshake = false
    }

    private suspend fun authenticate(handshakeLine: String, expectedId: String) {
        val step = minOf(connectTimeoutMs, NICK_AUTH_STEP_MS).toLong()
        val ch = handshakeFrame(step) ?: throw DirectoryException("Nick authentication timed out")
        if (ch.type != Wire.NICK_AUTH_CHALLENGE) throw DirectoryException("Unexpected nick authentication challenge type: ${ch.type}")
        val challenge = runCatching { NickAuth.parseChallenge(ch.line) }.getOrNull()
            ?: throw DirectoryException("Invalid nick authentication response")
        if (challenge.directoryId != expectedId) {
            throw DirectoryException("Nick authentication directory-id mismatch: expected $expectedId, received ${challenge.directoryId}")
        }
        sendRaw(Wire.envelope(Wire.NICK_AUTH_PROOF, NickAuth.proofLine(identity, challenge, handshakeLine)))
        val result = handshakeFrame(step) ?: throw DirectoryException("Nick authentication timed out")
        if (result.type != Wire.NICK_AUTH_RESULT) throw DirectoryException("Unexpected nick authentication result type: ${result.type}")
        if (!NickAuth.resultOk(result.line)) throw DirectoryException("Directory rejected nick authentication")
        authenticated = true
    }

    private suspend fun sendRaw(frame: String) {
        val size = frame.encodeToByteArray().size
        if (size > MAX_SENT_LINE) throw DirectoryException("Message too large for the directory: $size bytes")
        val c = conn ?: throw DirectoryException("Not connected")
        writeLock.withLock {
            // `\r` + the transport's `\n`: the directory reads `\r\n`-terminated lines.
            try { withContext(ioDispatcher) { c.writeLine("$frame\r") } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw DirectoryException("Send failed: ${e.message}", e)
            }
        }
    }

    /** Send one raw frame (already a `{"type", "line"}` JSON string). */
    suspend fun send(frame: String) {
        if (closed) throw DirectoryException("Connection closed")
        sendRaw(frame)
    }

    /** `send_public_message`: `<nick>!PUBLIC!<message>`, unsigned. */
    suspend fun sendPublic(message: String) = send(Wire.envelope(Wire.PUBMSG, "$nick!PUBLIC!$message"))

    /** `send_private_message`: `<nick>!<to>!<command> <data> <pubkey> <sig>`, signed over `data + onion-network`. */
    suspend fun sendPrivate(to: String, command: String, data: String) = send(Session.privmsg(identity, to, command, data))

    /**
     * The next PRIVMSG/PUBMSG (or other non-control) frame, or null after [timeoutMs]. PING,
     * PEERLIST and nick-auth frames never come out here. Throws once the connection is gone.
     */
    suspend fun receive(timeoutMs: Long): Wire.Frame? {
        val f = try {
            withTimeoutOrNull(timeoutMs) { frames.receive() }
        } catch (e: ClosedReceiveChannelException) {
            throw DirectoryException("Connection closed")
        } catch (e: DirectoryException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DirectoryException(e.message ?: "Connection lost", e)
        } ?: return null
        queuedFrames.addAndFetch(-1); queuedBytes.addAndFetch(-f.line.length.toLong())
        return f
    }

    /**
     * GETPEERLIST and its chunked answer (`_fetch_peerlist_result`): the first chunk within
     * [peerlistTimeoutMs] (the handshake timeout for a directory without `peerlist_features`),
     * then chunks until 5 s pass without one. At most once per 60 s: null when rate-limited or
     * when nothing came. Every PEERLIST also updates [peers].
     */
    suspend fun getPeerlist(): List<Peer>? {
        val now = monoMillis()
        if (now - lastPeerlistRequest < PEERLIST_MIN_INTERVAL_MS) return null
        lastPeerlistRequest = now
        val sink = Channel<List<Peer>>(Channel.UNLIMITED)
        peerlistSink = sink
        try {
            val start = monoMillis()
            send(Wire.envelope(Wire.GETPEERLIST, ""))
            val first = if ("peerlist_features" in directoryFeatures) peerlistTimeoutMs else connectTimeoutMs.toLong()
            val got = LinkedHashMap<String, Peer>()
            got.putAll((withTimeoutOrNull(first) { sink.receive() } ?: return null).associateBy { it.nick })
            val deadline = start + first + peerlistQuietMs
            while (true) {
                val left = minOf(peerlistQuietMs, deadline - monoMillis())
                if (left <= 0) break
                val chunk = withTimeoutOrNull(left) { sink.receive() } ?: break
                got.putAll(chunk.associateBy { it.nick })
            }
            return got.values.toList()
        } finally {
            peerlistSink = null
        }
    }

    private suspend fun readLoop(c: LineTransport) {
        var errors = 0
        try {
            while (!closed) {
                val text = withContext(ioDispatcher) { c.readLine() } ?: throw DirectoryException("Connection closed by the directory")
                if (text.length > MAX_RECEIVED_LINE / 4 && text.encodeToByteArray().size > MAX_RECEIVED_LINE) throw DirectoryException("Message too large")
                val f = runCatching { Wire.parse(text) }.getOrNull()
                if (f == null) {
                    if (inHandshake) throw DirectoryException("Unreadable frame from the directory")
                    if (++errors >= MAX_CONSECUTIVE_ERRORS) throw DirectoryException("Too many consecutive errors ($errors)")
                    continue
                }
                errors = 0
                when (f.type) {
                    Wire.PING -> sendRaw(Wire.envelope(Wire.PONG, ""))
                    Wire.PEERLIST -> onPeerlist(f.line)
                    Wire.NICK_AUTH_CHALLENGE, Wire.NICK_AUTH_PROOF, Wire.NICK_AUTH_RESULT ->
                        if (inHandshake) frames.trySend(f)
                        else throw DirectoryException("Out-of-order nick authentication message type: ${f.type}")
                    else -> {
                        if (inHandshake) { frames.trySend(f); continue }
                        if (queuedFrames.addAndFetch(1) > MAX_QUEUED_FRAMES ||
                            queuedBytes.addAndFetch(f.line.length.toLong()) > MAX_QUEUED_BYTES) {
                            throw DirectoryException("Directory message buffer limit exceeded")
                        }
                        frames.trySend(f)
                    }
                }
            }
            frames.close()
        } catch (e: CancellationException) {
            frames.close()
        } catch (e: Exception) {
            frames.close(if (closed) null else (e as? DirectoryException ?: DirectoryException("Connection lost: ${e.message}", e)))
        } finally {
            closeQuietly()
        }
    }

    private fun onPeerlist(line: String) {
        val entries = parsePeerlist(line)
        val listed = ArrayList<Peer>()
        val map = HashMap(peerMap.load())
        for ((peer, left) in entries) {
            if (left) { map.remove(peer.nick); continue }
            if (!map.containsKey(peer.nick) && map.size >= MAX_PEERS) throw DirectoryException("Directory active peer limit exceeded")
            // Features are merged, never downgraded (`_merge_peer_features`).
            val merged = peer.copy(features = (map[peer.nick]?.features.orEmpty() + peer.features).take(MAX_FEATURES).toSet())
            map[peer.nick] = merged
            listed.add(merged)
        }
        peerMap.store(map)
        peerlistSink?.trySend(listed)
    }

    val isConnected: Boolean get() = !closed && conn != null

    private fun closeQuietly() {
        closed = true
        runCatching { conn?.close() }
        frames.close()
    }

    /** Close the stream (no DISCONNECT frame, as Python) and stop the reader. */
    fun close() {
        closeQuietly()
        scope.cancel()
    }
}
