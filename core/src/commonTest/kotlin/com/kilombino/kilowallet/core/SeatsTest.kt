package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.kilojoin.J
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.PoolType
import com.kilombino.kilowallet.core.kilojoin.Protocol
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Several seats of one phone in a pool (how they are kept, and older saved rounds) and own seats
 * counting as one person: the Android app's SeatsAndPerpetualTest (1.4.4), its seat part.
 */
class SeatsTest {
    private fun key(n: Long) = ByteArray(32).also { var v = n; for (i in 31 downTo 24) { it[i] = (v and 0xFF).toByte(); v = v shr 8 } }
    private fun coin(n: Long, value: Long, type: PoolType = PoolType.P2WPKH) =
        PoolTx.Coin(n.toString(16).padStart(64, '0'), 0, value, Ec.publicKey(key(n)), type)
    private fun script(n: Long, type: ScriptType = ScriptType.P2WPKH) = Address.scriptPubKey(Ec.publicKey(key(n)), type)

    @Test fun firstSeatKeepsThePoolIdAndAnotherGetsItsOwn() {
        val id = PoolSession.newPool(100_000, 2.0, 5, 1).terms.id
        assertEquals(id, PoolSession.State.keyOf(id, ""))
        assertEquals("$id#0a1b2c3d", PoolSession.State.keyOf(id, "0a1b2c3d"))
        assertEquals(id, PoolSession.State.poolOf("$id#0a1b2c3d"))
        assertEquals(id, PoolSession.State.poolOf(id))
    }

    @Test fun anotherSeatRoundTripsAndAnOldSavedRoundKeepsItsKey() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1)
        val a = PoolSession.newState(np.terms, false, null, listOf(coin(11, 150_000)), listOf(key(11)), listOf("a"),
            listOf(script(41)), script(31))
        val b = PoolSession.newState(np.terms, false, null, listOf(coin(12, 150_000)), listOf(key(12)), listOf("b"),
            listOf(script(42)), script(32), seatId = "0a1b2c3d", joinAfter = 1_234_567)
        assertEquals(np.terms.id, a.key)
        assertEquals(np.terms.id + "#0a1b2c3d", b.key)
        // Each seat its own join key: nothing ties the two join requests together.
        assertNotEquals(a.joinSecret, b.joinSecret)
        val back = PoolSession.State.parse(J.parse(b.toJson().toString()))
        assertEquals(b.key, back.key); assertEquals(1_234_567L, back.joinAfter)
        // A round saved before seats could be more than one has none of the new fields: the pool's first seat.
        val old = JsonObject(a.toJson().filterKeys { it != "seat_id" && it != "join_after" })
        val o = PoolSession.State.parse(old)
        assertEquals(np.terms.id, o.key); assertEquals(0L, o.joinAfter)
    }

    @Test fun theCreatorTakesAnotherSeatInItsPrivatePoolWithItsKey() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, private = true)
        val creator = PoolSession.newState(np.terms, true, np.secret, listOf(coin(21, 150_000)), listOf(key(21)), listOf("c"),
            listOf(script(51)), script(52), password = "secreto", creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        val seat = PoolSession.newState(np.terms, false, null, listOf(coin(22, 150_000)), listOf(key(22)), listOf("d"),
            listOf(script(53)), script(54), seatId = "11112222", passwordKey = Hashes.hexToBytes(creator.pwKey!!))
        // The same proof a member who typed the password would send.
        val joinPub = NostrEvent.pubOf(NostrEvent.secretOf(seat.joinSecret))
        assertEquals(Protocol.passwordProof(Protocol.passwordKey(np.terms.id, "secreto"), joinPub), seat.pwProof)
    }

    @Test fun ownSeatsCountAsOnePerson() {
        assertEquals(1, PoolSession.distinctPeople(listOf("c", "c2"), setOf("c", "c2")))
        assertEquals(2, PoolSession.distinctPeople(listOf("c", "c2", "x"), setOf("c", "c2")))
        assertEquals(2, PoolSession.distinctPeople(listOf("c", "m1", "m2"), setOf("m1", "m2")))
        assertEquals(3, PoolSession.distinctPeople(listOf("c", "m1", "m2", "y"), setOf("m1", "m2")))
        assertEquals(3, PoolSession.distinctPeople(listOf("c", "x", "y"), setOf("c")))
        assertEquals(3, PoolSession.distinctPeople(listOf("c", "x", "y"), setOf("gone", "c")))
        assertEquals(3, PoolSession.distinctPeople(listOf("a", "b", "z"), emptySet()))
        assertEquals(0, PoolSession.distinctPeople(emptyList(), setOf("c")))
        // The template the translations match (the Android app's).
        assertEquals("at least 2 different people are needed: your own seats count as one",
            PoolSession.OWN_SEATS_ONE.replace("%d", "2"))
    }

    @Test fun aTaprootSeatIsMadeAndItsCoinSignsByTheKeyPath() {
        val np = PoolSession.newPool(50_000, 2.0, 5, 1, script = PoolType.P2TR)
        assertEquals(PoolType.P2TR, np.terms.script)
        val c = coin(31, 80_000, PoolType.P2TR)
        val st = PoolSession.newState(np.terms, false, null, listOf(c), listOf(key(31)), listOf("m/86'/0'/0'/0/0"),
            listOf(script(61, ScriptType.P2TR)), script(62, ScriptType.P2TR))
        assertTrue(PoolTx.isType(st.seat.coin.script, PoolType.P2TR))
        assertEquals(Address.scriptPubKey(c.pubkey, ScriptType.P2TR).toHex(), c.script.toHex())
        val other = coin(32, -1L + 50_000 + PoolTx.feeShare(2.0, false, 1, PoolType.P2TR) + 1, PoolType.P2TR)
        val plan = PoolTx.plan(listOf(c, other), listOf(script(61, ScriptType.P2TR), script(63, ScriptType.P2TR)),
            listOfNotNull(st.seat.changeScript?.let { com.kilombino.kilowallet.core.crypto.TxBuilder.Output(it, st.seat.changeValue) }), 50_000)
        val sig = PoolTx.sign(plan, c, key(31))
        assertEquals(65, sig.size)
        assertTrue(PoolTx.verify(plan, c, sig))
        assertTrue(!PoolTx.verify(plan, other, sig))
    }
}
