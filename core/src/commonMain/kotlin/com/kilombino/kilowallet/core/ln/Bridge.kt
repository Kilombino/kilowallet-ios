package com.kilombino.kilowallet.core.ln

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.ceil

/**
 * Pays a Lightning invoice of the SHA-256 chain with BTC, through Kilombino's bridge
 * (bridge.kilombino.com, the same service as its web page and with the same limits), ported from
 * the Android app's `ln/Bridge.kt` (1.4.4).
 *
 * Nothing is entrusted to the bridge: it hands back a hold invoice on this chain with the same
 * payment hash as the SHA-256 invoice, so it can only collect by paying that invoice, and the
 * preimage this node gets back is the proof. Before paying, the hold invoice is checked on this
 * phone's own node as the bridge's payer API asks of a payer ([mismatch]).
 *
 * The way there: the onion when the app's Tor is already running, else https (Android 1.1.3-beta3:
 * waiting for Tor made paying take a minute). [http] is the platform's request, given a SOCKS port
 * or null.
 */
object Bridge {
    const val CLEARNET = "https://bridge.kilombino.com"
    const val ONION = "http://li7nwmrffmt6iut2ygolrs6mgrzwdxe26mtatzpgxvff3fri3rnq4iid.onion"

    /** The bridge's node on this chain: every hold invoice must pay exactly this one. */
    const val NODE = Kilombino.NODE

    /** How far above the price shown the quote may come before asking again. */
    const val TOLERANCE = 0.02

    /** The longest timelock a bridge hold invoice may ask for: 2016 blocks, about two weeks. */
    const val MAX_HOLD_CLTV = 2016L

    /** (method, url, body, socks port or null) → (status, body); throws when nothing answered. */
    var http: (String, String, String?, Int?) -> Pair<Int, String> = { _, _, _, _ -> error("no network") }
    /** The app's Tor SOCKS port when Tor is already running, else null. */
    var torPort: () -> Int? = { null }
    var nowMs: () -> Long = { 0L }
    var sleepMs: suspend (Long) -> Unit = {}

    class Refused(val code: String, message: String) : Exception(message)

    /** What paying [sat] sats SHA-256 costs now; nothing is reserved by asking. */
    class Estimate(val sat: Long, val paySat: Long, val rate: Double, val spread: Double, val waitSeconds: Int) {
        /** The most the user agrees to pay: the estimate plus [TOLERANCE]. */
        val maxPaySat: Long get() = ceil(paySat * (1 + TOLERANCE)).toLong()
    }

    class Quote(val holdInvoice: String, val hash: String, val sat: Long, val paySat: Long, val expiresAt: Long)

    suspend fun check(invoice: String): Estimate {
        val o = call("/api/check", buildJsonObject { put("invoice", invoice) })
        return Estimate(o.long("sat"), o.long("estimate_sat"), o.double("rate"), o.double("spread"), o.int("wait_s"))
    }

    /**
     * The final price: a hold invoice the bridge reserves for a minute. Each one carries a small
     * proof of work for [hash] (the SHA-256 invoice's payment hash): it keeps one person from
     * taking every quote the bridge can hold.
     */
    suspend fun quote(invoice: String, hash: String): Quote {
        val pow = runCatching { work(hash.lowercase()) }.getOrNull()
        val body = buildJsonObject { put("invoice", invoice); pow?.let { put("pow", it) } }
        val o = call("/api/quote", body)
        return Quote(o.str("hold_invoice"), o.str("hash").lowercase(), o.long("sat"), o.long("pay_sat"), o.long("expires_at"))
    }

    /**
     * The bridge's challenge, and a nonce for it: SHA-256("<challenge>:<hash>:<nonce>") starting with
     * as many zero bits as it asks (18: a fraction of a second). Null when the bridge hands out none.
     */
    internal suspend fun work(hash: String): JsonObject? {
        val c = call("/api/pow", null)
        val challenge = c.str("challenge")
        val bits = c.int("bits").coerceIn(1, 32)
        return solve(challenge, hash, bits, nowMs() + 60_000)?.let { n -> buildJsonObject { put("challenge", challenge); put("nonce", n) } }
    }

    /** The first nonce (base 36, from 0) that gives [bits] leading zero bits, or null by [deadline]. */
    fun solve(challenge: String, hash: String, bits: Int, deadline: Long): String? {
        val prefix = "$challenge:$hash:".encodeToByteArray()
        var n = 0L
        while (nowMs() < deadline) {
            repeat(10_000) {
                val nonce = n++.toString(36)
                val d = Hashes.sha256(prefix + nonce.encodeToByteArray())
                val head = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
                    ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
                if (head ushr (32 - bits) == 0L) return nonce
            }
        }
        return null
    }

    /** The bridge's state for a swap ("settled", "failed", …), or null if it cannot say. */
    suspend fun state(hash: String): String? =
        runCatching { call("/api/swap/$hash", null)["state"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } }.getOrNull()

