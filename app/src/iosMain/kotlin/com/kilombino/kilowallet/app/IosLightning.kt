package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.ln.LndCallback
import com.kilombino.kilowallet.core.ln.LndEngine
import com.kilombino.kilowallet.core.ln.LndHost
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.posix.memcpy
import platform.posix.time

/** The Lightning node on iPhone: the engine and keychain through Swift, files through Foundation. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLightning(private val bridge: IosBridge, private val memoryPassword: Boolean) : LightningPlatform {

    override val engine = object : LndEngine {
        override fun start(args: String, cb: LndCallback) = bridge.lndStart(args, listener(cb))
        override fun call(method: String, request: ByteArray, cb: LndCallback) = bridge.lndCall(method, request.toNSData(), listener(cb))
        override fun stream(method: String, request: ByteArray, cb: LndCallback) = bridge.lndStream(method, request.toNSData(), listener(cb))
    }

    private fun listener(cb: LndCallback) = object : IosLndListener {
        override fun onResponse(bytes: NSData?) = cb.onResponse(bytes?.toBytes())
        override fun onError(message: String) = cb.onError(message)
    }

    override val host = object : LndHost {
        private val fm = NSFileManager.defaultManager
        override val dir: String = bridge.lndDir()
        private fun parent(path: String) = path.substringBeforeLast('/')
        override fun writeText(path: String, text: String) {
            fm.createDirectoryAtPath(parent(path), true, null, null)
            (NSString.create(string = text)).writeToFile(path, true, NSUTF8StringEncoding, null)
        }
        override fun readBytes(path: String): ByteArray? = NSData.create(contentsOfFile = path)?.toBytes()
        override fun writeBytes(path: String, bytes: ByteArray) {
            fm.createDirectoryAtPath(parent(path), true, null, null)
            bytes.toNSData().writeToFile(path, true)
        }
        override fun exists(path: String) = fm.fileExistsAtPath(path)
        override fun delete(path: String) { fm.removeItemAtPath(path, null) }
        override fun files(dir: String): List<String> =
            (fm.subpathsOfDirectoryAtPath(dir, null) ?: emptyList<Any?>()).mapNotNull { it as? String }.map { "$dir/$it" }
                .filter { p -> fm.attributesOfItemAtPath(p, null)?.get(NSFileType) == NSFileTypeRegular }
        override fun tail(path: String, maxBytes: Int): String? {
            val h = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
            val size = h.seekToEndOfFile()
            h.seekToFileOffset(if (size > maxBytes.toULong()) size - maxBytes.toULong() else 0u)
            val d = h.readDataToEndOfFile(); h.closeFile()
            return d.toBytes().decodeToString()
        }
        // Go resolves names itself on iOS (cgo, the system resolver): nothing to add here.
        override fun resolve(host: String): List<String> = emptyList()
        // Through Tor when it is already running (Android: the LSP and the explorer), so neither
        // sees this phone's address next to the node's.
        override fun http(method: String, url: String, body: String?): Pair<Int, String> {
            val socks = com.kilombino.kilowallet.core.tor.Tor.proxyIfRunning()
            val r = if (socks != null) bridge.httpRequestVia(method, url, body, socks) else bridge.httpRequest(method, url, body)
            if (r.status() == 0) throw IllegalStateException(r.body().ifBlank { "no connection" })
            return r.status() to r.body()
        }
        override fun nowSeconds(): Long = time(null)
    }

    override fun request(method: String, url: String, body: String?, socks: Int?): Pair<Int, String> {
        val r = bridge.httpRequestVia(method, url, body, socks ?: 0)
        if (r.status() == 0) throw IllegalStateException(r.body().ifBlank { "no connection" })
        return r.status() to r.body()
    }

    private var memory: String? = null
    override fun loadPassword(): String? = if (memoryPassword) memory else bridge.lndPasswordLoad()
    override fun savePassword(hex: String): String? = if (memoryPassword) { memory = hex; null } else bridge.lndPasswordSave(hex)
    override fun deletePassword() { if (memoryPassword) memory = null else bridge.lndPasswordDelete() }
    override fun beginBackgroundWork(name: String): Any? = bridge.beginBackgroundWork(name).takeIf { it != 0L }
    override fun endBackgroundWork(token: Any?) { (token as? Long)?.let { bridge.endBackgroundWork(it) } }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else memScoped {
    NSData.dataWithBytes(allocArrayOf(this@toNSData), this@toNSData.size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toBytes(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
