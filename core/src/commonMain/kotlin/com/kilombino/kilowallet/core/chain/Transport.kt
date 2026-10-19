package com.kilombino.kilowallet.core.chain

/**
 * A newline-delimited byte stream to an Electrum server. The platform supplies it: on iOS a
 * Network.framework TLS connection (Swift, `iosApp/iosApp/ElectrumTransport.swift`), on the JVM
 * an SSLSocket (`JvmTransport`). Calls block; they are made from [com.kilombino.kilowallet.core.platform.ioDispatcher].
 */
interface LineTransport {
    /** SHA-256 (hex) of the server's leaf certificate, or null on a plain-TCP connection. */
    val fingerprint: String?
    /** Send [line] and a newline; a batch passes several requests joined by '\n' in one call. */
    fun writeLine(line: String)
    /** The next line without its terminator; null when the server closed the connection. */
    fun readLine(): String?
    fun close()
}

fun interface TransportFactory {
    /**
     * Open [host]:[port]. With [tls], certificates are not validated against CAs (Electrum
     * servers are self-signed) but PINNED: when [pinnedFingerprint] is set and the server shows a
     * different certificate, throw [CertificateChangedException] before sending anything.
     */
    fun open(host: String, port: Int, tls: Boolean, pinnedFingerprint: String?): LineTransport
}
