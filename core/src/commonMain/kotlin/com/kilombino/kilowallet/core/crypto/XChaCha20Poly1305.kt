package com.kilombino.kilowallet.core.crypto

/**
 * XChaCha20-Poly1305 decryption (RFC 8439 with the 24-byte-nonce extension), as LND uses it to
 * encrypt channel.backup: the Android app's `crypto/XChaCha20Poly1305.kt`, pinned by [LnVectors]
 * (a backup made with LND's own code). Poly1305 here works on 26-bit limbs instead of BigInteger.
 */
object XChaCha20Poly1305 {

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

    /** HChaCha20: the subkey for an extended nonce. */
    private fun hchacha(key: ByteArray, nonce16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[1] = 0x3320646e; x[2] = 0x79622d32; x[3] = 0x6b206574
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le32(nonce16, i * 4)
        rounds(x)
        val out = ByteArray(32)
        for ((k, i) in intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).withIndex()) for (j in 0 until 4) out[k * 4 + j] = (x[i] ushr (8 * j)).toByte()
        return out
    }

    /** Poly1305 (RFC 8439) of [msg] under the one-time [key]: five 26-bit limbs, as poly1305-donna. */
    internal fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val t0 = le32(key, 0).toLong() and 0xFFFFFFFFL; val t1 = le32(key, 4).toLong() and 0xFFFFFFFFL
        val t2 = le32(key, 8).toLong() and 0xFFFFFFFFL; val t3 = le32(key, 12).toLong() and 0xFFFFFFFFL
        val r0 = t0 and 0x3ffffff
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
        val r4 = (t3 ushr 8) and 0x00fffff
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        var i = 0
        while (i < msg.size) {
            val n = minOf(16, msg.size - i)
            val b = ByteArray(17); msg.copyInto(b, 0, i, i + n); b[n] = 1
            val m0 = le32(b, 0).toLong() and 0xFFFFFFFFL; val m1 = le32(b, 4).toLong() and 0xFFFFFFFFL
            val m2 = le32(b, 8).toLong() and 0xFFFFFFFFL; val m3 = le32(b, 12).toLong() and 0xFFFFFFFFL
            val hi = (b[16].toLong() and 0xFF)
            h0 += m0 and 0x3ffffff
            h1 += ((m0 ushr 26) or (m1 shl 6)) and 0x3ffffff
            h2 += ((m1 ushr 20) or (m2 shl 12)) and 0x3ffffff
            h3 += ((m2 ushr 14) or (m3 shl 18)) and 0x3ffffff
            h4 += (m3 ushr 8) or (hi shl 24)
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and 0x3ffffff
            d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
            d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
            d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
            d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
            h1 += c
            i += 16
        }
        // Fully carry h, then h mod 2^130 - 5.
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) { h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4 }
        // h + s, mod 2^128.
        val f0 = ((h0) or (h1 shl 26)) and 0xFFFFFFFFL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL
        val k = LongArray(4) { le32(key, 16 + it * 4).toLong() and 0xFFFFFFFFL }
        val out = ByteArray(16)
        var carry = 0L
        for ((idx, f) in longArrayOf(f0, f1, f2, f3).withIndex()) {
            val v = f + k[idx] + carry
            carry = v ushr 32
            for (j in 0 until 4) out[idx * 4 + j] = (v ushr (8 * j)).toByte()
        }
        return out
    }

    private fun pad16(n: Int) = ByteArray((16 - n % 16) % 16)
    private fun le64(n: Long) = ByteArray(8) { (n ushr (8 * it)).toByte() }

    private fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    /** Open [ciphertext] (with its 16-byte tag at the end); null when it does not authenticate. */
    fun open(key: ByteArray, nonce24: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
        require(key.size == 32 && nonce24.size == 24 && ciphertext.size >= 16)
        val subKey = hchacha(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(4) + nonce24.copyOfRange(16, 24)
        val ct = ciphertext.copyOfRange(0, ciphertext.size - 16)
        val tag = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
        val polyKey = block(subKey, 0, nonce12).copyOf(32)
        val macData = ad + pad16(ad.size) + ct + pad16(ct.size) + le64(ad.size.toLong()) + le64(ct.size.toLong())
        if (!equalsConstantTime(poly1305(polyKey, macData), tag)) return null
        val out = ByteArray(ct.size)
        var counter = 1; var off = 0
        while (off < ct.size) {
            val ks = block(subKey, counter++, nonce12)
            for (j in 0 until minOf(64, ct.size - off)) out[off + j] = (ct[off + j].toInt() xor ks[j].toInt()).toByte()
            off += 64
        }
        return out
    }
}
