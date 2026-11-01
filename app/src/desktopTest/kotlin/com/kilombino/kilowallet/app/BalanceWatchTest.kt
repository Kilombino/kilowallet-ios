package com.kilombino.kilowallet.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceWatchTest {
    private val old = listOf(BalanceWatch.Entry("a", 10_000, 0), BalanceWatch.Entry("b", 0, 0))

    @Test fun aPaymentInTheMempoolThenConfirmed() {
        val inMempool = mapOf("a" to (10_000L to 0L), "b" to (0L to 5_000L))
        assertEquals(listOf("New incoming payment in the mempool: +5 000 sats"), BalanceWatch.changes(old, inMempool).map { it.replace(' ', ' ').replace(' ', ' ') })
        val after = old.map { BalanceWatch.Entry(it.s, inMempool[it.s]!!.first, inMempool[it.s]!!.second) }
        assertTrue(BalanceWatch.changes(after, mapOf("a" to (10_000L to 0L), "b" to (5_000L to 0L))).single().startsWith("Confirmed: "))
    }

    @Test fun sentAndNothing() {
        assertTrue(BalanceWatch.changes(old, mapOf("a" to (0L to 0L), "b" to (0L to 0L))).single().startsWith("Sent: "))
        assertEquals(emptyList(), BalanceWatch.changes(old, mapOf("a" to (10_000L to 0L))))
    }

    @Test fun theKeptListReadsBack() {
        val l = listOf(BalanceWatch.Entry("ab12", 5, -3), BalanceWatch.Entry("cd34", 0, 7))
        assertEquals(l, BalanceWatch.decode(BalanceWatch.encode(l)))
        assertEquals(emptyList(), BalanceWatch.decode(null))
    }
}
