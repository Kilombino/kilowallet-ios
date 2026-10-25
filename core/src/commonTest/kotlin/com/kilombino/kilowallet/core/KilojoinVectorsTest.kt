package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.kilojoin.BigNat
import com.kilombino.kilowallet.core.kilojoin.BlindRsa
import com.kilombino.kilowallet.core.kilojoin.Nip44
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolPayment
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.PoolType
import com.kilombino.kilowallet.core.kilojoin.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kilojoin, byte for byte as the Android app (1.3.0) does it: every vector was produced by the
 * app's own pool code ([KilojoinVectors.COMMIT], tools/android-vectors). Blind signatures, nostr
 * events and NIP-44 payloads, pool terms (which announcements are listed and as what), the v5
 * split, the per-person minimum, fee shares, and whole signed rounds (v3, v4, v5) whose raw
 * transactions must come out identical.
 */
class KilojoinVectorsTest {
    private val root = Json.parseToJsonElement(KilojoinVectors.JSON).jsonObject
    private fun hex(s: String) = Hashes.hexToBytes(s)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.boolean
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }

    @Test fun blindSignaturesAsTheAndroidApp() {
        val kp = BlindRsa.KeyPair.decode(root.s("blindKey"))
        assertEquals(root.s("blindPublic"), kp.public.encode())
        assertEquals(root.s("blindKey"), kp.encodePrivate())
        assertEquals(2048, kp.public.modBits)
        for (v in root.list("blind")) {
            val prepared = hex(v.s("prepared"))
            val r = BigNat.fromHex(v.s("r"))
            val b = BlindRsa.blind(kp.public, prepared, salt = hex(v.s("salt")), r = r)
            assertEquals(v.s("blinded"), b.blindedMsg.toHex())
            assertEquals(v.s("inv"), b.inv.toHex())
            val bs = BlindRsa.blindSign(kp, b.blindedMsg)
            assertEquals(v.s("blindSig"), bs.toHex())
            assertEquals(v.s("sig"), BlindRsa.finalize(kp.public, prepared, bs, b.inv).toHex())
            assertTrue(v.b("verifies") && BlindRsa.verify(kp.public, prepared, hex(v.s("sig"))))
            assertTrue(!BlindRsa.verify(kp.public, prepared + byteArrayOf(0), hex(v.s("sig"))))
        }
    }

    @Test fun nostrEventsAndNip44AsTheAndroidApp() {
        for (v in root.list("nostr")) {
            val tags = v["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            val ev = NostrEvent.sign(hex(v.s("secret")), Protocol.KIND_MSG, tags, v.s("content"), v.l("createdAt"), hex(v.s("aux")))
            assertEquals(v.s("pubkey"), ev.pubkey)
            assertEquals(v.s("id"), ev.id)
            assertEquals(v.s("sig"), ev.sig)
            assertTrue(ev.verify())
            assertTrue(NostrEvent.parse(Json.parseToJsonElement(ev.toJson().toString()).jsonObject).verify())
            assertTrue(!ev.copy(content = ev.content + " ").verify())
        }
        for (v in root.list("nip44")) {
            val conv = Nip44.conversationKey(hex(v.s("a")), hex(v.s("bPub")))
            assertEquals(v.s("conv"), conv.toHex())
            assertEquals(v.s("payload"), Nip44.encrypt(v.s("plain"), conv, hex(v.s("nonce"))))
            assertEquals(v.s("plain"), Nip44.decrypt(v.s("payload"), conv))
        }
    }

    @Test fun poolTermsAreListedAsTheAndroidAppListsThem() {
        Protocol.NETWORK = "blake2b"
        val terms = root.list("terms")
        assertEquals(20, terms.size)
        for (v in terms) {
            val ev = NostrEvent.parse(v["event"]!!.jsonObject)
            assertTrue(ev.verify())
            val t = Protocol.Terms.parse(ev)
            val want = v["parsed"]
            if (want == null || want is JsonNull) { assertNull(t, ev.content); continue }
            val w = want.jsonObject
            assertNotNull(t, ev.content)
            assertEquals(w.s("id"), t.id); assertEquals(w.i("v"), t.version); assertEquals(w.l("amount"), t.amount)
            assertEquals(w.d("feeRate"), t.feeRate); assertEquals(w.i("outputs"), t.outputs)
            assertEquals(w.s("script"), Protocol.scriptName(t.script)); assertEquals(w.i("maxInputs"), t.maxInputs)
            assertEquals(w["payments"]!!.jsonArray.map { it.jsonPrimitive.long }, t.payments)
            assertEquals(w.l("mixTotal"), t.mixTotal)
            assertEquals(w.b("isTest"), Protocol.isTest(t), "the per-person minimum: ${t.amount} × ${t.outputs}")
            assertEquals(w.i("minPeers"), t.minPeers); assertEquals(w.i("maxPeers"), t.maxPeers); assertEquals(w.b("private"), t.private)
            assertEquals(w.s("creator"), t.creatorPub ?: ""); assertEquals(w.s("blindKey"), t.blindKey ?: "")
            // What this app announces for the same terms reads back the same.
            val again = Protocol.Terms.fromJson(t.toJson(), t.createdAt)
            assertEquals(t, again)
        }
    }

    @Test fun theV5SplitAndThePerPersonMinimum() {
        for (v in root.list("v5split")) {
            assertEquals(v.s("result"), Protocol.v5Split(v.l("total"), v.i("wanted")).let { "${it.first}x${it.second}" })
            assertEquals(v.s("resultMin"), Protocol.v5Split(v.l("total"), v.i("wanted"), Protocol.MIN_AMOUNT).let { "${it.first}x${it.second}" })
        }
        for (v in root.list("paymentsOk"))
            assertEquals(v.b("ok"), Protocol.paymentsOk(v["payments"]!!.jsonArray.map { it.jsonPrimitive.long }, v.l("amount"), v.i("outputs")))
        for (v in root.list("payment")) {
            val rs = v["recipients"]!!.jsonArray.map { r -> r.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.long } }
            val c = PoolPayment.check(rs)
            val what = rs.toString()
            assertEquals(v.b("ok"), c.ok, what); assertEquals(v.b("paymentOk"), c.paymentOk, what)
            assertEquals(v.l("amount"), c.amount, what)
            assertEquals(v.s("type"), c.type?.let { Protocol.scriptName(it) } ?: "", what)
            assertEquals(v.b("reasonNull"), c.reason == null, what)
        }
        assertEquals(10_000L, Protocol.MIN_AMOUNT); assertEquals(1_000L, Protocol.TEST_MIN_AMOUNT)
    }

    @Test fun feeSharesAndChange() {
        for (v in root.list("fees")) {
            val rate = v.d("rate"); val n = v.i("inputs"); val o = v.i("outputs")
            assertEquals(v.l("with"), PoolTx.feeShare(rate, true, n, PoolType.P2WPKH, o))
            assertEquals(v.l("without"), PoolTx.feeShare(rate, false, n, PoolType.P2WPKH, o))
            assertEquals(v.l("trFee"), PoolTx.feeShare(rate, true, n, PoolType.P2TR, o))
            assertEquals(v.l("minCoin"), PoolTx.minimumCoin(10_000, rate, PoolType.P2WPKH, o))
            assertEquals(v.l("noChange"), PoolTx.noChangeAmount(v.l("inputValue"), n, rate, PoolType.P2WPKH, o) ?: -1)
            assertEquals(v.l("change"), PoolTx.change(v.l("inputValue"), 10_000, rate, n, PoolType.P2WPKH, o) ?: -1)
            assertEquals(v.l("changeOf"), PoolTx.changeOf(v.l("inputValue"), 59_000, rate, n, PoolType.P2WPKH, o) ?: -1)
        }
    }

    @Test fun messagesProofsAndMacs() {
        for (v in root.list("messages")) {
            val k = hex(v.s("key")); val pool = v.s("poolId"); val join = v.s("joinPub")
            val coins = v.list("coins").map { PoolTx.Coin(it.s("txid"), it.i("vout"), it.l("value"), Ec.publicKey(k)) }
            val cs = v.s("changeScript").ifEmpty { null }?.let { hex(it) }
            val msg = Protocol.changeMessage(pool, coins, cs, v.l("changeValue"))
            assertEquals(v.s("changeMessage"), msg.toHex())
            assertEquals(v.s("changeSig"), Ec.signDer(k, msg).toHex())
            assertEquals(v["proofs"]!!.jsonArray.map { it.jsonPrimitive.content }, coins.map { PoolTx.proveOwnership(pool, join, it, k).toHex() })
            coins.forEachIndexed { i, c -> assertTrue(PoolTx.checkOwnership(pool, join, c, hex(v["proofs"]!!.jsonArray[i].jsonPrimitive.content))) }
            assertTrue(!PoolTx.checkOwnership(pool + "x", join, coins[0], PoolTx.proveOwnership(pool, join, coins[0], k)))
            val tok = v.s("token")
            assertEquals(v.s("tokenHash"), Protocol.tokenHash(tok))
            val idx = pool.last().digitToInt()
            assertEquals(v.s("macVote"), Protocol.mac(tok, "vote", "v$idx", idx % 2 == 0))
            assertEquals(v.s("macClose"), Protocol.mac(tok, "close", "v$idx"))
            assertEquals(v.s("macLeave"), Protocol.mac(tok, "leave"))
            assertEquals(v.s("macBlind"), Protocol.mac(tok, "blind", "r$idx", "d".repeat(64)))
            assertTrue(Protocol.macOk(tok, v.s("macLeave"), "leave") && !Protocol.macOk(tok, v.s("macLeave"), "vote"))
            assertEquals(v.s("pwKey"), Protocol.passwordKey(pool, "secret $idx ñ").toHex())
            assertEquals(v.s("pwProof"), Protocol.passwordProof(Protocol.passwordKey(pool, "secret $idx ñ"), join))
        }
    }

    @Test fun wholeRoundsComeOutByteIdentical() {
        val rounds = root.list("rounds")
        assertEquals(listOf("v3", "v4", "v5", "v5one"), rounds.map { it.s("name") })
        for (r in rounds) {
            val amount = r.l("amount"); val rate = r.d("rate"); val per = r.i("perPerson")
            val payments = r["payments"]!!.jsonArray.map { it.jsonPrimitive.long }
            val v5 = payments.isNotEmpty()
            class Seat(val coins: List<PoolTx.Coin>, val keys: List<ByteArray>, val change: TxBuilder.Output?, val mine: List<TxBuilder.Output>,
                       val check: String, val tamperedFails: Boolean)
            val seats = r.list("seats").map { s ->
                val keys = s.list("coins").map { hex(it.s("key")) }
                Seat(s.list("coins").mapIndexed { i, c -> PoolTx.Coin(c.s("txid"), c.i("vout"), c.l("value"), Ec.publicKey(keys[i])) }, keys,
                    (s["change"] as? JsonObject)?.let { TxBuilder.Output(hex(it.s("script")), it.l("value")) },
                    s.list("mine").map { TxBuilder.Output(hex(it.s("script")), it.l("value")) }, s.s("check"), s.b("tamperedFails"))
            }
            // Each seat's change as this app works it out from its coins.
            seats.forEachIndexed { si, s ->
                val outs = if (v5 && si == 0) payments.size else per
                val total = if (v5 && si == 0) payments.sum() else amount * per
                assertEquals(s.change?.value ?: 0L, PoolTx.changeOf(s.coins.sumOf { it.value }, total, rate, s.coins.size, PoolType.P2WPKH, outs))
            }
            val joiners = if (v5) seats.drop(1) else seats
            val plan = PoolTx.plan(seats.flatMap { it.coins }, joiners.flatMap { s -> s.mine.map { it.scriptPubKey } }, seats.mapNotNull { it.change },
                amount, per, if (v5) seats[0].mine else emptyList())
            val name = r.s("name")
            assertEquals(r.list("outputs").map { it.s("script") + ":" + it.l("value") }, plan.outputs.map { it.scriptPubKey.toHex() + ":" + it.value }, name)
            assertEquals(r["inputs"]!!.jsonArray.map { it.jsonPrimitive.content }, plan.coins.map { it.outpoint }, name)
            assertEquals(r.l("fee"), plan.fee, name)
            assertEquals(r.s("txid"), PoolTx.txid(plan), name)
            val sigs = HashMap<String, ByteArray>()
            seats.forEach { s -> s.coins.forEachIndexed { i, c -> sigs[c.outpoint] = PoolTx.sign(plan, c, s.keys[i]) } }
            val want = r["sigs"]!!.jsonObject
            for ((op, sig) in sigs) {
                assertEquals(want[op]!!.jsonPrimitive.content, sig.toHex(), "$name $op: low-R ground as Android")
                assertTrue(PoolTx.verify(plan, plan.coins.first { it.outpoint == op }, sig))
            }
            val signed = PoolTx.assemble(plan, sigs)
            assertEquals(r.s("raw"), signed.rawHex, name)
            assertEquals(r.s("signedTxid"), signed.txid)
            assertEquals(signed.txid, TxParse.txid(TxParse.parse(signed.rawHex)))
            // Every member's own check before signing, and the same check on a plan with one output changed.
            val seatInputs = seats.map { it.coins.size }
            val tampered = PoolTx.Plan(plan.coins, plan.outputs.mapIndexed { i, o -> if (i == plan.outputs.size - 1) TxBuilder.Output(o.scriptPubKey, o.value - 1) else o })
            fun check(p: PoolTx.Plan, s: Seat): String? =
                if (!v5) PoolTx.checkOurs(p, s.mine.map { it.scriptPubKey }, s.change, amount, rate, seatInputs, per)
                else PoolTx.checkPayment(p, s.mine, s.change, amount, rate, seatInputs, seats.indices.map { if (it == 0) payments.size else per },
                    per, payments, seats.count { it.change != null })
            for (s in seats) {
                assertEquals(s.check, check(plan, s) ?: "", name)
                assertEquals(s.tamperedFails, check(tampered, s) != null, name)
            }
            // A signature of another round does not verify in this one.
            val other = PoolTx.Plan(plan.coins, plan.outputs.dropLast(1) + TxBuilder.Output(plan.outputs.last().scriptPubKey, plan.outputs.last().value - 1))
            assertTrue(!PoolTx.verify(other, plan.coins[0], sigs[plan.coins[0].outpoint]!!))
        }
    }
}
