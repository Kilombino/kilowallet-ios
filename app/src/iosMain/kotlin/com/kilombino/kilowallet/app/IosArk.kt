package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.ark.ArkEngine
import com.kilombino.kilowallet.core.ark.ArkHost
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/** Ark on iPhone: the engine and its words through Swift, its folder through Foundation. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosArk(private val bridge: IosBridge, private val prefs: Prefs, private val memoryWords: Boolean) : ArkPlatform {

    override val engine = object : ArkEngine {
        override fun start(datadir: String, port: Int, mnemonic: String?, passphrase: String) = bridge.arkStart(datadir, port, mnemonic, passphrase)
        override fun stop() = bridge.arkStop()
    }

    private var words: String? = null

    override val host = object : ArkHost {
        private val fm = NSFileManager.defaultManager
        override val dir: String = bridge.arkDir()
        override fun http(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int): Pair<Int, String> {
            val bearer = headers["Authorization"]?.removePrefix("Bearer ")
            val r = bridge.arkHttp(method, url, body, bearer, timeoutMs)
            if (r.status() == 0) throw IllegalStateException(r.body().ifBlank { "no answer" })
            return r.status() to r.body()
        }
        override fun readText(path: String): String? = NSData.create(contentsOfFile = path)?.toBytes()?.decodeToString()
        override fun writeText(path: String, text: String) {
            fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
            (NSString.create(string = text)).writeToFile(path, true, NSUTF8StringEncoding, null)
        }
        override fun readBytes(path: String): ByteArray? = NSData.create(contentsOfFile = path)?.toBytes()
        override fun writeBytes(path: String, bytes: ByteArray) {
            fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
            bytes.toNSData().writeToFile(path, true)
        }
        override fun exists(path: String) = fm.fileExistsAtPath(path)
        override fun delete(path: String) { fm.removeItemAtPath(path, null) }
        override fun list(path: String): List<String> = fm.contentsOfDirectoryAtPath(path, null)?.map { it.toString() } ?: emptyList()
        override fun tail(path: String, maxBytes: Int): String? {
            val h = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
            val size = h.seekToEndOfFile()
            h.seekToFileOffset(if (size > maxBytes.toULong()) size - maxBytes.toULong() else 0u)
            val d = h.readDataToEndOfFile(); h.closeFile()
            return d.toBytes().decodeToString()
        }
        override fun pref(key: String): String? = prefs.get(key)
        override fun setPref(key: String, value: String?) = prefs.put(key, value)
        override fun loadWords(): String? = if (memoryWords) words else bridge.arkWordsLoad()
        override fun saveWords(text: String): String? = if (memoryWords) { words = text; null } else bridge.arkWordsSave(text)
        override fun deleteWords() { if (memoryWords) words = null else bridge.arkWordsDelete() }
    }

    override fun beginArkWork(name: String): Any? = bridge.beginBackgroundWork(name).takeIf { it != 0L }
    override fun endArkWork(token: Any?) { (token as? Long)?.let { bridge.endBackgroundWork(it) } }
    override fun remind(atEpochSeconds: Long?, title: String, body: String) {
        if (memoryWords) return
        bridge.arkRemind(atEpochSeconds ?: 0L, com.kilombino.kilowallet.app.i18n.I18n.tr(title), com.kilombino.kilowallet.app.i18n.I18n.tr(body))
    }
    override fun scheduleBackgroundRenewal() { if (!memoryWords) bridge.arkScheduleBackground() }
}
