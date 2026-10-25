import com.kilombino.pyblockwatch.coinjoin.BlindRsa
import com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.Nip44
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.NativeSecp
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Kilojoin vectors from the Android app's own coinjoin code (Protocol, CoinjoinTx, BlindRsa,
 * NostrEvent, Nip44, CoinjoinPayment), unchanged, over fixed inputs: blind signing, pool terms,
 * the v5 split, the per-person minimum, fee shares, whole signed rounds (v3, v4, v5) and the
 * messages' MACs and proofs. Written as JSON for the iOS port's commonTest.
 */
private fun det(tag: String, n: Int = 32) = Hashes.sha256(tag.toByteArray()).copyOf(n)
private fun key(tag: String) = BigInteger(1, det("kj-key-$tag")).mod(Secp256k1.N)
private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
private fun wpkh(tag: String) = Address.scriptPubKey(pub(key(tag)), ScriptType.P2WPKH)
private fun b32(k: BigInteger) = NativeSecp.b32(k).toHex()

fun kilojoinVectors(): JSONObject {
    Protocol.NETWORK = "blake2b"
    val out = JSONObject()

    // ---- blind signatures (RFC 9474) with a fixed 2048-bit key
    val rnd = SecureRandom.getInstance("SHA1PRNG").apply { setSeed("kilowallet-ios-blind".toByteArray()) }
    val kp = BlindRsa.generate(2048, rnd)
    val blind = JSONArray()
    for (i in 0 until 4) {
        val msg = wpkh("blind-$i")
        val prepared = det("prefix-$i") + msg
        val salt = det("salt-$i", 32) + det("salt2-$i", 16)
        val r = BigInteger(1, det("r-$i") + det("r2-$i") + det("r3-$i") + det("r4-$i") + det("r5-$i") + det("r6-$i") + det("r7-$i") + det("r8-$i"))
            .mod(kp.public.n)
        val b = BlindRsa.blind(kp.public, prepared, salt = salt, r = r)
        val bs = BlindRsa.blindSign(kp, b.blindedMsg)
        val sig = BlindRsa.finalize(kp.public, prepared, bs, b.inv)
        blind.put(JSONObject().put("prepared", prepared.toHex()).put("salt", salt.toHex()).put("r", r.toString(16))
            .put("blinded", b.blindedMsg.toHex()).put("inv", b.inv.toString(16)).put("blindSig", bs.toHex()).put("sig", sig.toHex())
            .put("verifies", BlindRsa.verify(kp.public, prepared, sig)))
    }
    out.put("blindKey", kp.encodePrivate()).put("blindPublic", kp.public.encode()).put("blind", blind)

    // ---- nostr events (BIP-340 with fixed aux) and NIP-44
    val nostr = JSONArray()
    val contents = listOf("hello", "{\"type\":\"join\",\"x\":\"a/b\"}", "line\nbreak \"quoted\" back\\slash \t tab", "ñ 中文 ✓", "\u0001ctl")
    contents.forEachIndexed { i, c ->
        val k = key("nostr-$i")
        val tags = listOf(listOf("p", NostrEvent.pubOf(key("p-$i"))), listOf("d", "pool$i"), listOf("t", Protocol.TAG))
        val at = 1_790_000_000L + i
        val h = NostrEvent.hash(NostrEvent.pubOf(k), at, Protocol.KIND_MSG, tags, c)
        val aux = det("aux-$i")
        val sig = Schnorr.sign(k, h, aux)
        nostr.put(JSONObject().put("secret", b32(k)).put("createdAt", at).put("content", c).put("tags", JSONArray(tags.map { JSONArray(it) }))
            .put("aux", aux.toHex()).put("pubkey", NostrEvent.pubOf(k)).put("id", h.toHex()).put("sig", sig.toHex()))
    }
    out.put("nostr", nostr)
    val nip44 = JSONArray()
    for (i in 0 until 4) {
        val a = key("nip-a-$i"); val b = key("nip-b-$i")
        val conv = Nip44.conversationKey(a, Hashes.hexToBytes(NostrEvent.pubOf(b)))
        val plain = listOf("x", "a".repeat(33), "{\"type\":\"sig\",\"outpoint\":\"" + "ab".repeat(32) + ":1\"}", "z".repeat(300))[i]
        val nonce = det("nonce-$i")
        val payload = Nip44.encrypt(plain, conv, nonce)
        nip44.put(JSONObject().put("a", b32(a)).put("bPub", NostrEvent.pubOf(b)).put("conv", conv.toHex()).put("plain", plain)
            .put("nonce", nonce.toHex()).put("payload", payload))
    }
    out.put("nip44", nip44)

    // ---- pool terms: Android's own announcements, valid and not
    val terms = JSONArray()
    fun announce(t: Protocol.Terms, poolKey: BigInteger, edit: (JSONObject) -> Unit = {}, dTag: String = t.id) {
        val o = t.toJson().also(edit)
        val tags = listOf(listOf("d", dTag), listOf("t", Protocol.TAG), listOf("network", Protocol.NETWORK))
        val h = NostrEvent.hash(NostrEvent.pubOf(poolKey), 1_790_000_100L, Protocol.KIND_POOL, tags, o.toString())
        val ev = NostrEvent(h.toHex(), NostrEvent.pubOf(poolKey), 1_790_000_100L, Protocol.KIND_POOL, tags, o.toString(),
            Schnorr.sign(poolKey, h, det("terms-aux")).toHex())
        val p = Protocol.Terms.parse(ev)
        terms.put(JSONObject().put("event", ev.toJson()).put("parsed", if (p == null) JSONObject.NULL else JSONObject()
            .put("id", p.id).put("v", p.version).put("amount", p.amount).put("feeRate", p.feeRate).put("outputs", p.outputs)
            .put("script", Protocol.scriptName(p.script)).put("maxInputs", p.maxInputs).put("payments", JSONArray(p.payments))
            .put("mixTotal", p.mixTotal).put("isTest", Protocol.isTest(p)).put("minPeers", p.minPeers).put("maxPeers", p.maxPeers)
            .put("private", p.private).put("creator", p.creatorPub ?: "").put("blindKey", p.blindKey ?: "")))
    }
    val pk = key("pool"); val ck = key("creator")
    val base = Protocol.Terms("00112233aabbccdd", NostrEvent.pubOf(pk), 50_000, 2.0, 5, 1_790_100_000L, "open", 1, 0, 2, false,
        Protocol.V3, ScriptType.P2WPKH, 1, NostrEvent.pubOf(ck), kp.public.encode())
    announce(base, pk)
    announce(base.copy(version = Protocol.V1, creatorPub = null, blindKey = null), pk)
    announce(base.copy(version = Protocol.VERSION, script = ScriptType.P2TR, maxInputs = 3, creatorPub = null, blindKey = null), pk)
    announce(base.copy(version = Protocol.V4, amount = 20_000, outputs = 2, maxInputs = 4, private = true, feeRate = 3.5), pk)
    announce(base.copy(version = Protocol.V5, amount = 100_000, outputs = 2, payments = listOf(30_000L, 45_000L, 125_000L)), pk)
    announce(base.copy(version = Protocol.V5, amount = 1_000, outputs = 5, payments = listOf(2_000L, 3_000L)), pk)          // a test pool
    announce(base.copy(version = Protocol.V4, amount = 3_000, outputs = 3), pk)                                                 // 9 000 in all: test
    announce(base.copy(version = Protocol.V4, amount = 5_000, outputs = 2), pk)                                                 // 10 000: real
    announce(base.copy(amount = 9_999), pk)                                                                                     // test
    announce(base, pk, { it.put("network", "blake2b-regtest") })
    announce(base, pk, { it.put("payments", JSONArray(listOf(1L))) }, dTag = "other")
    announce(base.copy(version = Protocol.V5, amount = 100_000, outputs = 2, payments = listOf(30_000L, 45_000L, 125_001L)), pk)
    announce(base.copy(version = Protocol.V5, amount = 1_000, outputs = 2, payments = listOf(200L, 1_800L)), pk)
    announce(base.copy(version = Protocol.V4, outputs = 1), pk)
    announce(base.copy(minPeers = 6), pk)
    announce(base.copy(amount = 999), pk)
    announce(base.copy(feeRate = 0.5), pk)
    announce(base.copy(creatorPub = NostrEvent.pubOf(pk)), pk)
    announce(base, ck)                                                                                                          // signed by another key
    announce(base, pk, { it.put("v", 6) })
    out.put("terms", terms)

    // ---- the v5 split, payments and the per-person minimum
    val split = JSONArray()
    for ((total, wanted) in listOf(200_000L to 2, 200_000L to 3, 200_000L to 5, 9_000L to 3, 4_000L to 5, 10_000L to 1, 1_500L to 2, 5_000L to 5))
        split.put(JSONObject().put("total", total).put("wanted", wanted).put("result", Protocol.v5Split(total, wanted).let { "${it.first}x${it.second}" })
            .put("resultMin", Protocol.v5Split(total, wanted, Protocol.MIN_AMOUNT).let { "${it.first}x${it.second}" }))
    out.put("v5split", split)
    val paysOk = JSONArray()
    for ((p, ao) in listOf(listOf(30_000L, 45_000L, 125_000L) to (100_000L to 2), listOf(330L, 9_670L) to (10_000L to 1),
            listOf(329L, 9_671L) to (10_000L to 1), listOf(1_000L) to (1_000L to 1), List(6) { 1_000L } to (3_000L to 2),
            emptyList<Long>() to (1_000L to 1), listOf(5_000L, 5_000L) to (5_000L to 2), listOf(5_000L) to (1_000L to 6)))
        paysOk.put(JSONObject().put("payments", JSONArray(p)).put("amount", ao.first).put("outputs", ao.second)
            .put("ok", Protocol.paymentsOk(p, ao.first, ao.second)))
    out.put("paymentsOk", paysOk)
    val pay = JSONArray()
    val a1 = Address.encode(pub(key("rcpt1")), ScriptType.P2WPKH); val a2 = Address.encode(pub(key("rcpt2")), ScriptType.P2WPKH)
    val a3 = Address.encode(pub(key("rcpt3")), ScriptType.P2WPKH)
    val tr = "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297"
    for (rs in listOf(listOf(a1 to 10_000L), listOf(a1 to 9_999L), listOf(a1 to 5_000L, a2 to 5_000L), listOf(a1 to 4_000L, a2 to 4_000L),
            listOf(a1 to 3_000L, a2 to 3_000L, a3 to 3_000L), listOf(a1 to 30_000L, a2 to 45_000L, a3 to 125_000L), listOf(a1 to 329L, a2 to 20_000L),
            listOf(a1 to 330L, a2 to 9_670L), listOf(a1 to 1_000L, a1 to 9_000L), List(6) { (if (it % 2 == 0) a1 else a2) to 10_000L },
            listOf(a1 to 10_000L, tr to 10_000L), listOf("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2" to 20_000L), listOf("x@y.z" to 20_000L),
            listOf(a1 to 100_000_001L), listOf(a1 to 50_000_000L, a2 to 50_000_000L), listOf(a1 to 50_000_000L, a2 to 50_000_001L))) {
        val c = CoinjoinPayment.check(rs, taprootOk = false)
        pay.put(JSONObject().put("recipients", JSONArray(rs.map { JSONArray().put(it.first).put(it.second) }))
            .put("ok", c.ok).put("paymentOk", c.paymentOk).put("amount", c.amount).put("type", c.type?.let { Protocol.scriptName(it) } ?: "")
            .put("reasonNull", c.reason == null))
    }
    out.put("payment", pay)

    // ---- fees, change and the no-change amount
    val fees = JSONArray()
    for (rate in listOf(1.0, 2.0, 3.7, 12.5)) for (inputs in 1..3) for (outs in listOf(1, 2, 5)) {
        val type = ScriptType.P2WPKH
        val iv = 60_000L + inputs * 1_111L
        fees.put(JSONObject().put("rate", rate).put("inputs", inputs).put("outputs", outs)
            .put("with", CoinjoinTx.feeShare(rate, true, inputs, type, outs)).put("without", CoinjoinTx.feeShare(rate, false, inputs, type, outs))
            .put("trFee", CoinjoinTx.feeShare(rate, true, inputs, ScriptType.P2TR, outs))
            .put("minCoin", CoinjoinTx.minimumCoin(10_000, rate, type, outs))
            .put("noChange", CoinjoinTx.noChangeAmount(iv, inputs, rate, type, outs) ?: -1)
            .put("inputValue", iv).put("change", CoinjoinTx.change(iv, 10_000, rate, inputs, type, outs) ?: -1)
            .put("changeOf", CoinjoinTx.changeOf(iv, 59_000, rate, inputs, type, outs) ?: -1))
    }
    out.put("fees", fees)

    // ---- messages: MACs, tokens, passwords, change and ownership proofs
    val msgs = JSONArray()
    for (i in 0 until 3) {
        val k = key("own-$i")
        val coins = (0..i).map { j -> CoinjoinTx.Coin(det("own-tx-$i-$j").toHex(), j, 70_000L + j, pub(k), ScriptType.P2WPKH) }
        val join = NostrEvent.pubOf(key("join-$i"))
        val cs = if (i == 1) null else wpkh("change-$i")
        msgs.put(JSONObject().put("poolId", "pool$i").put("key", b32(k)).put("joinPub", join)
            .put("coins", JSONArray(coins.map { JSONObject().put("txid", it.txid).put("vout", it.vout).put("value", it.value) }))
            .put("changeScript", cs?.toHex() ?: "").put("changeValue", if (cs == null) 0 else 12_345L + i)
            .put("changeMessage", Protocol.changeMessage("pool$i", coins, cs, if (cs == null) 0 else 12_345L + i).toHex())
            .put("changeSig", Ecdsa.der(Ecdsa.sign(k, Protocol.changeMessage("pool$i", coins, cs, if (cs == null) 0 else 12_345L + i))).toHex())
            .put("proofs", JSONArray(coins.map { CoinjoinTx.proveOwnership("pool$i", join, it, k).toHex() }))
            .put("token", "tok$i").put("tokenHash", Protocol.tokenHash("tok$i"))
            .put("macVote", Protocol.mac("tok$i", "vote", "v$i", i % 2 == 0)).put("macClose", Protocol.mac("tok$i", "close", "v$i"))
            .put("macLeave", Protocol.mac("tok$i", "leave")).put("macBlind", Protocol.mac("tok$i", "blind", "r$i", "d".repeat(64)))
            .put("pwKey", Protocol.passwordKey("pool$i", "secret $i ñ").toHex())
            .put("pwProof", Protocol.passwordProof(Protocol.passwordKey("pool$i", "secret $i ñ"), join)))
    }
    out.put("messages", msgs)

    // ---- whole rounds, signed by the Android code: v3, v4 and v5
    out.put("rounds", JSONArray(listOf(round("v3", 50_000, 2.0, 1, emptyList(), listOf(listOf(60_000L), listOf(-1L), listOf(20_000L, 25_000L, 9_000L))),
        round("v4", 20_000, 3.0, 2, emptyList(), listOf(listOf(45_000L), listOf(30_000L, 30_000L), listOf(-1L), listOf(80_000L))),
        round("v5", 100_000, 1.0, 2, listOf(30_000L, 45_000L, 125_000L), listOf(listOf(250_000L), listOf(220_000L), listOf(-1L))),
        round("v5one", 20_000, 5.0, 1, listOf(7_500L, 12_500L), listOf(listOf(15_000L, 15_000L), listOf(-1L)))) ))
    return out
}

