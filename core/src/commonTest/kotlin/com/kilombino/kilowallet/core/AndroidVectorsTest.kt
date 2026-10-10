package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.crypto.TxParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-for-byte equality with the Android app: every vector below was produced by the app's own
 * code (see tools/android-vectors and [AndroidVectors.COMMIT]). Derivation, addresses, ECDSA
 * signatures and whole signed transactions must come out identical here, on the JVM and on iOS.
 */
class AndroidVectorsTest {
    private val root = Json.parseToJsonElement(AndroidVectors.JSON).jsonObject
    private fun hex(s: String) = Hashes.hexToBytes(s)

    @Test
    fun mnemonicsSeedsZpubsAndAddresses() {
        val wallets = root["wallets"]!!.jsonArray
        assertEquals(6, wallets.size)
        for (w in wallets.map { it.jsonObject }) {
            val words = Bip39.fromEntropy(hex(w["entropy"]!!.jsonPrimitive.content))
            assertEquals(w["words"]!!.jsonPrimitive.content, words.joinToString(" "))
            assertTrue(Bip39.isValid(words))
            val seed = Bip39.toSeed(words, w["passphrase"]!!.jsonPrimitive.content)
            assertEquals(w["seed"]!!.jsonPrimitive.content, seed.toHex())
            val master = Bip32Priv.fromSeed(seed)
            val zpub = Bip32Priv.accountXpub(master, 84, 0)
            assertEquals(w["zpub"]!!.jsonPrimitive.content, zpub)
            val parsed = Bip32.parseExtendedPubKey(zpub)
            for (a in w["addresses"]!!.jsonArray.map { it.jsonObject }) {
                val ch = a["chain"]!!.jsonPrimitive.int; val i = a["index"]!!.jsonPrimitive.int
                val pub = Bip32.derivePath(parsed, ch, i).pubkey()
                assertEquals(a["pubkey"]!!.jsonPrimitive.content, pub.toHex())
                assertEquals(a["address"]!!.jsonPrimitive.content, Address.encode(pub, ScriptType.P2WPKH))
                assertEquals(a["scriptHash"]!!.jsonPrimitive.content, Address.scriptHashFor(pub, ScriptType.P2WPKH))
                val priv = Bip32Priv.derivePath(master, "m/84'/0'/0'/$ch/$i")
                assertEquals(a["privkey"]!!.jsonPrimitive.content, priv.key.toHex())
                assertEquals(pub.toHex(), priv.publicKey().toHex())
            }
        }
    }

    @Test
    fun ecdsaSignaturesAreIdentical() {
        for (v in root["ecdsa"]!!.jsonArray.map { it.jsonObject }) {
            val der = Ec.signDer(hex(v["key"]!!.jsonPrimitive.content), hex(v["hash"]!!.jsonPrimitive.content))
            assertEquals(v["der"]!!.jsonPrimitive.content, der.toHex())
        }
    }

    @Test
    fun signedTransactionsAreIdentical() {
        val txs = root["transactions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(24, txs.size)
        var unifiedCount = 0
        for (t in txs) {
            val unified = t["unified"]!!.jsonPrimitive.boolean
            val inputs = t["inputs"]!!.jsonArray.map { it.jsonObject }.map { i ->
                val k = hex(i["privkey"]!!.jsonPrimitive.content)
                TxBuilder.Input(i["txid"]!!.jsonPrimitive.content, i["vout"]!!.jsonPrimitive.int,
                    i["value"]!!.jsonPrimitive.long, k, Ec.publicKey(k), i["sequence"]!!.jsonPrimitive.long)
            }
            val outputs = t["outputs"]!!.jsonArray.map { it.jsonObject }.map { o ->
                TxBuilder.Output(hex(o["script"]!!.jsonPrimitive.content), o["value"]!!.jsonPrimitive.long)
            }
            val sighash0 = if (unified) TxBuilder.unifiedSighash(2, inputs, outputs, 0, 0)
                           else TxBuilder.sighash(2, inputs, outputs, 0, 0)
            assertEquals(t["sighash0"]!!.jsonPrimitive.content, sighash0.toHex())
            val signed = TxBuilder.build(inputs, outputs, unified = unified)
            assertEquals(t["raw"]!!.jsonPrimitive.content, signed.rawHex)
            assertEquals(t["txid"]!!.jsonPrimitive.content, signed.txid)
            assertEquals(t["vbytes"]!!.jsonPrimitive.int, signed.vbytes)
            assertEquals(signed.txid, TxParse.txid(TxParse.parse(signed.rawHex)))
            if (unified) {
                unifiedCount++
                // Every witness signature ends with the unified hash type 0x21, never plain 0x01.
                for (inp in TxParse.parse(signed.rawHex).inputs) assertEquals(0x21, inp.witness[0].last().toInt() and 0xff)
            }
        }
        assertEquals(12, unifiedCount)
    }

    @Test
    fun recipientAddressesDecodeIdentically() {
        for (d in root["decodes"]!!.jsonArray.map { it.jsonObject }) {
            val a = d["address"]!!.jsonPrimitive.content
            assertEquals(d["script"]!!.jsonPrimitive.content, Address.decodeToScriptPubKey(a).toHex(), a)
        }
    }

    @Test
    fun defaultBuildIsUnified() {
        val k = Hashes.sha256("k".encodeToByteArray())
        val inp = TxBuilder.Input("11".repeat(32), 0, 10_000, k, Ec.publicKey(k))
        val out = TxBuilder.Output(Address.decodeToScriptPubKey("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"), 9_000)
        val tx = TxParse.parse(TxBuilder.build(listOf(inp), listOf(out)).rawHex)
        assertEquals(0x21, tx.inputs[0].witness[0].last().toInt() and 0xff)
    }
}
