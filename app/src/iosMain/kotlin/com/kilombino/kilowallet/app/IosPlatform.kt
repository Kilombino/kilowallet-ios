package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kilombino.kilowallet.core.chain.CertificateChangedException
import com.kilombino.kilowallet.core.chain.ElectrumException
import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.chain.TransportFactory
import com.kilombino.kilowallet.core.chain.isOnionHost
import com.kilombino.kilowallet.core.kilojoin.WebSocket
import com.kilombino.kilowallet.core.kilojoin.WebSocketFactory
import com.kilombino.kilowallet.core.kilojoin.WebSocketListener
import com.kilombino.kilowallet.core.tor.Tor
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.posix.memcpy

class IosPlatform(private val bridge: IosBridge, override val demoScreen: String?) : AppPlatform {

    override val seeds: SeedStore = if (demoScreen != null) MemorySeedStore() else object : SeedStore {
        override fun hasSeed() = bridge.keychainHas()
        override fun save(secret: String, done: (String?) -> Unit) = done(bridge.keychainSave(secret))
        override fun load(reason: String, done: (String?, String?) -> Unit) = bridge.keychainLoad(reason) { s, e -> done(s, e) }
        override fun delete() = bridge.keychainDelete()
    }

    override val prefs: Prefs = if (demoScreen != null) MemoryPrefs() else object : Prefs {
        private val d = NSUserDefaults.standardUserDefaults
        override fun get(key: String): String? = d.stringForKey("kw.$key")
        override fun put(key: String, value: String?) {
            if (value == null) d.removeObjectForKey("kw.$key") else d.setObject(value, forKey = "kw.$key")
        }
        override fun flush() { d.synchronize() }
    }

    init {
        // Tor when the app was built with Arti; screenshot demos only for the live ones.
        if (bridge.torAvailable() && (demoScreen == null || demoScreen in TOR_DEMOS)) {
            val t = IosTor(bridge)
            Tor.host = t.host
            Tor.engine = t.engine
        }
    }

    /**
     * Electrum through Tor as Android's ElectrumClient: a .onion always, every server with
     * "Connect to the server through Tor". Tor is started if needed and waited for; if it doesn't
     * come up, nothing is sent without it.
     */
    private fun electrumSocks(host: String): Int {
        val onion = isOnionHost(host)
        if (!onion && !Tor.electrum) return 0
        val notRunning = if (onion) "$host is a .onion: it needs Tor, and Tor is not running on this phone (or did not start)."
            else "Connecting through Tor is on, but Tor is not running on this phone (or did not start): nothing was sent without it."
        return runBlocking { Tor.startAndWait(90_000) } ?: throw ElectrumException(notRunning)
    }

    override val transports = TransportFactory { host, port, tls, pin ->
        val socks = electrumSocks(host)
        val c = if (socks > 0) bridge.openConnectionVia(host, port, tls, pin, socks) else bridge.openConnection(host, port, tls, pin)
        if (c.certificateChanged()) { c.close(); throw CertificateChangedException("$host:$port", c.fingerprint() ?: "") }
        c.error()?.let { c.close(); throw ElectrumException(it) }
        object : LineTransport {
            override val fingerprint: String? = c.fingerprint()
            override fun writeLine(line: String) { c.writeLine(line)?.let { throw ElectrumException(it) } }
            override fun readLine(): String? = c.readLine()
            override fun close() = c.close()
        }
    }

    /**
     * The pools' relay (RelayRoute): with Tor running, its onion first (plain ws through Tor, over
     * Network.framework: App Transport Security, which refuses cleartext ws:// for URLSession, does
     * not apply there), then wss through Tor; each connection on its own circuit. Without Tor, directly.
     */
    private fun wrap(ws: IosSocket): WebSocket {
        ws.error()?.let { ws.close(); throw IllegalStateException("Could not reach the relay: $it") }
        return object : WebSocket {
            override fun send(text: String) = ws.send(text)
            override fun ping() = ws.ping()
            override fun close() = ws.close()
        }
    }
    private fun listen(listener: WebSocketListener) = object : IosSocketListener {
        override fun onText(text: String) = listener.onText(text)
        override fun onPong() = listener.onPong()
        override fun onClosed(error: String?) = listener.onClosed(error)
    }
    val relayRoute = RelayRoute(
        torPort = { Tor.proxyIfRunning() },
        direct = { url, l -> wrap(bridge.openWebSocket(url, listen(l))) },
        viaTor = { url, l, port, iso ->
            if (url.startsWith("ws://") && url.substringAfter("://").substringBefore('/').substringBefore(':').endsWith(".onion"))
                wrap(bridge.openOnionWebSocket(url, listen(l), port, iso))
            else wrap(bridge.openWebSocketVia(url, listen(l), port, iso))
        })
    override val sockets: WebSocketFactory = relayRoute

    /** Back in the foreground: Tor's port is checked and Tor started again if iOS took it back. */
    override fun onForeground(action: () -> Unit) {
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null,
            NSOperationQueue.mainQueue) { _ -> action() }
    }

    override val unlockName: String get() = bridge.unlockName()

    @OptIn(ExperimentalForeignApi::class)
    override fun qrImage(text: String): ImageBitmap? {
        val data = bridge.qrPng(text) ?: return null
        val bytes = data.toByteArray()
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    override val canScanQr: Boolean get() = bridge.canScanQr()
    override fun scanQr(done: (String?) -> Unit) = bridge.scanQr { done(it) }
    override fun scanQrParts(onPart: (String) -> Boolean, onEnd: () -> Unit) = bridge.scanQrParts(object : IosQrPartListener {
        override fun onPart(text: String): Boolean = onPart(text)
        override fun onEnd() = onEnd()
    })
    override fun markReady(name: String) = bridge.markReady(name)
    override val appVersion: String get() = bridge.appVersion()
    override fun httpGet(url: String, done: (String?) -> Unit) = bridge.httpGet(url) { done(it) }
    override fun saveFile(name: String, data: ByteArray, done: (String?) -> Unit) = bridge.saveFile(name, data.toNSData()) { done(it) }
    override fun openFile(done: (ByteArray?) -> Unit) = bridge.openFile { done(it?.toBytes()) }

    /**
     * The node when the app was built with the engine. Screenshot demos use it only for the live
     * start ("ln-live"), with a throwaway wallet and the password kept in memory.
     */
    override val lightning: LightningPlatform? =
        if (bridge.lndAvailable() && (demoScreen == null || demoScreen == "ln-live")) IosLightning(bridge, memoryPassword = demoScreen != null) else null

    /** Ark when the app was built with the engine; screenshot demos only for the live start ("ark-live"). */
    override val ark: ArkPlatform? =
        if (bridge.arkAvailable() && (demoScreen == null || demoScreen == "ark-live")) IosArk(bridge, prefs, memoryWords = demoScreen != null) else null
}

/** Screenshot demos that run the real Tor. */
private val TOR_DEMOS = setOf("ln-live", "tor-live", "tor-log")

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** Screenshot mode never touches the real Keychain or settings. */
private class MemorySeedStore : SeedStore {
    private var s: String? = null
    override fun hasSeed() = s != null
    override fun save(secret: String, done: (String?) -> Unit) { s = secret; done(null) }
    override fun load(reason: String, done: (String?, String?) -> Unit) = done(s, if (s == null) "no words" else null)
    override fun delete() { s = null }
}

private class MemoryPrefs : Prefs {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}
