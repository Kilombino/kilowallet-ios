package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIP-44 v2 encryption (ChaCha20 + HMAC-SHA256, padded), the payload format nostr clients use for
 * private messages; the Android app's `coinjoin/Nip44.kt`. Pinned by the official vectors
 * (paulmillr/nip44) and by the Android app's own output (tools/android-vectors).
 */
@OptIn(ExperimentalEncodingApi::class)
object Nip44 {
    private val SALT = "nip44-v2".encodeToByteArray()

    /** Shared secret between our [secret] (32 bytes) and their x-only [pubX], as HKDF-extract. */
    fun conversationKey(secret: ByteArray, pubX: ByteArray): ByteArray =
        Hashes.hmacSha256(SALT, Ec.sharedX(secret, pubX))

    /** HKDF-expand(convKey, nonce, 76) → (chacha key, chacha nonce, hmac key). */
    internal fun messageKeys(convKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        var okm = ByteArray(0)
        var t = ByteArray(0); var i = 1
        while (okm.size < 76) { t = Hashes.hmacSha256(convKey, t + nonce + byteArrayOf(i.toByte())); okm += t; i++ }
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    internal fun paddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - (len - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    fun encrypt(plaintext: String, convKey: ByteArray, nonce: ByteArray = secureRandomBytes(32)): String {
        val p = plaintext.encodeToByteArray()
        require(p.size in 1..65535) { "NIP-44 message length out of range" }
        val padded = ByteArray(2 + paddedLen(p.size))
        padded[0] = (p.size ushr 8).toByte(); padded[1] = p.size.toByte()
        p.copyInto(padded, 2)
        val (key, n12, hmacKey) = messageKeys(convKey, nonce)
        val ct = ChaCha20.xor(key, n12, padded)
        val mac = Hashes.hmacSha256(hmacKey, nonce + ct)
        return Base64.encode(byteArrayOf(2) + nonce + ct + mac)
    }

    /** Null when the payload is malformed or its MAC does not match. */
    fun decrypt(payload: String, convKey: ByteArray): String? = runCatching {
        if (payload.startsWith("#")) return null
        val d = Base64.decode(payload)
        if (d.size < 99 || d[0].toInt() != 2) return null
        val nonce = d.copyOfRange(1, 33)
        val ct = d.copyOfRange(33, d.size - 32)
        val mac = d.copyOfRange(d.size - 32, d.size)
        val (key, n12, hmacKey) = messageKeys(convKey, nonce)
        if (!constantTimeEquals(mac, Hashes.hmacSha256(hmacKey, nonce + ct))) return null
        val padded = ChaCha20.xor(key, n12, ct)
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len == 0 || padded.size != 2 + paddedLen(len)) return null
        padded.copyOfRange(2, 2 + len).decodeToString()
    }.getOrNull()
}

/** Plain ChaCha20 (RFC 8439) keystream XOR, from the Android app's `crypto/XChaCha20Poly1305.kt`. */
internal object ChaCha20 {
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun rounds(x: IntArray) {
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
    }

    private fun block(key: ByteArray, counter: Int, nonce12: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce12, i * 4)
        val x = s.copyOf(); rounds(x)
        val out = ByteArray(64)
        for (i in 0 until 16) { val v = x[i] + s[i]; for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte() }
        return out
    }

    fun xor(key: ByteArray, nonce12: ByteArray, data: ByteArray, counter: Int = 0): ByteArray {
        val out = ByteArray(data.size)
        var off = 0; var c = counter
        while (off < data.size) {
            val ks = block(key, c++, nonce12)
            for (i in 0 until minOf(64, data.size - off)) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += 64
        }
        return out
    }
}

/** Compares two byte arrays in time that depends only on their lengths. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
    return d == 0
}
