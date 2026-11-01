package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.tor.TorEngine
import com.kilombino.kilowallet.core.tor.TorHost
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/** Tor on iPhone: Arti through Swift (ArtiEngine.swift), its files through Foundation. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTor(private val bridge: IosBridge) {

    val engine = object : TorEngine {
        override fun start(dir: String, logPath: String, port: Int): String? = bridge.torStart(dir, logPath, port)
        override fun stop() = bridge.torStop()
        override fun state(): Int = bridge.torState()
    }

    val host = object : TorHost {
        private val fm = NSFileManager.defaultManager
        override val dir: String = bridge.torDir()
        override val logPath: String = bridge.torLogPath()

        override fun files(dir: String): List<String> {
            val subs = fm.subpathsOfDirectoryAtPath(dir, null) ?: return emptyList()
            return subs.mapNotNull { it as? String }.map { "$dir/$it" }.filter { p ->
                fm.attributesOfItemAtPath(p, null)?.get(NSFileType) == NSFileTypeRegular
            }
        }

        override fun readBytes(path: String): ByteArray? = NSData.create(contentsOfFile = path)?.toBytes()

        override fun writeText(path: String, text: String) {
            fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
            NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
        }

        override fun delete(path: String) { fm.removeItemAtPath(path, null) }

        override fun size(path: String): Long =
            (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

        override fun read(path: String, from: Long, max: Int): ByteArray? {
            val h = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
            h.seekToFileOffset(from.toULong())
            val d = h.readDataOfLength(max.toULong())
            h.closeFile()
            return d.toBytes()
        }

        override fun freePort(): Int = bridge.torFreePort()
        override fun socksAnswers(port: Int): Boolean = bridge.torSocksAnswers(port)
        override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
    }
}
