package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.LineTransport
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.chain.TransportFactory
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.WebSocket
import com.kilombino.kilowallet.core.kilojoin.WebSocketFactory
import com.kilombino.kilowallet.core.kilojoin.WebSocketListener
import com.kilombino.kilowallet.core.wallet.AddressRow
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's side of Kilojoin ([PoolsModel]) end to end, two wallets with their own words: one opens
 * a pool, the other joins it from the open list, both check and sign, and the transaction is sent and
 * confirmed. Also a payment through a pool (v5) from SEND. Over an in-memory nostr relay and an
 * in-memory Electrum server; everything else (keys from the words, fresh addresses, the checks) is
 * the app's own code.
 */
class PoolsModelTest {
    // ------------------------------------------------------------------ a relay and a chain in memory

    class Relay : WebSocketFactory {
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
            for (t in listOf("p", "d", "t")) list("#$t")?.let { w -> if (ev.tags.none { it.size >= 2 && it[0] == t && it[1] in w }) return false }
            (f["since"] as? JsonPrimitive)?.long?.let { if (ev.createdAt < it) return false }
            return true
        }
        private fun ev(id: String, e: NostrEvent) = JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(id), e.toJson())).toString()
        @Synchronized private fun handle(c: Conn, text: String) {
            val a = Json.parseToJsonElement(text).jsonArray
            when (a[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val e = NostrEvent.parse(a[1].jsonObject); require(e.verify())
                    if (e.kind == Protocol.KIND_POOL) stored.removeAll { it.kind == e.kind && it.pubkey == e.pubkey && it.tag("d") == e.tag("d") }
                    stored.add(e)
                    c.l.onText("[\"OK\",\"${e.id}\",true,\"\"]")
                    for (o in conns) for ((id, fs) in o.subs) if (fs.any { matches(it, e) }) o.l.onText(ev(id, e))
                }
                "REQ" -> {
                    val id = a[1].jsonPrimitive.content; val fs = a.drop(2).map { it.jsonObject }
                    c.subs[id] = fs
                    for (e in stored) if (fs.any { matches(it, e) }) c.l.onText(ev(id, e))
                    c.l.onText("[\"EOSE\",\"$id\"]")
                }
                "CLOSE" -> c.subs.remove(a[1].jsonPrimitive.content)
            }
        }
    }

    /** An Electrum server over a chain in memory: coins by script, transactions, history, broadcast, mining. */
    class Chain {
        class U(val txid: String, val vout: Int, val value: Long, var height: Int)
        val utxos = HashMap<String, MutableList<U>>()           // scripthash → coins
        val history = HashMap<String, MutableList<Pair<String, Int>>>()
        val txs = HashMap<String, String>()
        val sent = CopyOnWriteArrayList<String>()

        @Synchronized fun fund(script: ByteArray, value: Long): Pair<String, Int> {
            val txid = Hashes.sha256("${script.toHex()}-$value-${txs.size}".encodeToByteArray()).toHex()
            val sh = Address.electrumScriptHash(script)
            utxos.getOrPut(sh) { mutableListOf() } += U(txid, 0, value, 100)
            history.getOrPut(sh) { mutableListOf() } += txid to 100
            // A transaction for verifyCoins-like readers: one output paying [script].
            txs[txid] = "02000000" + "01" + "00".repeat(32) + "ffffffff" + "00" + "ffffffff" + "01" +
                value.toULong().let { v -> (0 until 8).joinToString("") { ((v shr (8 * it)) and 0xffu).toString(16).padStart(2, '0') } } +
                script.size.toString(16).padStart(2, '0') + script.toHex() + "00000000"
            return txid to 0
        }

        @Synchronized fun broadcast(raw: String): String {
            val tx = TxParse.parse(raw)
            val id = TxParse.txid(tx)
            if (id in txs) return id
            for (i in tx.inputs) {
                val list = utxos.values.firstOrNull { l -> l.any { it.txid == i.txid && it.vout == i.vout } } ?: error("missing input")
                list.removeAll { it.txid == i.txid && it.vout == i.vout }
            }
            tx.outputs.forEachIndexed { n, o ->
                val sh = Address.electrumScriptHash(o.scriptPubKey)
                utxos.getOrPut(sh) { mutableListOf() } += U(id, n, o.value, 0)
                history.getOrPut(sh) { mutableListOf() } += id to 0
            }
            txs[id] = raw; sent += raw
            return id
        }

        @Synchronized fun mine() {
            utxos.values.flatten().forEach { if (it.height == 0) it.height = 101 }
            history.values.forEach { l -> l.replaceAll { (t, h) -> t to if (h == 0) 101 else h } }
        }

        @Synchronized fun answer(method: String, p: JsonArray): JsonElement = when (method) {
            "server.version" -> JsonArray(listOf(JsonPrimitive("Fake 1.0"), JsonPrimitive("1.4")))
            "blockchain.headers.subscribe" -> buildJsonObject { put("height", 101); put("hex", "00".repeat(164)) }
            "blockchain.scripthash.listunspent" -> JsonArray(utxos[p[0].jsonPrimitive.content].orEmpty().map { u ->
                buildJsonObject { put("tx_hash", u.txid); put("tx_pos", u.vout); put("value", u.value); put("height", u.height) } })
            "blockchain.scripthash.get_history" -> JsonArray(history[p[0].jsonPrimitive.content].orEmpty().map { (t, h) ->
                buildJsonObject { put("tx_hash", t); put("height", h) } })
            "blockchain.transaction.get" -> txs[p[0].jsonPrimitive.content]?.let { JsonPrimitive(it) } ?: JsonNull
            "blockchain.transaction.broadcast" -> JsonPrimitive(broadcast(p[0].jsonPrimitive.content))
            else -> error("unexpected $method")
        }

        val transports = TransportFactory { _, _, _, _ ->
            object : LineTransport {
                private val pending = ArrayDeque<String>()
                override val fingerprint: String? = "ab".repeat(32)
                override fun writeLine(line: String) {
                    for (one in line.split('\n').filter { it.isNotBlank() }) {
                        val o = Json.parseToJsonElement(one).jsonObject
                        val r = runCatching { answer(o["method"]!!.jsonPrimitive.content, o["params"]!!.jsonArray) }
                        pending += buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", o["id"]!!.jsonPrimitive.int)
                            r.onSuccess { put("result", it) }.onFailure { e -> put("error", buildJsonObject { put("message", e.message ?: "error") }) }
                        }.toString()
                    }
                }
                override fun readLine(): String? = pending.removeFirstOrNull()
                override fun close() {}
            }
        }
    }

    // ------------------------------------------------------------------ two wallets

    private val relay = Relay()
    private val chain = Chain()

    inner class Wallet(seedTag: String, coins: List<Long>) {
        val words = Bip39.fromEntropy(Hashes.sha256("pools-model-test-$seedTag".encodeToByteArray()).copyOf(16))
        val zpub = Signer.accountZpub(words, "")
        private val acct = Bip32.parseExtendedPubKey(zpub)
        fun script(chainIndex: Int, i: Int) = Address.scriptPubKey(Bip32.derivePath(acct, chainIndex, i).pubkey(), ScriptType.P2WPKH)
        val utxos = coins.mapIndexed { i, v -> val (txid, vout) = chain.fund(script(0, i), v); SpendableUtxo(txid, vout, v, 0, i, 100) }
        val ui = UiState(screen = Screen.Pools, kind = WalletKind.HOT, zpub = zpub, endpoint = NodeEndpoint("fake", 1, true),
            rows = coins.indices.map { i -> AddressRow(0, i, "", "", Address.electrumScriptHash(script(0, i)), 0, 0, 1) })
        val platform = object : AppPlatform {
            private val mem = java.util.concurrent.ConcurrentHashMap<String, String>()
            override val seeds = object : SeedStore {
                override fun hasSeed() = true
                override fun save(secret: String, done: (String?) -> Unit) = done(null)
                override fun load(reason: String, done: (String?, String?) -> Unit) = done(encodeSecret(words, ""), null)
                override fun delete() {}
            }
            override val prefs = object : Prefs {
                override fun get(key: String) = mem[key]
                override fun put(key: String, value: String?) { if (value == null) mem.remove(key) else mem[key] = value }
            }
            override val transports = chain.transports
            override val sockets: WebSocketFactory = relay
            override val unlockName = "test"
            override fun qrImage(text: String): ImageBitmap? = null
            override val canScanQr = false
            override fun scanQr(done: (String?) -> Unit) = done(null)
            override val demoScreen: String? = null
            override val appVersion = "test"
        }
        val pools = PoolsModel(platform, { ui }, gatherCoins = { utxos }, verifyCoins = {}, onRoundDone = {})
        val round get() = pools.state.value.rounds.single()
    }

    @BeforeTest fun quick() { Protocol.NETWORK = "blake2b-apptest"; PoolSession.handInDelaySeconds = { 0L } }
    @AfterTest fun down() { Protocol.NETWORK = "blake2b" }

    private fun waitFor(what: String, sec: Int = 60, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + sec * 1000L
        while (!cond()) { if (System.currentTimeMillis() > until) error("timed out: $what"); Thread.sleep(50) }
    }

    /** Both wallets through a whole round; returns the transaction that went out. */
    private fun play(a: Wallet, b: Wallet, open: (Wallet) -> Unit, join: (Wallet, Protocol.Terms) -> Unit): TxParse.Tx {
        a.pools.attach(); b.pools.attach()
        a.pools.setEnabled(true); b.pools.setEnabled(true)
        open(a)
        waitFor("A's pool open") { a.pools.state.value.rounds.singleOrNull()?.phase == PoolSession.Phase.OPEN }
        b.pools.refreshPools()
        waitFor("B sees it") { b.pools.state.value.open?.any { it.id == a.round.poolId } == true }
        val t = b.pools.state.value.open!!.first { it.id == a.round.poolId }
        join(b, t)
        waitFor("B in") { b.pools.state.value.rounds.singleOrNull()?.phase == PoolSession.Phase.OPEN && a.round.seats.size == 2 }
        b.pools.requestClose(t.id)
        waitFor("A asked") { a.round.phase == PoolSession.Phase.VOTING }
        a.pools.vote(t.id, true)
        waitFor("signing", 90) { a.round.phase == PoolSession.Phase.SIGNING && b.round.phase == PoolSession.Phase.SIGNING }
        // What each one sees before signing: its own check passes.
        assertEquals(null, a.pools.session(t.id)!!.planProblem())
        assertEquals(null, b.pools.session(t.id)!!.planProblem())
        a.pools.sign(t.id); b.pools.sign(t.id)
        waitFor("sent") { a.round.txid != null && b.round.txid != null }
        assertEquals(1, chain.sent.size)
        chain.mine()
        waitFor("confirmed", 40) { a.round.phase == PoolSession.Phase.CONFIRMED && b.round.phase == PoolSession.Phase.CONFIRMED }
        return TxParse.parse(chain.sent.single())
    }

    @Test fun openJoinCheckSignAndConfirm() {
        val a = Wallet("A", listOf(30_000, 15_000)); val b = Wallet("B", listOf(50_000))
        val tx = play(a, b, { w ->
            w.pools.create(20_000, 2.0, 2, 5, 1, 2, 2, null, w.utxos, PoolDest.HOT, null) {}
        }, { w, t -> w.pools.join(t, w.utxos, PoolDest.HOT, null) {} })
        // Two equal outputs each, to the fresh receive addresses after the used ones (2 and 3 for A, 1 and 2 for B).
        val outs = tx.outputs.map { it.scriptPubKey.toHex() to it.value }
        for (s in listOf(a.script(0, 2), a.script(0, 3), b.script(0, 1), b.script(0, 2))) assertTrue(s.toHex() to 20_000L in outs, "an equal output")
        // B's change to its first change address; A put in whole coins worth more than 2 × 20 000 plus its fee: change too.
        assertTrue(outs.any { it.first == b.script(1, 0).toHex() })
        assertTrue(outs.any { it.first == a.script(1, 0).toHex() })
        assertEquals(3, tx.inputs.size)
        for (i in tx.inputs) assertEquals(0x21, i.witness[0].last().toInt() and 0xff)
    }

    @Test fun payThroughAPool() {
        val a = Wallet("PA", listOf(100_000)); val b = Wallet("PB", listOf(90_000))
        val r1 = Address.encode(Bip32.derivePath(Bip32.parseExtendedPubKey(Wallet("R", emptyList()).zpub), 0, 0).pubkey(), ScriptType.P2WPKH)
        val r2 = Address.encode(Bip32.derivePath(Bip32.parseExtendedPubKey(Wallet("R", emptyList()).zpub), 0, 1).pubkey(), ScriptType.P2WPKH)
        val pay = PoolPayRequest(listOf(r1 to 30_000L, r2 to 45_000L), 2.0)
        val (d, k) = Protocol.v5Split(75_000, 3)
        assertEquals(25_000L to 3, d to k)
        val tx = play(a, b, { w ->
            w.pools.startPayment(pay)
            w.pools.create(d, 2.0, 2, 5, 1, 1, k, null, w.utxos, PoolDest.HOT, pay) {}
        }, { w, t ->
            assertEquals(Protocol.V5, t.version)
            w.pools.join(t, w.utxos, PoolDest.HOT, null) {}
        })
        val outs = tx.outputs.map { (Address.fromScriptPubKey(it.scriptPubKey) ?: "") to it.value }
        assertTrue(r1 to 30_000L in outs && r2 to 45_000L in outs, "the payments")
        assertEquals(3, tx.outputs.count { it.value == 25_000L }, "B's equal outputs")
        // The recipients' addresses are kept as a payment's, not as this wallet's equal outputs.
        assertEquals(PoolDest.PAY, a.pools.destOf(a.round.poolId))
        // A coin of exactly the amount is not offered twice: B's coin is spent, its seat ended.
        assertTrue(b.pools.waitingOutpoints().isEmpty() && b.pools.lockedOutpoints().isEmpty())
        // Unused helper so the test wallet type stays used.
        assertTrue(PoolTx.DUST > 0)
    }
}
