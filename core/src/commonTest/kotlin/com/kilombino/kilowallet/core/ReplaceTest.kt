package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.wallet.ReplacePlanner
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Speed-up and cancellation (RBF) against the Android app: the same unconfirmed send, replaced at
 * the same rates, must come out as the very same signed transactions (tools/android-vectors).
 */
class ReplaceTest {
    private val root = Json.parseToJsonElement(AndroidVectors.JSON).jsonObject

    @Test
    fun cancelAndSpeedUpAsTheAndroidApp() {
        val wallets = root["wallets"]!!.jsonArray.map { it.jsonObject }
        val cases = root["replacements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(9, cases.size)
        for (c in cases) {
            val w = wallets[c["wallet"]!!.jsonPrimitive.int]
            val words = w["words"]!!.jsonPrimitive.content.split(' ')
            val pass = w["passphrase"]!!.jsonPrimitive.content
            val zpub = w["zpub"]!!.jsonPrimitive.content
            val rate = c["rate"]!!.jsonPrimitive.double
            val txid = c["originalTxid"]!!.jsonPrimitive.content
            val original = TxParse.parse(c["original"]!!.jsonPrimitive.content)
            assertEquals(txid, TxParse.txid(original))
            val coins = c["coins"]!!.jsonArray.map { it.jsonObject }.map {
                SpendableUtxo(it["txid"]!!.jsonPrimitive.content, it["vout"]!!.jsonPrimitive.int, it["value"]!!.jsonPrimitive.long,
                    it["chain"]!!.jsonPrimitive.int, it["index"]!!.jsonPrimitive.int, 0)
            }
            val backTo = Hashes.hexToBytes(c["backTo"]!!.jsonPrimitive.content)

            val cancel = ReplacePlanner.cancel(txid, original, coins, backTo, rate)
            assertEquals(c["cancelFee"]!!.jsonPrimitive.long, cancel.fee)
            assertTrue(cancel.cancels && cancel.replaces == txid && cancel.outputs.size == 1)
            assertEquals(c["cancel"]!!.jsonPrimitive.content, Signer.sign(cancel, words, pass, zpub).rawHex)

            val change = SendPlanner.changeScript(zpub, 4)
            val bump = ReplacePlanner.bump(txid, original, coins, { it.contentEquals(change) }, rate)
            assertEquals(c["bumpFee"]!!.jsonPrimitive.long, bump.fee)
            assertEquals(original.outputs[0].value, bump.amount) // the recipient gets the same
            assertEquals(c["bump"]!!.jsonPrimitive.content, Signer.sign(bump, words, pass, zpub).rawHex)

            // BIP-125: more fee than the original, by at least 1 sat/vB of the replacement's size.
            val oldFee = coins.sumOf { it.value } - original.outputs.sumOf { it.value }
            assertEquals(oldFee, cancel.replacedFee)
            assertTrue(cancel.fee > oldFee + SendPlanner.estimateVbytes(coins.size, listOf(backTo)))
        }
    }

    @Test
    fun refusals() {
        val c = root["replacements"]!!.jsonArray[0].jsonObject
        val original = TxParse.parse(c["original"]!!.jsonPrimitive.content)
        val coins = c["coins"]!!.jsonArray.map { it.jsonObject }.map {
            SpendableUtxo(it["txid"]!!.jsonPrimitive.content, it["vout"]!!.jsonPrimitive.int, it["value"]!!.jsonPrimitive.long, 0, 0, 0)
        }
        // No change of ours: nothing to pay a higher fee from.
        assertFailsWith<IllegalStateException> { ReplacePlanner.bump("x", original, coins, { false }, 2.0) }
        // A fee the coins can't cover.
        assertFailsWith<IllegalArgumentException> {
            ReplacePlanner.cancel("x", original, coins, Hashes.hexToBytes(c["backTo"]!!.jsonPrimitive.content), 1000.0)
        }
        // Coins that aren't all this wallet's.
        assertFailsWith<IllegalArgumentException> { ReplacePlanner.cancel("x", original, emptyList(), ByteArray(22), 1.0) }
    }

    @Test
    fun plainXpubKeptUnderTheChosenType() {
        val zpub = root["wallets"]!!.jsonArray[0].jsonObject["zpub"]!!.jsonPrimitive.content
        val xpub = Bip32.withScriptType(zpub, ScriptType.P2PKH)
        assertTrue(xpub.startsWith("xpub"))
        assertEquals(ScriptType.P2PKH, Bip32.parseExtendedPubKey(xpub).scriptType)
        assertEquals(zpub, Bip32.withScriptType(xpub, ScriptType.P2WPKH))
        assertTrue(Bip32.withScriptType(xpub, ScriptType.P2SH_P2WPKH).startsWith("ypub"))
        assertEquals(Bip32.parseExtendedPubKey(zpub), Bip32.parseExtendedPubKey(xpub)) // the same key
    }
}
