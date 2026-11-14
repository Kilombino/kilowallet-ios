package com.kilombino.kilowallet.core.joinblaket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** The decimal stand-in against java.math.BigDecimal, which Android's fee maths uses. */
class DecTest {
    private val samples = listOf("0", "0.0", "1", "0.00002", "0.00020", "0.0002", "1E-5", "1e-5", "2.5E-4", "1E+3", "0.5", "1.5",
        "2.5", "0.000015", "0.1", "0.00025", "123.456", ".5", "5.", "0.0000000000000000000000000000001", "+0.001")

    @Test fun parsingFormattingAndOrderMatchBigDecimal() {
        for (s in samples) {
            val d = Dec.parse(s); val b = BigDecimal(s)
            assertEquals(s, b.toPlainString(), d.toPlainString())
            assertEquals(s, b.scale(), d.scale)
            assertEquals(s, b.unscaledValue().abs().toString().length, d.precision)
            for (t in samples) assertEquals("$s vs $t", b.compareTo(BigDecimal(t)).coerceIn(-1, 1), d.compareTo(Dec.parse(t)).coerceIn(-1, 1))
        }
        for (bad in listOf("", "-0.1", "abc", "1E", "1.2.3", "1e+-3")) assertNull(bad, runCatching { Dec.parse(bad) }.getOrNull())
    }

    @Test fun halfEvenProductsMatchBigDecimal() {
        val r = Random(7)
        repeat(5_000) {
            val fee = samples[r.nextInt(samples.size)]
            val amount = if (r.nextBoolean()) r.nextLong(0, 10_000_000_000L) else r.nextLong(0, 100_000)
            val want = BigDecimal(fee).multiply(BigDecimal.valueOf(amount)).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
            assertEquals("$fee × $amount", want, Fees.relativeFee(amount, fee))
        }
        // Exact halves go to the even neighbour.
        assertEquals(2L, Fees.relativeFee(5, "0.5")); assertEquals(4L, Fees.relativeFee(7, "0.5")); assertEquals(0L, Fees.relativeFee(1, "0.5"))
    }
}
