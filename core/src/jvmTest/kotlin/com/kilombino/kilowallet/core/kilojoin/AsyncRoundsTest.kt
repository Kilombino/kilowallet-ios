package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.ScriptType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kilojoin v6 (asynchronous rounds): the announcement, its bounds, what a join binds, and what a round keeps
 * on disk (the Android app's `AsyncRoundsTest`, 1.5.2), plus the protocol's own v6 vectors (Kilojoin
 * docs/vectors.json, "v6"), so the wire is the one Android 1.5.2 and Kilojoin 0.10.2 speak.
 */
class AsyncRoundsTest {
    private fun key(n: Long) = ByteArray(32).also { b -> var x = n; for (i in 31 downTo 24) { b[i] = (x and 0xff).toByte(); x = x shr 8 } }
    private fun pub(n: Long) = Ec.publicKey(key(n))
    private fun script(n: Long) = Address.scriptPubKey(pub(n), ScriptType.P2WPKH)

    private fun announce(np: PoolSession.Companion.NewPool, edit: (JsonObject) -> JsonObject = { it }): Protocol.Terms? {
        val o = edit(np.terms.toJson())
        val ev = NostrEvent.sign(NostrEvent.secretOf(np.secret), Protocol.KIND_POOL, listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), o.toString())
        return Protocol.Terms.parse(ev)
    }

    private fun pool(outputs: Int = 1, payments: List<Long> = emptyList(), async: Boolean = true, asyncVote: Long? = null,
                     asyncOutputs: Long? = null, asyncSign: Long? = null) =
        PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, outputs = outputs, payments = payments, async = async,
            asyncVote = asyncVote, asyncOutputs = asyncOutputs, asyncSign = asyncSign)

    @Test fun anAsyncPoolIsV6WithItsWindows() {
        val np = pool()
        val t = announce(np)!!
        assertEquals(Protocol.V6, t.version); assertTrue(t.v6); assertFalse(t.v5, "no payments: not a payment pool")
        assertEquals(np.terms.async, t.async)
        assertEquals(3600L, t.voteSeconds); assertEquals(1800L, t.outputsSeconds); assertEquals(7200L, t.signSeconds)
        assertTrue(t.async!!.final > t.expiresAt)
        // Shorter windows, as the creator chooses them.
        assertEquals(900L, announce(pool(asyncVote = 900, asyncOutputs = 600, asyncSign = 1800))!!.voteSeconds)
        // v6 with several outputs each, and with payments (as v4 and v5).
        assertEquals(3, announce(pool(outputs = 3))!!.outputs)
        val pay = announce(pool(outputs = 2, payments = listOf(7_000L, 13_000L)))!!
        assertTrue(pay.v5 && pay.v6); assertEquals(listOf(7_000L, 13_000L), pay.payments)
    }

    @Test fun windowsOutOfBoundsAreNotListed() {
        val np = pool()
        fun with(k: String, v: Long) = announce(np) { o -> J.with(o, "async" to J.with(o.obj("async"), k to v)) }
        assertNull(with("vote", 3601)); assertNull(with("vote", 599))
        assertNull(with("outputs", 1801)); assertNull(with("outputs", 299))
        assertNull(with("sign", 7201)); assertNull(with("sign", 1199))
        assertNull(with("final", np.terms.expiresAt)); assertNull(with("final", np.terms.expiresAt + Protocol.ASYNC_TAIL_MAX + 1))
        assertNotNull(with("final", np.terms.expiresAt + Protocol.ASYNC_TAIL_MAX))
        assertNull(announce(np) { JsonObject(it - "async") }, "a v6 pool without its timing")
        assertNull(announce(np) { J.with(it, "payments" to listOf(5_000L)) }, "unbalanced payments")
        // And newPool refuses to make one.
        assertTrue(runCatching { pool(asyncSign = 60) }.isFailure)
        assertTrue(runCatching { PoolSession.newPool(10_000, 1.0, 5, 2, v3 = false, async = true) }.isFailure)
    }

    /** What older wallets see: `v` 6, a version none of them accepts (they take 1 to 5 only), so they don't list it. */
    @Test fun olderWalletsSeeAnUnknownVersion() {
        assertEquals("6", (pool().terms.toJson()["v"] as JsonPrimitive).content)
        // A v5 pool is announced exactly as before: no new field.
        val v5 = pool(outputs = 2, payments = listOf(7_000L, 13_000L), async = false).terms.toJson()
        assertEquals("5", (v5["v"] as JsonPrimitive).content); assertFalse("async" in v5)
        assertEquals(setOf("v", "app", "network", "id", "public_key", "denomination", "fee_rate", "min_peers", "max_peers", "private",
            "timeout", "state", "peers", "script", "max_inputs", "creator", "blind_key", "outputs", "payments"), v5.keys)
        assertEquals(3, pool(async = false).terms.version)
    }

    @Test fun aJoinBindsEveryTerm() {
        val np = pool()
        val t = np.terms
        val h = Protocol.termsHash(t)
        assertEquals(64, h.length); assertEquals(h, Protocol.termsHash(announce(np)!!))
        assertNotEquals(h, Protocol.termsHash(t.copy(async = t.async!!.copy(sign = 3600))))
        assertNotEquals(h, Protocol.termsHash(t.copy(async = t.async!!.copy(final = t.async!!.final + 1))))
        assertNotEquals(h, Protocol.termsHash(t.copy(feeRate = 1.001)))
        // State and peer counts change while the pool lives: they are not terms.
        assertEquals(h, Protocol.termsHash(t.copy(state = "closing", peers = 4)))
    }

    @Test fun aRoundKeepsItsV6StateOnDisk() {
        val np = pool()
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(PoolTx.Coin("ab".repeat(32), 1, 30_000, pub(5))),
            listOf(key(5)), listOf("m/0"), listOf(script(6)), script(7), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        st.voteSeq = 3; st.voteN = 2; st.voteEndedN = 1; st.voteAcked = "aa"; st.votesSent = 2; st.usedVotes += listOf("aa", "bb")
        st.revotes = 1; st.lastMsgs["roster"] = J.obj("at" to 5, "msg" to J.obj("type" to "roster")).toString()
        st.stateN = 7; st.signedAt = 1234; st.confs = 3
        val back = PoolSession.State.parse(J.parse(st.toJson().toString()))
        assertEquals(np.terms.async, back.terms.async); assertEquals(Protocol.V6, back.terms.version)
        assertEquals(listOf(3, 2, 1, 2, 1, 7, 3), listOf(back.voteSeq, back.voteN, back.voteEndedN, back.votesSent, back.revotes, back.stateN, back.confs))
        assertEquals("aa", back.voteAcked); assertEquals(setOf("aa", "bb"), back.usedVotes); assertEquals(1234L, back.signedAt)
        assertEquals(st.lastMsgs, back.lastMsgs)
        assertEquals(st.toJson(), back.toJson())
        // A v5 round's saved form has none of it.
        val v5 = pool(async = false)
        val s5 = PoolSession.newState(v5.terms, true, v5.secret, listOf(PoolTx.Coin("ab".repeat(32), 1, 30_000, pub(5))),
            listOf(key(5)), listOf("m/0"), listOf(script(6)), script(7), creatorSecret = v5.creatorSecret, blindPriv = v5.blindPriv)
        assertFalse("vote_n" in s5.toJson())
    }

    /**
     * The v6 vectors of Kilojoin's docs/vectors.json (made by Kilowallet 1.5.0's code): the announcement parses
     * here as v6 with its windows, its terms line and hash are byte for byte the same, and so is a `sync` MAC
     * (and v3's vote MAC, which v6 keeps).
     */
    @Test fun protocolVectorsV6() {
        val blindKey = "c5".repeat(256) + ":010001"
        val ann = J.obj("app" to "kilowallet", "public_key" to "489a926955893dc08c27b6452f366605f92e9c0801faf49aa4a280e77579bd19",
            "outputs" to 1, "max_peers" to 5, "private" to false, "creator" to "74527243860a52404d6f30c0ed9834ba2cebeeede0e3fd2f497abf3198ca5ec4",
            "min_peers" to 2, "payments" to JsonArray(emptyList()), "peers" to 1, "timeout" to 1791400000L, "script" to "p2wpkh",
            "network" to "blake2b", "denomination" to 10000, "fee_rate" to 2, "v" to 6, "blind_key" to blindKey,
            "async" to J.obj("outputs" to 1800, "sign" to 7200, "final" to 1791418000L, "vote" to 3600),
            "id" to "fedcba9876543210", "state" to "open", "max_inputs" to 1)
        // The vector's blind key is a placeholder, not an RSA key: parse the fields, then check the line and hash.
        val t = Protocol.Terms("fedcba9876543210", "489a926955893dc08c27b6452f366605f92e9c0801faf49aa4a280e77579bd19", 10000, 2.0, 5,
            1791400000L, "open", 1, 0, 2, false, Protocol.V6, PoolType.P2WPKH, 1,
            "74527243860a52404d6f30c0ed9834ba2cebeeede0e3fd2f497abf3198ca5ec4", blindKey, 1, emptyList(),
            Protocol.Async.parse(ann.obj("async")))
        assertEquals(Protocol.Async(3600, 1800, 7200, 1791418000L), t.async)
        assertTrue(t.async!!.valid(t.expiresAt))
        assertEquals("kilojoin/v6/terms|6|blake2b|fedcba9876543210|489a926955893dc08c27b6452f366605f92e9c0801faf49aa4a280e77579bd19|10000|2000|2|5|false|1791400000|p2wpkh|1|74527243860a52404d6f30c0ed9834ba2cebeeede0e3fd2f497abf3198ca5ec4|$blindKey|1||3600|1800|7200|1791418000",
            Protocol.termsLine(t))
        assertEquals("e320a1402a035530874e45452bd2209849f08ff68a38c5c72ae9d72832dd9f66", Protocol.termsHash(t))
        // The announcement's own fields, as a wallet reads them (its v and async object), give the same terms.
        assertEquals(J.parse(t.toJson().toString())["async"], ann["async"])
        // The MACs are keyed by the vectors' token (docs/vectors.json "v3"): a sync's and a vote's.
        val tok = "00112233445566778899aabbccddeeff"
        assertEquals("ecfb468750ddb844ac6165d1580994faa375d2b72e81ab84b631580c9667d679", Protocol.tokenHash(tok))
        assertEquals("c337402f79157613f5e35bda35b3c539926529d8a986ec6fbba1900ff12bd509", Protocol.mac(tok, "sync", 1791395000L))
        assertEquals("2d57740af03d8df43f9084cfd5cb0573ab1ec257861274fd257d5de4ad6fc851", Protocol.mac(tok, "vote", "0102030405060708", true))
    }
}
