package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.kilojoin.BigNat
import com.kilombino.kilowallet.core.kilojoin.BlindRsa
import com.kilombino.kilowallet.core.kilojoin.Nip44
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolAddresses
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.PooledCoins
import com.kilombino.kilowallet.core.kilojoin.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The building blocks against their published vectors (RFC 9474, NIP-44, NIP-01), and pool rules. */
class KilojoinTest {
    private fun hex(s: String) = Hashes.hexToBytes(s)
    private fun pubX(sec: String) = Ec.xOnly(hex(sec.padStart(64, '0')))

    @Test fun rfc9474Vector() {
        val pk = BlindRsa.PublicKey(BigNat.fromHex(Rfc9474.N), BigNat.fromHex(Rfc9474.E))
        val kp = BlindRsa.KeyPair(pk, BigNat.fromHex(Rfc9474.D))
        val prepared = hex(Rfc9474.MSG_PREFIX) + hex(Rfc9474.MSG)
        assertEquals(Rfc9474.PREPARED_MSG, prepared.toHex())
        val inv = BigNat.fromHex(Rfc9474.INV)
        val b = BlindRsa.blind(pk, prepared, salt = hex(Rfc9474.SALT), r = inv.modInverse(pk.n))
        assertEquals(Rfc9474.BLINDED_MSG, b.blindedMsg.toHex())
        val blindSig = BlindRsa.blindSign(kp, b.blindedMsg)
        assertEquals(Rfc9474.BLIND_SIG, blindSig.toHex())
        assertEquals(Rfc9474.SIG, BlindRsa.finalize(pk, prepared, blindSig, b.inv).toHex())
        assertTrue(BlindRsa.verify(pk, prepared, hex(Rfc9474.SIG)))
        assertFalse(BlindRsa.verify(pk, hex(Rfc9474.MSG), hex(Rfc9474.SIG)))
        val bad = hex(Rfc9474.SIG).also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertFalse(BlindRsa.verify(pk, prepared, bad))
    }

    @Test fun blindRoundTripWithAFreshKey() {
        val kp = BlindRsa.generate()
        assertEquals(2048, kp.public.modBits)
        val pk = BlindRsa.PublicKey.decode(kp.public.encode())
        val msg = "0014" + "ab".repeat(20)
        val prepared = BlindRsa.prepare(hex(msg))
        val b = BlindRsa.blind(pk, prepared)
        val sig = BlindRsa.finalize(pk, prepared, BlindRsa.blindSign(BlindRsa.KeyPair.decode(kp.encodePrivate()), b.blindedMsg), b.inv)
        assertTrue(BlindRsa.verify(pk, prepared, sig))
        assertFalse(b.blindedMsg.contentEquals(sig))
        assertFalse(BlindRsa.verify(pk, BlindRsa.prepare(hex(msg)), sig))
    }

    @Test fun nip44ConversationKeyAndPadding() {
        assertEquals("3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
            Nip44.conversationKey(hex("315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268"),
                hex("c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133")).toHex())
        assertEquals("4d14f36e81b8452128da64fe6f1eae873baae2f444b02c950b90e43553f2178b",
            Nip44.conversationKey(hex("a1e37752c9fdc1273be53f68c5f74be7c8905728e8de75800b94262f9497c86e"),
                hex("03bb7947065dde12ba991ea045132581d0954f042c84e06d8c00066e23c1a800")).toHex())
        for ((len, padded) in listOf(16 to 32, 32 to 32, 33 to 64, 37 to 64, 45 to 64, 49 to 64, 64 to 64,
                65 to 96, 100 to 128, 111 to 128, 200 to 224, 250 to 256))
            assertEquals(padded, Nip44.paddedLen(len), "len $len")
    }

    @Test fun nip44OfficialVectors() {
        data class V(val sec1: String, val sec2: String, val nonce: String, val plain: String, val payload: String)
        val vs = listOf(
            V("0000000000000000000000000000000000000000000000000000000000000001", "0000000000000000000000000000000000000000000000000000000000000002",
                "0000000000000000000000000000000000000000000000000000000000000001", "a",
                "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"),
            V("0000000000000000000000000000000000000000000000000000000000000002", "0000000000000000000000000000000000000000000000000000000000000001",
                "f00000000000000000000000000000f00000000000000000000000000000000f", "🍕🫃",
                "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"),
            V("5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a", "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d",
                "b635236c42db20f021bb8d1cdff5ca75dd1a0cc72ea742ad750f33010b24f73b",
                "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀",
                "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="),
        )
        for (v in vs) {
            val ck = Nip44.conversationKey(hex(v.sec1), pubX(v.sec2))
            assertEquals(v.payload, Nip44.encrypt(v.plain, ck, hex(v.nonce)))
            assertEquals(v.plain, Nip44.decrypt(v.payload, ck))
            assertEquals(v.plain, Nip44.decrypt(v.payload, Nip44.conversationKey(hex(v.sec2), pubX(v.sec1))))
        }
        val ck = Nip44.conversationKey(hex("01".padStart(64, '0')), pubX("02"))
        val p = Nip44.encrypt("hola", ck)
        val bad = p.substring(0, 10) + (if (p[10] == 'A') 'B' else 'A') + p.substring(11)
        assertNull(Nip44.decrypt(bad, ck))
    }

