package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.*
import kotlin.test.*

class EverydayToolsTest {
    private class Memory : Prefs {
        val data = mutableMapOf<String,String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
    }
    private val words = List(11) { "abandon" } + "about"
    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    @Test fun unitsRoundTripExactlyAndRejectAmbiguousOrOverflowingAmounts() {
        listOf(1L, 120000L, 100000001L, PaymentRequest.MAX_REQUEST_SATS).forEach { value ->
            AmountUnit.entries.forEach { unit -> assertEquals(value, unit.parse(unit.input(value))) }
        }
        listOf("", "0", "-1", "1.2", "1e3", "1,000", "9223372036854775808").forEach { assertNull(AmountUnit.SATS.parse(it)) }
        listOf("", "0", "-1", "0.000000001", "21000000.00000001", "1e2").forEach { assertNull(AmountUnit.XBT.parse(it)) }
        assertEquals(1, AmountUnit.XBT.parse("0.00000001"))
        assertEquals("-0.00000001 XBT", AmountUnit.XBT.text(-1))
        assertEquals("bitcoin:$address?chain=xbt&amount=0.00120000", PaymentRequest.create(address, AmountUnit.XBT.parse("0.0012")))
    }

    @Test fun searchFiltersUseAllLoadedHistoryAndKeepUnknownAmountsDistinct() {
        val incoming = TxConf("ab".repeat(32), 6, false, 123, height = 970000)
        val outgoing = TxConf("cd".repeat(32), 0, true, -456)
        val unknown = TxConf("ef".repeat(32), 0, true)
        val notes = mapOf(incoming.txid to TransactionNote("Mining", "October payout"))
        val txs = listOf(incoming, outgoing, unknown)
        assertEquals(listOf(incoming), filterHistory(txs, notes, "OCTOBER", HistoryFilter.ALL))
        assertEquals(listOf(outgoing), filterHistory(txs, notes, "CDCD", HistoryFilter.OUTGOING))
        assertEquals(listOf(outgoing, unknown), filterHistory(txs, notes, "", HistoryFilter.PENDING))
        assertEquals(listOf(incoming), filterHistory(txs, notes, "", HistoryFilter.INCOMING))
        assertTrue(filterHistory(txs, emptyMap(), "October", HistoryFilter.ALL).isEmpty())
        val many = (1..250).map { TxConf(it.toString(16).padStart(64, '0'), 1, false) }
        assertEquals(listOf(many.last()), filterHistory(many, emptyMap(), many.last().txid, HistoryFilter.ALL))
    }

    @Test fun recipientsPersistPerWalletValidateAndDoNotOverwriteCorruption() {
        val p = Memory()
        val a = Signer.accountZpub(words, "")
        val b = Signer.accountZpub(words, "other")
        val store = Recipients(p)
        store.save(a, address.uppercase(), "Alice")
        assertEquals(listOf(Recipient(address, "Alice")), Recipients(p).read(a))
        assertTrue(store.read(b).isEmpty())
        store.save(a, address, "Renamed")
        assertEquals(1, store.read(a).size)
        assertEquals("Renamed", store.read(a).single().name)
        assertFails { store.save(a, "not-an-address", "Bad") }
        assertFails { store.save(a, address, "line\nbreak") }
        assertFails { store.save(a, address, "") }
        store.save(b, address, "Other")
        store.remove(a, address)
        assertTrue(store.read(a).isEmpty())
        assertEquals("Other", store.read(b).single().name)
        val key = "recipients.v1." + WalletCatalog.idFor(a)
        p.data[key] = "{broken"
        assertFails { store.save(a, address, "Replacement") }
        assertFails { store.remove(a, address) }
        assertEquals("{broken", p.data[key])
    }
}
