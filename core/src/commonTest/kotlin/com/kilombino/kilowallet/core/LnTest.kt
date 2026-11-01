package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.ln.LnSeed
import com.kilombino.kilowallet.core.ln.Lnd
import com.kilombino.kilowallet.core.ln.LndCallback
import com.kilombino.kilowallet.core.ln.LndEngine
import com.kilombino.kilowallet.core.ln.LndHost
import com.kilombino.kilowallet.core.ln.Pb
import com.kilombino.kilowallet.core.ln.PbMsg
import com.kilombino.kilowallet.core.ln.TxSize
import com.kilombino.kilowallet.core.ln.cpfpChildRate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Lightning port against the Android app's own ln/ code (LnVectors, from tools/android-vectors). */
class LnTest {
    private fun rows(tsv: String) = tsv.trim().lines().map { it.split('\t') }

    @Test fun nodeKeysAreTheAndroidOnes() {
        for ((seed, xprv, pub) in rows(LnVectors.SEEDS)) {
            assertEquals(xprv, LnSeed.masterXprv(Hashes.hexToBytes(seed)))
            assertEquals(pub, LnSeed.nodePubkey(Hashes.hexToBytes(seed)))
        }
    }

    @Test fun requestBytesAreTheAndroidOnes() {
        val point = "a1".repeat(32) + ":1"
        val pk = "02" + "5b".repeat(32)
        val ours = mapOf(
            "initWallet" to Pb().bytes(1, "00ff".repeat(16).encodeToByteArray()).string(7, "xprv-test").int(8, 1788220800L).int(4, 2500)
                .msg(5, Pb().msg(2, Pb().bytes(2, byteArrayOf(1, 2, 3)))).build(),
            "newAddress" to Pb().int(1, 4).build(),
            "listChannels" to Pb().bool(6, true).build(),
            "openChannelSync" to Pb().string(3, pk).int(4, 250_000).int(1, 2).bool(8, true).bool(26, false)
                .msg(28, Pb().string(2, "c3".repeat(32)).int(3, 0)).build(),
            "sendPayment" to Pb().string(5, "lnbc1testinvoice").int(6, 60).int(7, 25).int(2, 1234).int(19, 987654321L).build(),
            "payOffer" to Pb().string(1, "lno1testoffer").int(6, 60).int(7, 25_000).int(3, 5_000_000).build(),
            "closeChannel" to Pb().msg(1, Pb().string(2, point.substringBefore(':')).int(3, point.substringAfter(':').toInt())).bool(2, false)
                .int(6, 3).string(5, "bc1qtest").build(),
            "addInvoice" to Pb().string(1, "memo é").int(5, 21_000).int(11, 3600).bool(15, true).build(),
            "connectPeer" to Pb().msg(1, Pb().string(1, pk).string(2, "ln.kilombino.com:50002")).int(3, 30).build(),
            "fundPsbt" to Pb().msg(2, Pb().msg(1, Pb().string(2, "d4".repeat(32)).int(3, 2)).mapEntry(2, "bc1qfunding", 300_000)).int(4, 3).int(6, 1).int(8, 1).build(),
            "negative" to Pb().int(2, -1).int(1, 0).build(),
            "bigint" to Pb().int(1, Long.MAX_VALUE).int(2, 1L shl 40).build(),
        )
        val theirs = rows(LnVectors.REQUESTS).associate { it[0] to it[1] }
        assertEquals(theirs.keys, ours.keys)
        for ((k, v) in ours) assertEquals(theirs[k], v.toHex(), k)
    }

    @Test fun readerReadsLikeAndroid() {
        val r = rows(LnVectors.READ).single()
        val m = PbMsg.parse(Hashes.hexToBytes(r[0]))
        assertEquals(r.drop(1), listOf(m.string(1), m.string(2), m.int(6).toString(), m.bool(9).toString(), m.int(5).toString(),
            m.msgs(11).size.toString(), m.msgs(11)[0].string(3), m.msgs(11)[1].long(5).toString(), m.long(13).toString()))
    }

    @Test fun txSizesAndCpfpLikeAndroid() {
        for ((tx, vsize, outs) in rows(LnVectors.TX)) assertEquals(TxSize(vsize.toLong(), outs.toLong()), TxSize.of(tx))
        for (r in rows(LnVectors.CPFP)) {
            val (t, pv, pf, cv, rate) = r.map { it.toLong() }
            assertEquals(rate, cpfpChildRate(t, pv, pf, cv))
        }
    }