    @Test fun nostrSerialisation() {
        assertEquals("[0,\"ab\",1,1,[[\"p\",\"x/y\"]],\"a\\\"b\\nc/d\"]",
            NostrEvent.serialize("ab", 1, 1, listOf(listOf("p", "x/y")), "a\"b\nc/d"))
        val ev = NostrEvent.sign(hex("03".padStart(64, '0')), 2023, listOf(listOf("p", "00".repeat(32))), "contenido con / y \"comillas\"", 1700000000)
        assertTrue(ev.verify())
        assertFalse(ev.copy(content = ev.content + " ").verify())
        assertEquals(64, ev.id.length); assertEquals(128, ev.sig.length)
        // A secret Android kept without its leading zeros reads the same.
        assertEquals(NostrEvent.pubOf(hex("00ff".padEnd(64, '1'))), NostrEvent.pubOf(NostrEvent.secretOf("ff".padEnd(62, '1'))))
    }

    @Test fun newPoolsAndTheirMinimum() {
        val np = PoolSession.newPool(5_000, 2.0, 5, 1, outputs = 2)
        assertEquals(Protocol.V4, np.terms.version)
        assertFalse(Protocol.isTest(np.terms), "5 000 × 2 = 10 000: a real pool")
        assertTrue(Protocol.isTest(PoolSession.newPool(3_000, 2.0, 5, 1, outputs = 3).terms))
        val pay = PoolSession.newPool(100_000, 2.0, 5, 1, outputs = 2, payments = listOf(30_000L, 45_000L, 125_000L))
        assertEquals(Protocol.V5, pay.terms.version)
        assertEquals(Protocol.V3, PoolSession.newPool(50_000, 2.0, 5, 1).terms.version)
        assertFailsWith<IllegalArgumentException> { PoolSession.newPool(100_000, 2.0, 5, 1, outputs = 2, payments = listOf(1L, 199_999L)) }
        assertFailsWith<IllegalArgumentException> { PoolSession.newPool(100_000, 2.0, 5, 1, outputs = 6) }
        // The creator's own announcement reads back as these terms (signed by the pool's key).
        val ev = NostrEvent.sign(NostrEvent.secretOf(pay.secret), Protocol.KIND_POOL, listOf(listOf("d", pay.terms.id), listOf("t", Protocol.TAG)),
            pay.terms.toJson().toString(), pay.terms.createdAt)
        assertEquals(pay.terms, Protocol.Terms.parse(ev))
    }

    @Test fun aSeatIsSignedForItsPool() {
        val np = PoolSession.newPool(50_000, 2.0, 5, 1, maxInputs = 2)
        val keys = listOf(hex("11".repeat(32)), hex("22".repeat(32)))
        val coins = keys.mapIndexed { i, k -> PoolTx.Coin("${i}a".repeat(32), i, 30_000L, Ec.publicKey(k)) }
        val mix = Address.scriptPubKey(Ec.publicKey(hex("33".repeat(32))), ScriptType.P2WPKH)
        val change = Address.scriptPubKey(Ec.publicKey(hex("44".repeat(32))), ScriptType.P2WPKH)
        val st = PoolSession.newState(np.terms, false, null, coins, keys, listOf("m/84'/0'/0'/0/1", "m/84'/0'/0'/0/2"), listOf(mix), change)
        assertEquals(PoolTx.change(60_000, 50_000, 2.0, 2), st.seat.changeValue)
        assertTrue(Ec.verifyDer(coins[0].pubkey, Protocol.changeMessage(np.terms.id, coins, change, st.seat.changeValue), hex(st.changeSig)))
        // The state survives being saved and read back.
        val back = PoolSession.State.parse(st.toJson())
        assertEquals(st.toJson(), back.toJson())
        // Keys that are not the coins', or a coin of the wrong amount for the pool, are refused.
        assertFailsWith<IllegalArgumentException> { PoolSession.newState(np.terms, false, null, coins, keys.reversed(), listOf("a", "b"), listOf(mix), change) }
        assertFailsWith<IllegalStateException> {
            PoolSession.newState(np.terms, false, null, listOf(coins[0].copy(value = 40_000)), listOf(keys[0]), listOf("a"), listOf(mix), change)
        }
    }

    @Test fun pickUnusedAddresses() {
        val used = setOf(0, 1, 3)
        val derive = { i: Int -> byteArrayOf(0, 20) + ByteArray(20) { i.toByte() } }
        assertEquals(listOf(4, 5), PoolAddresses.pickUnusedIndexes(2, 0, emptySet(), derive) { it[2].toInt() in used })
        assertEquals(listOf(5, 6), PoolAddresses.pickUnusedIndexes(2, 0, setOf(derive(4).toList()), derive) { it[2].toInt() in used })
    }

    @Test fun aRoundLooksLikeARound() {
        val keys = (1..2).map { hex("$it$it".repeat(32)) }
        val coins = keys.mapIndexed { i, k -> PoolTx.Coin("${i + 1}f".repeat(32), 0, 60_000L, Ec.publicKey(k)) }
        val mixes = (5..6).map { Address.scriptPubKey(Ec.publicKey(hex("$it$it".repeat(32))), ScriptType.P2WPKH) }
        val changes = (7..8).map { TxBuilder.Output(Address.scriptPubKey(Ec.publicKey(hex("$it$it".repeat(32))), ScriptType.P2WPKH), 9_000L + it) }
        val plan = PoolTx.plan(coins, mixes, changes, 50_000)
        val raw = PoolTx.assemble(plan, coins.indices.associate { coins[it].outpoint to PoolTx.sign(plan, coins[it], keys[it]) }).rawHex
        val equal = plan.outputs.indexOfFirst { it.value == 50_000L }
        val ch = plan.outputs.indexOfFirst { it.value == 9_007L }
        assertTrue(PooledCoins.looksPooled(raw, equal))
        assertFalse(PooledCoins.looksPooled(raw, ch))
    }
}
