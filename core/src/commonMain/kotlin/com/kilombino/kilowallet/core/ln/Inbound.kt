package com.kilombino.kilowallet.core.ln

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull

/** Kilombino's node and what the app relies on it for (shared by the LN screen and [Inbound]). */
object Kilombino {
    /** Kilombino's node: offered for the first channel, never required. */
    const val NODE = "0222d36d7b634ead665c982145f6e4507ad65c4f369902ab3ca11e379d2139dc60"
    /**
     * Its clearnet address, a name of ours kept on AirVPN's current exit IPs. AirDNS's own name is
     * the fallback: on 25 Oct 2026 it kept the old IPs for over an hour after AirVPN changed them.
     */
    val HOSTS = listOf("ln.kilombino.com:50002", "nodoblake2b.airdns.org:50002")
    const val ONION = "eu2pntt4zzsvm2fkcn5uaqxac2mj5t2fk5ecwyhhejp3deu7env2h2ad.onion:9735"
    /** Kilombino's watchtower (onion: reached when Tor is on). */
    const val TOWER = "033f366c8e43b040dbc27648d68e123650ec1f23176de092f39b9e0a7fb4f2a95d"
    const val TOWER_HOST = "b44c2u3dar4fdhb4m4vsp3v6kzohh2cqrggk2io74v4olr4nn5nytyad.onion:9911"
    /** The longest a hold invoice of ours (the LSP's fee, the bridge) may keep a payment held: two weeks of blocks. */
    const val MAX_HOLD_CLTV = 2016L
}

/**
 * Kilombino's inbound liquidity service (lsp.kilombino.com): it opens a private channel from
 * Kilombino's node to this one, so the wallet can receive. The request is signed with the
 * node key; the fee is paid with a hold invoice that Kilombino's node only settles once the
 * channel's funding transaction is out (otherwise it is cancelled and the payment comes back).
 * Ported from the Android app's `ln/Inbound.kt`.
 */
object Inbound {
    const val API = "https://lsp.kilombino.com/v1"

    data class Info(val pubkey: String, val minSats: Long, val maxSats: Long, val feePerMillion: Long,
                    val availableSats: Long, val minDays: Int)
    data class Quote(val id: String, val invoice: String, val fee: Long, val sats: Long, val expires: Long)
    data class Order(val status: String, val fundingTxid: String?, val reason: String?)

    private fun http(method: String, path: String, body: JsonObject? = null): JsonObject {
        val (code, text) = Lnd.host.http(method, API + path, body?.toString())
        val o = runCatching { Json.parseToJsonElement(text.ifBlank { "{}" }) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (code !in 200..299) throw IllegalStateException(o["error"]?.jsonPrimitive?.contentOrNull ?: "HTTP $code")
        return o
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.long

    fun info(): Info = http("GET", "/info").let {
        Info(it.str("pubkey"), it.num("minSats"), it.num("maxSats"), it.num("feePerMillion"),
            it.num("availableSats"), it.getValue("minDays").jsonPrimitive.int)
    }

    /** Asks for a channel of [sats]; the node signs the request. Connect to Kilombino's node first. */
    suspend fun quote(ourPubkey: String, sats: Long): Quote {
        val ts = Lnd.host.nowSeconds()
        val sig = Lnd.signMessage("kilowallet-lsp:v1:$ourPubkey:$sats:$ts")
        val o = http("POST", "/quote", buildJsonObject {
            put("pubkey", ourPubkey); put("sats", sats); put("ts", ts); put("signature", sig)
        })
        return Quote(o.str("id"), o.str("invoice"), o.num("fee"), o.num("sats"), o.num("expires"))
    }

    /**
     * Why the quote's invoice must not be paid, or null when it is the one the user agreed to:
     * decoded on this phone's own node, it must pay Kilombino's node on this chain, ask for
     * exactly the fee shown (the service's rate for the size asked), for the size asked, and
     * still have time left.
     */
    suspend fun mismatch(q: Quote, askedSats: Long, feePerMillion: Long): String? {
        val r = Lnd.decode(q.invoice)
        val fee = feePerMillion * (askedSats / 1_000_000)
        val now = Lnd.host.nowSeconds()
        return when {
            r.destination != Kilombino.NODE -> "the invoice pays another node"
            !r.blake2b -> "the invoice is not a BTC invoice"
            q.sats != askedSats -> "the offer is for another channel size"
            q.fee != fee || r.msat != fee * 1000 -> "the invoice asks for another fee (${(r.msat + 999) / 1000} sats, not $fee)"
            r.timestamp + r.expiry - now < 30 -> "the offer has expired: ask again"
            // The fee is held until the channel is out: never for longer than two weeks of blocks.
            r.cltv > Kilombino.MAX_HOLD_CLTV -> "the invoice could hold the fee too long (${r.cltv} blocks)"
            else -> null
        }
    }

    fun order(id: String): Order = http("GET", "/order/$id").let {
        Order(it.str("status"), it["fundingTxid"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.length == 64 },
            it["reason"]?.jsonPrimitive?.contentOrNull?.ifBlank { null })
    }
}
