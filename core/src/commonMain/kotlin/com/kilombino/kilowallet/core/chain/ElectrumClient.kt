package com.kilombino.kilowallet.core.chain

import com.kilombino.kilowallet.core.crypto.TxParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the server said about one scripthash. */
data class ScriptHashBalance(val confirmed: Long, val unconfirmed: Long) {
    val total: Long get() = confirmed + unconfirmed
}

open class ElectrumException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server's TLS certificate is not the pinned one; [fingerprint] is the new one, for the user to check. */
class CertificateChangedException(val endpoint: String, val fingerprint: String) :
    ElectrumException("The certificate of $endpoint has CHANGED; nothing was sent to it. Check the new fingerprint before trusting it.")

/**
 * A minimal Electrum client (protocol 1.4 to 1.6), ported from the Android app's
 * `chain/ElectrumClient.kt` (same requests, same checks) over a platform [LineTransport] instead
 * of java.net sockets, and kotlinx.serialization instead of org.json.
 *
 * As in the app: `jsonrpc: "2.0"` is always sent; certificates are self-signed and pinned on
 * first use (trust on first use, a later change is refused until the user accepts it); a node on
 * the local network without TLS is spoken to in the clear, never one we hold a pin for.
 */
class ElectrumClient(
    val endpoint: NodeEndpoint,
    private val pinnedFingerprint: String?,
    private val transports: TransportFactory,
) {
    private var conn: LineTransport? = null
    private var nextId = 0

    /** SHA-256 fingerprint of the certificate this connection presented (null on plain TCP). */
    var serverFingerprint: String? = null
        private set

    var serverVersion: String? = null
        private set

    /** The protocol version the server agreed to: "1.4" up to "1.6". */
    var protocol: String = "1.4"
        private set

    /**
     * Agree on a protocol version: anything from 1.4 to 1.6 (Android app: `negotiate`, 1.1.1). A
     * server whose chain has headers that are not 80 bytes (BLAKE2b's are 164) sends them in
     * batches only from 1.6 on, so one that refuses blockchain.block.headers below it (Shulcrum 2.1)
     * picks 1.6; a server that knows only 1.4 keeps it. Nothing this client calls changes between
     * them but blockchain.relayfee, gone in 1.6 (see [relayFeePerKb]); headers are read in either form.
     */
    private fun negotiate() {
        val version = call("server.version", buildJsonArray {
            add(JsonPrimitive("Kilowallet-iOS"))
            add(buildJsonArray { add(JsonPrimitive("1.4")); add(JsonPrimitive("1.6")) })
        })
        val arr = version as? JsonArray
        serverVersion = arr?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: version.toString()
        protocol = arr?.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "1.4"
    }

    fun connect() {
        val c = try {
            transports.open(endpoint.host, endpoint.port, tls = true, pinnedFingerprint = pinnedFingerprint)
        } catch (e: CertificateChangedException) {
            throw e
        } catch (e: Exception) {
            if (isLocal(endpoint.host) && pinnedFingerprint == null) {
                try { transports.open(endpoint.host, endpoint.port, tls = false, pinnedFingerprint = null) }
                catch (e2: Exception) { throw ElectrumException("Could not connect to $endpoint: ${e2.message}", e2) }
            } else throw ElectrumException("Could not establish TLS with $endpoint: ${e.message}", e)
        }
        conn = c
        serverFingerprint = c.fingerprint
        negotiate()
        // Only a server on the BLAKE2b chain may answer: its headers are 164 bytes (328 hex), the
        // SHA-256 chain's are 80. Talking to the wrong chain would show and spend the wrong coins.
        val tip = tipHeaderHex()
        if (tip.length != 328) {
            close()
            throw ElectrumException("$endpoint does not follow the BLAKE2b chain (its block headers are ${tip.length / 2} bytes, not 164).")
        }
    }

    /** A private-network address (192.168.x.x, 10.x, 172.16–31.x, localhost, *.local, fd00::/8). */
    private fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.startsWith("127.")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) h.split('.').getOrNull(1)?.toIntOrNull()?.let { if (it in 16..31) return true }
        if (h.startsWith("fd") || h.startsWith("fe80:")) return true
        return false
    }

    private fun params(vararg p: Any): JsonArray = JsonArray(p.map {
        when (it) {
            is String -> JsonPrimitive(it)
            is Int -> JsonPrimitive(it)
            is Boolean -> JsonPrimitive(it)
            else -> error("unsupported param")
        }
    })

    /** One JSON-RPC round trip. Requests are newline-delimited. */
    private fun call(method: String, params: JsonArray): JsonElement {
        val c = conn ?: throw ElectrumException("client not connected")
        val id = nextId++
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            c.writeLine(req.toString())
        } catch (e: Exception) {
            throw ElectrumException("Lost the connection while sending $method: ${e.message}", e)
        }
        while (true) {
            val line = try {
                c.readLine()
            } catch (e: Exception) {
                throw ElectrumException("Lost the connection while waiting for $method: ${e.message}", e)
            } ?: throw ElectrumException("The server closed the connection during $method")

            val obj = try { Json.parseToJsonElement(line).jsonObject } catch (e: Exception) { continue }
            val err = obj["error"]
            if (err != null && err !is JsonNull) throw ElectrumException("$method: $err")
            if ((obj["id"] as? JsonPrimitive)?.intOrNull != id) continue   // a notification, not our answer
            return obj["result"] ?: JsonNull
        }
    }

    fun blockHeight(): Int {
        val r = call("blockchain.headers.subscribe", JsonArray(emptyList())) as? JsonObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r["height"]!!.jsonPrimitive.int
    }

    /** The tip header's hex, to check this server really follows the BLAKE2b chain (164-byte headers). */
    fun tipHeaderHex(): String {
        val r = call("blockchain.headers.subscribe", JsonArray(emptyList())) as? JsonObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r["hex"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    fun balance(scriptHash: String): ScriptHashBalance {
        val r = call("blockchain.scripthash.get_balance", params(scriptHash)) as? JsonObject
            ?: throw ElectrumException("unexpected response from get_balance")
        return ScriptHashBalance(
            r["confirmed"]?.jsonPrimitive?.longOrNull ?: 0, r["unconfirmed"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    /** The Electrum status of a scripthash: a hash of its whole history, or null when it has none. */
    fun status(scriptHash: String): String? =
        (call("blockchain.scripthash.subscribe", params(scriptHash)) as? JsonPrimitive)?.contentOrNull

    /**
     * [status] of many scripthashes in one go (Android app: `statuses`): every request is written
     * first and the answers read as they come (by id), so a scan waits for one round trip per
     * batch instead of one per address.
     */
    fun statuses(scriptHashes: List<String>): List<String?> {
        if (scriptHashes.size <= 1) return scriptHashes.map { status(it) }
        return batch("blockchain.scripthash.subscribe", scriptHashes).map { (it as? JsonPrimitive)?.contentOrNull }
    }

    /** [listUnspent] of many scripthashes, batched like [statuses] (Android app: `listUnspentMany`). */
    fun listUnspentMany(scriptHashes: List<String>): List<List<Utxo>> {
        if (scriptHashes.size <= 1) return scriptHashes.map { listUnspent(it) }
        return batch("blockchain.scripthash.listunspent", scriptHashes).map { utxos(it as? JsonArray) }
    }

    /** One request per scripthash, all written before any answer is read; the answers in order. */
    private fun batch(method: String, scriptHashes: List<String>): List<JsonElement?> {
        // Servers cap how many requests may wait at once: a long list goes in rounds.
        if (scriptHashes.size > BATCH) return scriptHashes.chunked(BATCH).flatMap { batch(method, it) }
        val what = method.substringAfterLast('.')
        val c = conn ?: throw ElectrumException("client not connected")
        val ids = scriptHashes.map { nextId++ }
        // All the requests in a single write (one line each), so they leave in one go.
        val lines = scriptHashes.zip(ids).joinToString("\n") { (sh, id) ->
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params(sh))
            }.toString()
        }
        try {
            c.writeLine(lines)
        } catch (e: Exception) {
            throw ElectrumException("Lost the connection while sending $what: ${e.message}", e)
        }
        val want = ids.toHashSet()
        val got = HashMap<Int, JsonElement?>()
        while (got.size < ids.size) {
            val line = try {
                c.readLine()
            } catch (e: Exception) {
                throw ElectrumException("Lost the connection while waiting for $what: ${e.message}", e)
            } ?: throw ElectrumException("The server closed the connection during $what")
            val obj = try { Json.parseToJsonElement(line).jsonObject } catch (e: Exception) { continue }
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull
            if (id == null || id !in want) continue   // a notification, not one of our answers
            val err = obj["error"]
            if (err != null && err !is JsonNull) throw ElectrumException("$what: $err")
            got[id] = obj["result"] ?: JsonNull
        }
        return ids.map { got[it] }
    }

    data class HistoryItem(val txid: String, val height: Int)

    fun history(scriptHash: String): List<HistoryItem> {
        val r = call("blockchain.scripthash.get_history", params(scriptHash)) as? JsonArray ?: return emptyList()
        return r.map {
            val o = it.jsonObject
            HistoryItem(o["tx_hash"]!!.jsonPrimitive.content, o["height"]?.jsonPrimitive?.intOrNull ?: 0)
        }
    }

    data class Utxo(val txid: String, val vout: Int, val value: Long, val height: Int)

    fun listUnspent(scriptHash: String): List<Utxo> =
        utxos(call("blockchain.scripthash.listunspent", params(scriptHash)) as? JsonArray)

    private fun utxos(r: JsonArray?): List<Utxo> = r?.map {
        val o = it.jsonObject
        Utxo(o["tx_hash"]!!.jsonPrimitive.content, o["tx_pos"]!!.jsonPrimitive.int,
            o["value"]!!.jsonPrimitive.longOrNull ?: 0, o["height"]?.jsonPrimitive?.intOrNull ?: 0)
    } ?: emptyList()

    /** Estimated fee to confirm within [blocks], in BTC/kB; -1 when the server has no estimate. */
    fun estimateFeePerKb(blocks: Int): Double =
        (call("blockchain.estimatefee", params(blocks)) as? JsonPrimitive)?.doubleOrNull ?: -1.0

    /** The server's minimum relay fee, in BTC/kB. */
    fun relayFeePerKb(): Double {
        if (protocolAtLeast("1.6")) {
            // blockchain.relayfee is gone in 1.6; mempool.get_info carries the same number.
            val info = call("mempool.get_info", JsonArray(emptyList())) as? JsonObject
            return info?.get("minrelaytxfee")?.jsonPrimitive?.doubleOrNull?.takeIf { !it.isNaN() } ?: 0.0
        }
        return (call("blockchain.relayfee", JsonArray(emptyList())) as? JsonPrimitive)?.doubleOrNull ?: 0.0
    }

    internal fun protocolAtLeast(v: String): Boolean = protocolAtLeast(protocol, v)

    /** The raw (hex) transaction for [txid]; it must hash to [txid] or it is refused. */
    fun transaction(txid: String): String {
        val raw = (call("blockchain.transaction.get", params(txid, false)) as? JsonPrimitive)?.contentOrNull
            ?: throw ElectrumException("unknown transaction $txid")
        val got = runCatching { TxParse.txid(TxParse.parse(raw)) }.getOrNull()
        if (!txid.equals(got, ignoreCase = true)) throw ElectrumException("The server returned a transaction that is not $txid")
        return raw
    }

    /** Electrum's merkle branch for [txid] in block [height]: (branch hashes, position). */
    fun merkle(txid: String, height: Int): Pair<List<String>, Int> {
        val o = call("blockchain.transaction.get_merkle", params(txid, height)) as? JsonObject
            ?: throw ElectrumException("no merkle proof for $txid")
        return o["merkle"]!!.jsonArray.map { it.jsonPrimitive.content } to o["pos"]!!.jsonPrimitive.int
    }

    fun blockHeader(height: Int): String =
        (call("blockchain.block.header", params(height)) as? JsonPrimitive)?.contentOrNull
            ?: throw ElectrumException("no header for block $height")

    /** Broadcast a raw (hex) transaction. Returns the txid, or throws with the server's reason. */
    fun broadcast(rawTxHex: String): String {
        val txid = (call("blockchain.transaction.broadcast", params(rawTxHex)) as? JsonPrimitive)?.contentOrNull
        if (txid == null || txid.length != 64) throw ElectrumException("The server rejected the transaction: $txid")
        return txid
    }

    fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    companion object {
        /** Most requests a batch leaves waiting at once (Android app: chunks of 50). */
        const val BATCH = 50
    }
}

/** Whether protocol version [have] ("1.4", "1.6", "1.4.2") is at least [want]. */
internal fun protocolAtLeast(have: String, want: String): Boolean {
    val a = have.split('.').map { it.toIntOrNull() ?: 0 }
    val b = want.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}
