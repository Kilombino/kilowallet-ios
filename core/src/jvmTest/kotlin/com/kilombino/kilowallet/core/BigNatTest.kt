package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.kilojoin.BigNat
import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [BigNat] against java.math.BigInteger, on random numbers of every size blind signatures use. */
class BigNatTest {
    private val rnd = Random(20261025)
    private fun big(bits: Int) = BigInteger(bits, rnd)
    private fun nat(b: BigInteger) = BigNat.fromHex(b.toString(16))
    private fun eq(want: BigInteger, got: BigNat, what: String) = assertEquals(want.toString(16), got.toHex(), what)

    @Test fun arithmeticMatchesJava() {
        for (round in 0 until 400) {
            val ab = listOf(1, 31, 32, 33, 63, 64, 65, 200, 1024, 2048, 4096)[round % 11]
            val bb = listOf(1, 17, 32, 64, 96, 512, 1024, 2047, 2048)[round % 9]
            val a = big(ab); var b = big(bb); if (b.signum() == 0) b = BigInteger.ONE
            val x = nat(a); val y = nat(b)
            eq(a.add(b), x + y, "add")
            eq(a.multiply(b), x * y, "mul")
            if (a >= b) eq(a.subtract(b), x - y, "sub")
            eq(a.divide(b), x / y, "div $a / $b")
            eq(a.mod(b), x.mod(y), "mod")
            eq(a.shiftLeft(round % 70), x shl (round % 70), "shl")
            eq(a.shiftRight(round % 70), x shr (round % 70), "shr")
            assertEquals(a.compareTo(b), x.compareTo(y).coerceIn(-1, 1), "cmp")
            assertEquals(a.bitLength(), x.bitLength(), "bits")
            eq(a.gcd(b), x.gcd(y), "gcd")
            assertEquals(a.toByteArray().dropWhile { it == 0.toByte() }, x.toBytes().toList(), "bytes")
        }
    }

    @Test fun modularPowersAndInversesMatchJava() {
        for (round in 0 until 60) {
            val bits = listOf(64, 256, 1024, 2048)[round % 4]
            val m = big(bits).setBit(bits - 1).let { if (round % 3 == 0) it.clearBit(0) else it.setBit(0) } // odd and even moduli
            val base = big(bits + 10)
            val e = if (round % 5 == 0) BigInteger.valueOf(65537) else big(bits)
            eq(base.modPow(e, m), nat(base).modPow(nat(e), nat(m)), "modPow $bits")
            val inv = runCatching { base.modInverse(m) }.getOrNull()
            if (inv != null) eq(inv, nat(base).modInverse(nat(m)), "modInverse")
        }
    }

    @Test fun primesAreJavasPrimes() {
        for (i in 0 until 6) {
            val p = BigNat.probablePrime(256)
            assertEquals(256, p.bitLength())
            assertTrue(p.testBit(254), "top two bits set")
            assertTrue(BigInteger(p.toHex(), 16).isProbablePrime(64))
        }
        for (i in 0 until 200) {
            val c = big(128)
            assertEquals(c.isProbablePrime(64), BigNat.isProbablePrime(nat(c)), c.toString())
        }
        // Carmichael numbers fool Fermat, not Miller–Rabin.
        for (c in listOf(561L, 41041L, 825265L, 321197185L, 5394826801L))
            assertTrue(!BigNat.isProbablePrime(BigNat.of(c)), "$c")
    }
}
