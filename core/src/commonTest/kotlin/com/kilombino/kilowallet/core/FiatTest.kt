package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.core.market.MarketFeed
import com.kilombino.kilowallet.core.market.paymentRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android app's data/FiatTest.kt and MarketFeedParseTest.exchangeRates (1.2.3), on the shared code. */
class FiatTest {
    @Test fun formats() {
        assertEquals("$1,234.56", Fiat.format(1234.561, "USD"))
        assertEquals("€0.47", Fiat.format(0.4712, "EUR"))
        assertEquals("¥157,880", Fiat.format(157880.4, "JPY"))
        assertEquals("1,234.56 RON", Fiat.format(1234.56, "RON"))
        assertEquals("3,174 COP", Fiat.format(3173.5, "COP"))
        assertEquals("R$5.02", Fiat.format(5.0233, "BRL"))
        assertEquals("$1,000,000.00", Fiat.format(1_000_000.0, "USD"))
        assertEquals("$ –", Fiat.missing("USD"))
        assertEquals("– RON", Fiat.missing("RON"))
        assertEquals(36, Fiat.ALL.size)
    }

    @Test fun typedAmounts() {
        assertEquals(12.5, Fiat.parse("12.5")!!, 0.0)
        assertEquals(12.5, Fiat.parse("12,5")!!, 0.0)
        assertEquals(1234.56, Fiat.parse("1,234.56")!!, 1e-9)
        assertEquals(1234.56, Fiat.parse("1.234,56")!!, 1e-9)
        assertEquals(1234567.0, Fiat.parse("1.234.567")!!, 0.0)
        assertEquals(1234567.0, Fiat.parse("1,234,567")!!, 0.0)
        assertNull(Fiat.parse(""))
        assertNull(Fiat.parse("abc"))
    }

    @Test fun satsFromFiat() {
        // 1 XBT = 500 RON: 10 RON is 2,000,000 sats.
        assertEquals(2_000_000L, Fiat.toSats("10", 500.0))
        assertEquals(2_000_000L, Fiat.toSats("10,00", 500.0))
        assertNull(Fiat.toSats("10", null))
        assertNull(Fiat.toSats("10", 0.0))
        assertNull(Fiat.toSats("-1", 500.0))
        // And back, as the fiat box shows it under typed sats.
        assertEquals("10.00", Fiat.plain(500.0 * 2_000_000 / 1e8, "RON"))
        assertEquals("1579", Fiat.plain(1578.8, "JPY"))
    }

    @Test fun availableNeedsARate() {
        val m = MarketFeed.parse("""{"xbtUsd":600,"fiatPerUsd":{"JPY":150,"RON":4.7,"XYZ":2}}""", 1L)
        assertEquals(listOf("USD", "EUR", "JPY", "RON"), Fiat.available(m).map { it.code })
        assertEquals(listOf("USD", "EUR"), Fiat.available(null).map { it.code })
    }

    @Test fun exchangeRates() {
        val json = """{"pollMinutes":5,"xbtUsd":630.42,"xbtEur":561.2,"fiatPerUsd":{"EUR":0.8902,"JPY":157.88,"RON":4.7503,"BAD":-1},"fiatDate":"2026-10-21"}"""
        val m = MarketFeed.parse(json, 1L)
        assertEquals(3, m.fiatPerUsd!!.size) // a rate that is not positive is dropped
        assertEquals(630.42, m.xbtIn("USD")!!, 1e-9)
        assertEquals(561.2, m.xbtIn("EUR")!!, 1e-9) // the feed's own XBT/EUR first
        assertEquals(630.42 * 157.88, m.xbtIn("JPY")!!, 1e-6)
        assertEquals(630.42 * 4.7503 * 0.5, m.fiatValue(50_000_000, "RON")!!, 1e-6)
        assertNull(m.xbtIn("ARS")) // no rate, no price
        // An older server: only USD and EUR.
        val old = MarketFeed.parse("""{"xbtUsd":630.42,"xbtEur":561.2,"height":1}""", 1L)
        assertNull(old.fiatPerUsd)
        assertNull(old.xbtIn("JPY"))
        assertTrue(old.pollMinutes >= MarketFeed.MIN_POLL_MIN)
    }

    @Test fun bip21Request() {
        val a = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertEquals(a, paymentRequest(a, null))
        assertEquals(a, paymentRequest(a, 0))
        assertEquals("bitcoin:$a?amount=0.0012", paymentRequest(a, 120_000))
        assertEquals("bitcoin:$a?amount=1", paymentRequest(a, 100_000_000))
        assertEquals("bitcoin:$a?amount=21.00000001", paymentRequest(a, 2_100_000_001))
    }
}
