package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.wallet.*
import kotlinx.serialization.json.*

enum class AmountUnit { SATS, XBT;
    val label: String get() = if (this == SATS) "sats" else "XBT"
    fun number(value: Long): String = if (this == SATS) formatSats(value) else formatBtc(value)
    fun text(value: Long): String = number(value) + " " + label
    fun input(value: Long): String = if (this == SATS) value.toString() else formatBtc(value)
    fun parse(value: String): Long? = runCatching {
        val sats = if (this == SATS) {
            require(value.isNotEmpty() && value.all { it in '0'..'9' })
            value.toLong()
        } else PaymentRequest.toSats(value)
        require(sats in 1..PaymentRequest.MAX_REQUEST_SATS)
        sats
    }.getOrNull()
}

enum class HistoryFilter { ALL, INCOMING, OUTGOING, PENDING }
fun filterHistory(txs: List<TxConf>, notes: Map<String, TransactionNote>, query: String, filter: HistoryFilter): List<TxConf> {
    val q = query.trim()
    return txs.filter { tx ->
        val direction = when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.INCOMING -> (tx.amount ?: 0) > 0
            HistoryFilter.OUTGOING -> (tx.amount ?: 0) < 0
            HistoryFilter.PENDING -> tx.pending
        }
        val note = notes[tx.txid]
        direction && (q.isEmpty() || tx.txid.contains(q, true) ||
            note?.label?.contains(q, true) == true || note?.note?.contains(q, true) == true)
    }
}

data class Recipient(val address: String, val name: String)
internal class Recipients(private val prefs: Prefs) {
    private fun key(zpub: String) = "recipients.v1." + WalletCatalog.idFor(zpub)
    private fun checked(address: String, name: String): Recipient {
        val a = address.trim()
        Address.requireSpendableDestination(Address.decodeToScriptPubKey(a), "Recipient")
        require(name.trim().isNotEmpty() && name.length <= 60 && name.none { it.code < 32 || it.code == 127 }) { "Use a name of 1–60 characters." }
        // Canonicalize bech32 case; Base58 is case-sensitive.
        return Recipient(if (a.startsWith("bc1", true)) a.lowercase() else a, name.trim())
    }
    fun read(zpub: String): List<Recipient> {
        val raw = prefs.get(key(zpub)) ?: return emptyList()
        val list = Json.parseToJsonElement(raw).jsonArray.map {
            checked(it.jsonObject["address"]!!.jsonPrimitive.content, it.jsonObject["name"]!!.jsonPrimitive.content)
        }
        require(list.size <= 200 && list.map { it.address }.distinct().size == list.size) { "Invalid recipients list." }
        return list
    }
    fun save(zpub: String, address: String, name: String): List<Recipient> {
        val r = checked(address, name)
        val list = (read(zpub).filter { it.address != r.address } + r).sortedBy { it.name.lowercase() }
        require(list.size <= 200) { "Up to 200 saved recipients per wallet." }
        write(zpub, list)
        return list
    }
    fun remove(zpub: String, address: String): List<Recipient> {
        val list = read(zpub).filter { it.address != address }
        write(zpub, list)
        return list
    }
    private fun write(zpub: String, list: List<Recipient>) = prefs.put(key(zpub),
        JsonArray(list.map { buildJsonObject { put("address", it.address); put("name", it.name) } }).toString())
    fun clear(zpub: String) = prefs.put(key(zpub), null)
}
