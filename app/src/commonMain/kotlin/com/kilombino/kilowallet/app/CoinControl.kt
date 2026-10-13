package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.SendPlanner
import kotlinx.serialization.json.*

fun SpendableUtxo.coinId(): String = "${txid.lowercase()}:$vout"

/** Frozen outputs are local policy, scoped by canonical account identity. Never silently reset corrupt policy. */
internal class FrozenCoins(private val prefs: Prefs) {
    private fun key(zpub: String) = "frozen.v1." + WalletCatalog.idFor(zpub)
    fun read(zpub: String): Set<String> {
        val raw = prefs.get(key(zpub)) ?: return emptySet()
        return Json.parseToJsonElement(raw).jsonArray.map {
            it.jsonPrimitive.content.also(::checkId)
        }.toSet()
    }
    fun set(zpub: String, id: String, frozen: Boolean): Set<String> {
        checkId(id)
        val next = read(zpub).toMutableSet()
        if (frozen) next.add(id) else next.remove(id)
        prefs.put(key(zpub), JsonArray(next.sorted().map(::JsonPrimitive)).toString())
        return next
    }
    fun clear(zpub: String) = prefs.put(key(zpub), null)
    private fun checkId(id: String) {
        val pieces = id.split(':')
        require(pieces.size == 2 && pieces[0].matches(Regex("[0-9a-f]{64}")) &&
            pieces[1].toIntOrNull()?.let { it >= 0 && it.toString() == pieces[1] } == true) { "Invalid frozen coin record." }
    }
}

/** null selection means automatic; an empty manual selection must never fall back to automatic. */
internal fun eligibleCoins(coins: List<SpendableUtxo>, frozen: Set<String>, selected: Set<String>?): List<SpendableUtxo> {
    SendPlanner.validateCoins(coins)
    if (selected != null) {
        require(selected.isNotEmpty()) { "Select at least one unfrozen coin." }
        require(selected.intersect(frozen).isEmpty()) { "A selected coin is frozen. Review your selection." }
        require(coins.map { it.coinId() }.toSet().containsAll(selected)) { "A selected coin is no longer available. Refresh and select again." }
    }
    return coins.filter { it.coinId() !in frozen && (selected == null || it.coinId() in selected) }
}
