package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.chain.TransportFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The client against a scripted server: protocol negotiation, the 1.6 relay fee, batched requests. */
class ElectrumClientTest {
    /** Answers each request line with [answer]; records every request and every write. */
    private class FakeServer(
        val versions: List<String>,
        val answer: (method: String, params: JsonArray) -> JsonElement,
    ) : LineTransport {
        val requests = mutableListOf<Pair<String, JsonArray>>()
        var writes = 0
        private val pending = ArrayDeque<String>()
        override val fingerprint: String? = "ab".repeat(32)

        override fun writeLine(line: String) {
            writes++
            for (one in line.split('\n').filter { it.isNotBlank() }) {
                val o = Json.parseToJsonElement(one).jsonObject
                val method = o["method"]!!.jsonPrimitive.content
                val params = o["params"]!!.jsonArray
                requests += method to params
                val result = if (method == "server.version") {
                    val asked = params[1]
                    val range = if (asked is JsonArray) asked.map { it.jsonPrimitive.content } else listOf(asked.jsonPrimitive.content)
                    // The highest the server knows within what was asked.
                    val pick = versions.filter { v -> range.size == 1 && v == range[0] || range.size == 2 && v >= range[0] && v <= range[1] }.maxOrNull()
                    JsonArray(listOf(JsonPrimitive("FakeServer 1.0"), JsonPrimitive(pick ?: "1.4")))
                } else answer(method, params)
                pending += buildJsonObject { put("jsonrpc", "2.0"); put("id", o["id"]!!.jsonPrimitive.int); put("result", result) }.toString()
            }
            // A notification first, as a real server may send: the client must skip it.
            pending.addFirst("""{"jsonrpc":"2.0","method":"blockchain.headers.subscribe","params":[]}""")
        }

        override fun readLine(): String? = pending.removeFirstOrNull()
        override fun close() {}
    }

    private val tip = buildJsonObject { put("height", 980_000); put("hex", "00".repeat(164)) }

    private fun client(server: FakeServer) =
        ElectrumClient(NodeEndpoint("example.org", 50002, true), null, TransportFactory { _, _, _, _ -> server })

    @Test
    fun agreesOn16AndReadsTheRelayFeeFromMempoolInfo() {
        val server = FakeServer(listOf("1.4", "1.5", "1.6")) { m, _ ->
            when (m) {
                "blockchain.headers.subscribe" -> tip
                "mempool.get_info" -> buildJsonObject { put("mempoolminfee", 0.00001); put("minrelaytxfee", 0.000001) }
                else -> error("unexpected $m")
            }
        }
        val c = client(server)
        c.connect()
        assertEquals("1.6", c.protocol)
        assertEquals(listOf("1.4", "1.6"), server.requests.first().second[1].jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0.000001, c.relayFeePerKb())
        assertTrue(server.requests.none { it.first == "blockchain.relayfee" })
    }

    @Test
    fun keeps14WithAnOlderServer() {
        val server = FakeServer(listOf("1.4")) { m, _ ->
            when (m) {
                "blockchain.headers.subscribe" -> tip
                "blockchain.relayfee" -> JsonPrimitive(0.00001)
                else -> error("unexpected $m")
            }
        }
        val c = client(server)
        c.connect()
        assertEquals("1.4", c.protocol)
        assertEquals(0.00001, c.relayFeePerKb())
    }

    @Test
    fun batchesStatusesAndUnspentFiftyAtATime() {
        val server = FakeServer(listOf("1.4")) { m, p ->
            val sh = p.getOrNull(0)?.jsonPrimitive?.content.orEmpty()
            when (m) {
                "blockchain.headers.subscribe" -> tip
                "blockchain.scripthash.subscribe" -> if (sh.endsWith("0")) JsonNull else JsonPrimitive("st-$sh")
                "blockchain.scripthash.listunspent" -> Json.parseToJsonElement(
                    """[{"tx_hash":"${"cd".repeat(32)}","tx_pos":1,"value":${sh.trimStart('a').ifEmpty { "0" }.toInt() + 1000},"height":7}]""")
                else -> error("unexpected $m")
            }
        }
        val c = client(server)
        c.connect()
        val hashes = (0 until 120).map { it.toString().padStart(64, 'a') }
        val before = server.writes
        val st = c.statuses(hashes)
        assertEquals(3, server.writes - before) // 50 + 50 + 20, one write (and one round trip) each
        assertEquals(hashes.map { if (it.endsWith("0")) null else "st-$it" }, st)

        val w = server.writes
        val us = c.listUnspentMany(hashes)
        assertEquals(3, server.writes - w)
        assertEquals((0 until 120).map { it + 1000L }, us.map { it.single().value })
        assertEquals(listOf(1), us.map { it.single().vout }.distinct())
    }
}