    /**
     * Why the [hold] invoice must not be paid, or null when it is the one the user agreed to:
     * the payer API's checks, made on this phone's own node.
     */
    fun mismatch(hold: Lnd.PayReq, holdMsat: Long, sha: Lnd.PayReq, q: Quote, maxPaySat: Long, now: Long): String? = when {
        q.hash != sha.hash.lowercase() || hold.hash.lowercase() != sha.hash.lowercase() ->
            "the bridge's invoice is not for this payment (another payment hash)"
        hold.destination != NODE -> "the bridge's invoice pays another node"
        !hold.blake2b -> "the bridge's invoice is not a BTC invoice"
        holdMsat <= 0 -> "the bridge's invoice has no amount"
        hold.cltv > MAX_HOLD_CLTV -> "the bridge's invoice could hold your payment too long (${hold.cltv} blocks)"
        ceilSat(holdMsat) > maxPaySat -> "the price moved: it is now ${ceilSat(holdMsat)} sats, more than the $maxPaySat you accepted"
        q.expiresAt - now < 15 || hold.timestamp + hold.expiry - now < 15 -> "the bridge's price expired before it could be paid"
        else -> null
    }

    fun ceilSat(msat: Long) = (msat + 999) / 1000

    /** True when [preimageHex] is the secret of [hashHex]: the proof the invoice was paid. */
    fun proves(preimageHex: String, hashHex: String): Boolean = runCatching {
        Hashes.sha256(Hashes.hexToBytes(preimageHex)).toHex() == hashHex.lowercase()
    }.getOrDefault(false)

    /** A refusal in words for the person paying (Android's texts). */
    fun text(code: String, o: JsonObject? = null): String = when (code) {
        "checksum" -> "That invoice is damaged (checksum): copy it again, ideally from the wallet that made it."
        "invalid_invoice" -> "The bridge could not read that invoice."
        "wrong_network" -> "That invoice is not for mainnet."
        "is_xbt" -> "That is a BTC invoice: pay it directly."
        "no_amount" -> "The invoice has no amount: the bridge only pays invoices with one."
        "expires_soon" -> "The invoice expires too soon to be paid safely: ask for a new one."
        "too_small" -> "Too small for the bridge: at least ${o?.optLong("min_sat") ?: 1000} sats SHA-256."
        "too_large" -> "Too large for the bridge: at most ${o?.optLong("max_sat") ?: 100000} sats SHA-256."
        "daily_cap" -> "The bridge has reached its limit for today. Try again tomorrow."
        "no_liquidity" -> "The bridge has not enough liquidity for that amount right now: try a smaller one or later."
        "price_unavailable" -> "The bridge has no reliable price right now (the market is moving or can't be read): try again in a few minutes."
        "chain_unmeasured" -> "The bridge is warming up: try again in a few minutes."
        "self_payment", "route_budget", "no_direction" -> "The bridge can't pay that invoice."
        "in_progress" -> "That invoice is already being paid through the bridge: wait a moment."
        "already_paid" -> "That invoice has already been paid through the bridge."
        "needs_operator" -> "That invoice needs Kilombino to look at it."
        "limit" -> "The bridge is busy: wait a minute and try again."
        "pow_expired", "pow_used", "pow_invalid", "pow_missing" -> "The bridge asked for a fresh check: try again."
        "wait" -> "Wait ${o?.optLong("wait_s") ?: 60} s before asking the bridge for a new price for this invoice."
        "bad_hold" -> "The bridge gave an invoice that did not check out: nothing was paid. Try again."
        "network" -> "Could not reach the bridge. Check the connection and try again."
        else -> "The bridge is not available right now: try again later."
    }

    /** What the bridge says about a payment that did not end well, for the person paying. */
    fun afterFailure(state: String?, payError: String): String = when (state) {
        "aborted" -> "Not paid: the price moved against the bridge before it paid, so it gave your BTC back. Nothing was charged."
        "failed" -> "Not paid: the bridge could not pay the SHA-256 invoice (no route there, or the invoice was refused). Your BTC came back."
        "expired" -> "Not paid: the price expired before your payment reached the bridge. Nothing was charged."
        "funded", "paying", "paid" -> "Your payment reached the bridge and is still being settled: it completes, or comes back, by itself. Don't pay again; see Payments."
        "settled" -> "The bridge paid the invoice. This phone lost track of the end of it: see Payments."
        "quoted", "offered" -> "$payError Nothing was charged."
        else -> "$payError If it is still on its way it completes or comes back by itself: check Payments before paying again."
    }

    // ------------------------------------------------------------------------------- transport

    private const val PATIENCE_MS = 8_000L

    /** JSON from the bridge's web API; a failed request is tried again for a few seconds (never a quote). */
    private suspend fun call(path: String, body: JsonObject?): JsonObject {
        var raw: String? = null
        val until = nowMs() + if (path == "/api/quote") 0 else PATIENCE_MS
        while (true) {
            raw = runCatching { request(path, body?.toString()) }.getOrNull()
            if (raw != null || nowMs() >= until) break
            sleepMs(2_000)
        }
        if (raw == null) throw Refused("network", text("network"))
        val o = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrElse { throw Refused("unavailable", text("unavailable")) }
        o["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { throw Refused(it, text(it, o)) }
        return o
    }

    /** Over the onion when Tor already runs (and answers), else over https at once. */
    private fun request(path: String, body: String?): String {
        val method = if (body != null) "POST" else "GET"
        torPort()?.let { p -> runCatching { http(method, ONION + path, body, p) }.getOrNull()?.let { return it.second } }
        return http(method, CLEARNET + path, body, null).second
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: throw Refused("unavailable", text("unavailable"))
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.double(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun JsonObject.optLong(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}
