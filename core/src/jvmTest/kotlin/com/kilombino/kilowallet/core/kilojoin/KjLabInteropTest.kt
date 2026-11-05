package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bech32
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.kilojoin.PoolSession.Phase
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kilojoin v6 against the real thing, in the regtest lab (/mnt/datos2/kj-lab): this port's [PoolSession] (the
 * code the iPhone runs) with Kilojoin 0.10.2 (= Kilowallet 1.5.2's shared code) on the lab's strfry relay and its
 * BLAKE2b regtest node, which checks every signature. Both ways: a Kilojoin creator with two of these members, and
 * one of these as creator with Kilojoin members m1 and m2. Runs only with KJ_LAB_RPC=user:pass@127.0.0.1:19443
 * (and the lab started); otherwise it does nothing.
 */
class KjLabInteropTest {
    private val rpcSpec = System.getenv("KJ_LAB_RPC")
    private val lab = System.getenv("KJ_LAB_DIR") ?: "/mnt/datos2/kj-lab"

    private fun rpc(method: String, vararg params: Any?, wallet: String? = "faucet"): kotlinx.serialization.json.JsonElement? {
        val (auth, host) = rpcSpec!!.split('@', limit = 2)
        val c = URI("http://$host/" + (wallet?.let { "wallet/$it" } ?: "")).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray()))
        c.outputStream.write(J.obj("jsonrpc" to "1.0", "id" to "kw", "method" to method, "params" to params.toList()).toString().toByteArray())
        val body = (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
        val o = Json.parseToJsonElement(body).jsonObject
        val err = o["error"]
        if (err != null && err !is kotlinx.serialization.json.JsonNull) throw IllegalStateException(err.jsonObject["message"]?.jsonPrimitive?.content)
        return o["result"]
    }

    private fun mine(n: Int = 1) { val a = rpc("getnewaddress")!!.jsonPrimitive.content; rpc("generatetoaddress", n, a) }

    private fun kj(vararg args: String): String {
        val p = ProcessBuilder(listOf("$lab/kj-lab") + args).redirectErrorStream(true)
        p.environment()["XDG_RUNTIME_DIR"] = "/run/user/1000"
        val pr = p.start()
        val out = pr.inputStream.bufferedReader().readText()
        pr.waitFor()
        return out
    }

    private val env = object : PoolSession.Env {
        override fun coinUnspent(coin: PoolTx.Coin): Boolean {
            val o = rpc("gettxout", coin.txid, coin.vout, true, wallet = null) as? JsonObject ?: return false
            return Math.round(o["value"]!!.jsonPrimitive.content.toDouble() * 1e8) == coin.value &&
                o["scriptPubKey"]!!.jsonObject["hex"]!!.jsonPrimitive.content == coin.script.toHex()
        }
        override fun broadcast(rawHex: String): String = rpc("sendrawtransaction", rawHex, wallet = null)!!.jsonPrimitive.content
        override fun confirmations(txid: String): Int? = runCatching {
            (rpc("getrawtransaction", txid, true, wallet = null) as JsonObject)["confirmations"]?.jsonPrimitive?.content?.toInt() ?: 0
        }.getOrNull()
        override fun save(state: PoolSession.State) { PoolSession.State.parse(J.parse(state.toJson().toString())) }
        override fun event(e: PoolSession.Event) {}
        override fun log(msg: String) { log.add(msg); if (System.getenv("KILOJOIN_VERBOSE") != null) println("[kw] $msg") }
    }
    private val log = CopyOnWriteArrayList<String>()

    private inner class M(val name: String) {
        val key: ByteArray = run { while (true) { val k = secureRandomBytes(32); if (runCatching { Ec.publicKey(k) }.isSuccess) return@run k }; error("") }
        val pub = Ec.publicKey(key)
        val coin: PoolTx.Coin = run {
            val addr = Bech32.encodeSegwit("bcrt", 0, Hashes.hash160(pub))
            val txid = rpc("sendtoaddress", addr, "0.00130000")!!.jsonPrimitive.content
            val tx = rpc("getrawtransaction", txid, true, wallet = null) as JsonObject
            val vout = tx["vout"]!!.jsonArray.indexOfFirst { it.jsonObject["scriptPubKey"]!!.jsonObject["address"]?.jsonPrimitive?.content == addr }
            PoolTx.Coin(txid, vout, 130_000, pub)
        }
        lateinit var s: PoolSession
        fun script(): ByteArray { while (true) { val k = secureRandomBytes(32); runCatching { return Address.scriptPubKey(Ec.publicKey(k), ScriptType.P2WPKH) } } }
    }

    private fun waitFor(what: String, seconds: Int = 180, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(500) }
        log.takeLast(40).forEach { println("   $it") }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun listed(id: String): Protocol.Terms = runBlocking {
        var t: Protocol.Terms? = null
        val r = RelayClient(Protocol.RELAY, JvmWebSocket, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == id) t = it } })
        r.connect(); r.subscribe("pools", listOf(filter(listOf(Protocol.KIND_POOL), t = Protocol.TAG))); r.close()
        t ?: error("pool $id not listed")
    }

    private fun <T> withLab(f: () -> T): T? {
        if (rpcSpec == null) { println("KJ_LAB_RPC not set: the lab interop test does nothing"); return null }
        val net = Protocol.NETWORK; val relay = Protocol.RELAY
        Protocol.NETWORK = "blake2b-regtest"; Protocol.RELAY = "ws://127.0.0.1:7787"
        PoolSession.TICK_SECONDS = 2; PoolSession.handInDelaySeconds = { 1L }
        try { return f() } finally {
            Protocol.NETWORK = net; Protocol.RELAY = relay; PoolSession.TICK_SECONDS = 15
            PoolSession.handInDelaySeconds = { 3L + (secureRandomBytes(1)[0].toInt() and 0xFF) % 40 }
        }
    }

    /** Kilojoin 0.10.2 (the lab's short creator, v6 windows 600/300/1200) opens; two of these join, vote, sign. */
    @Test fun kilojoinCreatorWithTwoMembersHere() { withLab {
        val b = M("B"); val c = M("C"); mine()
        val out = kj("pool", "short", "--min", "3", "--max", "3", "--amount", "100000", "--fee", "2", "--hours", "2")
        val id = Regex("\"(?:id|pool)\": \"([0-9a-f]{16})\"").find(out)?.groupValues?.get(1) ?: error("no pool id in: $out")
        println("KW lab: Kilojoin pool $id")
        var t: Protocol.Terms? = null
        waitFor("pool $id listed") { t = runCatching { listed(id) }.getOrNull(); t != null }
        val terms = t!!
        assertTrue(terms.v6, "a v6 pool"); println("KW lab: windows ${terms.async}")
        for (m in listOf(b, c)) {
            m.s = PoolSession(env, PoolSession.newState(terms, false, null, listOf(m.coin), listOf(m.key), listOf("m/${m.name}"),
                listOf(m.script()), m.script()), JvmWebSocket)
            m.s.start()
            waitFor("${m.name} welcomed") { m.s.state.token != null && m.s.state.phase in setOf(Phase.OPEN, Phase.VOTING) }
        }
        waitFor("vote open (the pool is full)") { listOf(b, c).all { it.s.state.phase == Phase.VOTING } }
        runBlocking { b.s.vote(true); c.s.vote(true) }
        waitFor("both yes recorded by Kilojoin") { listOf(b, c).all { it.s.state.voteAcked == it.s.state.voteId } }
        waitFor("signing", 400) { listOf(b, c).all { it.s.state.phase == Phase.SIGNING } }
        assertEquals(b.s.plannedTxid(), c.s.plannedTxid())
        runBlocking { b.s.sign(listOf(b.key)); c.s.sign(listOf(c.key)) }
        waitFor("broadcast", 300) { listOf(b, c).all { it.s.state.txid != null } }
        val txid = b.s.state.txid!!
        println("KW lab: round $txid broadcast; node has it: ${env.confirmations(txid)}")
        mine(1)
        waitFor("1/6 seen") { listOf(b, c).all { it.s.state.confs >= 1 } }
        assertTrue(listOf(b, c).all { it.s.state.phase == Phase.BROADCAST }, "one block is not confirmed")
        mine(5)
        waitFor("confirmed 6 deep") { listOf(b, c).all { it.s.state.phase == Phase.CONFIRMED } }
        println("KW lab: Kilojoin creator round CONFIRMED: " + kj("kj", "short", "brief").lines().firstOrNull { id in it })
        listOf(b, c).forEach { it.s.dispose() }
    } }

    /** One of these opens a v6 pool (the iPhone as creator); Kilojoin 0.10.2 members m1 and m2 join, vote, sign. */
    @Test fun creatorHereWithKilojoinMembers() { withLab {
        val a = M("A"); mine()
        fun unit(verb: String) = listOf("m1", "m2").forEach { m ->
            ProcessBuilder("systemctl", "--user", verb, "kjlab-kilojoin@$m").also { it.environment()["XDG_RUNTIME_DIR"] = "/run/user/1000" }.start().waitFor()
        }
        // m1 and m2 run only for this test (as the lab's smoke test does), and stop at the end.
        unit("start")
        try {
        Thread.sleep(8000)
        for (m in listOf("m1", "m2")) kj("kj", m, "unlock")
        val np = PoolSession.newPool(100_000, 2.0, 3, 2, 3, v3 = true, async = true)
        a.s = PoolSession(env, PoolSession.newState(np.terms, true, np.secret, listOf(a.coin), listOf(a.key), listOf("m/A"),
            listOf(a.script()), a.script(), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv), JvmWebSocket)
        a.s.start()
        waitFor("open") { a.s.state.phase == Phase.OPEN }
        val id = np.terms.id
        println("KW lab: pool $id opened here, windows ${np.terms.async}")
        Thread.sleep(3000)
        for (m in listOf("m1", "m2")) {
            val r = kj("kj", m, "join", id)
            println("KW lab: $m join -> ${r.lines().take(3).joinToString(" ")}")
            waitFor("$m seated") { a.s.state.seats.size >= (if (m == "m1") 2 else 3) }
        }
        waitFor("vote (full)") { a.s.state.phase == Phase.VOTING }
        Thread.sleep(3000)
        for (m in listOf("m1", "m2")) if (a.s.state.phase == Phase.VOTING) println("KW lab: $m vote -> " + kj("kj", m, "vote", id, "yes").lines().take(2).joinToString(" "))
        waitFor("signing", 400) { a.s.state.phase == Phase.SIGNING }
        runBlocking { a.s.sign(listOf(a.key)) }
        waitFor("broadcast", 300) { a.s.state.txid != null }
        val txid = a.s.state.txid!!
        println("KW lab: round $txid broadcast")
        mine(6)
        waitFor("confirmed 6 deep") { a.s.state.phase == Phase.CONFIRMED }
        Thread.sleep(5000)
        for (m in listOf("m1", "m2")) println("KW lab: $m: " + kj("kj", m, "brief").lines().firstOrNull { id in it })
        a.s.dispose()
        } finally { unit("stop") }
    } }
}