/**
 * One round as the Android wallets build it: each seat's coins ([values]; -1 = a coin of exactly
 * the pool's minimum, no change), its change and its equal outputs (in v5, seat 0 is the creator
 * and its outputs are [payments]); the canonical plan, every input signed, the raw transaction,
 * and each member's own check before signing (all pass), and a tampered plan's (it fails).
 */
private fun round(name: String, amount: Long, rate: Double, perPerson: Int, payments: List<Long>, values: List<List<Long>>): JSONObject {
    val v5 = payments.isNotEmpty()
    val type = ScriptType.P2WPKH
    data class Seat(val coins: List<CoinjoinTx.Coin>, val keys: List<BigInteger>, val change: TxBuilder.Output?, val mine: List<TxBuilder.Output>)
    val seats = values.mapIndexed { si, vs ->
        val creator = v5 && si == 0
        val outs = if (creator) payments.size else perPerson
        val total = if (creator) payments.sum() else amount * perPerson
        val keys = vs.indices.map { key("$name-coin-$si-$it") }
        val coins = vs.mapIndexed { ci, v ->
            val value = if (v < 0) total + CoinjoinTx.feeShare(rate, false, 1, type, outs) else v
            CoinjoinTx.Coin(det("$name-tx-$si-$ci").toHex(), ci, value, pub(keys[ci]), type)
        }
        val ch = CoinjoinTx.changeOf(coins.sumOf { it.value }, total, rate, coins.size, type, outs) ?: error("$name seat $si too small")
        val mine = if (creator) payments.mapIndexed { i, p -> TxBuilder.Output(wpkh("$name-pay-$i"), p) }
            else (0 until perPerson).map { TxBuilder.Output(wpkh("$name-mix-$si-$it"), amount) }
        Seat(coins, keys, if (ch > 0) TxBuilder.Output(wpkh("$name-change-$si"), ch) else null, mine)
    }
    val joiners = if (v5) seats.drop(1) else seats
    val plan = CoinjoinTx.plan(seats.flatMap { it.coins }, joiners.flatMap { s -> s.mine.map { it.scriptPubKey } }, seats.mapNotNull { it.change },
        amount, perPerson, if (v5) seats[0].mine else emptyList())
    val sigs = HashMap<String, ByteArray>()
    seats.forEach { s -> s.coins.forEachIndexed { i, c -> sigs[c.outpoint] = CoinjoinTx.sign(plan, c, s.keys[i]) } }
    seats.forEach { s -> s.coins.forEach { c -> require(CoinjoinTx.verify(plan, c, sigs[c.outpoint]!!)) } }
    val signed = CoinjoinTx.assemble(plan, sigs)
    val seatInputs = seats.map { it.coins.size }
    fun check(p: CoinjoinTx.Plan, s: Seat, si: Int): String? =
        if (!v5) CoinjoinTx.checkOurs(p, s.mine.map { it.scriptPubKey }, s.change, amount, rate, seatInputs, perPerson)
        else CoinjoinTx.checkPayment(p, s.mine, s.change, amount, rate, seatInputs, seats.indices.map { if (it == 0) payments.size else perPerson },
            perPerson, payments, seats.count { it.change != null })
    val tampered = CoinjoinTx.Plan(plan.coins, plan.outputs.mapIndexed { i, o -> if (i == plan.outputs.size - 1) TxBuilder.Output(o.scriptPubKey, o.value - 1) else o })
    return JSONObject().put("name", name).put("amount", amount).put("rate", rate).put("perPerson", perPerson).put("payments", JSONArray(payments))
        .put("seats", JSONArray(seats.mapIndexed { si, s -> JSONObject()
            .put("coins", JSONArray(s.coins.mapIndexed { i, c -> JSONObject().put("txid", c.txid).put("vout", c.vout).put("value", c.value).put("key", b32(s.keys[i])) }))
            .put("change", s.change?.let { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) } ?: JSONObject.NULL)
            .put("mine", JSONArray(s.mine.map { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) }))
            .put("check", check(plan, s, si) ?: "").put("tamperedFails", check(tampered, s, si) != null) }))
        .put("outputs", JSONArray(plan.outputs.map { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) }))
        .put("inputs", JSONArray(plan.coins.map { it.outpoint })).put("fee", plan.fee)
        .put("sigs", JSONObject(plan.coins.associate { it.outpoint to sigs[it.outpoint]!!.toHex() }))
        .put("txid", CoinjoinTx.txid(plan)).put("raw", signed.rawHex).put("signedTxid", signed.txid)
}
