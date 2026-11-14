package com.kilombino.kilowallet.core.joinblaket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

/**
 * The JoinBlaket market (JoinMarket on BTC BLAKE2b), as start10's orderbook watcher sees it:
 * the offers of makers with a verified fidelity bond. It feeds "PAY THROUGH JOINBLAKET" on the
 * send screen: how many makers there are, the smallest and largest amount they take, and what a
 * round of a given amount would cost (makers' fees + mining fee). Kilowallet for Android 1.6.0's
 * `JoinBlaketMarket`; the request itself is the platform's (iOS: URLSession).
 *
 * Source: https://mempool.kilombino.com/joinblaket/market.json (no nicks, no bond UTXOs).
 */
object JoinBlaketMarket {
    const val URL_MARKET = "https://mempool.kilombino.com/joinblaket/market.json"

    data class Offer(val rel: Boolean, val minsize: Long, val maxsize: Long, val cjfee: Double, val txfee: Long, val bondValue: Long) {
        /** This maker's fee for a round of [amount] sats. */
        fun fee(amount: Long): Long = if (rel) ceil(amount * cjfee).toLong() else cjfee.toLong()
        fun takes(amount: Long): Boolean = amount in minsize..maxsize
    }

    data class Market(val makers: Int, val minAmount: Long?, val maxAmount: Long?, val offers: List<Offer>, val height: Int?)

    data class Estimate(
        val makers: Int,          // makers that take this amount (the round goes on with all of them, up to [MAX_MAKERS])
        val makerFees: Long,      // what they charge in total
        val miningFee: Long,      // the whole transaction's mining fee, which the taker pays
    ) {
        val total: Long get() = makerFees + miningFee
    }

    /** The market as start10's watcher published it at block 981 795 (Android's JoinBlaketMarketTest): tests and screenshots. */
    const val SAMPLE = """{"updated":"x","height":981795,"network":"xbt","makers":2,"min_amount":100000,"max_amount":12312375,
        "offers":[{"type":"rel","minsize":100000,"maxsize":12312375,"cjfee":"0.00002","txfee":0,"bond_value":27738},
                  {"type":"abs","minsize":500000,"maxsize":2000000,"cjfee":"300","txfee":50,"bond_value":100}]}"""

    /** Jam asks for 8-10 makers; the estimate never counts more than that. */
    const val MAX_MAKERS = 9

    fun parse(json: String): Market {
        val o = JbJson.obj(json)
        fun optLong(x: JsonObject, k: String): Long? = JbJson.prim(x, k)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        val offers = (o["offers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { x ->
            Offer(
                rel = JbJson.optString(x, "type") == "rel",
                minsize = optLong(x, "minsize") ?: error("offer without minsize"),
                maxsize = optLong(x, "maxsize") ?: error("offer without maxsize"),
                cjfee = (JbJson.prim(x, "cjfee")?.content ?: "0").toDoubleOrNull() ?: 0.0,
                txfee = optLong(x, "txfee") ?: 0,
                bondValue = optLong(x, "bond_value") ?: 0,
            )
        }
        fun nullable(k: String) = if (o[k] == null || o[k] is JsonNull) null else optLong(o, k)
        return Market(
            makers = JbJson.optInt(o, "makers", 0),
            minAmount = nullable("min_amount"),
            maxAmount = nullable("max_amount"),
            offers = offers,
            height = nullable("height")?.toInt(),
        )
    }

    /**
     * What a round of [amount] sats would cost at [feeRate] sat/vB: the cheapest offers that take
     * the amount (up to [MAX_MAKERS]), and a mining fee for one P2WPKH input and two outputs per
     * maker plus the taker's input, payment output and change. Null if no maker takes the amount.
     */
    fun estimate(market: Market, amount: Long, feeRate: Double, takerInputs: Int = 1): Estimate? {
        val chosen = market.offers.filter { it.takes(amount) }.sortedBy { it.fee(amount) }.take(MAX_MAKERS)
        if (chosen.isEmpty()) return null
        val n = chosen.size
        val vbytes = 11 + 68 * (n + takerInputs) + 31 * (2 * n + 2)
        val mining = ceil(vbytes * feeRate).toLong() - chosen.sumOf { it.txfee }
        return Estimate(n, chosen.sumOf { it.fee(amount) }, maxOf(0L, mining))
    }
}
