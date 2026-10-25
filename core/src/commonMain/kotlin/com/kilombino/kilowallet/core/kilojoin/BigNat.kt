package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.platform.secureRandomBytes

/**
 * Non-negative big integers, just what RSA blind signatures need ([BlindRsa]): the Android app
 * uses java.math.BigInteger, which Kotlin/Native lacks. Immutable; 32-bit limbs, least
 * significant first, with no leading zero limbs (zero has none). Modular powers go through
 * Montgomery multiplication when the modulus is odd (every RSA modulus and prime is).
 *
 * The JVM tests check every operation against java.math.BigInteger on random inputs, and the
 * RFC 9474 vector checks the whole blind-signature flow.
 */
class BigNat private constructor(private val mag: IntArray) : Comparable<BigNat> {

    val isZero: Boolean get() = mag.isEmpty()
    val isOdd: Boolean get() = mag.isNotEmpty() && (mag[0] and 1) != 0

    fun bitLength(): Int = if (mag.isEmpty()) 0 else (mag.size - 1) * 32 + (32 - mag.last().countLeadingZeroBits())

    fun testBit(i: Int): Boolean {
        val w = i ushr 5
        return w < mag.size && (mag[w] ushr (i and 31)) and 1 != 0
    }

    override fun compareTo(other: BigNat): Int = cmp(mag, other.mag)
    override fun equals(other: Any?): Boolean = other is BigNat && mag.contentEquals(other.mag)
    override fun hashCode(): Int = mag.contentHashCode()
    override fun toString(): String = toHex()

    operator fun plus(o: BigNat): BigNat = BigNat(add(mag, o.mag))

    /** this − [o]; requires this ≥ [o]. */
    operator fun minus(o: BigNat): BigNat {
        require(this >= o) { "negative result" }
        return BigNat(sub(mag, o.mag))
    }

    operator fun times(o: BigNat): BigNat = BigNat(mul(mag, o.mag))

    infix fun shl(bits: Int): BigNat {
        if (mag.isEmpty() || bits == 0) return this
        val words = bits ushr 5; val b = bits and 31
        val out = IntArray(mag.size + words + 1)
        for (i in mag.indices) {
            val v = mag[i].toLong() and M
            out[i + words] = out[i + words] or (v shl b).toInt()
            if (b != 0) out[i + words + 1] = (v ushr (32 - b)).toInt()
        }
        return BigNat(norm(out))
    }

    infix fun shr(bits: Int): BigNat {
        val words = bits ushr 5; val b = bits and 31
        if (words >= mag.size) return ZERO
        val out = IntArray(mag.size - words)
        for (i in out.indices) {
            val lo = (mag[i + words].toLong() and M) ushr b
            val hi = if (b != 0 && i + words + 1 < mag.size) ((mag[i + words + 1].toLong() and M) shl (32 - b)) and M else 0L
            out[i] = (lo or hi).toInt()
        }
        return BigNat(norm(out))
    }

