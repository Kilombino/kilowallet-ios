package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.backup.AesGcm
import com.kilombino.kilowallet.core.backup.Gzip
import com.kilombino.kilowallet.core.backup.Kab
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.DiceCheck
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.wallet.CpfpPlanner
import com.kilombino.kilowallet.core.wallet.SendDraft
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
 * Taproot, dice and the backup file against the Android app's own output ([OnchainVectors], made by
 * tools/android-vectors from commit [OnchainVectors.COMMIT]).
 */
class OnchainTest {
    private val root = Json.parseToJsonElement(OnchainVectors.JSON).jsonObject
    private fun hex(s: String) = Hashes.hexToBytes(s)
    private fun words(w: kotlinx.serialization.json.JsonObject) = w["words"]!!.jsonPrimitive.content.split(" ")

    @Test
    fun taprootAccountAndAddresses() {
        for (w in root["taprootWallets"]!!.jsonArray.map { it.jsonObject }) {
            val pass = w["passphrase"]!!.jsonPrimitive.content
            val tpub = Signer.accountTpub(words(w), pass)
            assertEquals(w["tpub"]!!.jsonPrimitive.content, tpub)
            assertEquals(w["zpub"]!!.jsonPrimitive.content, Signer.accountZpub(words(w), pass))
            val parsed = Bip32.parseExtendedPubKey(tpub).withType(ScriptType.P2TR)
            for (a in w["addresses"]!!.jsonArray.map { it.jsonObject }) {
                val pub = Bip32.derivePath(parsed, a["chain"]!!.jsonPrimitive.int, a["index"]!!.jsonPrimitive.int).pubkey()
                assertEquals(a["pubkey"]!!.jsonPrimitive.content, pub.toHex())
                assertEquals(a["address"]!!.jsonPrimitive.content, Address.encode(pub, ScriptType.P2TR))
                assertEquals(a["scriptHash"]!!.jsonPrimitive.content, Address.scriptHashFor(pub, ScriptType.P2TR))
                assertEquals(a["address"]!!.jsonPrimitive.content, Address.fromScriptPubKey(Address.scriptPubKey(pub, ScriptType.P2TR)))
            }
        }
    }

    /** The well-known BIP-86 vector: "abandon … about", m/86'/0'/0'/0/0. */
    @Test
    fun bip86Vector() {
        val tpub = Signer.accountTpub(List(11) { "abandon" } + "about", "")
        val pub = Bip32.derivePath(Bip32.parseExtendedPubKey(tpub), 0, 0).pubkey()
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", Address.encode(pub, ScriptType.P2TR))
    }

