package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.chain.isOnionHost
import com.kilombino.kilowallet.core.tor.Tor
import com.kilombino.kilowallet.core.wallet.AddressRow
import com.kilombino.kilowallet.core.wallet.formatSats

/**
 * "Tell me when the balance changes" with the app closed (Android's WatchService + Notifier, as far
 * as iOS lets an app): after each scan the app keeps the wallet's addresses (used ones and the
 * unused window) with what each holds; iOS's background refresh (BGAppRefreshTask, when iOS decides,
 * about 30 s) asks the server for their coins and says, with a local notification, what changed:
 * a payment in the mempool, one confirmed, or one sent. Nothing secret is kept: scripthashes and
 * amounts, as the scan already shows.
 *
 * Not when the server is reached through Tor (an onion, or "Connect to the server through Tor"):
 * Tor doesn't come up in a background refresh's half minute, and nothing goes without it.
 */
object BalanceWatch {
    const val KEY_ON = "watch.notify"
    private const val KEY_LIST = "watch.list."

    /** One address: its scripthash, confirmed and unconfirmed sats. */
    data class Entry(val s: String, val c: Long, val u: Long)

    fun enabled(p: Prefs) = p.get(KEY_ON) == "1"

    /** Kept after a scan of the [kind] wallet ("HOT" or "WATCH"). */
    fun remember(p: Prefs, kind: String, rows: List<AddressRow>) {
        if (!enabled(p)) return
        p.put(KEY_LIST + kind, encode(rows.map { Entry(it.scriptHash, it.confirmed, it.unconfirmed) }))
    }

    fun forget(p: Prefs, kind: String) = p.put(KEY_LIST + kind, null)

    fun encode(list: List<Entry>): String = list.joinToString(";") { "${it.s},${it.c},${it.u}" }
    fun decode(text: String?): List<Entry> = text?.split(';')?.mapNotNull { e ->
        val f = e.split(','); if (f.size != 3) null else runCatching { Entry(f[0], f[1].toLong(), f[2].toLong()) }.getOrNull()
    } ?: emptyList()

    /**
     * What changed between [old] and [now] (scripthash → confirmed, unconfirmed), in the words of
     * Android's notifications. Empty when nothing did.
     */
    fun changes(old: List<Entry>, now: Map<String, Pair<Long, Long>>): List<String> {
        val oc = old.sumOf { it.c }; val ou = old.sumOf { it.u }
        val nc = old.sumOf { now[it.s]?.first ?: it.c }; val nu = old.sumOf { now[it.s]?.second ?: it.u }
        val before = oc + ou; val after = nc + nu
        return when {
            after > before && nu > ou -> listOf("New incoming payment in the mempool: +${formatSats(after - before)} sats")
            after > before -> listOf("Received: +${formatSats(after - before)} sats (confirmed)")
            after < before -> listOf("Sent: −${formatSats(before - after)} sats")
            nc > oc -> listOf("Confirmed: ${formatSats(nc - oc)} sats")
            else -> emptyList()
        }
    }

    /**
     * The background check itself (blocking): every kept address list, against the wallet's server
     * with its pinned certificate. Returns the messages to show; the kept lists are updated.
     */
    fun check(platform: AppPlatform): List<String> {
        val p = platform.prefs
        if (!enabled(p)) return emptyList()
        val host = p.get("server.host"); val port = p.get("server.port")?.toIntOrNull()
        val endpoint = if (host != null && port != null) NodeEndpoint(host, port, true) else NodeEndpoint.DEFAULT
        if (isOnionHost(endpoint.host) || Tor.electrum) return emptyList()
        val out = ArrayList<String>()
        val client = ElectrumClient(endpoint, p.get("pin.${endpoint.host}:${endpoint.port}"), platform.transports)
        try {
            client.connect()
            for (kind in listOf("HOT", "WATCH")) {
                val old = decode(p.get(KEY_LIST + kind)).takeIf { it.isNotEmpty() } ?: continue
                val coins = client.listUnspentMany(old.map { it.s })
                val now = old.mapIndexed { i, e ->
                    val u = coins.getOrNull(i).orEmpty()
                    e.s to (u.filter { it.height > 0 }.sumOf { it.value } to u.filter { it.height <= 0 }.sumOf { it.value })
                }.toMap()
                out += changes(old, now)
                p.put(KEY_LIST + kind, encode(old.map { Entry(it.s, now[it.s]!!.first, now[it.s]!!.second) }))
            }
        } finally { runCatching { client.close() } }
        return out
    }
}
