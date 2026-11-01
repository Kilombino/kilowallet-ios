import com.kilombino.pyblockwatch.ln.AndroidLnSeed
import com.kilombino.pyblockwatch.ln.Pb
import com.kilombino.pyblockwatch.ln.PbMsg
import com.kilombino.pyblockwatch.ln.TxSize
import com.kilombino.pyblockwatch.ln.cpfpChildRate
import java.io.File
import java.security.MessageDigest

/**
 * Lightning vectors from the Android app's own code: the node keys from seeds (ln/LnSeed.kt),
 * the bytes of the requests the app sends LND (built with ln/Proto.kt exactly as ln/Lnd.kt builds
 * them), what the reader makes of a message, and transaction sizes and CPFP rates (ln/TxSize.kt).
 */
fun main(args: Array<String>) {
    val out = File(args[0]); val commit = args[1]
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun seed(i: Int) = MessageDigest.getInstance("SHA-512").digest("kilowallet ln vector $i".toByteArray())
    val seeds = (0 until 12).map { i -> val s = seed(i); listOf(hex(s), AndroidLnSeed.masterXprv(s.copyOf()), AndroidLnSeed.nodePubkey(s.copyOf())).joinToString("\t") }

    val point = "a1".repeat(32) + ":1"
    val pk = "02" + "5b".repeat(32)
    val req = linkedMapOf(
        "initWallet" to Pb().bytes(1, "00ff".repeat(16).toByteArray()).string(7, "xprv-test").int(8, 1788220800L).int(4, 2500)
            .msg(5, Pb().msg(2, Pb().bytes(2, byteArrayOf(1, 2, 3)))).build(),
        "newAddress" to Pb().int(1, 4).build(),
        "listChannels" to Pb().bool(6, true).build(),
        "openChannelSync" to Pb().string(3, pk).int(4, 250_000).int(1, 2).bool(8, true).bool(26, false)
            .msg(28, Pb().string(2, "c3".repeat(32)).int(3, 0)).build(),
        "sendPayment" to Pb().string(5, "lnbc1testinvoice").int(6, 60).int(7, 25).int(2, 1234).int(19, 987654321L).build(),
        "payOffer" to Pb().string(1, "lno1testoffer").int(6, 60).int(7, 25_000).int(3, 5_000_000).build(),
        "closeChannel" to Pb().msg(1, Pb().string(2, point.substringBefore(':')).int(3, point.substringAfter(':').toInt())).bool(2, false)
            .int(6, 3).string(5, "bc1qtest").build(),
        "addInvoice" to Pb().string(1, "memo é").int(5, 21_000).int(11, 3600).bool(15, true).build(),
        "connectPeer" to Pb().msg(1, Pb().string(1, pk).string(2, "ln.kilombino.com:50002")).int(3, 30).build(),
        "fundPsbt" to Pb().msg(2, Pb().msg(1, Pb().string(2, "d4".repeat(32)).int(3, 2)).mapEntry(2, "bc1qfunding", 300_000)).int(4, 3).int(6, 1).int(8, 1).build(),
        "negative" to Pb().int(2, -1).int(1, 0).build(),
        "bigint" to Pb().int(1, Long.MAX_VALUE).int(2, 1L shl 40).build(),
    )
    val requests = req.map { (k, v) -> "$k\t${hex(v)}" }

    // A message as LND would send it, read back field by field.
    val m = Pb().string(1, pk).string(2, "alias").int(6, 979_937).bool(9, true).int(5, 3)
        .msg(11, Pb().bool(1, true).string(3, point).int(5, 1_000_000)).msg(11, Pb().string(3, "x:0").int(5, 2)).int(13, 1793474821L).build()
    val pm = PbMsg.parse(m)
    val read = listOf(hex(m), pm.string(1), pm.string(2), pm.int(6).toString(), pm.bool(9).toString(), pm.int(5).toString(),
        pm.msgs(11).size.toString(), pm.msgs(11)[0].string(3), pm.msgs(11)[1].long(5).toString(), pm.long(13).toString()).joinToString("\t")

    // Transactions (a segwit and a legacy one) and the CPFP rate for a few targets.
    val txs = listOf(
        "02000000000101" + "11".repeat(32) + "00000000" + "00" + "fdffffff" + "02" +
            "a086010000000000" + "160014" + "22".repeat(20) + "50c3000000000000" + "225120" + "33".repeat(32) +
            "02" + "47" + "44".repeat(71) + "21" + "02" + "55".repeat(32) + "00000000",
        "01000000" + "01" + "66".repeat(32) + "01000000" + "6a" + "77".repeat(106) + "ffffffff" + "01" +
            "e803000000000000" + "19" + "76a914" + "88".repeat(20) + "88ac" + "00000000",
    ).map { t -> val z = TxSize.of(t); "$t\t${z.vsize}\t${z.outputs}" }
    val cpfp = listOf(listOf(5L, 141L, 141L, 111L), listOf(20L, 300L, 600L, 111L), listOf(1L, 200L, 1000L, 111L), listOf(50L, 1000L, 1500L, 111L))
        .map { (t, pv, pf, cv) -> "$t\t$pv\t$pf\t$cv\t${cpfpChildRate(t, pv, pf, cv)}" }

    // channel.backup made by LND's own code for its test aezeed (the Android app's ScbTest): the root
    // seed it is opened with, the file, and each channel as the Android reader lists it.
    val res = File("build/androidSrc/res")
    val aez = ("about luggage aunt phone relief reunion crack pig cigar travel spin veteran " +
        "dinner art wealth thrive zone venture valve setup phone chief artist high").split(" ")
    val entropy = com.kilombino.pyblockwatch.crypto.Aezeed.decode(aez, "").entropy
    val root = com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(entropy)
    val scbHex = res.resolve("scb_vector.hex").readText().trim()
    val scbFile = com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(scbHex)
    val chans = com.kilombino.pyblockwatch.data.Scb.decode(scbFile, root)
    val scb = listOf("${hex(entropy)}\t$scbHex\t${hex(com.kilombino.pyblockwatch.data.Scb.encryptionKey(root))}") + chans.map { c ->
        val local = com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(root, "m/1017'/0'/0'/0/${c.localKeyIndex[0]}").publicKey()
        listOf(c.version, c.isInitiator, c.fundingTxid, c.fundingVout, c.shortChannelId, c.remoteNode, c.capacity, c.localCsvDelay,
            (0..4).joinToString(",") { "${c.localKeyIndex[it]}" }, c.remoteCsvDelay, hex(c.remotePayment), c.shaChainIndex,
            hex(c.fundingScript(local))).joinToString("\t")
    }
    // XChaCha20-Poly1305 opened by the Android code: key, nonce, ciphertext+tag, associated data, plain ("-" = refused).
    val xk = MessageDigest.getInstance("SHA-256").digest("xchacha key".toByteArray())
    val nonce = scbFile.copyOfRange(0, 24); val body = scbFile.copyOfRange(24, scbFile.size)
    val ek = com.kilombino.pyblockwatch.data.Scb.encryptionKey(root)
    val xchacha = listOf(
        listOf(hex(ek), hex(nonce), hex(body), hex(nonce)),
        listOf(hex(ek), hex(nonce), hex(body.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }), hex(nonce)),
        listOf(hex(xk), hex(nonce), hex(body), hex(nonce)),
    ).map { (k, n, c, a) ->
        val plain = com.kilombino.pyblockwatch.crypto.XChaCha20Poly1305.open(
            com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(k), com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(n),
            com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(c), com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(a))
        "$k\t$n\t$c\t$a\t${plain?.let { hex(it) } ?: "-"}"
    }

    fun block(name: String, rows: List<String>) = "    val $name = \"\"\"\n${rows.joinToString("\n")}\n\"\"\""
    out.writeText(
        """
        |package com.kilombino.kilowallet.core
        |
        |// GENERATED by tools/android-vectors (./gradlew -p tools/android-vectors ln) from the Android
        |// app's ln/LnSeed.kt, ln/Proto.kt, ln/TxSize.kt, data/Scb.kt and crypto/XChaCha20Poly1305.kt (Kilombino/kilowallet commit $commit).
        |internal object LnVectors {
        |    const val COMMIT = "$commit"
        |    // seed (BIP-39 seed bytes, hex), masterXprv, nodePubkey
        |${block("SEEDS", seeds)}
        |    // request, protobuf bytes
        |${block("REQUESTS", requests)}
        |    // message, then what the reader gives: string(1), string(2), int(6), bool(9), int(5), msgs(11).size, msgs(11)[0].string(3), msgs(11)[1].long(5), long(13)
        |${block("READ", listOf(read))}
        |    // raw tx, vsize, sum of outputs
        |${block("TX", txs)}
        |    // target, parent vsize, parent fee, child vsize, child rate
        |${block("CPFP", cpfp)}
        |    // channel.backup: first row root seed, file, encryption key; then per channel: version, initiator, funding txid,
        |    // vout, scid, peer, capacity, local csv, local key indices (families 0..4), remote csv, remote payment key, shachain index, funding script
        |${block("SCB", scb)}
        |    // XChaCha20-Poly1305: key, nonce, ciphertext+tag, ad, plain ("-": does not authenticate)
        |${block("XCHACHA", xchacha)}
        |}
        |""".trimMargin()
    )
    println("wrote ${out.absolutePath}")
}