    /** (quotient, remainder) of this ÷ [d]. */
    fun divRem(d: BigNat): Pair<BigNat, BigNat> {
        require(!d.isZero) { "division by zero" }
        if (this < d) return ZERO to this
        if (d.mag.size == 1) {
            val dv = d.mag[0].toLong() and M
            val q = IntArray(mag.size); var r = 0L
            for (i in mag.indices.reversed()) {
                val cur = (r shl 32) or (mag[i].toLong() and M)
                q[i] = (cur.toULong() / dv.toULong()).toLong().toInt()
                r = (cur.toULong() % dv.toULong()).toLong()
            }
            return BigNat(norm(q)) to of(r)
        }
        // Knuth, TAOCP vol. 2, 4.3.1, algorithm D: normalise so the divisor's top limb has its high bit set.
        val s = d.mag.last().countLeadingZeroBits()
        val v = (d shl s).mag
        val uN = (this shl s).mag
        val n = v.size
        val u = IntArray(uN.size + 1).also { uN.copyInto(it) }
        val m = u.size - n - 1
        val q = IntArray(m + 1)
        val vTop = v[n - 1].toLong() and M
        val vNext = v[n - 2].toLong() and M
        for (j in m downTo 0) {
            val num = ((u[j + n].toLong() and M) shl 32) or (u[j + n - 1].toLong() and M)
            var qhat = (num.toULong() / vTop.toULong()).toLong()
            var rhat = (num.toULong() % vTop.toULong()).toLong()
            while (qhat >= B || (qhat.toULong() * vNext.toULong()) > ((rhat.toULong() shl 32) or (u[j + n - 2].toLong() and M).toULong())) {
                qhat--; rhat += vTop
                if (rhat >= B) break
            }
            // Multiply and subtract.
            var borrow = 0L; var carry = 0L
            for (i in 0 until n) {
                val p = qhat.toULong() * (v[i].toLong() and M).toULong() + carry.toULong()
                carry = (p shr 32).toLong()
                val t = (u[i + j].toLong() and M) - (p.toLong() and M) - borrow
                u[i + j] = t.toInt()
                borrow = if (t < 0) 1 else 0
            }
            val t = (u[j + n].toLong() and M) - carry - borrow
            u[j + n] = t.toInt()
            if (t < 0) {
                // Added back: qhat was one too big (rare).
                qhat--
                var c = 0L
                for (i in 0 until n) {
                    val sum = (u[i + j].toLong() and M) + (v[i].toLong() and M) + c
                    u[i + j] = sum.toInt(); c = sum ushr 32
                }
                u[j + n] = ((u[j + n].toLong() and M) + c).toInt()
            }
            q[j] = qhat.toInt()
        }
        val r = BigNat(norm(u.copyOfRange(0, n))) shr s
        return BigNat(norm(q)) to r
    }

    operator fun div(d: BigNat): BigNat = divRem(d).first
    operator fun rem(d: BigNat): BigNat = divRem(d).second
    fun mod(m: BigNat): BigNat = divRem(m).second

    /** this^[e] mod [m]. */
    fun modPow(e: BigNat, m: BigNat): BigNat {
        require(!m.isZero) { "zero modulus" }
        if (m == ONE) return ZERO
        if (m.isOdd) return Montgomery(m).pow(this.mod(m), e)
        var result = ONE
        var base = this.mod(m)
        for (i in 0 until e.bitLength()) {
            if (e.testBit(i)) result = (result * base).mod(m)
            base = (base * base).mod(m)
        }
        return result
    }

    fun gcd(o: BigNat): BigNat {
        var a = this; var b = o
        while (!b.isZero) { val r = a.mod(b); a = b; b = r }
        return a
    }

    /** The inverse of this modulo [m]; throws when there is none. */
    fun modInverse(m: BigNat): BigNat {
        require(!m.isZero) { "zero modulus" }
        // Extended Euclid, the Bézout coefficient kept in [0, m).
        var r0 = m; var r1 = this.mod(m)
        var t0 = ZERO; var t1 = ONE
        while (!r1.isZero) {
            val (q, r) = r0.divRem(r1)
            val qt = (q * t1).mod(m)
            val t2 = if (t0 >= qt) t0 - qt else t0 + m - qt
            r0 = r1; r1 = r; t0 = t1; t1 = t2
        }
        require(r0 == ONE) { "not invertible" }
        return t0
    }

    /** Big-endian, exactly [len] bytes (left-padded with zeros); throws when it does not fit. */
    fun toBytes(len: Int): ByteArray {
        val need = (bitLength() + 7) / 8
        require(need <= len) { "integer too large" }
        val out = ByteArray(len)
        for (i in 0 until need) out[len - 1 - i] = (mag[i / 4] ushr (8 * (i % 4))).toByte()
        return out
    }

    /** Big-endian with no leading zero bytes (empty for zero). */
    fun toBytes(): ByteArray = toBytes((bitLength() + 7) / 8)

    /** Lower-case hex without leading zeros ("0" for zero): java.math.BigInteger.toString(16). */
    fun toHex(): String {
        if (mag.isEmpty()) return "0"
        val sb = StringBuilder()
        sb.append(mag.last().toUInt().toString(16))
        for (i in mag.size - 2 downTo 0) sb.append(mag[i].toUInt().toString(16).padStart(8, '0'))
        return sb.toString()
    }

    fun toLong(): Long = when (mag.size) {
        0 -> 0L; 1 -> mag[0].toLong() and M
        else -> ((mag[1].toLong() and M) shl 32) or (mag[0].toLong() and M)
    }

