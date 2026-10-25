import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx as ACoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession as APoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol as AProtocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient as ARelayClient
import com.kilombino.pyblockwatch.crypto.ScriptType as AScriptType
import com.kilombino.kilowallet.core.crypto.Address as IAddress
import com.kilombino.kilowallet.core.crypto.Ec as IEc
import com.kilombino.kilowallet.core.crypto.Hashes as IHashes
import com.kilombino.kilowallet.core.crypto.ScriptType as IScriptType
import com.kilombino.kilowallet.core.kilojoin.JvmWebSocket
import com.kilombino.kilowallet.core.kilojoin.NostrEvent as INostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolSession as IPoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx as IPoolTx
import com.kilombino.kilowallet.core.kilojoin.Protocol as IProtocol
import com.kilombino.kilowallet.core.kilojoin.RelayClient as IRelayClient

/**
 * Interop rounds between this iOS port's pool code and the Android app's own (1.3.0, compiled
 * here unchanged), for real: relay.kilombino.com under a network name of their own (so nothing
 * shows in anyone's pool list), and a BLAKE2b regtest node of their own (KW_RPC, user:pass@host:port).
 * Coins are made on that regtest only. Each round checks that both sides build the same
 * transaction, that it is broadcast and mined, and that the node's transaction is byte for byte
 * the one each side assembles.
 *
 * Run: ./gradlew -p tools/android-vectors interop -PandroidRepo=… (after :core:jvmJar).
 */
private val rpcUrl = System.getenv("KW_RPC") ?: "kwi:kwi@127.0.0.1:18979"

private fun rpc(method: String, vararg params: Any?, wallet: Boolean = false): Any? {
    val (auth, host) = rpcUrl.split('@')
    val c = URL("http://$host/" + if (wallet) "wallet/w" else "").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray()))
    c.outputStream.write(JSONObject().put("method", method).put("params", JSONArray(params.toList())).toString().toByteArray())
    val body = (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
    val o = JSONObject(body)
    if (!o.isNull("error")) throw IllegalStateException(o.getJSONObject("error").getString("message"))
    return o.opt("result")
}

private fun mine(n: Int = 1) { rpc("generatetoaddress", n, rpc("getnewaddress", "", "bech32", wallet = true), wallet = true) }

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private val rnd = SecureRandom()

/** A fresh key and a coin of exactly [value] sats to its native SegWit address, confirmed on the regtest. */
private class Key(val secret: ByteArray) {
    val big get() = BigInteger(1, secret)
    val pub get() = IEc.publicKey(secret)
    val script get() = IAddress.scriptPubKey(pub, IScriptType.P2WPKH)
    /** The regtest address (bcrt1…) of that script. */
    val address get() = com.kilombino.kilowallet.core.crypto.Bech32.encodeSegwit("bcrt", 0, script.copyOfRange(2, 22))
}
private fun newKey(): Key { while (true) { val k = ByteArray(32).also { rnd.nextBytes(it) }; if (IEc.isValidSecret(k)) return Key(k) } }

private class Funded(val key: Key, val txid: String, val vout: Int, val value: Long)

private fun fund(values: List<Long>): List<Funded> {
    val keys = values.map { newKey() }
    val outs = JSONObject()
    keys.forEachIndexed { i, k -> outs.put(k.address, "%.8f".format(java.util.Locale.ROOT, values[i] / 1e8)) }
    val txid = rpc("sendmany", "", outs, wallet = true) as String
    mine()
    val tx = rpc("getrawtransaction", txid, true) as JSONObject
    val vouts = tx.getJSONArray("vout")
    return keys.mapIndexed { i, k ->
        val v = (0 until vouts.length()).first { vouts.getJSONObject(it).getJSONObject("scriptPubKey").getString("hex") == hex(k.script) }
        Funded(k, txid, v, values[i])
    }
}

private fun unspent(txid: String, vout: Int, value: Long, script: ByteArray): Boolean {
    val o = rpc("gettxout", txid, vout, true) as? JSONObject ?: return false
    return Math.round(o.getDouble("value") * 1e8) == value && o.getJSONObject("scriptPubKey").getString("hex") == hex(script)
}

private fun confs(txid: String): Int? = runCatching {
    val o = rpc("getrawtransaction", txid, true) as JSONObject
    o.optInt("confirmations", 0)
}.getOrNull()

