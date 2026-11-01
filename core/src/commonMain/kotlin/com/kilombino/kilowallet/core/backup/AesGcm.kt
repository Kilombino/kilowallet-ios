package com.kilombino.kilowallet.core.backup

/**
 * AES-256-GCM (NIST SP 800-38D) in plain Kotlin, for the backup file: the Android app writes it
 * with the JVM's `AES/GCM/NoPadding` (12-byte IV, 128-bit tag), which iOS has no common-code
 * equivalent of. Only encryption of the block cipher is needed (CTR mode and GHASH). Pinned by
 * NIST's GCM test vectors and by files the Android code made.
 *
 * Table-free byte-wise AES: slower than a T-table one, but a backup file is a few kilobytes.
 */
object AesGcm {
    class BadTag : Exception("The data does not match its authentication tag.")

    /** ciphertext ‖ 16-byte tag, as the JVM's cipher returns it. */
    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        val rk = expandKey(key)
        val h = encryptBlock(rk, ByteArray(16))
        val j0 = iv + byteArrayOf(0, 0, 0, 1)
        val c = ctr(rk, inc32(j0), plain)
        val tag = xor16(encryptBlock(rk, j0), ghash(h, aad, c))
        return c + tag
    }

    /** The plain text of ciphertext ‖ tag; throws [BadTag] when it was altered or the key is wrong. */
    fun decrypt(key: ByteArray, iv: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        if (sealed.size < 16) throw BadTag()
        val rk = expandKey(key)
        val h = encryptBlock(rk, ByteArray(16))
        val j0 = iv + byteArrayOf(0, 0, 0, 1)
        val c = sealed.copyOfRange(0, sealed.size - 16)
        val expected = xor16(encryptBlock(rk, j0), ghash(h, aad, c))
        var diff = 0
        for (i in 0 until 16) diff = diff or (expected[i].toInt() xor sealed[sealed.size - 16 + i].toInt())
        if (diff != 0) throw BadTag()
        return ctr(rk, inc32(j0), c)
    }

    // ---- GCM -------------------------------------------------------------------------

    private fun inc32(b: ByteArray): ByteArray {
        val o = b.copyOf()
        for (i in 15 downTo 12) { o[i] = (o[i] + 1).toByte(); if (o[i].toInt() != 0) break }
        return o
    }

    private fun ctr(rk: Array<ByteArray>, start: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = start
        var off = 0
        while (off < data.size) {
            val ks = encryptBlock(rk, counter)
            val n = minOf(16, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            counter = inc32(counter)
        }
        return out
    }

    private fun xor16(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    /** GF(2^128) multiplication, bit by bit (SP 800-38D algorithm 1). */
    private fun gmul(x: ByteArray, y: ByteArray): ByteArray {
        val z = ByteArray(16)
        val v = y.copyOf()
        for (i in 0 until 128) {
            if ((x[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) for (k in 0 until 16) z[k] = (z[k].toInt() xor v[k].toInt()).toByte()
            val lsb = v[15].toInt() and 1
            for (k in 15 downTo 1) v[k] = (((v[k].toInt() and 0xff) ushr 1) or ((v[k - 1].toInt() and 1) shl 7)).toByte()
            v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
            if (lsb == 1) v[0] = (v[0].toInt() xor 0xe1).toByte()
        }
        return z
    }

    private fun ghash(h: ByteArray, aad: ByteArray, c: ByteArray): ByteArray {
        var y = ByteArray(16)
        fun absorb(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val blk = ByteArray(16)
                data.copyInto(blk, 0, off, minOf(data.size, off + 16))
                y = gmul(xor16(y, blk), h)
                off += 16
            }
        }
        absorb(aad); absorb(c)
        val len = ByteArray(16)
        val a = aad.size.toLong() * 8; val l = c.size.toLong() * 8
        for (i in 0 until 8) { len[7 - i] = (a ushr (8 * i)).toByte(); len[15 - i] = (l ushr (8 * i)).toByte() }
        return gmul(xor16(y, len), h)
    }

    // ---- AES block cipher (encryption only) -----------------------------------------------

    private val SBOX: IntArray = run {
        val s = IntArray(256)
        var p = 1; var q = 1
        do {
            p = p xor ((p shl 1) and 0xff) xor (if (p and 0x80 != 0) 0x1b else 0)
            q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xff
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            s[p] = (x xor 0x63) and 0xff
        } while (p != 1)
        s[0] = 0x63
        s
    }

    private fun rotl8(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xff

    private fun xtime(b: Int) = ((b shl 1) xor (if (b and 0x80 != 0) 0x1b else 0)) and 0xff

    /** Round keys for AES-128/192/256 (Nr + 1 of 16 bytes). */
    private fun expandKey(key: ByteArray): Array<ByteArray> {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        val nk = key.size / 4
        val nr = nk + 6
        val w = IntArray(4 * (nr + 1))
        for (i in 0 until nk) w[i] = ((key[4 * i].toInt() and 0xff) shl 24) or ((key[4 * i + 1].toInt() and 0xff) shl 16) or
            ((key[4 * i + 2].toInt() and 0xff) shl 8) or (key[4 * i + 3].toInt() and 0xff)
        var rcon = 1
        for (i in nk until w.size) {
            var t = w[i - 1]
            if (i % nk == 0) {
                t = (t shl 8) or (t ushr 24)
                t = subWord(t) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                t = subWord(t)
            }
            w[i] = w[i - nk] xor t
        }
        return Array(nr + 1) { r -> ByteArray(16) { (w[4 * r + it / 4] ushr (24 - 8 * (it % 4))).toByte() } }
    }

    private fun subWord(t: Int) = (SBOX[(t ushr 24) and 0xff] shl 24) or (SBOX[(t ushr 16) and 0xff] shl 16) or
        (SBOX[(t ushr 8) and 0xff] shl 8) or SBOX[t and 0xff]

    private fun encryptBlock(rk: Array<ByteArray>, input: ByteArray): ByteArray {
        val s = IntArray(16) { (input[it].toInt() xor rk[0][it].toInt()) and 0xff }
        val nr = rk.size - 1
        for (round in 1..nr) {
            for (i in 0 until 16) s[i] = SBOX[s[i]]
            // ShiftRows (state is column-major: s[r + 4c])
            for (r in 1..3) {
                val row = IntArray(4) { s[r + 4 * ((it + r) % 4)] }
                for (c in 0 until 4) s[r + 4 * c] = row[c]
            }
            if (round != nr) for (c in 0 until 4) {
                val a0 = s[4 * c]; val a1 = s[4 * c + 1]; val a2 = s[4 * c + 2]; val a3 = s[4 * c + 3]
                val all = a0 xor a1 xor a2 xor a3
                s[4 * c] = a0 xor all xor xtime(a0 xor a1)
                s[4 * c + 1] = a1 xor all xor xtime(a1 xor a2)
                s[4 * c + 2] = a2 xor all xor xtime(a2 xor a3)
                s[4 * c + 3] = a3 xor all xor xtime(a3 xor a0)
            }
            for (i in 0 until 16) s[i] = s[i] xor (rk[round][i].toInt() and 0xff)
        }
        return ByteArray(16) { s[it].toByte() }
    }
}