    /** Montgomery arithmetic modulo an odd [n] (CIOS). */
    private class Montgomery(val n: BigNat) {
        val k = n.mag.size
        val nm = n.mag
        // −n⁻¹ mod 2³², by Newton's iteration.
        val n0inv: Int = run {
            val n0 = nm[0]
            var x = n0 // correct to 3 bits for odd n0
            repeat(5) { x *= 2 - n0 * x }
            -x
        }
        /** R² mod n, R = 2^(32k). */
        val r2: BigNat = (ONE shl (64 * k)).mod(n)

        fun mul(a: IntArray, b: IntArray): IntArray {
            val t = IntArray(k + 2)
            for (i in 0 until k) {
                val ai = (if (i < a.size) a[i] else 0).toLong() and M
                var c = 0L
                for (j in 0 until k) {
                    val bj = (if (j < b.size) b[j] else 0).toLong() and M
                    val s = (t[j].toLong() and M).toULong() + ai.toULong() * bj.toULong() + c.toULong()
                    t[j] = s.toLong().toInt(); c = (s shr 32).toLong()
                }
                var s2 = (t[k].toLong() and M) + c
                t[k] = s2.toInt(); t[k + 1] = (s2 ushr 32).toInt()
                val mq = (t[0] * n0inv).toLong() and M
                var s3 = (t[0].toLong() and M).toULong() + mq.toULong() * (nm[0].toLong() and M).toULong()
                c = (s3 shr 32).toLong()
                for (j in 1 until k) {
                    s3 = (t[j].toLong() and M).toULong() + mq.toULong() * (nm[j].toLong() and M).toULong() + c.toULong()
                    t[j - 1] = s3.toLong().toInt(); c = (s3 shr 32).toLong()
                }
                s2 = (t[k].toLong() and M) + c
                t[k - 1] = s2.toInt()
                t[k] = ((t[k + 1].toLong() and M) + (s2 ushr 32)).toInt()
                t[k + 1] = 0
            }
            val res = t.copyOfRange(0, k + 1)
            // Final subtraction: the result is below 2n.
            return if (cmp(norm(res.copyOf()), nm) >= 0) sub(norm(res), nm).let { padTo(it, k) } else padTo(norm(res), k)
        }

        fun pow(base: BigNat, e: BigNat): BigNat {
            val one = padTo(ONE.mag, k)
            val bm = mul(base.mag, r2.mag)          // base · R mod n
            var acc = mul(one, r2.mag)              // R mod n
            for (i in e.bitLength() - 1 downTo 0) {
                acc = mul(acc, acc)
                if (e.testBit(i)) acc = mul(acc, bm)
            }
            return BigNat(norm(mul(acc, one)))       // out of the Montgomery domain
        }
    }