private class AEnv(val name: String) : APoolSession.Env {
    override fun coinUnspent(coin: ACoinjoinTx.Coin) = unspent(coin.txid, coin.vout, coin.value, coin.script)
    override fun broadcast(rawHex: String) = rpc("sendrawtransaction", rawHex) as String
    override fun confirmations(txid: String) = confs(txid)
    override fun save(state: APoolSession.State) { APoolSession.State.parse(state.toJson()) }
    override fun event(e: APoolSession.Event) { if (e !is APoolSession.Event.Changed) println("  [$name android] ${e.javaClass.simpleName}") }
    override fun log(msg: String) { if (System.getenv("KW_DEBUG") != null) println("  [$name android] $msg") }
}

private class IEnv(val name: String) : IPoolSession.Env {
    override fun coinUnspent(coin: IPoolTx.Coin) = unspent(coin.txid, coin.vout, coin.value, coin.script)
    override fun broadcast(rawHex: String) = rpc("sendrawtransaction", rawHex) as String
    override fun confirmations(txid: String) = confs(txid)
    override fun save(state: IPoolSession.State) { IPoolSession.State.parse(state.toJson()) }
    override fun event(e: IPoolSession.Event) { if (e !is IPoolSession.Event.Changed) println("  [$name ios] ${e::class.simpleName}") }
    override fun log(msg: String) { if (System.getenv("KW_DEBUG") != null) println("  [$name ios] $msg") }
}

/** One member of a round, from either code base. */
private interface Member {
    val name: String
    val phase: String
    val seats: Int
    val txid: String?
    val planTxid: String?
    fun requestClose()
    fun voteIfAsked()
    fun sign()
    fun stop()
    /** The transaction this member assembles from the signatures it holds. */
    fun assembled(): String?
}

private class AndroidMember(override val name: String, val s: APoolSession, val keys: List<Key>) : Member {
    override val phase get() = s.state.phase.name
    override val seats get() = s.state.seats.size
    override val txid get() = s.state.txid
    override val planTxid get() = s.plan()?.let { ACoinjoinTx.txid(it) }
    override fun requestClose() = s.requestClose()
    override fun voteIfAsked() { if (s.state.phase == APoolSession.Phase.VOTING && s.state.voteBy != s.state.token?.let { AProtocol.tokenHash(it) }) s.vote(true) }
    override fun sign() = s.sign(keys.map { it.big })
    override fun stop() = s.stop()
    override fun assembled() = s.plan()?.let { p -> ACoinjoinTx.assemble(p, s.state.sigs.mapValues { IHashes.hexToBytes(it.value) }).rawHex }
}

private class IosMember(override val name: String, val s: IPoolSession, val keys: List<Key>) : Member {
    override val phase get() = s.state.phase.name
    override val seats get() = s.state.seats.size
    override val txid get() = s.state.txid
    override val planTxid get() = s.plan()?.let { IPoolTx.txid(it) }
    override fun requestClose() = kotlinx.coroutines.runBlocking { s.requestClose() }
    override fun voteIfAsked() = kotlinx.coroutines.runBlocking {
        if (s.state.phase == IPoolSession.Phase.VOTING && s.state.voteBy != s.state.token?.let { IProtocol.tokenHash(it) }) s.vote(true)
    }
    override fun sign() = kotlinx.coroutines.runBlocking { s.sign(keys.map { it.secret }) }
    override fun stop() = s.dispose()
    override fun assembled() = s.plan()?.let { p -> IPoolTx.assemble(p, s.state.sigs.mapValues { IHashes.hexToBytes(it.value) }).rawHex }
}

private fun waitFor(what: String, sec: Int = 120, cond: () -> Boolean) {
    val until = System.currentTimeMillis() + sec * 1000L
    while (!cond()) { if (System.currentTimeMillis() > until) error("timed out: $what"); Thread.sleep(250) }
}

/** The pool's announcement as the Android app reads it from the relay. */
private fun androidTerms(id: String): AProtocol.Terms {
    val f = java.util.concurrent.CompletableFuture<AProtocol.Terms>()
    val r = ARelayClient(AProtocol.RELAY, { _, ev -> AProtocol.Terms.parse(ev)?.let { if (it.id == id) f.complete(it) } })
    r.connect()
    r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(AProtocol.KIND_POOL)).put("#d", JSONArray().put(id))))
    return try { f.get(30, java.util.concurrent.TimeUnit.SECONDS) } finally { r.close() }
}

