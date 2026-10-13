package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kilombino.kilowallet.core.chain.CertificateChangedException
import com.kilombino.kilowallet.core.chain.ElectrumException
import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.chain.TransportFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.posix.memcpy

class IosPlatform(private val bridge: IosBridge, override val demoScreen: String?) : AppPlatform {

    override val seeds: SeedStore = if (demoScreen != null) MemorySeedStore() else object : SeedStore {
        override fun hasSeed(walletId: String) = when (bridge.keychainStatus(walletId)) {
            "present" -> true
            "missing" -> false
            else -> error("Keychain is unavailable. Unlock the device and reopen the app.")
        }
        override fun save(walletId: String, secret: String, done: (String?) -> Unit) = done(bridge.keychainSave(walletId, secret))
        override fun load(walletId: String, reason: String, done: (String?, String?) -> Unit) = bridge.keychainLoad(walletId, reason) { s, e -> done(s, e) }
        override fun delete(walletId: String) = bridge.keychainDelete(walletId)
    }

    override val prefs: Prefs = if (demoScreen != null) MemoryPrefs() else object : Prefs {
        private val d = NSUserDefaults.standardUserDefaults
        override fun get(key: String): String? = d.stringForKey("kw.$key")
        override fun put(key: String, value: String?) {
            if (value == null) d.removeObjectForKey("kw.$key") else d.setObject(value, forKey = "kw.$key")
        }
    }

    override val transports = TransportFactory { host, port, tls, pin ->
        val c = bridge.openConnection(host, port, tls, pin)
        if (c.certificateChanged()) { c.close(); throw CertificateChangedException("$host:$port", c.fingerprint() ?: "") }
        c.error()?.let { c.close(); throw ElectrumException(it) }
        object : LineTransport {
            override val fingerprint: String? = c.fingerprint()
            override fun writeLine(line: String) { c.writeLine(line)?.let { throw ElectrumException(it) } }
            override fun readLine(): String? = c.readLine()
            override fun close() = c.close()
        }
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
    override fun markReady(name: String) = bridge.markReady(name)
    override val appVersion: String get() = bridge.appVersion()
    override fun sharePayment(request: String, done: (String?) -> Unit) = bridge.sharePayment(request) { done(it) }
    override fun shareCsv(fileName: String, contents: String, done: (String?) -> Unit) =
        bridge.shareCsv(fileName, contents) { error -> done(error) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** Screenshot mode never touches the real Keychain or settings. */
private class MemorySeedStore : SeedStore {
    private val secrets = HashMap<String, String>()
    override fun hasSeed(walletId: String) = secrets.containsKey(walletId)
    override fun save(walletId: String, secret: String, done: (String?) -> Unit) { secrets[walletId] = secret; done(null) }
    override fun load(walletId: String, reason: String, done: (String?, String?) -> Unit) = done(secrets[walletId], if (secrets[walletId] == null) "no words" else null)
    override fun delete(walletId: String): String? { secrets.remove(walletId); return null }
}

private class MemoryPrefs : Prefs {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}