    companion object {
        private const val M = 0xFFFFFFFFL
        private const val B = 0x100000000L
        val ZERO = BigNat(IntArray(0))
        val ONE = BigNat(intArrayOf(1))

        fun of(v: Long): BigNat {
            require(v >= 0) { "negative" }
            return BigNat(norm(intArrayOf(v.toInt(), (v ushr 32).toInt())))
        }

        /** Unsigned big-endian bytes. */
        fun fromBytes(b: ByteArray): BigNat {
            val out = IntArray((b.size + 3) / 4)
            for (i in b.indices) {
                val byte = b[b.size - 1 - i].toInt() and 0xFF
                out[i / 4] = out[i / 4] or (byte shl (8 * (i % 4)))
            }
            return BigNat(norm(out))
        }

        /** Hex, upper or lower case, leading zeros allowed (java.math.BigInteger(s, 16) for s ≥ 0). */
        fun fromHex(s: String): BigNat {
            require(s.isNotEmpty() && s.all { it.digitToIntOrNull(16) != null }) { "not hex" }
            val h = if (s.length % 2 == 1) "0$s" else s
            return fromBytes(ByteArray(h.length / 2) { ((h[2 * it].digitToInt(16) shl 4) or h[2 * it + 1].digitToInt(16)).toByte() })
        }

        /** A uniformly random number of at most [bits] bits. */
        fun random(bits: Int): BigNat {
            val bytes = secureRandomBytes((bits + 7) / 8)
            val extra = bytes.size * 8 - bits
            if (extra > 0) bytes[0] = (bytes[0].toInt() and (0xFF ushr extra)).toByte()
            return fromBytes(bytes)
        }

        /** A uniformly random number in [1, n). */
        fun randomBelow(n: BigNat): BigNat {
            while (true) {
                val c = random(n.bitLength())
                if (!c.isZero && c < n) return c
            }
        }

        private val SMALL_PRIMES: IntArray = run {
            val lim = 2000
            val sieve = BooleanArray(lim + 1) { it >= 2 }
            for (i in 2..lim) if (sieve[i]) { var j = i * i; while (j <= lim) { sieve[j] = false; j += i } }
            (3..lim).filter { sieve[it] }.toIntArray()
        }

        /** Miller–Rabin with [rounds] random bases, after trial division by the primes under 2000. */
        fun isProbablePrime(p: BigNat, rounds: Int = 40): Boolean {
            if (p < of(2)) return false
            if (!p.isOdd) return p == of(2)
            for (sp in SMALL_PRIMES) {
                val spn = of(sp.toLong())
                if (p == spn) return true
                if (p.mod(spn).isZero) return false
            }
            val pm1 = p - ONE
            var s = 0
            while (!pm1.testBit(s)) s++
            val d = pm1 shr s
            val mont = Montgomery(p)
            repeat(rounds) {
                val a = randomBelow(pm1).let { if (it < of(2)) of(2) else it }
                var x = mont.pow(a, d)
                if (x == ONE || x == pm1) return@repeat
                var witness = true
                for (r in 1 until s) {
                    x = (x * x).mod(p)
                    if (x == pm1) { witness = false; break }
                    if (x == ONE) return false
                }
                if (witness) return false
            }
            return true
        }

        /**
         * A random prime of exactly [bits] bits whose two top bits are set, so the product of two
         * such primes has exactly twice the bits.
         */
        fun probablePrime(bits: Int): BigNat {
            require(bits >= 16)
            while (true) {
                val bytes = secureRandomBytes((bits + 7) / 8)
                val extra = bytes.size * 8 - bits
                bytes[0] = (bytes[0].toInt() and (0xFF ushr extra)).toByte()
                // The top two bits of the [bits]-bit number, and odd.
                val top = 7 - extra
                bytes[0] = (bytes[0].toInt() or (1 shl top)).toByte()
                if (top >= 1) bytes[0] = (bytes[0].toInt() or (1 shl (top - 1))).toByte()
                else bytes[1] = (bytes[1].toInt() or 0x80).toByte()
                bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or 1).toByte()
                val c = fromBytes(bytes)
                if (isProbablePrime(c, 1) && isProbablePrime(c, 39)) return c
            }
        }

        // ---- limb arithmetic

        private fun norm(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        private fun padTo(a: IntArray, k: Int): IntArray = if (a.size >= k) a else a.copyOf(k)

        private fun cmp(a: IntArray, b: IntArray): Int {
            val an = norm(a); val bn = norm(b)
            if (an.size != bn.size) return an.size.compareTo(bn.size)
            for (i in an.indices.reversed()) {
                val x = an[i].toLong() and M; val y = bn[i].toLong() and M
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        private fun add(a: IntArray, b: IntArray): IntArray {
            val n = maxOf(a.size, b.size)
            val out = IntArray(n + 1); var c = 0L
            for (i in 0 until n) {
                val s = (if (i < a.size) a[i].toLong() and M else 0L) + (if (i < b.size) b[i].toLong() and M else 0L) + c
                out[i] = s.toInt(); c = s ushr 32
            }
            out[n] = c.toInt()
            return norm(out)
        }

        /** a − b for a ≥ b. */
        private fun sub(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size); var borrow = 0L
            for (i in a.indices) {
                val t = (a[i].toLong() and M) - (if (i < b.size) b[i].toLong() and M else 0L) - borrow
                out[i] = t.toInt(); borrow = if (t < 0) 1 else 0
            }
            return norm(out)
        }

        private fun mul(a: IntArray, b: IntArray): IntArray {
            if (a.isEmpty() || b.isEmpty()) return IntArray(0)
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                val ai = (a[i].toLong() and M).toULong()
                var c = 0UL
                for (j in b.indices) {
                    val s = (out[i + j].toLong() and M).toULong() + ai * (b[j].toLong() and M).toULong() + c
                    out[i + j] = s.toLong().toInt(); c = s shr 32
                }
                out[i + b.size] = c.toLong().toInt()
            }
            return norm(out)
        }
    }
}
