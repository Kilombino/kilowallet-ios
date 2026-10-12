package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.AddressRow
import kotlin.test.*

class AddressBookTest {
    private class MemoryPrefs : Prefs {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            check(!failWrites) { "storage unavailable" }
            if (value == null) values.remove(key) else values[key] = value
        }
    }
    private fun used(chain: Int, index: Int) = AddressRow(chain, index, "test", "test", "test", txCount = 1)

    @Test fun reservationSurvivesRestartAndBranchesStayIndependent() {
        val p = MemoryPrefs(); val book = AddressBook(p)
        book.reserve("public-wallet", 0, 0)
        book.reserve("public-wallet", 1, 0)
        book.reserve("public-wallet", 0, 1)
        val restored = AddressBook(p)
        assertEquals(2, restored.next("public-wallet", 0))
        assertEquals(1, restored.next("public-wallet", 1))
        assertEquals(0, restored.next("another-wallet", 0))
        assertFailsWith<IllegalArgumentException> { restored.reserve("public-wallet", 0, 0) }
    }
    @Test fun discoveryNeverMovesBackwardAfterServerSwitchOrReorg() {
        val book = AddressBook(MemoryPrefs())
        book.observe("wallet", listOf(used(0, 50), used(1, 23)))
        book.observe("wallet", emptyList())
        book.observe("wallet", listOf(used(0, 2), used(1, 1)))
        assertEquals(51, book.next("wallet", 0))
        assertEquals(24, book.next("wallet", 1))
    }
    @Test fun unusedAddressLimitProtectsStandardGapRecovery() {
        val book = AddressBook(MemoryPrefs())
        for (chain in 0..1) {
            repeat(20) { book.reserve("wallet", chain, it) }
            assertFailsWith<IllegalArgumentException> { book.reserve("wallet", chain, 20) }
            book.observe("wallet", listOf(used(chain, 0)))
            book.reserve("wallet", chain, 20)
            assertFailsWith<IllegalArgumentException> { book.reserve("wallet", chain, 21) }
        }
    }
    @Test fun migratesPreviouslyDisplayedLegacyAddressWithoutReusingIt() {
        val p = MemoryPrefs(); p.values["receiveIndex.wallet"] = "25"
        val book = AddressBook(p)
        assertEquals(26, book.read("wallet").receive)
        assertFailsWith<IllegalArgumentException> { book.reserve("wallet", 0, 26) }
        book.observe("wallet", listOf(used(0, 25)))
        book.reserve("wallet", 0, 26)
        assertEquals(27, AddressBook(p).read("wallet").receive)
    }
    @Test fun corruptProgressAndFailedWritesNeverSilentlyResetAnAddress() {
        val p = MemoryPrefs(); val book = AddressBook(p)
        for (raw in listOf("", "1:2", "-1:0:0:0", "0:0:1:0", "10001:0:0:0", "x:0:0:0")) {
            p.values["addresses.v1.wallet"] = raw
            assertFails { book.next("wallet", 0) }
        }
        p.values.clear(); p.failWrites = true
        assertFails { book.reserve("wallet", 0, 0) }
        assertTrue(p.values.isEmpty())
    }
}
