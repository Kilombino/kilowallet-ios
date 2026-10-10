package com.kilombino.kilowallet.core.crypto

/**
 * Base58 and Base58Check — the encoding behind xpub/ypub/zpub and legacy `1…`/`3…` addresses.
 * Ported from the Android app's `crypto/Base58.kt`, which converts through java.math.BigInteger;
 * here the same base conversion is done digit by digit on byte arrays (the classic algorithm
 * of Bitcoin Core's base58.cpp), so it runs on every Kotlin target.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        // Base-58 digits, little-endian, grown as needed.
        val digits = IntArray(input.size * 138 / 100 + 1)
        var len = 0
        for (i in zeros until input.size) {
            var carry = input[i].toInt() and 0xFF
            var j = 0
            while (j < len || carry != 0) {
                if (j < len) carry += digits[j] * 256
                digits[j] = carry % 58
                carry /= 58
                j++
            }
            len = j
        }
        val sb = StringBuilder(zeros + len)
        repeat(zeros) { sb.append(ALPHABET[0]) }
        for (k in len - 1 downTo 0) sb.append(ALPHABET[digits[k]])
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) zeros++
        val bytes = IntArray(input.length * 733 / 1000 + 1) // base-256 digits, little-endian
        var len = 0
        for (i in zeros until input.length) {
            val c = input[i]
            var carry = if (c.code < 128) INDEX[c.code] else -1
            require(carry >= 0) { "invalid base58 character '$c'" }
            var j = 0
            while (j < len || carry != 0) {
                if (j < len) carry += bytes[j] * 58
                bytes[j] = carry and 0xFF
                carry = carry ushr 8
                j++
            }
            len = j
        }
        val out = ByteArray(zeros + len)
        for (k in 0 until len) out[zeros + k] = bytes[len - 1 - k].toByte()
        return out
    }

    /** Decode and verify the 4-byte double-SHA256 checksum, returning the payload. */
    fun decodeChecked(input: String): ByteArray {
        val raw = decode(input)
        require(raw.size >= 5) { "base58check string too short" }
        val payload = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = Hashes.doubleSha256(payload).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "bad base58check checksum" }
        return payload
    }

    fun encodeChecked(payload: ByteArray): String =
        encode(payload + Hashes.doubleSha256(payload).copyOfRange(0, 4))
}