    @Test
    fun taprootAndMixedSpendsAreByteIdentical() {
        val wallets = root["taprootWallets"]!!.jsonArray.map { it.jsonObject }
        val txs = root["taprootTxs"]!!.jsonArray.map { it.jsonObject }
        assertTrue(txs.size >= 12)
        for (t in txs) {
            val w = wallets[t["wallet"]!!.jsonPrimitive.int]
            val pass = w["passphrase"]!!.jsonPrimitive.content
            val unified = t["unified"]!!.jsonPrimitive.boolean
            val coins = t["coins"]!!.jsonArray.map { it.jsonObject }.map { c ->
                SpendableUtxo(c["txid"]!!.jsonPrimitive.content, c["vout"]!!.jsonPrimitive.int, c["value"]!!.jsonPrimitive.long,
                    c["chain"]!!.jsonPrimitive.int, c["index"]!!.jsonPrimitive.int, 0,
                    if (c["taproot"]!!.jsonPrimitive.boolean) ScriptType.P2TR else null)
            }
            val outputs = t["outputs"]!!.jsonArray.map { it.jsonObject }.map { TxBuilder.Output(hex(it["script"]!!.jsonPrimitive.content), it["value"]!!.jsonPrimitive.long) }
            val aux = hex(t["aux"]!!.jsonPrimitive.content)
            if (unified) {
                // The hot wallet's real signing path: words → keys at m/86' or m/84', checked against its accounts.
                val draft = SendDraft("", 0, 0, 0, coins, outputs, 1.0)
                val s = Signer.sign(draft, words(w), pass, w["zpub"]!!.jsonPrimitive.content, w["tpub"]!!.jsonPrimitive.content, aux)
                assertEquals(t["raw"]!!.jsonPrimitive.content, s.rawHex)
                assertEquals(t["txid"]!!.jsonPrimitive.content, s.txid)
                assertEquals(t["vbytes"]!!.jsonPrimitive.int, s.vbytes)
            } else {
                // Plain BIP-341 key-path signing (SIGHASH_DEFAULT), straight through the builder.
                val master = com.kilombino.kilowallet.core.crypto.Bip32Priv.fromSeed(Bip39.toSeed(words(w), pass))
                val inputs = coins.map { c ->
                    val node = com.kilombino.kilowallet.core.crypto.Bip32Priv.derivePath(master,
                        "m/${if (c.type == ScriptType.P2TR) 86 else 84}'/0'/0'/${c.chainIndex}/${c.index}")
                    TxBuilder.Input(c.txid, c.vout, c.value, node.key, node.publicKey(), 0xfffffffdL,
                        if (c.type == ScriptType.P2TR) ScriptType.P2TR else ScriptType.P2WPKH)
                }
                assertEquals(t["sighash0"]!!.jsonPrimitive.content, TxBuilder.taprootSighash(2, inputs, outputs, 0, 0).toHex())
                val s = TxBuilder.build(inputs, outputs, unified = false, auxRand = aux)
                assertEquals(t["raw"]!!.jsonPrimitive.content, s.rawHex)
                assertEquals(t["txid"]!!.jsonPrimitive.content, s.txid)
            }
        }
    }

