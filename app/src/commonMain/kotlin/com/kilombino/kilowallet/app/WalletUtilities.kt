package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.TxConf
import kotlinx.serialization.json.*
import kotlin.time.Instant

data class TransactionNote(val label: String = "", val note: String = "")

/** Local annotations only; keyed by account identity, never by the current screen or wallet name. */
internal class TransactionNotes(private val prefs: Prefs) {
    private fun key(zpub: String) = "notes.v1." + WalletCatalog.idFor(zpub)
    fun clear(zpub: String) { prefs.put(key(zpub), null) }
    fun read(zpub: String): Map<String, TransactionNote> {
        val raw = prefs.get(key(zpub)) ?: return emptyMap()
        return Json.parseToJsonElement(raw).jsonObject.mapValues { (id, value) ->
            require(id.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid saved transaction ID." }
            val o = value.jsonObject
            checked(o["label"]!!.jsonPrimitive.content, o["note"]!!.jsonPrimitive.content)
        }
    }
    fun save(zpub: String, txid: String, label: String, note: String): Map<String, TransactionNote> {
        require(txid.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid transaction ID." }
        val value = checked(label, note)
        val next = read(zpub).toMutableMap()
        if (value.label.isEmpty() && value.note.isEmpty()) next.remove(txid) else next[txid] = value
        prefs.put(key(zpub), buildJsonObject {
            next.forEach { (id, n) -> put(id, buildJsonObject { put("label", n.label); put("note", n.note) }) }
        }.toString())
        return next
    }
    private fun checked(label: String, note: String): TransactionNote {
        require(label.length <= 80 && label.none { it.code < 32 || it.code == 127 }) { "Use a label of up to 80 characters." }
        require(note.length <= 500 && note.none { (it.code < 32 && it !in "\n\r\t") || it.code == 127 }) { "Use a note of up to 500 characters." }
        return TransactionNote(label.trim(), note.trim())
    }
}

/** The timestamp belongs to the in-memory snapshot, not a cached balance from a previous launch. */
fun syncTimeText(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()
    .substringBefore('.').removeSuffix("Z").replace('T', ' ') + " UTC"

/** Exports the selected wallet's complete loaded history, not just the first 200 visible rows. */
internal fun transactionCsv(wallet: String, txs: List<TxConf>, notes: Map<String, TransactionNote>, syncedAt: Long): String {
    fun cell(value: String, untrusted: Boolean = false): String {
        // Quoting alone does not stop spreadsheet formulas. Only free text gets this prefix;
        // signed numeric net amounts stay numeric, including outgoing negative values.
        val safe = if (untrusted && (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.firstOrNull() in listOf('\t', '\r', '\n'))) "'" + value else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    val header = listOf("network", "wallet", "txid", "net_sats", "confirmations", "status", "block_height", "label", "note", "snapshot_utc")
    return buildString {
        append(header.joinToString(",") { cell(it) }); append("\r\n")
        txs.forEach { tx ->
            val n = notes[tx.txid] ?: TransactionNote()
            append(listOf(cell("XBT BLAKE2b"), cell(wallet, true), cell(tx.txid, true),
                cell(tx.amount?.toString() ?: ""), cell(tx.confirmations.toString()),
                cell(if (tx.pending) "pending" else "confirmed"), cell(if (tx.pending) "" else tx.height.toString()),
                cell(n.label, true), cell(n.note, true), cell(syncTimeText(syncedAt))).joinToString(","))
            append("\r\n")
        }
    }
}
