package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.crypto.*
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.wallet.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SecurityRegressionTest {
    private val fp = "a".repeat(64)
    private val header = HeaderVectors.HEADERS.first().third
    // Synthetic transaction only; no real UTXO or private key is used.
    private val raw = "0200000001" + "00".repeat(32) + "ffffffff00ffffffff01e803000000000000015100000000"
    private class Fake(val fp: String, val respond: (JsonObject) -> String) : LineTransport {
        override val fingerprint = fp
        var request: JsonObject? = null
        var closed = false
        var writes = 0
        override fun writeLine(line: String) { writes++; request = Json.parseToJsonElement(line).jsonObject }
        override fun readLine() = respond(request!!)
        override fun close() { closed = true }
    }
    private fun fake(tip: String = header, receipt: String = TxParse.txid(TxParse.parse(raw))) = Fake(fp) { q ->
        val result = when (q["method"]!!.jsonPrimitive.content) {
            "server.version" -> buildJsonArray { add("regression"); add("1.4") }
            "blockchain.headers.subscribe" -> buildJsonObject { put("hex", tip); put("height", 976086) }
            "blockchain.transaction.broadcast" -> JsonPrimitive(receipt)
            else -> error("Unexpected request")
        }
        buildJsonObject { put("id", q["id"]!!); put("result", result) }.toString()
    }
    private fun client(f: Fake, pin: String? = fp) = ElectrumClient(NodeEndpoint.DEFAULT, pin, TransportFactory { _, _, _, _ -> f })

    @Test fun neverDowngradesTlsForAnyHostname() {
        for (host in listOf("fd-wallet.example", "10.wallet.example", "192.168.wallet.example", "172.16.wallet.example", "localhost", "192.168.1.2", "[fd00::1]")) {
            val tlsAttempts = mutableListOf<Boolean>()
            val c = ElectrumClient(NodeEndpoint(host, 50002), null, TransportFactory { _, _, tls, _ ->
                tlsAttempts += tls; throw ElectrumException("TLS failure")
            })
            assertFailsWith<ElectrumException> { c.connect() }
            assertEquals(listOf(true), tlsAttempts)
        }
    }
    @Test fun noWalletQueryBeforeExplicitPinAcceptance() {
        for (pin in listOf(null, "b".repeat(64))) {
            val f = fake()
            assertFailsWith<CertificateChangedException> { client(f, pin).connect() }
            assertEquals(0, f.writes)
            assertTrue(f.closed)
        }
    }
    @Test fun rejectsMalformedWrongChainAndInvalidPowHeaders() {
        val mutated = Hashes.hexToBytes(header).also { it[76] = (it[76].toInt() xor 1).toByte() }.toHex()
        for (tip in listOf("zz".repeat(164), "00".repeat(164), HeaderVectors.HEADERS.last().third, mutated)) {
            val f = fake(tip)
            assertFails { client(f).connect() }
            assertTrue(f.closed)
        }
        client(fake()).also { it.connect(); it.close() }
    }
    @Test fun broadcastReceiptMustMatchLocalTransactionId() {
        for (receipt in listOf("z".repeat(64), "b".repeat(64), "")) {
            val c = client(fake(receipt = receipt)); c.connect()
            assertFailsWith<ElectrumException> { c.broadcast(raw) }
            c.close()
        }
        val c = client(fake()); c.connect()
        assertEquals(TxParse.txid(TxParse.parse(raw)), c.broadcast(raw))
        c.close()
    }
    @Test fun rejectsMalformedOrUnboundedRpcStreams() {
        for (answer in listOf("not-json", "{\"id\":-1,\"result\":null}")) {
            val f = Fake(fp) { answer }
            assertFailsWith<ElectrumException> { client(f).connect() }
            assertTrue(f.closed)
        }
    }
    private val coin = SpendableUtxo("ab".repeat(32), 0, 50_000, 0, 0, 100)
    private val to = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val change = Hashes.hexToBytes("0014" + "11".repeat(20))
    @Test fun rejectsDuplicateNegativeAndOverflowingCoins() {
        for (coins in listOf(listOf(coin, coin), listOf(coin.copy(value = -1)), listOf(coin.copy(value = 0)),
            listOf(coin.copy(value = Long.MAX_VALUE), coin.copy(vout = 1)), listOf(coin.copy(vout = -1)))) {
            assertFailsWith<IllegalArgumentException> { SendPlanner.plan(to, 1000, 1.0, coins, change) }
        }
        assertFailsWith<IllegalArgumentException> { SendPlanner.plan(to, Long.MAX_VALUE, 1.0, listOf(coin), change) }
    }
    @Test fun rejectsNonfiniteFeesAndAccountsForCompactSizeBoundary() {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SendPlanner.plan(to, 1000, rate, listOf(coin), change) }
            assertFailsWith<IllegalArgumentException> { SendPlanner.maxSendable(rate, listOf(coin)) }
        }
        assertEquals(70.0, SendPlanner.estimateVbytes(253, listOf(change)) - SendPlanner.estimateVbytes(252, listOf(change)))
    }
    @Test fun parserRejectsTruncationTrailingBytesAndHostileCounts() {
        val id = TxParse.txid(TxParse.parse(raw))
        assertEquals(64, id.length)
        for (n in 0 until raw.length step 2) assertFails { TxParse.parse(raw.take(n)) }
        for (bad in listOf(raw + "00", "02000000ffffffffffffffffff", raw.replaceRange(8, 10, "fd0100"), raw.replaceRange(94, 110, "ff".repeat(8)))) {
            assertFails { TxParse.parse(bad) }
        }
    }
    @Test fun merkleProofRejectsNegativeAndUnusedPositionBits() {
        val h = BlockHeader.parse(header)
        val root = h.merkleRoot.reversedArray().toHex()
        assertTrue(BlockHeader.inBlock(root, emptyList(), 0, h))
        assertFalse(BlockHeader.inBlock(root, emptyList(), -1, h))
        assertFalse(BlockHeader.inBlock(root, emptyList(), 1, h))
        val bad = h.raw.copyOf().also { it[75] = 0xff.toByte() }
        assertFalse(BlockHeader.meetsItsTarget(BlockHeader.Parsed(bad)))
    }
    @Test fun strictPaymentRequestRejectsOtherChainsAndAmbiguity() {
        assertEquals(to to 120_000L, PaymentRequest.parse("bitcoin:$to?amount=0.0012&chain=xbt"))
        assertEquals(to to null, PaymentRequest.parse(to))
        for (q in listOf("chain=btc", "req-payjoin=yes", "amount=1&amount=2", "amount=NaN", "amount=0.000000001", "amount=١")) {
            assertFails { PaymentRequest.parse("bitcoin:$to?$q") }
        }
        assertNull(PaymentRequest.toSats("."))
        assertNull(PaymentRequest.toSats("١"))
    }
}
