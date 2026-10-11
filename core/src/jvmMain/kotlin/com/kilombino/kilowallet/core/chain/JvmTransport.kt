package com.kilombino.kilowallet.core.chain

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The JVM transport (tests, desktop preview): the Android app's SSLSocket code, with the same pinning. */
object JvmTransport : TransportFactory {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    override fun open(host: String, port: Int, tls: Boolean, pinnedFingerprint: String?): LineTransport {
        val raw = connectRaw(host, port)
        if (!tls) return wrap(raw, null)
        var seen: String? = null
        var changed = false
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = Hashes.sha256(chain[0].encoded).toHex()
                seen = fp
                if (pinnedFingerprint != null && !pinnedFingerprint.equals(fp, ignoreCase = true)) {
                    changed = true
                    throw CertificateException("certificate changed")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(capture), java.security.SecureRandom())
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = READ_TIMEOUT_MS
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            runCatching { ssl.close() }
            if (changed) throw CertificateChangedException("$host:$port", seen ?: "")
            throw ElectrumException("TLS handshake failed: ${e.message}", e)
        }
        return wrap(ssl, seen)
    }

    private fun wrap(s: Socket, fp: String?): LineTransport {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        return object : LineTransport {
            override val fingerprint: String? = fp
            override fun writeLine(line: String) { writer.write(line); writer.write("\n"); writer.flush() }
            override fun readLine(): String? {
                val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
                val line = StringBuilder()
                while (true) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) { s.close(); throw ElectrumException("Response deadline exceeded") }
                    s.soTimeout = remaining.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
                    val ch = reader.read()
                    if (ch == -1) return null
                    if (ch == 10) return line.toString().removeSuffix("\r")
                    if (line.length >= ElectrumClient.MAX_LINE_BYTES) { s.close(); throw ElectrumException("Response too large") }
                    line.append(ch.toChar())
                }
            }
            override fun close() { runCatching { s.close() } }
        }
    }

    private fun connectRaw(host: String, port: Int): Socket {
        val addresses = try { InetAddress.getAllByName(host) } catch (e: Exception) {
            throw ElectrumException("Could not resolve $host: ${e.message}", e)
        }
        var last: Exception? = null
        for (addr in addresses.sortedBy { it.address.size }) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) { last = e }
        }
        throw ElectrumException("Could not connect to $host:$port: ${last?.message}", last)
    }
}