/** The same, as this port reads it. */
private fun iosTerms(id: String): IProtocol.Terms = kotlinx.coroutines.runBlocking {
    val found = kotlinx.coroutines.CompletableDeferred<IProtocol.Terms>()
    val r = IRelayClient(IProtocol.RELAY, JvmWebSocket, { _, ev -> IProtocol.Terms.parse(ev)?.let { if (it.id == id) found.complete(it) } })
    r.connect()
    r.subscribe("p", listOf(kotlinx.serialization.json.buildJsonObject {
        put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(IProtocol.KIND_POOL))))
        put("#d", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(id))))
    }))
    try { kotlinx.coroutines.withTimeout(30_000) { found.await() } } finally { r.close() }
}

private fun androidJoin(name: String, t: AProtocol.Terms, values: List<Long>, outputs: Int): AndroidMember {
    val f = fund(values)
    val st = APoolSession.newState(t, false, null, f.map { ACoinjoinTx.Coin(it.txid, it.vout, it.value, it.key.pub, AScriptType.P2WPKH) },
        f.map { it.key.big }, f.map { "m/$name" }, List(outputs) { newKey().script }, newKey().script)
    return AndroidMember(name, APoolSession(AEnv(name), st), f.map { it.key }).also { it.s.start() }
}

private fun iosJoin(name: String, t: IProtocol.Terms, values: List<Long>, outputs: Int): IosMember {
    val f = fund(values)
    val st = IPoolSession.newState(t, false, null, f.map { IPoolTx.Coin(it.txid, it.vout, it.value, it.key.pub) },
        f.map { it.key.secret }, f.map { "m/$name" }, List(outputs) { newKey().script }, newKey().script)
    return IosMember(name, IPoolSession(IEnv(name), st, JvmWebSocket), f.map { it.key }).also { it.s.start() }
}

private fun runRound(title: String, creator: Member, joiners: List<Member>) {
    val all = listOf(creator) + joiners
    try {
        waitFor("$title: everyone seated") { all.all { it.phase == "OPEN" && it.seats == all.size } }
        joiners.first().requestClose()
        waitFor("$title: vote") { all.all { it.phase == "VOTING" } }
        all.forEach { it.voteIfAsked() }
        waitFor("$title: signing", 180) { all.all { it.phase == "SIGNING" } }
        val txids = all.map { it.planTxid }
        check(txids.toSet().size == 1 && txids[0] != null) { "$title: the members built different transactions: $txids" }
        all.forEach { it.sign() }
        waitFor("$title: broadcast") { all.all { it.txid != null } }
        val txid = all.first().txid!!
        check(all.all { it.txid == txid } && txid == txids[0]) { "$title: different txids" }
        val raw = rpc("getrawtransaction", txid) as String
        all.forEach { m -> check(m.assembled() == raw) { "$title: ${m.name} assembles another transaction than the node's" } }
        mine()
        waitFor("$title: confirmed", 90) { all.all { it.phase == "CONFIRMED" } }
        val tx = rpc("getrawtransaction", txid, true) as JSONObject
        println("$title: OK · ${all.size} people (${all.joinToString { it.name }}) · ${tx.getJSONArray("vin").length()} inputs · " +
            "${tx.getJSONArray("vout").length()} outputs · ${tx.getInt("vsize")} vB · byte-identical on both sides")
    } finally { all.forEach { runCatching { it.stop() } } }
}

