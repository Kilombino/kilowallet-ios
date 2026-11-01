package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.backup.AesGcm
import com.kilombino.kilowallet.core.backup.Zip
import com.kilombino.kilowallet.core.crypto.Scrypt
import com.kilombino.kilowallet.core.ln.LnMove
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Lightning move file both ways against the JVM's own zip and AES-GCM, exactly as Android's
 * LnMove.export writes it (ZipOutputStream with deflated entries, Cipher "AES/GCM/NoPadding",
 * the key from scrypt 2^16/8/1): a file made on one phone opens on the other.
 */
class LnMoveTest {
    private val files = listOf(
        "data/chain/bitcoin/mainnet/wallet.db" to ByteArray(300_000) { (it * 7 + it / 13).toByte() },
        "data/graph/mainnet/channel.db" to ByteArray(120_000) { (it % 251).toByte() },
        "data/chain/bitcoin/mainnet/admin.macaroon" to "macaroon".encodeToByteArray(),
        "data/chain/bitcoin/mainnet/neutrino.db" to ByteArray(1000),
        "logs/bitcoin/mainnet/lnd.log" to "log".encodeToByteArray(),
    )
    private val walletPw = "ab".repeat(32).encodeToByteArray()

    /** Android's export, line for line, on the JVM. */
    private fun androidPack(password: String): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            files.filter { !LnMove.skip(it.first) }.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            z.putNextEntry(ZipEntry("kilowallet/password")); z.write(walletPw); z.closeEntry()
            z.putNextEntry(ZipEntry("kilowallet/birthday")); z.write("1788220800".toByteArray()); z.closeEntry()
        }
        val salt = ByteArray(16) { it.toByte() }
        val key = SecretKeySpec(Scrypt.derive(password.toByteArray(), salt, 1 shl 16, 8, 1, 32), "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return "KWLNMOVE2".toByteArray() + salt + c.iv + c.doFinal(zip.toByteArray())
    }

    @Test fun aFileMadeOnAndroidOpensHere() {
        val e = LnMove.entries(androidPack("correct horse battery"), "correct horse battery").toMap()
        assertTrue(LnMove.hasWallet(e.toList()))
        assertContentEquals(files[0].second, e["data/chain/bitcoin/mainnet/wallet.db"])
        assertContentEquals(files[1].second, e["data/graph/mainnet/channel.db"])
        assertContentEquals(walletPw, e[LnMove.PASSWORD_ENTRY])
        assertEquals("1788220800", e[LnMove.BIRTHDAY_ENTRY]!!.decodeToString())
        assertTrue("data/chain/bitcoin/mainnet/neutrino.db" !in e)
        assertFailsWith<LnMove.WrongPassword> { LnMove.entries(androidPack("correct horse battery"), "correct horse batterz") }
    }

    @Test fun aFileMadeHereOpensOnAndroid() {
        val pack = LnMove.pack(files, walletPw, 1_788_220_800L, "correct horse battery")
        // Android's import: magic, salt, IV, AES-GCM with the scrypt key, then ZipInputStream.
        assertContentEquals("KWLNMOVE2".toByteArray(), pack.copyOfRange(0, 9))
        val salt = pack.copyOfRange(9, 25); val iv = pack.copyOfRange(25, 37)
        val key = SecretKeySpec(Scrypt.derive("correct horse battery".toByteArray(), salt, 1 shl 16, 8, 1, 32), "AES")
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            .doFinal(pack, 37, pack.size - 37)
        val got = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(plain)).use { z -> while (true) { val en = z.nextEntry ?: break; got[en.name] = z.readBytes() } }
        assertContentEquals(files[0].second, got["data/chain/bitcoin/mainnet/wallet.db"])
        assertContentEquals(files[2].second, got["data/chain/bitcoin/mainnet/admin.macaroon"])
        assertContentEquals(walletPw, got["kilowallet/password"])
        assertEquals(setOf("data/chain/bitcoin/mainnet/wallet.db", "data/graph/mainnet/channel.db",
            "data/chain/bitcoin/mainnet/admin.macaroon", "kilowallet/password", "kilowallet/birthday"), got.keys)
    }

    @Test fun scryptMatchesRfc7914() {
        // RFC 7914 §12, the second vector: "password", "NaCl", N=1024, r=8, p=16.
        val dk = Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)
        assertEquals("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            dk.joinToString("") { "%02x".format(it) })
    }

    @Test fun noEntryLeavesTheFolder() {
        assertTrue(LnMove.safePath("data/chain/x.db"))
        assertTrue(!LnMove.safePath("../x") && !LnMove.safePath("/etc/x") && !LnMove.safePath("data/../../x"))
    }

    @Test fun aesGcmSpeed() {
        val big = ByteArray(8_000_000)
        val t = System.nanoTime(); AesGcm.encrypt(ByteArray(32), ByteArray(12), big)
        println("AES-GCM 8 MB: ${(System.nanoTime() - t) / 1_000_000} ms")
        val t2 = System.nanoTime(); Zip.read(Zip.write(listOf("a" to big)))
        println("zip 8 MB: ${(System.nanoTime() - t2) / 1_000_000} ms")
    }
}
