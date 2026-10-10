package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Base58
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.BlockHeader
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val abandon = (List(11) { "abandon" } + "about")

    @Test
    fun bip39OfficialVectorWithPassphrase() {
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            Bip39.toSeed(abandon, "TREZOR").toHex(),
        )
        assertFalse(Bip39.isValid(List(12) { "abandon" }))
        assertEquals(12, Bip39.generate(12).size)
        assertEquals(24, Bip39.generate(24).size)
        assertTrue(Bip39.isValid(Bip39.generate(24)))
    }

    @Test
    fun bip84OfficialVector() {
        // BIP-84's own test vector for the "abandon … about" mnemonic.
        assertEquals(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            Signer.accountZpub(abandon, ""),
        )
    }

    @Test
    fun base58RoundTrip() {
        for (n in listOf(0, 1, 2, 5, 21, 78)) {
            val b = ByteArray(n) { (it * 37 + 3).toByte() }.also { if (n > 2) { it[0] = 0; it[1] = 0 } }
            assertTrue(b.contentEquals(Base58.decode(Base58.encode(b))))
        }
        assertEquals("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
            Address.fromScriptPubKey(Address.decodeToScriptPubKey("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")))
    }

    @Test
    fun realBlake2bHeaders() {
        for ((_, hash, header) in HeaderVectors.HEADERS) {
            val h = BlockHeader.parse(header)
            assertEquals(hash, BlockHeader.blockHash(h))
            assertTrue(BlockHeader.meetsItsTarget(h))
            // One flipped nonce bit and the proof of work no longer holds.
            val bad = Hashes.hexToBytes(header).also { it[76] = (it[76].toInt() xor 1).toByte() }
            assertFalse(BlockHeader.meetsItsTarget(BlockHeader.parse(bad.toHex())))
        }
        assertTrue(BlockHeader.compare(BlockHeader.target(0x1900d82aL), BlockHeader.target(0x191b0540L)) < 0)
        assertEquals("00000000ffff0000000000000000000000000000000000000000000000000000", BlockHeader.target(0x1d00ffffL).toHex())
    }

    private fun coin(v: Long, i: Int = 0) = SpendableUtxo("ab".repeat(32), i, v, 0, i, 100)
    private val change = Hashes.hexToBytes("0014" + "11".repeat(20))
    private val to = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    @Test
    fun coinSelectionAndFees() {
        val d = SendPlanner.plan(to, 10_000, 2.0, listOf(coin(5_000, 0), coin(50_000, 1)), change)
        assertEquals(1, d.inputs.size)
        assertEquals(50_000, d.inputs[0].value)
        assertEquals(2, d.outputs.size)
        assertEquals(50_000, d.amount + d.fee + d.change)
        assertNull(SendPlanner.feeConcern(d))

        val all = SendPlanner.plan(to, 0, 1.0, listOf(coin(5_000, 0), coin(50_000, 1)), change, sendAll = true)
        assertEquals(1, all.outputs.size)
        assertEquals(55_000, all.amount + all.fee)

        // Change below dust is folded into the fee rather than creating an unspendable output.
        val tight = SendPlanner.plan(to, 49_700, 1.0, listOf(coin(50_000)), change)
        assertEquals(1, tight.outputs.size)
        assertEquals(300, tight.fee)

        assertFailsWith<IllegalArgumentException> { SendPlanner.plan(to, 60_000, 1.0, listOf(coin(50_000)), change) }
        assertFailsWith<IllegalArgumentException> { SendPlanner.plan("bc1qnotanaddress", 1_000, 1.0, listOf(coin(50_000)), change) }
        assertNotNull(SendPlanner.feeConcern(SendPlanner.plan(to, 1_000, 500.0, listOf(coin(5_000_000)), change)))
    }
}
