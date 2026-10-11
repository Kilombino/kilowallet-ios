package com.kilombino.kilowallet.core.chain

import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.crypto.BlockHeader
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
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
    ElectrumException("Check the TLS certificate fingerprint of $endpoint before trusting it. No application data was sent.")

/**
 * A minimal Electrum protocol 1.4 client, ported from the Android app's `chain/ElectrumClient.kt`
 * (same requests, same checks) over a platform [LineTransport] instead of java.net sockets, and
 * kotlinx.serialization instead of org.json.
 *
 * As in the app: `jsonrpc: "2.0"` is always sent; certificates are self-signed and pinned on
 * explicit first-use approval; no wallet queries are sent until the fingerprint is accepted.
 * Connections always require TLS, including local nodes.
 */
class ElectrumClient(
    val endpoint: NodeEndpoint,
    private val pinnedFingerprint: String?,
    private val transports: TransportFactory,
) {
    companion object { const val MAX_LINE_BYTES = 4 * 1024 * 1024 }

    private var conn: LineTransport? = null
    private var nextId = 0

    /** SHA-256 fingerprint of the certificate this connection presented (null on plain TCP). */
    var serverFingerprint: String? = null
        private set

    var serverVersion: String? = null
        private set

    fun connect() {
        close()
        val c = try {
            transports.open(endpoint.host, endpoint.port, tls = true, pinnedFingerprint = pinnedFingerprint)
        } catch (e: CertificateChangedException) {
            throw e
        } catch (e: Exception) {
            throw ElectrumException("Could not establish TLS with $endpoint: ${e.message}", e)
        }
        conn = c
        serverFingerprint = c.fingerprint
        try {
            val fp = c.fingerprint ?: throw ElectrumException("TLS did not supply a certificate fingerprint")
            if (pinnedFingerprint == null || !pinnedFingerprint.equals(fp, ignoreCase = true)) {
                throw CertificateChangedException(endpoint.toString(), fp)
            }
            val version = call("server.version", buildJsonArray { add(JsonPrimitive("Kilowallet-iOS")); add(JsonPrimitive("1.4")) })
            serverVersion = (version as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: version.toString()
            // A format/PoW check rejects other chains and corrupt replies. It does not establish
            // the highest-work chain: confirmations still depend on the selected server.
            val tip = BlockHeader.parse(tipHeaderHex())
            require(tip.extended && BlockHeader.meetsItsTarget(tip)) { "Server returned an invalid BLAKE2b tip header" }
        } catch (e: Exception) {
            close()
            throw e
        }
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
        val started = TimeSource.Monotonic.markNow()
        repeat(32) {
            if (started.elapsedNow() > 90.seconds) throw ElectrumException("Timed out waiting for $method")
            val line = try {
                c.readLine()
            } catch (e: Exception) {
                throw ElectrumException("Lost the connection while waiting for $method: ${e.message}", e)
            } ?: throw ElectrumException("The server closed the connection during $method")

            require(line.length <= MAX_LINE_BYTES) { "Server response exceeds the size limit" }
            val obj = try { Json.parseToJsonElement(line).jsonObject } catch (e: Exception) {
                throw ElectrumException("Malformed response to $method", e)
            }
            if ((obj["id"] as? JsonPrimitive)?.intOrNull != id) return@repeat
            val err = obj["error"]
            if (err != null && err !is JsonNull) throw ElectrumException("$method: $err")
            return obj["result"] ?: JsonNull
        }
        throw ElectrumException("Too many unrelated responses to $method")
    }

    fun blockHeight(): Int {
        val r = call("blockchain.headers.subscribe", JsonArray(emptyList())) as? JsonObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r["height"]!!.jsonPrimitive.int.also { require(it in 0..10_000_000) { "Invalid block height" } }
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

    data class HistoryItem(val txid: String, val height: Int)

    fun history(scriptHash: String): List<HistoryItem> {
        val r = call("blockchain.scripthash.get_history", params(scriptHash)) as? JsonArray ?: return emptyList()
        return r.map {
            val o = it.jsonObject
            HistoryItem(o["tx_hash"]!!.jsonPrimitive.content, o["height"]?.jsonPrimitive?.intOrNull ?: 0)
        }
    }

    data class Utxo(val txid: String, val vout: Int, val value: Long, val height: Int)

    fun listUnspent(scriptHash: String): List<Utxo> {
        val r = call("blockchain.scripthash.listunspent", params(scriptHash)) as? JsonArray ?: return emptyList()
        return r.map {
            val o = it.jsonObject
            Utxo(o["tx_hash"]!!.jsonPrimitive.content, o["tx_pos"]!!.jsonPrimitive.int,
                o["value"]!!.jsonPrimitive.longOrNull ?: 0, o["height"]?.jsonPrimitive?.intOrNull ?: 0)
        }
    }

    /** Estimated fee to confirm within [blocks], in BTC/kB; -1 when the server has no estimate. */
    fun estimateFeePerKb(blocks: Int): Double =
        (call("blockchain.estimatefee", params(blocks)) as? JsonPrimitive)?.doubleOrNull ?: -1.0

    /** The server's minimum relay fee, in BTC/kB. */
    fun relayFeePerKb(): Double =
        (call("blockchain.relayfee", JsonArray(emptyList())) as? JsonPrimitive)?.doubleOrNull ?: 0.0

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
        val expected = TxParse.txid(TxParse.parse(rawTxHex))
        val txid = (call("blockchain.transaction.broadcast", params(rawTxHex)) as? JsonPrimitive)?.contentOrNull
        if (!expected.equals(txid, ignoreCase = true)) throw ElectrumException("Broadcast receipt did not match $expected. Delivery is uncertain; check this transaction before retrying.")
        return expected
    }

    fun close() {
        runCatching { conn?.close() }
        conn = null
    }
}