    /** The engine LND would be: records each call and answers with [answers]. */
    private class FakeEngine(val answers: Map<String, ByteArray> = emptyMap()) : LndEngine {
        val calls = ArrayList<Pair<String, String>>()
        override fun start(args: String, cb: LndCallback) { calls += "start" to args; cb.onResponse(null) }
        override fun call(method: String, request: ByteArray, cb: LndCallback) {
            calls += method to request.toHex()
            answers[method]?.let { cb.onResponse(it) } ?: cb.onError("no answer for $method")
        }
        override fun stream(method: String, request: ByteArray, cb: LndCallback) { calls += method to request.toHex(); cb.onError("EOF") }
    }

    private class MemHost : LndHost {
        val files = HashMap<String, ByteArray>()
        override val dir = "/lnd"
        override fun writeText(path: String, text: String) { files[path] = text.encodeToByteArray() }
        override fun readBytes(path: String) = files[path]
        override fun writeBytes(path: String, bytes: ByteArray) { files[path] = bytes }
        override fun exists(path: String) = path in files
        override fun delete(path: String) { files.keys.removeAll { it == path || it.startsWith("$path/") } }
        override fun tail(path: String, maxBytes: Int) = files[path]?.let { it.copyOfRange(maxOf(0, it.size - maxBytes), it.size).decodeToString() }
        override fun resolve(host: String) = emptyList<String>()
        override fun http(method: String, url: String, body: String?) = 404 to "{}"
        override fun nowSeconds() = 1_793_000_000L
    }

    @Test fun lndSendsTheAndroidRequests() = runBlocking {
        val e = FakeEngine(mapOf(
            "openChannelSync" to Pb().string(2, "ab".repeat(32)).build(),
            "getInfo" to Pb().string(1, "02aa").string(2, "Kilowallet-1a2b").int(6, 979_937).bool(9, true).int(5, 1).build(),
            "connectPeer" to ByteArray(0),
        ))
        Lnd.engine = e; Lnd.host = MemHost()
        val pk = "02" + "5b".repeat(32)
        assertEquals("ab".repeat(32), Lnd.openChannel(pk, 250_000, 2, coins = listOf("c3".repeat(32) + ":0")))
        // Android's openChannel with coins and an amount: fund_max off, the coin as outpoint.
        assertEquals(rows(LnVectors.REQUESTS).first { it[0] == "openChannelSync" }[1], e.calls.last().second)
        val info = Lnd.info()
        assertEquals(979_937, info.height); assertEquals(true, info.syncedChain); assertEquals("Kilowallet-1a2b", info.alias)
        Lnd.connect(pk, "ln.kilombino.com:50002")
        assertEquals(rows(LnVectors.REQUESTS).first { it[0] == "connectPeer" }[1], e.calls.last().second)
    }

    @Test fun configIsAndroidsWithoutTor() {
        Lnd.engine = FakeEngine(); val h = MemHost(); Lnd.host = h
        Lnd.writeConfig(listOf("179.27.118.130:8343"), null, "Kilowallet-test")
        val conf = h.files.getValue("/lnd/lnd.conf").decodeToString()
        for (line in listOf("bitcoin.mainnet=true", "bitcoin.node=neutrino", "neutrino.connect=179.27.118.130:8343", "nolisten=true",
            "alias=Kilowallet-test", "protocol.splice=true", "wtclient.active=true", "routing.blinding.min-num-real-hops=0"))
            assertEquals(true, line in conf.lines(), line)
        assertEquals(false, "tor.active=true" in conf)
    }

    /**
     * The node LND made in the iOS simulator from the "abandon … about" words (CI run 41268837589,
     * KW_DEMO=ln-live) has the identity key LnSeed works out from the same words: the key LND gets
     * (masterXprv) and the one the app expects (nodePubkey) agree.
     */
    @Test fun theSimulatorNodeHasTheWordsKey() {
        val seed = com.kilombino.kilowallet.core.crypto.Bip39.toSeed(List(11) { "abandon" } + "about", "")
        assertEquals(true, LnSeed.nodePubkey(seed).startsWith("02faeca8e7361ff87b57"), LnSeed.nodePubkey(seed))
    }
}
