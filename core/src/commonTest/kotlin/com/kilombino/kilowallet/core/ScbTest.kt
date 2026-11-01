package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.XChaCha20Poly1305
import com.kilombino.kilowallet.core.ln.Scb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * channel.backup as LND writes it and the Android app reads it ([LnVectors.SCB], [LnVectors.XCHACHA]):
 * decrypted, its channels listed with their funding scripts, refused for another seed; and
 * XChaCha20-Poly1305 / Poly1305 on their own.
 */
class ScbTest {
    private fun rows(tsv: String) = tsv.trim().lines().map { it.split('\t') }
    private fun hex(s: String) = Hashes.hexToBytes(s)

    @Test fun theBackupOpensAndListsItsChannelsAsTheAndroidApp() {
        val all = rows(LnVectors.SCB)
        val (seed, file, key) = all.first()
        val root = Bip32Priv.fromSeed(hex(seed))
        assertEquals(key, Scb.encryptionKey(root).toHex())
        val chans = Scb.decode(hex(file), root)
        assertEquals(all.size - 1, chans.size)
        chans.zip(all.drop(1)).forEach { (c, e) ->
            assertEquals(e[0].toInt(), c.version)
            assertEquals(e[1].toBoolean(), c.isInitiator)
            assertEquals(e[2], c.fundingTxid)
            assertEquals(e[3].toInt(), c.fundingVout)
            assertEquals(e[4], c.shortChannelId)
            assertEquals(e[5], c.remoteNode)
            assertEquals(e[6].toLong(), c.capacity)
            assertEquals(e[7].toInt(), c.localCsvDelay)
            assertEquals(e[8], (0..4).joinToString(",") { "${c.localKeyIndex[it]}" })
            assertEquals(e[9].toInt(), c.remoteCsvDelay)
            assertEquals(e[10], c.remotePayment.toHex())
            assertEquals(e[11].toInt(), c.shaChainIndex)
            val local = Bip32Priv.derivePath(root, "m/1017'/0'/0'/0/${c.localKeyIndex[0]}").publicKey()
            assertEquals(e[12], c.fundingScript(local).toHex())
        }
    }

    @Test fun anotherSeedCannotOpenIt() {
        val file = hex(rows(LnVectors.SCB).first()[1])
        assertFailsWith<Scb.WrongSeed> { Scb.decode(file, Bip32Priv.fromSeed(ByteArray(16) { 7 })) }
    }

    @Test fun xchachaOpensOrRefusesAsTheAndroidApp() {
        for ((k, n, c, a, plain) in rows(LnVectors.XCHACHA)) {
            val got = XChaCha20Poly1305.open(hex(k), hex(n), hex(c), hex(a))
            if (plain == "-") assertNull(got) else assertEquals(plain, got!!.toHex())
        }
    }

    @Test fun poly1305AsRfc8439() {
        // RFC 8439 §2.5.2.
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9",
            XChaCha20Poly1305.poly1305(key, "Cryptographic Forum Research Group".encodeToByteArray()).toHex())
    }
}
