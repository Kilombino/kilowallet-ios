package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.WebSocket
import com.kilombino.kilowallet.core.kilojoin.WebSocketFactory
import com.kilombino.kilowallet.core.kilojoin.WebSocketListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole rounds of this port's [PoolSession] against each other, over an in-memory relay and an
 * in-memory chain: v3 (one equal output each), v4 (two each, several coins), v5 (a payment of
 * three different amounts) and a private pool with a wrong password. Every message goes through
 * the real NIP-44, blind signatures and checks; only the network and the node are stand-ins.
 */
class PoolSessionTest {
    /** A nostr relay in memory: NIP-01 REQ / EVENT / CLOSE, replaceable pool announcements. */
    class FakeRelay : WebSocketFactory {
        private val stored = CopyOnWriteArrayList<NostrEvent>()
        private val conns = CopyOnWriteArrayList<Conn>()

        inner class Conn(val l: WebSocketListener) : WebSocket {
            val subs = java.util.concurrent.ConcurrentHashMap<String, List<JsonObject>>()
            override fun send(text: String): Boolean { handle(this, text); return true }
            override fun ping(): Boolean { l.onPong(); return true }
            override fun close() { conns.remove(this) }
        }

        override fun open(url: String, listener: WebSocketListener): WebSocket = Conn(listener).also { conns.add(it) }

        private fun matches(f: JsonObject, ev: NostrEvent): Boolean {
            fun list(k: String) = (f[k] as? JsonArray)?.map { it.jsonPrimitive.content }
            list("kinds")?.let { if (ev.kind.toString() !in it) return false }
            list("authors")?.let { if (ev.pubkey !in it) return false }
            for (t in listOf("p", "d", "t")) list("#$t")?.let { want -> if (ev.tags.none { it.size >= 2 && it[0] == t && it[1] in want }) return false }
            (f["since"] as? JsonPrimitive)?.long?.let { if (ev.createdAt < it) return false }
            return true
        }

