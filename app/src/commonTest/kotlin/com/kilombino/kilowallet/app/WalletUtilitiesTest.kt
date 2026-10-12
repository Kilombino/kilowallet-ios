package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.TxConf
import kotlin.test.*

class WalletUtilitiesTest {
    private class Memory : Prefs {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private val words = List(11) { "abandon" } + "about"
    private val txid = "ab".repeat(32)

    @Test fun notesAreLocalPerWalletPersistentEditableAndRemovable() {
        val prefs = Memory()
        val store = TransactionNotes(prefs)
        val a = Signer.accountZpub(words, "")
        val b = Signer.accountZpub(words, "separate")
        store.save(a, txid, "Mining", "First payout")
        store.save(b, txid, "Savings", "Separate wallet")
        assertEquals(TransactionNote("Mining", "First payout"), TransactionNotes(prefs).read(a)[txid])
        assertEquals(TransactionNote("Savings", "Separate wallet"), store.read(b)[txid])
        store.save(a, txid, "Updated", "New note")
        assertEquals("Updated", store.read(a)[txid]?.label)
        store.save(a, txid, "", "")
        assertTrue(store.read(a).isEmpty())
        assertEquals("Savings", store.read(b)[txid]?.label)
        store.save(a, txid, "Retained", "")
        store.clear(b)
        assertTrue(store.read(b).isEmpty())
        assertEquals("Retained", store.read(a)[txid]?.label)
        assertTrue(prefs.values.values.none { it.contains(words.joinToString(" ")) })
    }

    @Test fun invalidNotesAndCorruptStorageDoNotOverwriteAnything() {
        val prefs = Memory()
        val store = TransactionNotes(prefs)
        val zpub = Signer.accountZpub(words, "")
        store.save(zpub, txid, "Original", "Note")
        val before = prefs.values.toMap()
        assertFails { store.save(zpub, "bad", "", "") }
        assertFails { store.save(zpub, txid, "a".repeat(81), "") }
        assertFails { store.save(zpub, txid, "", "a".repeat(501)) }
        assertFails { store.save(zpub, txid, "two\nlines", "") }
        assertEquals(before, prefs.values)
        prefs.values[prefs.values.keys.single()] = "{broken"
        assertFails { store.save(zpub, txid, "replacement", "") }
        assertEquals("{broken", prefs.values.values.single())
    }

    @Test fun csvQuotesTextNeutralizesFormulasAndKeepsSignedAmounts() {
        val tx = TxConf(txid, 6, false, -123, height = 970000)
        val note = TransactionNote("=SUM(1,2)", "Two \"quotes\",\nand a newline")
        val csv = transactionCsv("@wallet", listOf(tx), mapOf(txid to note), 0)
        assertTrue(csv.contains("\"'@wallet\""))
        assertTrue(csv.contains("\"'=SUM(1,2)\""))
        assertTrue(csv.contains("\"Two \"\"quotes\"\",\nand a newline\""))
        assertTrue(csv.contains("\"-123\""))
        assertTrue(csv.contains("\"1970-01-01 00:00:00 UTC\""))
        listOf("+x", "-x", "@x", "  =x", "\t=x", "\r=x").forEach { value ->
            assertTrue(transactionCsv(value, listOf(tx), emptyMap(), 0).contains("\"'$value\""))
        }
    }

    @Test fun csvIncludesAllLoadedRowsAndLeavesUnknownAmountsBlank() {
        val txs = (1..205).map { TxConf(it.toString(16).padStart(64, '0'), 0, true) }
        val csv = transactionCsv("Wallet", txs, emptyMap(), 0)
        assertEquals(206, csv.split("\r\n").filter { it.isNotEmpty() }.size)
        assertTrue(csv.contains(txs.last().txid))
        assertTrue(csv.contains("\"\",\"0\",\"pending\",\"\""))
        assertEquals("1970-01-01 00:00:01 UTC", syncTimeText(1500))
    }
}