    @Test
    fun wrongWordsSignNothing() {
        val w = root["taprootWallets"]!!.jsonArray[1].jsonObject
        val other = root["taprootWallets"]!!.jsonArray[3].jsonObject
        val coin = SpendableUtxo("ab".repeat(32), 0, 50_000, 0, 0, 0, ScriptType.P2TR)
        val draft = SendDraft("", 0, 0, 0, listOf(coin), listOf(TxBuilder.Output(ByteArray(22), 40_000)), 1.0)
        assertFailsWith<IllegalStateException> {
            Signer.sign(draft, words(other), "", w["zpub"]!!.jsonPrimitive.content, w["tpub"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun diceEntropyAndSanityCheck() {
        for (d in root["dice"]!!.jsonArray.map { it.jsonObject }) {
            val rolls = d["rolls"]!!.jsonPrimitive.content
            assertEquals(d["problems"]!!.jsonArray.map { it.jsonPrimitive.content }, DiceCheck.problems(rolls), rolls)
            d["words"]?.let { expected ->
                val bits = if (rolls.length == 50) 128 else 256
                assertEquals(expected.jsonPrimitive.content, Bip39.fromEntropy(Bip39.entropyFromDiceRolls(rolls, bits)).joinToString(" "))
            }
        }
    }

    @Test
    fun backupFileFromAndroidOpensHere() {
        val k = root["kab"]!!.jsonObject
        val s = Kab.decode(hex(k["androidFile"]!!.jsonPrimitive.content), k["androidPassword"]!!.jsonPrimitive.content)
        assertEquals(k["words"]!!.jsonPrimitive.content, s.words.joinToString(" "))
        assertEquals(k["androidPassphrase"]!!.jsonPrimitive.content, s.passphrase)
        assertEquals(k["app"]!!.jsonObject, s.app)
        assertEquals(7, s.movements)
        assertFailsWith<Kab.WrongPassword> { Kab.decode(hex(k["androidFile"]!!.jsonPrimitive.content), "wrong password") }
    }

    /** A whole-wallet file made by Android (Ark derived from the words, its database and WAL, Lightning's birthday and channel backup). */
    @Test
    fun wholeWalletFileFromAndroidOpensHere() {
        val k = root["kab"]!!.jsonObject
        val s = Kab.decode(hex(k["androidFull"]!!.jsonPrimitive.content), "correct horse")
        assertEquals(k["words"]!!.jsonPrimitive.content, s.words.joinToString(" "))
        assertTrue(s.hasArk && s.arkDerived)
        assertEquals(k["config"]!!.jsonPrimitive.content, s.config)
        assertTrue(hex(k["db"]!!.jsonPrimitive.content).contentEquals(s.db!!))
        assertTrue(hex(k["wal"]!!.jsonPrimitive.content).contentEquals(s.dbWal!!))
        assertEquals(k["fullApp"]!!.jsonObject, s.app)
        val ln = s.app!!["lightning"]!!.jsonObject
        assertEquals(1_788_220_800L, ln["birthday"]!!.jsonPrimitive.content.toLong())
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val scb = kotlin.io.encoding.Base64.Default.decode(ln["scb"]!!.jsonPrimitive.content)
        assertTrue(hex(k["scb"]!!.jsonPrimitive.content).contentEquals(scb))
        // And back through this code: the same Ark files and version 4.
        val again = Kab.decode(Kab.encode(Kab.Snapshot(s.words, "", 1, 2, s.app, config = s.config, db = s.db, dbWal = s.dbWal, arkDerived = true), "a long password"), "a long password")
        assertTrue(again.hasArk && again.arkDerived && again.db!!.contentEquals(s.db!!) && again.dbWal!!.contentEquals(s.dbWal!!))
    }

    @Test
    fun backupFileRoundTrip() {
        val words = Bip39.generate(12)
        val file = Kab.encode(Kab.Snapshot(words, "x", 5, 2), "a long password")
        val back = Kab.decode(file, "a long password")
        assertEquals(words, back.words)
        assertEquals("x", back.passphrase)
        assertFailsWith<Kab.WrongPassword> { Kab.decode(file, "a long passwore") }
        // A flipped byte in the sealed part is caught by the tag.
        val bad = file.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertFailsWith<Kab.WrongPassword> { Kab.decode(bad, "a long password") }
    }

    @Test
    fun gzipRoundTrip() {
        for (n in listOf(0, 1, 100, 70_000, 140_000)) {
            val data = ByteArray(n) { (it * 31 + it / 7).toByte() }
            assertTrue(Gzip.gunzip(Gzip.gzip(data)).contentEquals(data), "n=$n")
        }
    }

    /** NIST SP 800-38D (McGrew & Viega test cases 13 and 14: AES-256, zero key and IV). */
    @Test
    fun aesGcmNistVectors() {
        val key = ByteArray(32); val iv = ByteArray(12)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", AesGcm.encrypt(key, iv, ByteArray(0)).toHex())
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", AesGcm.encrypt(key, iv, ByteArray(16)).toHex())
        // Test case 16: key/IV/plain/AAD from the spec.
        val k16 = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv16 = hex("cafebabefacedbaddecaf888")
        val p16 = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val a16 = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        assertEquals("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
            "76fc6ece0f4e1768cddf8853bb2d551b", AesGcm.encrypt(k16, iv16, p16, a16).toHex())
        assertTrue(AesGcm.decrypt(k16, iv16, AesGcm.encrypt(k16, iv16, p16, a16), a16).contentEquals(p16))
    }

    @Test
    fun psbtsLikeAndroidAndTheirSignaturesChecked() {
        val ps = root["psbts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, ps.size)
        for (p in ps) {
            val type = ScriptType.valueOf(p["type"]!!.jsonPrimitive.content)
            val coins = p["coins"]!!.jsonArray.map { it.jsonObject }.map { c ->
                val o = hex(c["origin"]!!.jsonPrimitive.content)
                val path = (4 until o.size step 4).map { i -> (0 until 4).fold(0L) { a, k -> a or ((o[i + k].toLong() and 0xff) shl (8 * k)) } }
                com.kilombino.kilowallet.core.crypto.Psbt.Coin(c["txid"]!!.jsonPrimitive.content, c["vout"]!!.jsonPrimitive.int,
                    c["value"]!!.jsonPrimitive.long, type, hex(c["pubkey"]!!.jsonPrimitive.content),
                    com.kilombino.kilowallet.core.crypto.Psbt.Origin(o.copyOfRange(0, 4), path))
            }
            val outputs = p["outputs"]!!.jsonArray.map { it.jsonObject }.map { TxBuilder.Output(hex(it["script"]!!.jsonPrimitive.content), it["value"]!!.jsonPrimitive.long) }
            val purpose = p["purpose"]!!.jsonPrimitive.long
            val change = mapOf(1 to com.kilombino.kilowallet.core.crypto.Psbt.Change(hex(p["changePubkey"]!!.jsonPrimitive.content), type,
                com.kilombino.kilowallet.core.crypto.Psbt.Origin(byteArrayOf(1, 2, 3, 4), listOf(purpose + 0x80000000L, 0x80000000L, 0x80000000L, 1L, 5L))))
            val exported = com.kilombino.kilowallet.core.crypto.Psbt.create(coins, outputs, change)
            assertEquals(p["exported"]!!.jsonPrimitive.content, exported.toHex(), type.name)
            val returned = hex(p["returned"]!!.jsonPrimitive.content)
            val tx = com.kilombino.kilowallet.core.crypto.Psbt.finish(exported, returned, coins, outputs)
            assertEquals(p["raw"]!!.jsonPrimitive.content, tx.rawHex, type.name)
            assertEquals(p["txid"]!!.jsonPrimitive.content, tx.txid)
            // A signature over something else is refused, never broadcast.
            val parsed = com.kilombino.kilowallet.core.crypto.Psbt.parse(returned)
            val badInputs = parsed.inputs.mapIndexed { i, m -> if (i != 0) m else m.map { (k, v) ->
                if (k[0] == 0x02.toByte() || k[0] == 0x13.toByte()) k to v.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() } else k to v } }
            val tampered = com.kilombino.kilowallet.core.crypto.Psbt.serialize(com.kilombino.kilowallet.core.crypto.Psbt.Parsed(parsed.global, badInputs, parsed.outputs))
            assertTrue(runCatching { com.kilombino.kilowallet.core.crypto.Psbt.finish(exported, tampered, coins, outputs) }.isFailure)
            // The animated QR: the same parts as the Android app, and back to the same bytes.
            val enc = com.kilombino.kilowallet.core.crypto.Ur.psbtEncoder(exported)
            val parts = p["ur"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(parts, parts.map { enc.nextPart() })
            val dec = com.kilombino.kilowallet.core.crypto.Ur.Decoder()
            for (part in parts) if (dec.receive(part)) break
            assertTrue(dec.psbt().contentEquals(exported))
        }
    }

    @Test
    fun cpfpChildPaysForThePair() {
        val coin = SpendableUtxo("cd".repeat(32), 1, 40_000, 0, 3, 0)
        val to = ByteArray(22).also { it[1] = 0x14 }
        // A 141-vB parent that paid 141 sats (1 sat/vB), pulled to 5 sat/vB.
        val d = CpfpPlanner.plan("ef".repeat(32), 141, 141, listOf(coin), to, 5.0)
        assertEquals(1, d.outputs.size)
        assertTrue(d.packageRate >= 5.0, "package rate ${d.packageRate}")
        assertEquals(40_000 - d.fee, d.amount)
        assertFailsWith<IllegalArgumentException> { CpfpPlanner.plan("ef".repeat(32), 141 * 10, 141, listOf(coin), to, 5.0) }
    }
}
