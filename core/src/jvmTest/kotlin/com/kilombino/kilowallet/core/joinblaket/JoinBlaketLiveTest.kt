package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.JvmTransport
import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.wallet.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Mainnet, read-only (`JB_MAINNET_ORDERBOOK=1`), as Android's `JoinBlaketLiveTest`: the JoinBlaket
 * directory through Tor (`JB_SOCKS`, default 127.0.0.1:9050), network `xbt`, nick auth required.
 * It sends exactly what an orderbook watcher sends (handshake, nick proof, GETPEERLIST,
 * `!orderbook`) and values the bonds through the app's default Electrum server, every height
 * proven by SPV as the app does. Only counts are reported: never nicks or bond outpoints.
 * (Android's regtest-lab variant needs node RPC, which the iOS wallet does not have.)
 */
class JoinBlaketLiveTest {
    private fun env(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() }

    private class Socks(val host: String, val port: Int) : DirectoryTransport {
        override fun open(host: String, port: Int): LineTransport {
            val s = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(this.host, this.port)))
            s.connect(InetSocketAddress.createUnresolved(host, port), 120_000)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()
            return object : LineTransport {
                override val fingerprint: String? = null
                override fun writeLine(line: String) = synchronized(this) { out.write((line + "\n").toByteArray()); out.flush() }
                override fun readLine(): String? = try { reader.readLine() } catch (e: java.io.IOException) { null }
                override fun close() { runCatching { s.close() } }
            }
        }
    }

    @Test fun mainnetOrderbookReadOnly() {
        assumeTrue(env("JB_MAINNET_ORDERBOOK") == "1")
        val (sh, sp) = (env("JB_SOCKS") ?: "127.0.0.1:9050").split(":")
        fun client() = DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, NickIdentity.random(),
            JoinBlaketDirectory.NETWORK.wire, JoinBlaketDirectory.ID, transport = Socks(sh, sp.toInt()), viaTor = true)
        runBlocking {
            var tries = 0
            var c = client()
            while (true) {
                try { c.connect(); break } catch (e: DirectoryException) {
                    println("[mainnet] connect attempt ${++tries} failed: ${e.message}")
                    if (tries >= 3) throw e
                    c = client()
                }
            }
            val ob = try { Orderbook.fetch(c) } finally { c.close() }
            val valued = withContext(Dispatchers.IO) {
                val e = ElectrumClient(NodeEndpoint(Chain.DEFAULT_HOST, Chain.DEFAULT_PORT), null, JvmTransport)
                e.connect()
                try {
                    BondValues.value(ob.offers, ElectrumChainLookup(e) { txid, h -> ElectrumChainLookup.spvProof(e, txid, h, Scanner.SPV_FLOOR_BITS) })
                } finally { e.close() }
            }
            val amount = valued.firstOrNull()?.offer?.let { maxOf(it.minsize, minOf(30_000_000L, it.maxsize)) } ?: 30_000_000L
            val pick = MakerSelection.choose(valued, amount, n = 9)
            println("[mainnet] nick authenticated: ${c.authenticated}, features: ${c.directoryFeatures.sorted()}, peers: ${c.peers.size}")
            println("[mainnet] offer messages: ${ob.offerMessages}, bonded: ${ob.offers.size}, verified on chain: ${valued.count { it.value > 0 }}, " +
                "problems: ${valued.mapNotNull { it.problem }.groupingBy { it.substringBefore(":") }.eachCount()}")
            println("[mainnet] selection of $amount sats: " + when (pick) {
                is MakerSelection.Result.Chosen -> "${pick.offers.size} makers, total fee ${pick.totalFee} sats"
                is MakerSelection.Result.Failed -> pick.reason
            })
            assertTrue(c.authenticated)
        }
    }
}