        @Synchronized private fun handle(c: Conn, text: String) {
            val a = Json.parseToJsonElement(text).jsonArray
            when (a[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val ev = NostrEvent.parse(a[1].jsonObject)
                    require(ev.verify()) { "bad event" }
                    if (ev.kind == Protocol.KIND_POOL) stored.removeAll { it.kind == ev.kind && it.pubkey == ev.pubkey && it.tag("d") == ev.tag("d") }
                    stored.add(ev)
                    c.l.onText("[\"OK\",\"${ev.id}\",true,\"\"]")
                    for (o in conns) for ((id, fs) in o.subs) if (fs.any { matches(it, ev) })
                        o.l.onText(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(id), ev.toJson())).toString())
                }
                "REQ" -> {
                    val id = a[1].jsonPrimitive.content
                    val fs = a.drop(2).map { it.jsonObject }
                    c.subs[id] = fs
                    for (ev in stored) if (fs.any { matches(it, ev) })
                        c.l.onText(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(id), ev.toJson())).toString())
                    c.l.onText("[\"EOSE\",\"$id\"]")
                }
                "CLOSE" -> c.subs.remove(a[1].jsonPrimitive.content)
            }
        }
    }

    /** A chain in memory: unspent coins, and what was broadcast. */
    class Chain {
        val utxos = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val txs = CopyOnWriteArrayList<String>()
        val mined = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Synchronized fun broadcast(raw: String): String {
            val tx = TxParse.parse(raw)
            val id = TxParse.txid(tx)
            if (raw in txs) return id
            for (i in tx.inputs) require(utxos.remove("${i.txid}:${i.vout}") != null) { "missing input" }
            txs.add(raw)
            return id
        }
    }

    inner class Env(val name: String, val chain: Chain) : PoolSession.Env {
        val events = CopyOnWriteArrayList<String>()
        override fun coinUnspent(coin: PoolTx.Coin) = chain.utxos[coin.outpoint] == coin.value
        override fun broadcast(rawHex: String) = chain.broadcast(rawHex)
        override fun confirmations(txid: String): Int? = when {
            txid in chain.mined -> 1
            chain.txs.any { TxParse.txid(TxParse.parse(it)) == txid } -> 0
            else -> null
        }
        override fun save(state: PoolSession.State) { PoolSession.State.parse(state.toJson()) } // must round-trip
        override fun event(e: PoolSession.Event) { if (e !is PoolSession.Event.Changed) events.add(e::class.simpleName!!) }
        override fun log(msg: String) { if (System.getenv("KW_DEBUG") != null) println("[$name] $msg") }
    }

    private val relay = FakeRelay()
    private val chain = Chain()
    private val sessions = mutableListOf<PoolSession>()

    @BeforeTest fun quick() { Protocol.NETWORK = "blake2b-test"; PoolSession.handInDelaySeconds = { 0L } }
    @AfterTest fun down() { sessions.forEach { it.dispose() }; Protocol.NETWORK = "blake2b" }

    private var n = 0
    private fun key(): ByteArray = Hashes.sha256("pool-session-test-${n++}-${System.nanoTime()}".encodeToByteArray())
    private fun script() = Address.scriptPubKey(Ec.publicKey(key()), ScriptType.P2WPKH)
    private fun coin(value: Long): Pair<PoolTx.Coin, ByteArray> {
        val k = key()
        val c = PoolTx.Coin(Hashes.sha256(k).let { with(Hashes) { it.toHex() } }, 0, value, Ec.publicKey(k))
        chain.utxos[c.outpoint] = value
        return c to k
    }

    private suspend fun waitFor(what: String, sec: Int = 60, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + sec * 1000L
        while (!cond()) { if (System.currentTimeMillis() > until) error("timed out: $what"); delay(50) }
    }

    private fun session(name: String, st: PoolSession.State) = PoolSession(Env(name, chain), st, relay).also { sessions += it; it.start() }

    private class Member(val s: PoolSession, val keys: List<ByteArray>)

    /** A full round: [creatorCoins]/[joinerCoins] values, [outputs] each, [payments] for v5. */
    private fun round(amount: Long, outputs: Int, creatorCoins: List<Long>, joinerCoins: List<List<Long>>, payments: List<Long> = emptyList(),
                      maxInputs: Int = 1) = runBlocking {
        val np = PoolSession.newPool(amount, 2.0, 5, 1, maxInputs = maxInputs, outputs = outputs, payments = payments)
        val t = np.terms
        val cc = creatorCoins.map { coin(it) }
        val creatorOuts = if (t.v5) payments.size else outputs
        val a = Member(session("A", PoolSession.newState(t, true, np.secret, cc.map { it.first }, cc.map { it.second }, cc.map { "m/a" },
            List(creatorOuts) { script() }, script(), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)), cc.map { it.second })
        waitFor("A open") { a.s.state.phase == PoolSession.Phase.OPEN }
        val joiners = joinerCoins.mapIndexed { i, vs ->
            val jc = vs.map { coin(it) }
            Member(session("J$i", PoolSession.newState(t, false, null, jc.map { it.first }, jc.map { it.second }, jc.map { "m/j" },
                List(outputs) { script() }, script())), jc.map { it.second }).also { m ->
                waitFor("J$i welcomed") { m.s.state.phase == PoolSession.Phase.OPEN }
            }
        }
        val all = listOf(a) + joiners
        waitFor("roster") { all.all { it.s.state.seats.size == all.size } }
        joiners.first().s.requestClose()
        waitFor("voting") { all.all { it.s.state.phase == PoolSession.Phase.VOTING } }
        all.drop(1).plus(a).forEach { if (it.s.state.voteBy != it.s.state.token?.let { t -> Protocol.tokenHash(t) }) it.s.vote(true) }
        waitFor("signing", 90) { all.all { it.s.state.phase == PoolSession.Phase.SIGNING } }
        val plans = all.map { it.s.plan()!! }
        assertTrue(plans.all { PoolTx.txid(it) == PoolTx.txid(plans[0]) }, "everyone builds the same transaction")
        all.forEach { assertEquals(null, it.s.planProblem()) }
        all.forEach { it.s.sign(it.keys) }
        waitFor("broadcast") { all.all { it.s.state.txid != null } }
        assertEquals(1, chain.txs.size)
        val raw = chain.txs.single()
        val tx = TxParse.parse(raw)
        val plan = plans[0]
        assertEquals(PoolTx.txid(plan), TxParse.txid(tx))
        tx.inputs.forEachIndexed { i, inp -> assertTrue(PoolTx.verify(plan, plan.coins[i], inp.witness[0])) }
        // The equal outputs: (joiners, or everyone before v5) × outputs of the amount, and the payments.
        val equal = (if (t.v5) joiners.size else all.size) * outputs
        assertTrue(tx.outputs.count { it.value == amount } >= equal)
        for (p in payments) assertTrue(tx.outputs.any { it.value == p })
        chain.mined.add(TxParse.txid(tx))
        waitFor("confirmed", 40) { all.all { it.s.state.phase == PoolSession.Phase.CONFIRMED } }
        tx
    }

    @Test fun v3OneEqualOutputEach() { round(50_000, 1, listOf(60_000), listOf(listOf(51_000), listOf(80_000))) }

    @Test fun v4TwoEqualOutputsEachSeveralCoins() {
        val tx = round(20_000, 2, listOf(30_000, 15_000), listOf(listOf(41_000), listOf(25_000, 25_000)), maxInputs = 2)
        assertEquals(6, tx.outputs.count { it.value == 20_000L })
    }

    @Test fun v5APaymentOfDifferentAmounts() {
        val tx = round(100_000, 2, listOf(250_000), listOf(listOf(220_000), listOf(200_700)), payments = listOf(30_000, 45_000, 125_000))
        assertEquals(4, tx.outputs.count { it.value == 100_000L })
    }

    @Test fun aWrongPasswordIsRefused() = runBlocking {
        val np = PoolSession.newPool(50_000, 2.0, 5, 1, private = true)
        val (c, k) = coin(60_000)
        val a = session("A", PoolSession.newState(np.terms, true, np.secret, listOf(c), listOf(k), listOf("m"), listOf(script()), script(),
            password = "right", creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
        waitFor("open") { a.state.phase == PoolSession.Phase.OPEN }
        val (c2, k2) = coin(60_000)
        val b = session("B", PoolSession.newState(np.terms, false, null, listOf(c2), listOf(k2), listOf("m"), listOf(script()), script(), password = "wrong"))
        waitFor("refused") { b.state.phase == PoolSession.Phase.REJECTED }
        assertEquals("wrong password", b.state.reason)
        val (c3, k3) = coin(60_000)
        val d = session("D", PoolSession.newState(np.terms, false, null, listOf(c3), listOf(k3), listOf("m"), listOf(script()), script(), password = "right"))
        waitFor("in") { d.state.phase == PoolSession.Phase.OPEN }
        // A coin that is not on the chain gets no seat.
        val fake = PoolTx.Coin("ee".repeat(32), 0, 60_000, Ec.publicKey(k3))
        val e = session("E", PoolSession.newState(np.terms, false, null, listOf(fake), listOf(k3), listOf("m"), listOf(script()), script(), password = "right"))
        waitFor("not real") { e.state.phase == PoolSession.Phase.REJECTED }
        assertEquals("that coin is not unspent on the chain", e.state.reason)
    }
}
