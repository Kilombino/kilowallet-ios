package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.crypto.Ec
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What the Android tests take from the Android app and the iOS core does differently, so the
 * ported tests read as Android's: the curve as Android's `Secp256k1` object (keys and points are
 * byte arrays here), its file-backed commitment store, and a plain TCP transport for the
 * in-process directories.
 */
object Secp256k1 {
    val N: BigInteger = BigInteger(1, Ec.N)
    fun compress(p: ByteArray): ByteArray = p
    fun publicPoint(k: ByteArray): ByteArray = Ec.publicKey(k)
    fun multiply(k: ByteArray, p: ByteArray): ByteArray = Podle.mul(p, k)
}

fun BigInteger.to32(): ByteArray {
    val b = toByteArray()
    val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
    return ByteArray(32 - src.size) + src
}

typealias FileCommitmentStore = JsonCommitmentStore

/** Android's FileCommitmentStore: the same JSON in [file], written before returning. */
fun FileCommitmentStore(file: File): JsonCommitmentStore = JsonCommitmentStore(
    load = { file.takeIf { it.exists() }?.readText() },
    save = { text ->
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
    },
)

/** A plain TCP line stream, no timeouts (the client's own timeouts are what is tested). */
object PlainTcp : DirectoryTransport {
    override fun open(host: String, port: Int): LineTransport {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5_000)
        s.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        return object : LineTransport {
            override val fingerprint: String? = null
            override fun writeLine(line: String) = synchronized(this) { out.write((line + "\n").toByteArray()); out.flush() }
            override fun readLine(): String? = try { reader.readLine() } catch (e: java.io.IOException) { null }
            override fun close() { runCatching { s.close() } }
        }
    }
}
