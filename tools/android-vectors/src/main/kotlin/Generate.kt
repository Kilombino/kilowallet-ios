import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxBuilder
import java.io.File

/**
 * Runs the Android app's crypto code (unchanged, pure-Kotlin path) over fixed inputs and writes
 * what it produced as a Kotlin source file of vectors for the iOS port's commonTest.
 */

private fun q(s: String) = "\"" + s + "\""
private fun obj(vararg kv: Pair<String, Any>): String = kv.joinToString(",", "{", "}") { (k, v) ->
    q(k) + ":" + when (v) {
        is String -> q(v)
        is List<*> -> v.joinToString(",", "[", "]") { if (it is String && !it.startsWith("{")) q(it) else it.toString() }
        else -> v.toString()
    }
}

private fun det(tag: String, n: Int = 32) = Hashes.sha256(tag.toByteArray()).copyOf(n)

fun main(args: Array<String>) {
    val out = File(args[0])
    val commit = args[1]

    val entropies = listOf(
        ByteArray(16), ByteArray(16) { 0x7f }, det("kw-ios-12", 16), det("kw-ios-12b", 16),
        ByteArray(32) { 0xff.toByte() }, det("kw-ios-24", 32),
    )
    val passphrases = listOf("", "", "", "TREZOR", "", "")

    val wallets = entropies.mapIndexed { wi, ent ->
        val words = Bip39.fromEntropy(ent)
        val pass = passphrases[wi]
        val seed = Bip39.toSeed(words, pass)
        val master = Bip32Priv.fromSeed(seed)
        val zpub = Bip32Priv.accountXpub(master, 84, 0)
        val parsed = Bip32.parseExtendedPubKey(zpub)
        val addrs = (0..1).flatMap { ch ->
            (0 until if (ch == 0) 6 else 3).map { i ->
                val pub = Bip32.derivePath(parsed, ch, i).pubkey()
                val priv = Bip32Priv.derivePath(master, "m/84'/0'/0'/$ch/$i")
                require(priv.publicKey().contentEquals(pub))
                obj(
                    "chain" to ch, "index" to i,
                    "address" to Address.encode(pub, ScriptType.P2WPKH),
                    "scriptHash" to Address.scriptHashFor(pub, ScriptType.P2WPKH),
                    "pubkey" to pub.toHex(), "privkey" to priv.keyBytes().toHex(),
                )
            }
        }
        obj(
            "entropy" to ent.toHex(), "words" to words.joinToString(" "), "passphrase" to pass,
            "seed" to seed.toHex(), "zpub" to zpub, "addresses" to addrs,
        ) to Triple(master, zpub, words)
    }

    // Plain ECDSA over fixed keys and hashes (no low-R grind: the app's normal sends do not grind).
    val ecdsa = (0 until 8).map { i ->
        val key = java.math.BigInteger(1, det("key$i")).mod(com.kilombino.pyblockwatch.crypto.Secp256k1.N)
        val h = det("msg$i")
        obj("key" to com.kilombino.pyblockwatch.crypto.NativeSecp.b32(key).toHex(), "hash" to h.toHex(),
            "der" to Ecdsa.der(Ecdsa.sign(key, h, grindLowR = false)).toHex())
    }

    // Whole transactions exactly as the app's hot wallet builds them for a BLAKE2b send.
    val recipients = listOf(
        "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
        "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297",
        "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
        "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
        "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3",
    )
    val txs = mutableListOf<String>()
    wallets.take(4).forEachIndexed { wi, (_, w) ->
        val (master, zpub, _) = w
        for (variant in 0 until 3) {
            val nIn = variant + 1
            val inputs = (0 until nIn).map { k ->
                val ch = k % 2; val idx = k + wi
                val node = Bip32Priv.derivePath(master, "m/84'/0'/0'/$ch/$idx")
                TxBuilder.Input(
                    det("prev-$wi-$variant-$k").toHex(), k * 3 + variant, 50_000L + 12_345L * k + wi,
                    node.key, node.publicKey(), 0xfffffffdL,
                )
            }
            val parsed = Bip32.parseExtendedPubKey(zpub)
            val change = Address.scriptPubKey(Bip32.derivePath(parsed, 1, 7).pubkey(), ScriptType.P2WPKH)
            val to = recipients[(wi + variant) % recipients.size]
            val outputs = listOf(
                TxBuilder.Output(Address.decodeToScriptPubKey(to), 20_000L + variant),
                TxBuilder.Output(change, inputs.sumOf { it.value } - 20_000L - variant - 300L),
            )
            for (unified in listOf(true, false)) {
                val s = TxBuilder.build(inputs, outputs, unified = unified)
                val coins = inputs.map { i ->
                    obj("txid" to i.txid, "vout" to i.vout, "value" to i.value,
                        "privkey" to com.kilombino.pyblockwatch.crypto.NativeSecp.b32(i.privateKey).toHex(),
                        "sequence" to i.sequence)
                }
                txs += obj(
                    "unified" to unified, "to" to to, "inputs" to coins,
                    "outputs" to outputs.map { obj("script" to it.scriptPubKey.toHex(), "value" to it.value) },
                    "sighash0" to (if (unified) TxBuilder.unifiedSighash(2, inputs, outputs, 0, 0)
                        else TxBuilder.sighash(2, inputs, outputs, 0, 0)).toHex(),
                    "raw" to s.rawHex, "txid" to s.txid, "vbytes" to s.vbytes,
                )
            }
        }
    }

    val decodes = (recipients + listOf(
        "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4",
        "bc1pqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqsyjer9e",
    )).map { a -> obj("address" to a, "script" to Address.decodeToScriptPubKey(a).toHex()) }

    val json = obj(
        "androidCommit" to commit, "wallets" to wallets.map { it.first }, "ecdsa" to ecdsa,
        "transactions" to txs, "decodes" to decodes,
    )
    val pretty = json.replace("},{", "},\n{")
    out.parentFile.mkdirs()
    out.writeText(
        """
        |package com.kilombino.kilowallet.core
        |
        |// GENERATED by tools/android-vectors from the Android app's own code (Kilombino/kilowallet
        |// commit $commit), unchanged, on the JVM. Do not edit: regenerate instead.
        |internal object AndroidVectors {
        |    const val COMMIT = "$commit"
        |    val JSON = ""${'"'}
        |$pretty
        |""${'"'}
        |}
        |""".trimMargin()
    )
    println("wrote ${out.absolutePath} (${pretty.length} chars)")
}
