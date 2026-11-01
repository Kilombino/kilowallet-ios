import com.kilombino.pyblockwatch.ark.ArkBackup
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.DiceCheck
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-chain vectors from the Android app's own code (unchanged, pure-Kotlin path): the BIP-86 Taproot
 * account and its addresses, whole transactions spending Taproot (and mixed) coins with a pinned
 * BIP-340 aux, dice entropy and the dice sanity check, and backup files (.kab) — one made by the
 * Android code for the iOS port to open, and one made by the iOS port (core-jvm.jar) that the
 * Android code must open with the same words, passphrase and settings.
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    val commit = args[1]
    fun det(tag: String, n: Int = 32) = Hashes.sha256(tag.toByteArray()).copyOf(n)

    val entropies = listOf(ByteArray(16), det("kw-ios-12", 16), det("kw-ios-12b", 16), det("kw-ios-24", 32))
    val passphrases = listOf("", "", "TREZOR", "")
    val wallets = JSONArray()
    val txs = JSONArray()
    val recipients = listOf(
        "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
        "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297",
        "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
    )
    entropies.forEachIndexed { wi, ent ->
        val words = Bip39.fromEntropy(ent)
        val pass = passphrases[wi]
        val master = Bip32Priv.fromSeed(Bip39.toSeed(words, pass))
        val zpub = Bip32Priv.accountXpub(master, 84, 0)
        val tpub = Bip32Priv.accountXpub(master, 86, 0)
        val parsed = Bip32.parseExtendedPubKey(tpub)
        val addrs = JSONArray()
        for (ch in 0..1) for (i in 0 until if (ch == 0) 5 else 3) {
            val pub = Bip32.derivePath(parsed, ch, i).pubkey()
            val priv = Bip32Priv.derivePath(master, "m/86'/0'/0'/$ch/$i")
            require(priv.publicKey().contentEquals(pub))
            addrs.put(JSONObject().put("chain", ch).put("index", i)
                .put("address", Address.encode(pub, ScriptType.P2TR))
                .put("scriptHash", Address.scriptHashFor(pub, ScriptType.P2TR))
                .put("pubkey", pub.toHex()))
        }
        wallets.put(JSONObject().put("words", words.joinToString(" ")).put("passphrase", pass)
            .put("zpub", zpub).put("tpub", tpub).put("addresses", addrs))

        // Taproot-only and mixed (Taproot + native SegWit) spends, unified and plain.
        for (variant in 0 until 3) {
            val inputs = (0..variant).map { k ->
                val tr = variant != 2 || k == 0
                val purpose = if (tr) 86 else 84
                val ch = k % 2; val idx = k + wi
                val node = Bip32Priv.derivePath(master, "m/$purpose'/0'/0'/$ch/$idx")
                TxBuilder.Input(det("tr-prev-$wi-$variant-$k").toHex(), k * 2 + variant, 60_000L + 4_321L * k + wi,
                    node.key, node.publicKey(), 0xfffffffdL, if (tr) ScriptType.P2TR else ScriptType.P2WPKH) to Triple(ch, idx, tr)
            }
            val change = Address.scriptPubKey(Bip32.derivePath(parsed, 1, 9).pubkey(), ScriptType.P2TR)
            val to = recipients[(wi + variant) % recipients.size]
            val sum = inputs.sumOf { it.first.value }
            val outputs = listOf(
                TxBuilder.Output(Address.decodeToScriptPubKey(to), 25_000L + variant),
                TxBuilder.Output(change, sum - 25_000L - variant - 250L),
            )
            for (unified in listOf(true, false)) {
                val aux = det("aux-$wi-$variant-$unified")
                val s = TxBuilder.build(inputs.map { it.first }, outputs, unified = unified, auxRand = aux)
                txs.put(JSONObject().put("wallet", wi).put("unified", unified).put("aux", aux.toHex())
                    .put("coins", JSONArray(inputs.map { (inp, w) ->
                        JSONObject().put("txid", inp.txid).put("vout", inp.vout).put("value", inp.value)
                            .put("chain", w.first).put("index", w.second).put("taproot", w.third)
                    }))
                    .put("outputs", JSONArray(outputs.map { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) }))
                    .put("sighash0", (if (unified) TxBuilder.unifiedSighash(2, inputs.map { it.first }, outputs, 0, 0)
                        else TxBuilder.taprootSighash(2, inputs.map { it.first }, outputs, 0, 0)).toHex())
                    .put("raw", s.rawHex).put("txid", s.txid).put("vbytes", s.vbytes))
            }
        }
    }

    // Dice: SeedSigner entropy and the sanity check, over honest-looking and made-up sequences.
    val rng = java.util.Random(4242)
    fun fair(n: Int) = (1..n).joinToString("") { (rng.nextInt(6) + 1).toString() }
    val dice = JSONArray()
    for (rolls in listOf(fair(50), fair(99), "1".repeat(50), "123456".repeat(9).take(50), "12".repeat(25), fair(30) + "5".repeat(20))) {
        val o = JSONObject().put("rolls", rolls).put("problems", JSONArray(DiceCheck.problems(rolls)))
        if (rolls.length == 50 || rolls.length == 99) {
            o.put("words", Bip39.fromEntropy(Bip39.entropyFromDiceRolls(rolls, if (rolls.length == 50) 128 else 256)).joinToString(" "))
        }
        dice.put(o)
    }

    // Backup files both ways.
    val words = Bip39.fromEntropy(det("kw-ios-kab", 16))
    val app = JSONObject().put("store", JSONObject().put("fiat", JSONObject().put("t", "s").put("v", "EUR"))
        .put("gap_limit", JSONObject().put("t", "i").put("v", 25)))
    val androidFile = ArkBackup.encode(ArkBackup.Snapshot(words, "pass phrase", null, null, null, 7, 1_793_000_000_000L,
        null, app.toString()), "correct horse")
    val iosApp = kotlinx.serialization.json.Json.parseToJsonElement(app.toString()) as kotlinx.serialization.json.JsonObject
    val iosFile = com.kilombino.kilowallet.core.backup.Kab.encode(
        com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, "", 1_793_000_000_001L, 3, iosApp), "battery staple")
    val back = ArkBackup.decode(iosFile, "battery staple")
    require(back.words == words && back.passphrase == "" && back.movements == 3 && back.created == 1_793_000_000_001L &&
        JSONObject(back.app!!).similar(app)) { "the Android code read the iOS backup file differently" }
    require(runCatching { ArkBackup.decode(iosFile, "wrong") }.exceptionOrNull() is ArkBackup.WrongPassword)
    // With Ark (config, database and WAL; Ark's words derived from the wallet's) and Lightning
    // (birthday and channel backup in the app part), both ways: the whole wallet moves between phones.
    val config = "server_address = \"https://ark.paperclippool.xyz\"\nesplora_address = \"https://mempool.kilombino.com/api\"\nnetwork = \"bitcoin\"\n"
    val db = det("kw-ios-kab-db", 4096); val wal = det("kw-ios-kab-wal", 1024)
    val scb = det("kw-ios-kab-scb", 345)
    val fullApp = JSONObject(app.toString()).put("lightning", JSONObject().put("birthday", 1_788_220_800L)
        .put("scb", android.util.Base64.encodeToString(scb, android.util.Base64.NO_WRAP)))
    val androidFull = ArkBackup.encode(ArkBackup.Snapshot(words, "", config, db, wal, 12, 1_793_000_000_002L,
        null, fullApp.toString(), arkDerived = true), "correct horse")
    val iosFull = com.kilombino.kilowallet.core.backup.Kab.encode(com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, "", 1_793_000_000_003L, 4,
        kotlinx.serialization.json.Json.parseToJsonElement(fullApp.toString()) as kotlinx.serialization.json.JsonObject,
        config = config, db = db, dbWal = wal, arkDerived = true), "battery staple")
    val backFull = ArkBackup.decode(iosFull, "battery staple")
    require(backFull.hasArk && backFull.arkDerived && backFull.config == config && backFull.db!!.contentEquals(db) &&
        backFull.dbWal!!.contentEquals(wal) && JSONObject(backFull.app!!).similar(fullApp) &&
        android.util.Base64.decode(JSONObject(backFull.app!!).getJSONObject("lightning").getString("scb"), android.util.Base64.NO_WRAP).contentEquals(scb)) {
        "the Android code read the iOS whole-wallet file (Ark + Lightning) differently"
    }
    val kab = JSONObject().put("words", words.joinToString(" "))
        .put("androidFile", androidFile.toHex()).put("androidPassword", "correct horse").put("androidPassphrase", "pass phrase")
        .put("app", app)
        .put("androidFull", androidFull.toHex()).put("fullApp", fullApp).put("config", config)
        .put("db", db.toHex()).put("wal", wal.toHex()).put("scb", scb.toHex())

    // PSBTs for a watch-only wallet of each type: what the app exports, a signed copy as a signer
    // returns it (unified 0x21 signatures put in by the Android code), and the transaction it builds.
    val psbts = JSONArray()
    val pw = Bip39.fromEntropy(det("kw-ios-psbt", 16))
    val pm = Bip32Priv.fromSeed(Bip39.toSeed(pw, ""))
    for ((type, purpose) in listOf(ScriptType.P2WPKH to 84, ScriptType.P2SH_P2WPKH to 49, ScriptType.P2TR to 86)) {
        val coins = (0..1).map { k ->
            val node = Bip32Priv.derivePath(pm, "m/$purpose'/0'/0'/$k/${k + 1}")
            com.kilombino.pyblockwatch.crypto.Psbt.Coin(det("psbt-prev-$purpose-$k").toHex(), k, 70_000L + 1_000L * k, type, node.publicKey(),
                com.kilombino.pyblockwatch.crypto.Psbt.Origin(byteArrayOf(1, 2, 3, 4), listOf(purpose + 0x80000000L, 0x80000000L, 0x80000000L, k.toLong(), (k + 1).toLong()))) to node
        }
        val changePub = Bip32Priv.derivePath(pm, "m/$purpose'/0'/0'/1/5").publicKey()
        val outputs = listOf(
            TxBuilder.Output(Address.decodeToScriptPubKey(recipients[purpose % 3]), 100_000),
            TxBuilder.Output(Address.scriptPubKey(changePub, type), 39_000),
        )
        val change = mapOf(1 to com.kilombino.pyblockwatch.crypto.Psbt.Change(changePub, type,
            com.kilombino.pyblockwatch.crypto.Psbt.Origin(byteArrayOf(1, 2, 3, 4), listOf(purpose + 0x80000000L, 0x80000000L, 0x80000000L, 1L, 5L))))
        val exported = com.kilombino.pyblockwatch.crypto.Psbt.create(coins.map { it.first }, outputs, change)
        val signedInputs = coins.map { (c, node) -> TxBuilder.Input(c.txid, c.vout, c.value, node.key, node.publicKey(), c.sequence, type) }
        val parsed = com.kilombino.pyblockwatch.crypto.Psbt.parse(exported)
        val newInputs = parsed.inputs.mapIndexed { i, m ->
            val sig = TxBuilder.witnessSignature(2, signedInputs, outputs, i, 0, unified = true, auxRand = det("psbt-aux-$purpose-$i"))
            m + listOf(if (type == ScriptType.P2TR) byteArrayOf(0x13) to sig else (byteArrayOf(0x02) + coins[i].first.pubkey) to sig)
        }
        val returned = com.kilombino.pyblockwatch.crypto.Psbt.serialize(com.kilombino.pyblockwatch.crypto.Psbt.Parsed(parsed.global, newInputs, parsed.outputs))
        val final = com.kilombino.pyblockwatch.crypto.Psbt.finish(exported, returned, coins.map { it.first }, outputs)
        val enc = com.kilombino.pyblockwatch.crypto.Ur.psbtEncoder(exported)
        psbts.put(JSONObject().put("type", type.name).put("purpose", purpose)
            .put("coins", JSONArray(coins.map { (c, _) -> JSONObject().put("txid", c.txid).put("vout", c.vout).put("value", c.value)
                .put("pubkey", c.pubkey.toHex()).put("origin", c.origin!!.bytes().toHex()) }))
            .put("changePubkey", changePub.toHex())
            .put("outputs", JSONArray(outputs.map { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) }))
            .put("exported", exported.toHex()).put("returned", returned.toHex()).put("raw", final.rawHex).put("txid", final.txid)
            .put("ur", JSONArray((0 until 12).map { enc.nextPart() })))
    }

    val json = JSONObject().put("androidCommit", commit).put("taprootWallets", wallets).put("taprootTxs", txs)
        .put("dice", dice).put("kab", kab).put("psbts", psbts).toString().replace("},{", "},\n{")
    out.writeText(
        """
        |package com.kilombino.kilowallet.core
        |
        |// GENERATED by tools/android-vectors (./gradlew -p tools/android-vectors onchain) from the Android
        |// app's own code (Kilombino/kilowallet commit $commit), unchanged, on the JVM. Do not edit.
        |internal object OnchainVectors {
        |    const val COMMIT = "$commit"
        |    val JSON = ""${'"'}
        |${json.replace("$", "${'$'}{'$'}")}
        |""${'"'}
        |}
        |""".trimMargin()
    )
    println("wrote ${out.absolutePath} (${json.length} chars); the Android code opened the iOS backup file")
}
