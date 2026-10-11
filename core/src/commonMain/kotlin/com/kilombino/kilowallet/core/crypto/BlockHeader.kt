package com.kilombino.kilowallet.core.crypto

import com.kilombino.kilowallet.core.crypto.Hashes.toHex

/**
 * Block headers, enough to check a server's word for a payment (SPV). Ported from the Android
 * app's `crypto/BlockHeader.kt`: a BLAKE2b-chain header is 164 bytes and its hash (its proof of
 * work) follows rust-bitcoin's blake2b_header.rs byte for byte. Targets are compared as 32-byte
 * big-endian numbers instead of java.math.BigInteger.
 */
object BlockHeader {
    class Parsed(val raw: ByteArray) {
        private fun le32(o: Int): Long = (0 until 4).fold(0L) { a, i -> a or ((raw[o + i].toLong() and 0xff) shl (8 * i)) }
        val version: Long get() = le32(0)
        val extended: Boolean get() = (version and 0x80000000L) != 0L
        /** Merkle root as serialised (internal byte order). */
        val merkleRoot: ByteArray get() = raw.copyOfRange(36, 68)
        val bits: Long get() = le32(72)
    }

    fun parse(hex: String): Parsed {
        val b = Hashes.hexToBytes(hex)
        require(b.size == 80 || b.size == 164) { "a header is 80 or 164 bytes, not ${b.size}" }
        val p = Parsed(b)
        require(p.extended == (b.size == 164)) { "header size does not match its version" }
        return p
    }

    private fun tag(t: String, data: ByteArray): ByteArray {
        val th = Hashes.sha256(t.encodeToByteArray())
        return Hashes.sha256(th + th + data)
    }

    private fun u32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xff).toByte() }

    /** The block hash in display order (hex, as an explorer shows it). */
    fun blockHash(h: Parsed): String {
        val r = h.raw
        if (!h.extended) return Hashes.doubleSha256(r).reversedArray().toHex()
        fun le(o: Int, n: Int): Long = (0 until n).fold(0L) { a, i -> a or ((r[o + i].toLong() and 0xff) shl (8 * i)) }
        val prevDisplay = r.copyOfRange(4, 36).reversedArray()
        val time = le(68, 4)
        val nonce = le(76, 4)
        val nonce2 = le(80, 4); val nonce3 = le(84, 4)
        val extranonce = r.copyOfRange(88, 104)
        val timeOffset = le(104, 4)
        val txcount = le(108, 2)
        val flags = r[110].toInt() and 0xff
        val clearBits = r[111].toInt() and 0xff
        val xorKey = r.copyOfRange(112, 128)
        val height = r.copyOfRange(128, 132)
        val mmRhs = r.copyOfRange(132, 164)

        val mask = ByteArray(32)
        if (xorKey.any { it.toInt() != 0 }) {
            val m = tag("Bitcoin block hash PoW XOR mask", xorKey)
            val full = clearBits / 8
            for (i in 0 until minOf(full, 32)) m[i] = 0
            if (full < 32) m[full] = (m[full].toInt() and (0xff ushr (clearBits % 8))).toByte()
            m.copyInto(mask, 0, 0, 32)
        }
        val h1 = r.copyOfRange(0, 4) + prevDisplay + height + h.merkleRoot + u32(time) + byteArrayOf(0) +
            r.copyOfRange(72, 76) + u32(txcount) + byteArrayOf(flags.toByte(), clearBits.toByte()) +
            tag("Bitcoin block hash PoW XOR key", xorKey)
        val h2 = tag("Merge-mining hook", tag("Bitcoin block header 1", h1) + ByteArray(32) + mmRhs)
        val first = Blake2b.digest(ByteArray(4) + h2 + extranonce, 32)
        var second = when (flags and 3) {
            0 -> tag("Bitcoin prevblock header, hashed", prevDisplay).also { for (i in 0 until 6) it[i] = 0 }
            2 -> ByteArray(48) + h2
            3 -> ByteArray(80) + h2
            else -> ByteArray(0)
        }
        second += u32(nonce) + u32(nonce2)
        second += if ((flags and 3) == 1) u32(nonce3) + u32(timeOffset) else u32(timeOffset) + u32(nonce3)
        second += first
        if ((flags and 3) == 1) second += h2
        val result = Blake2b.digest(second, 32)
        for (i in 0 until 32) result[i] = (result[i].toInt() xor mask[i].toInt()).toByte()
        return result.toHex()
    }

    /**
     * The target [bits] encodes (compact form), as a 32-byte big-endian number; a target that does
     * not fit in 256 bits saturates to all-ones (no real header has one).
     */
    fun target(bits: Long): ByteArray {
        val exp = ((bits ushr 24) and 0xff).toInt()
        var mant = bits and 0x007fffff
        val out = ByteArray(32)
        if (exp <= 3) {
            mant = mant ushr (8 * (3 - exp))
            for (i in 0 until 3) out[31 - i] = ((mant ushr (8 * i)) and 0xff).toByte()
            return out
        }
        // mant · 256^(exp-3): its three bytes land at big-endian positions 32-exp .. 34-exp.
        for (i in 0 until 3) {
            val b = ((mant ushr (8 * (2 - i))) and 0xff).toInt()
            val pos = 32 - exp + i
            if (pos < 0) { if (b != 0) return ByteArray(32) { 0xff.toByte() } else continue }
            out[pos] = b.toByte()
        }
        return out
    }

    /** Unsigned comparison of two 32-byte big-endian numbers. */
    fun compare(a: ByteArray, b: ByteArray): Int = Ec.compareUnsigned(a, b)

    /** True when the header's hash is at or below the target its own bits claim. */
    fun meetsItsTarget(h: Parsed): Boolean {
        val exponent = (h.bits ushr 24).toInt()
        val mantissa = h.bits and 0x007fffff
        if (h.bits and 0x00800000 != 0L || mantissa == 0L || exponent > 34 ||
            (mantissa > 0xff && exponent > 33) || (mantissa > 0xffff && exponent > 32)) return false
        val target = target(h.bits)
        return target.any { it != 0.toByte() } && compare(Hashes.hexToBytes(blockHash(h)), target) <= 0
    }

    /** Whether [txid] is in the block of [header], by an Electrum merkle branch at position [pos]. */
    fun inBlock(txid: String, branch: List<String>, pos: Int, header: Parsed): Boolean {
        if (pos < 0 || branch.size > 31 || txid.length != 64 || branch.any { it.length != 64 }) return false
        var h = Hashes.hexToBytes(txid).reversedArray()
        var p = pos
        for (s in branch) {
            val sib = Hashes.hexToBytes(s).reversedArray()
            h = Hashes.doubleSha256(if (p and 1 == 0) h + sib else sib + h)
            p = p shr 1
        }
        return p == 0 && h.contentEquals(header.merkleRoot)
    }
}
