package com.kilombino.kilowallet.core.market

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The XBT price, as the Android app's `data/MarketFeed.kt` reads it from
 * https://mempool.kilombino.com/api/v1/blake2b/widget (only the fields the iOS app shows).
 */
data class MarketData(
    val xbtUsd: Double?,
    val xbtEur: Double?,
    /** Units of each currency per US dollar (the ECB's daily reference, open.er-api.com for the rest). */
    val fiatPerUsd: Map<String, Double>?,
    val fetchedMs: Long,
    val pollMinutes: Long = MarketFeed.MIN_POLL_MIN,
) {
    /** Price of one XBT in [fiat] (a [Fiat] code), or null when there is no price or rate. */
    fun xbtIn(fiat: String): Double? = when (fiat) {
        "USD" -> xbtUsd
        "EUR" -> xbtEur ?: fiatPerUsd?.get("EUR")?.let { r -> xbtUsd?.times(r) }
        else -> fiatPerUsd?.get(fiat)?.let { r -> xbtUsd?.times(r) }
    }

    /** Fiat value of [sats] XBT, or null when there is no price yet. */
    fun fiatValue(sats: Long, fiat: String): Double? = xbtIn(fiat)?.let { it * sats / 1e8 }
}

object MarketFeed {
    const val API = "https://mempool.kilombino.com/api/v1/blake2b/widget"
    const val SOURCE = "mempool.kilombino.com"
    const val MIN_POLL_MIN = 5L

    /** Parses the endpoint's JSON. Fields an older server doesn't send come out null. */
    fun parse(json: String, fetchedMs: Long): MarketData {
        val o = Json.parseToJsonElement(json).jsonObject
        fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { !it.isNaN() }
        val rates = (o["fiatPerUsd"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.let { k to it }
        }?.toMap()?.takeIf { it.isNotEmpty() }
        return MarketData(num("xbtUsd"), num("xbtEur"), rates, fetchedMs,
            maxOf(num("pollMinutes")?.toLong() ?: MIN_POLL_MIN, MIN_POLL_MIN))
    }
}

/**
 * The fiat currencies the wallet can show values in, from the Android app's `data/Fiat.kt`
 * (1.2.3): USD and EUR always, the rest once the feed brings their rate. [OFF] shows none and
 * never asks the feed.
 */
object Fiat {
    const val OFF = "OFF"

    /** [symbol] goes before the number; null writes the code after it ("1,234.56 RON"). */
    data class Currency(val code: String, val symbol: String?, val decimals: Int)

    val ALL: List<Currency> = listOf(
        Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2), Currency("JPY", "¥", 0),
        Currency("CHF", null, 2), Currency("CNY", "CN¥", 2), Currency("RON", null, 2), Currency("PLN", null, 2),
        Currency("CZK", null, 2), Currency("HUF", null, 0), Currency("SEK", null, 2), Currency("NOK", null, 2),
        Currency("DKK", null, 2), Currency("ISK", null, 0), Currency("TRY", "₺", 2), Currency("CAD", "CA$", 2),
        Currency("AUD", "A$", 2), Currency("NZD", "NZ$", 2), Currency("BRL", "R$", 2), Currency("MXN", "MX$", 2),
        Currency("ARS", null, 2), Currency("CLP", null, 0), Currency("COP", null, 0), Currency("PEN", null, 2),
        Currency("UYU", null, 2), Currency("VES", null, 2), Currency("INR", "₹", 2), Currency("KRW", "₩", 0),
        Currency("HKD", "HK$", 2), Currency("SGD", "S$", 2), Currency("ZAR", null, 2), Currency("ILS", "₪", 2),
        Currency("THB", null, 2), Currency("IDR", null, 0), Currency("PHP", "₱", 2), Currency("MYR", null, 2),
    )

    private val byCode = ALL.associateBy { it.code }

    fun of(code: String): Currency = byCode[code] ?: Currency(code, null, 2)

    /** The ones with a price in [m]: USD and EUR always, the rest when the feed has their rate. */
    fun available(m: MarketData?): List<Currency> =
        ALL.filter { it.code == "USD" || it.code == "EUR" || m?.fiatPerUsd?.containsKey(it.code) == true }

    /** [value] with [decimals] decimals, rounded half up, optionally with "," between thousands. */
    private fun fixed(value: Double, decimals: Int, grouping: Boolean): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val units = floor(abs(value) * scale + 0.5).toLong()
        val whole = (units / scale).toString()
        val w = if (!grouping) whole else whole.reversed().chunked(3).joinToString(",").reversed()
        val frac = if (decimals == 0) "" else "." + (units % scale).toString().padStart(decimals, '0')
        return (if (value < 0 && units != 0L) "-" else "") + w + frac
    }

    /** "$1,234.56", "¥157,880", "1,234.56 RON". */
    fun format(value: Double, code: String): String {
        val c = of(code)
        val n = fixed(value, c.decimals, grouping = true)
        return if (c.symbol != null) c.symbol + n else "$n ${c.code}"
    }

    /** Like [format] but for an amount being typed: no thousands separators, the currency's decimals. */
    fun plain(value: Double, code: String): String =
        if (abs(value) < 1e15) fixed(value, of(code).decimals, grouping = false) else ""

    /** "$ –" style placeholder when there is no price yet. */
    fun missing(code: String): String = of(code).symbol?.let { "$it –" } ?: "– $code"

    /**
     * Sats for [fiat] units at [perCoin] (fiat per whole coin), or null when the text is not a
     * number or there is no price. Accepts a comma as the decimal mark.
     */
    fun toSats(fiat: String, perCoin: Double?): Long? {
        val v = parse(fiat) ?: return null
        if (perCoin == null || perCoin <= 0 || v < 0) return null
        return (v / perCoin * 1e8).roundToLong()
    }

    /**
     * A typed amount: "12.5", "12,5", "1,234.56" or "1.234,56" (the last mark is the decimal one
     * when both appear; a lone mark is decimal too). Null if it is not a number.
     */
    fun parse(text: String): Double? {
        val t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val dot = t.lastIndexOf('.'); val comma = t.lastIndexOf(',')
        val normal = when {
            dot >= 0 && comma >= 0 -> if (dot > comma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            comma >= 0 -> if (t.count { it == ',' } == 1) t.replace(',', '.') else t.replace(",", "")
            t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        if (!normal.all { it.isDigit() || it == '.' || it == '-' }) return null
        return normal.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

/** BIP-21: "bitcoin:<address>?amount=<BTC>", the amount with no trailing zeros (as wallets read it). */
fun paymentRequest(address: String, sats: Long?): String {
    if (sats == null || sats <= 0) return address
    val btc = (sats / 100_000_000L).toString() +
        (sats % 100_000_000L).toString().padStart(8, '0').trimEnd('0').let { if (it.isEmpty()) "" else ".$it" }
    return "bitcoin:$address?amount=$btc"
}
