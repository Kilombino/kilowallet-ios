package com.kilombino.kilowallet.core.crypto

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA512

/**
 * The hash primitives Bitcoin address derivation needs. Same API as the Android app's
 * `crypto/Hashes.kt`; there SHA-256 and HMAC come from the JVM, here from KotlinCrypto
 * (pure Kotlin, identical on every target). RIPEMD-160 is the app's own in-tree code.
 */
object Hashes {

    fun sha256(data: ByteArray): ByteArray = SHA256().digest(data)

    /** Bitcoin's double-SHA256, used for Base58Check checksums and txids. */
    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg). */
    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val t = sha256(tag.encodeToByteArray())
        return sha256(t + t + msg)
    }

    /** HASH160 = RIPEMD160(SHA256(x)) — the payload of every P2PKH/P2WPKH address. */
    fun hash160(data: ByteArray): ByteArray = Ripemd160.digest(sha256(data))

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray = HmacSHA512(key).doFinal(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = HmacSHA256(key).doFinal(data)

    /**
     * PBKDF2-HMAC-SHA512 (RFC 8018) for a 64-byte output, i.e. a single block: what BIP-39
     * uses to stretch a mnemonic into a seed. The JVM provides this as PBKDF2WithHmacSHA512;
     * the tests pin this implementation to the official BIP-39 vectors and to the Android
     * app's output.
     */
    fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = HmacSHA512(password)
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    private const val HEX = "0123456789abcdef"

    fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xFF; sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F]) }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        fun d(c: Char): Int = c.digitToIntOrNull(16) ?: throw IllegalArgumentException("not hex: '$c'")
        return ByteArray(hex.length / 2) { ((d(hex[it * 2]) shl 4) or d(hex[it * 2 + 1])).toByte() }
    }
}