fun main() {
    val net = "blake2b-regtest-interop-" + hex(ByteArray(3).also { rnd.nextBytes(it) })
    AProtocol.NETWORK = net; IProtocol.NETWORK = net
    println("network $net, node ${rpcUrl.substringAfter('@')}, relay ${IProtocol.RELAY}")
    val which = System.getenv("KW_ROUNDS")?.split(',')?.toSet()

    if (which == null || "v4a" in which) {
        // v4, opened by the Android app: two equal outputs each; this port joins with two coins.
        val np = APoolSession.newPool(20_000, 2.0, 5, 1, maxInputs = 2, v3 = true, outputs = 2)
        val f = fund(listOf(45_000))
        val a = AndroidMember("A", APoolSession(AEnv("A"), APoolSession.newState(np.terms, true, np.secret,
            f.map { ACoinjoinTx.Coin(it.txid, it.vout, it.value, it.key.pub, AScriptType.P2WPKH) }, f.map { it.key.big }, listOf("m/A"),
            List(2) { newKey().script }, newKey().script, null, np.creatorSecret, np.blindPriv)), f.map { it.key })
        a.s.start()
        waitFor("A open") { a.phase == "OPEN" }
        val t = iosTerms(np.terms.id)
        check(t.version == IProtocol.V4 && t.outputs == 2)
        val b = iosJoin("B", t, listOf(25_000, 20_000), 2)
        val c = androidJoin("C", androidTerms(np.terms.id), listOf(41_000), 2)
        runRound("v4 (Android pool, iOS joins)", a, listOf(b, c))
    }
    if (which == null || "v4i" in which) {
        // v4, opened by this port; the Android app joins.
        val np = IPoolSession.newPool(25_000, 2.0, 5, 1, outputs = 3)
        val f = fund(listOf(80_000))
        val a = IosMember("A", IPoolSession(IEnv("A"), IPoolSession.newState(np.terms, true, np.secret,
            f.map { IPoolTx.Coin(it.txid, it.vout, it.value, it.key.pub) }, f.map { it.key.secret }, listOf("m/A"),
            List(3) { newKey().script }, newKey().script, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv), JvmWebSocket), f.map { it.key })
        a.s.start()
        waitFor("A open") { a.phase == "OPEN" }
        val t = androidTerms(np.terms.id)
        check(t.version == AProtocol.V4 && t.outputs == 3)
        val b = androidJoin("B", t, listOf(ACoinjoinTx.minimumCoin(25_000, 2.0, AScriptType.P2WPKH, 3)), 3)
        runRound("v4 (iOS pool, Android joins)", a, listOf(b))
    }
    if (which == null || "v5i" in which) {
        // v5, a payment from this port: three recipients of different amounts; Android and iOS join.
        val pays = listOf(30_000L, 45_000L, 125_000L)
        val (d, k) = IProtocol.v5Split(pays.sum(), 2)
        val np = IPoolSession.newPool(d, 2.0, 5, 1, outputs = k, payments = pays)
        val f = fund(listOf(250_000))
        val recipients = pays.map { newKey().script }
        val a = IosMember("A", IPoolSession(IEnv("A"), IPoolSession.newState(np.terms, true, np.secret,
            f.map { IPoolTx.Coin(it.txid, it.vout, it.value, it.key.pub) }, f.map { it.key.secret }, listOf("m/A"),
            recipients, newKey().script, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv), JvmWebSocket), f.map { it.key })
        a.s.start()
        waitFor("A open") { a.phase == "OPEN" }
        val t = androidTerms(np.terms.id)
        check(t.version == AProtocol.V5 && t.payments == pays)
        val b = androidJoin("B", t, listOf(220_000), k)
        val c = iosJoin("C", iosTerms(np.terms.id), listOf(IPoolTx.minimumCoin(d, 2.0, outputs = k)), k)
        runRound("v5 (iOS pays 3 recipients, Android and iOS join)", a, listOf(b, c))
        val txid = a.txid!!
        val outs = (rpc("getrawtransaction", txid, true) as JSONObject).getJSONArray("vout")
        for ((i, s) in recipients.withIndex()) check((0 until outs.length()).any {
            outs.getJSONObject(it).getJSONObject("scriptPubKey").getString("hex") == hex(s) && Math.round(outs.getJSONObject(it).getDouble("value") * 1e8) == pays[i]
        }) { "recipient $i not paid" }
    }
    if (which == null || "v5a" in which) {
        // v5, a payment from the Android app; this port joins.
        val pays = listOf(7_500L, 12_500L)
        val np = APoolSession.newPool(20_000, 2.0, 5, 1, v3 = true, outputs = 1, payments = pays)
        val f = fund(listOf(30_000))
        val a = AndroidMember("A", APoolSession(AEnv("A"), APoolSession.newState(np.terms, true, np.secret,
            f.map { ACoinjoinTx.Coin(it.txid, it.vout, it.value, it.key.pub, AScriptType.P2WPKH) }, f.map { it.key.big }, listOf("m/A"),
            pays.map { newKey().script }, newKey().script, null, np.creatorSecret, np.blindPriv)), f.map { it.key })
        a.s.start()
        waitFor("A open") { a.phase == "OPEN" }
        val t = iosTerms(np.terms.id)
        check(t.version == IProtocol.V5 && t.payments == pays)
        val b = iosJoin("B", t, listOf(23_000), 1)
        runRound("v5 (Android pays 2 recipients, iOS joins)", a, listOf(b))
    }
    println("all interop rounds passed")
    System.exit(0)
}
