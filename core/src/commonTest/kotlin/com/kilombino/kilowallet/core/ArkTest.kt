package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.ark.Ark
import com.kilombino.kilowallet.core.ark.ArkDerive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArkTest {
    @Test
    fun arkWordsAreTheAndroidOnes() {
        val rows = ArkVectors.TSV.trim('\n').lines()
        assertEquals(5, rows.size)
        for (row in rows) {
            val (words, pass, ark) = row.split('\t')
            assertEquals(ark, ArkDerive.words(words.split(' '), pass).joinToString(" "), "Ark words of '$words' / '$pass'")
        }
    }

    @Test
    fun engineAmountsAreWordedInSats() {
        assertEquals("You need 9 000 sats more.", Ark.inSats("You need 0.00009000 BTC more"))
        assertEquals("Paid 1 234 567 sats.", Ark.inSats("Paid 0.01234567 BTC."))
        assertEquals("Not enough funds.", Ark.inSats("Not enough funds"))
    }

    @Test
    fun invoiceAmounts() {
        assertEquals(250_000L, Ark.invoiceSats("lnbc2500u1pxyz"))
        assertEquals(100_000_000L, Ark.invoiceSats("lnbc11pxyz"))
        assertEquals(150L, Ark.invoiceSats("LNBC1500N1pxyz"))
        assertEquals(1L, Ark.invoiceSats("lnbc10000p1pxyz"))
        assertNull(Ark.invoiceSats("lnbc1pxyz"))
        assertNull(Ark.invoiceSats("ark1qqq"))
    }

    @Test
    fun timesAndText() {
        assertEquals("1970-01-01T00:00:00", Ark.isoUtc(0))
        assertEquals("2026-11-01T12:34:56", Ark.isoUtc(1_793_536_496_000))
        assertEquals(1_793_536_496_000, Ark.parseIsoUtc("2026-11-01T12:34:56.123Z"))
        assertEquals("12 345 678", Ark.groupSats(12_345_678))
        assertEquals("-1 000", Ark.groupSats(-1000))
        assertEquals("2.5", Ark.oneDecimal(2.46))
        assertEquals("bc1q%2Bx+y", Ark.urlEncode("bc1q+x y"))
    }
}
