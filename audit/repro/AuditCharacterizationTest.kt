package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * AUDIT ONLY: passing tests reproduce defects in upstream e20e31e, not secure behavior.
 * All networking is mocked; no seeds, real wallets, live servers or broadcasts are used.
 * Kept outside production test source sets. Copy to commonTest only for characterization.
 */
class AuditCharacterizationTest {
    private class FakeTransport : LineTransport {
        override val fingerprint: String? = null
        var request: JsonObject? = null
        override fun writeLine(line: String) { request = Json.parseToJsonElement(line).jsonObject }
        override fun readLine(): String {
            val q = request!!
            val result: JsonElement = when (q["method"]!!.jsonPrimitive.content) {
                "server.version" -> JsonArray(listOf(JsonPrimitive("audit-mock"), JsonPrimitive("1.4")))
                "blockchain.headers.subscribe" -> buildJsonObject {
                    put("height", 1_000_000)
                    put("hex", "zz".repeat(164)) // not even hexadecimal, yet connect accepts it
                }
                "blockchain.transaction.broadcast" -> JsonPrimitive("z".repeat(64))
                else -> error("Unexpected mock request")
            }
            return buildJsonObject { put("id", q["id"]!!); put("result", result) }.toString()
        }
        override fun close() {}
    }

    @Test fun auditReproducesPublicHostnamePlaintextDowngrade() {
        for (host in listOf("fd-wallet.example", "10.wallet.example", "192.168.wallet.example", "172.16.wallet.example")) {
            val attempts = mutableListOf<Boolean>()
            val factory = TransportFactory { _, _, tls, _ ->
                attempts += tls
                if (tls) throw ElectrumException("simulated TLS failure")
                FakeTransport()
            }
            val client = ElectrumClient(NodeEndpoint(host, 50002, true), null, factory)
            try { client.connect(); assertEquals(listOf(true, false), attempts, host) }
            finally { client.close() }
        }
    }

    @Test fun controlPinnedHostNeverFallsBack() {
        val attempts = mutableListOf<Boolean>()
        val factory = TransportFactory { _, _, tls, _ ->
            attempts += tls
            throw ElectrumException("simulated TLS failure")
        }
        val client = ElectrumClient(NodeEndpoint("fd-wallet.example", 50002, true), "a".repeat(64), factory)
        assertFailsWith<ElectrumException> { client.connect() }
        assertEquals(listOf(true), attempts)
    }

    @Test fun auditReproducesInvalidHeaderAcceptedAsChainIdentity() {
        val client = ElectrumClient(NodeEndpoint.DEFAULT, null, TransportFactory { _, _, _, _ -> FakeTransport() })
        try { client.connect(); assertEquals("zz".repeat(164), client.tipHeaderHex()) }
        finally { client.close() }
    }

    @Test fun auditReproducesUnrelatedNonHexBroadcastReceiptAccepted() {
        val client = ElectrumClient(NodeEndpoint.DEFAULT, null, TransportFactory { _, _, _, _ -> FakeTransport() })
        try {
            client.connect()
            assertEquals("z".repeat(64), client.broadcast("00"))
        } finally { client.close() }
    }

    private val destination = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val change = Hashes.hexToBytes("0014" + "11".repeat(20))
    private val coin = SpendableUtxo("ab".repeat(32), 0, 50_000, 0, 0, 100)

    @Test fun auditReproducesDuplicateOutpointsInSendPlan() {
        val draft = SendPlanner.plan(destination, 0, 1.0, listOf(coin, coin), change, sendAll = true)
        assertEquals(2, draft.inputs.size)
        assertEquals(1, draft.inputs.map { it.txid to it.vout }.distinct().size)
        assertEquals(100_000, draft.amount + draft.fee)
    }

    @Test fun auditReproducesAmountOverflowAndNegativeFee() {
        val draft = SendPlanner.plan(destination, Long.MAX_VALUE, 1.0, listOf(coin), change)
        assertTrue(draft.fee < 0)
        assertEquals(Long.MAX_VALUE, draft.amount)
    }
}
