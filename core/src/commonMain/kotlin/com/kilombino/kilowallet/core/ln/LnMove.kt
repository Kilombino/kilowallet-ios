package com.kilombino.kilowallet.core.ln

import com.kilombino.kilowallet.core.backup.AesGcm
import com.kilombino.kilowallet.core.backup.Kab
import com.kilombino.kilowallet.core.backup.Zip
import com.kilombino.kilowallet.core.crypto.Scrypt
import com.kilombino.kilowallet.core.platform.secureRandomBytes

/**
 * Moving the Lightning node to another phone with its channels open, the Android app's
 * `ln/LnMove.kt` (1.4.4) file format: the node's whole state (wallet, channel database,
 * macaroons) and its wallet password, zipped and sealed with a password the user chooses, so a
 * file made on Android opens on iPhone and the other way round.
 *
 * Layout: "KWLNMOVE2", a 16-byte salt, a 12-byte IV, AES-256-GCM over the zip; the key from
 * scrypt (N 2^16, r 8, p 1: 64 MB). "KWLNMOVE1" files (PBKDF2-HMAC-SHA256 × 310 000) still open.
 * Extra entries: "kilowallet/password" (the bytes LND's wallet is sealed with) and
 * "kilowallet/birthday" (decimal seconds).
 *
 * The danger is two copies: if the old phone ever ran the node again it would publish an old
 * channel state, which the peer punishes by taking the whole channel. The app stops the node,
 * checks the file reads back, and deletes the node here once the file is saved.
 */
object LnMove {
    private val MAGIC = "KWLNMOVE2".encodeToByteArray()
    private val MAGIC_V1 = "KWLNMOVE1".encodeToByteArray()
    private const val SCRYPT_N = 1 shl 16
    /** The file holds the node's keys: a password at least this long. */
    const val MIN_PASSWORD = 12
    const val PASSWORD_ENTRY = "kilowallet/password"
    const val BIRTHDAY_ENTRY = "kilowallet/birthday"

    /** The chain sync data: large, and the new phone downloads it again. Logs stay too. */
    fun skip(relPath: String): Boolean {
        val name = relPath.substringAfterLast('/')
        return name in setOf("neutrino.db", "block_headers.bin", "reg_filter_headers.bin") ||
            relPath.startsWith("logs/") || "/logs/" in relPath
    }

    class WrongPassword : Exception("Wrong password, or not a Lightning move file.")

    /** The sealed file for [files] (paths relative to LND's folder) plus the wallet password and birthday. */
    fun pack(files: List<Pair<String, ByteArray>>, walletPassword: ByteArray, birthday: Long, password: String,
             salt: ByteArray = secureRandomBytes(16), iv: ByteArray = secureRandomBytes(12)): ByteArray {
        require(password.length >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters." }
        val zip = Zip.write(files.filterNot { skip(it.first) } +
            listOf(PASSWORD_ENTRY to walletPassword, BIRTHDAY_ENTRY to birthday.toString().encodeToByteArray()))
        return MAGIC + salt + iv + AesGcm.encrypt(key(password, salt, v1 = false), iv, zip)
    }

    /** Every entry of a move file, opened with [password]. */
    fun entries(pack: ByteArray, password: String): List<Pair<String, ByteArray>> {
        if (pack.size < MAGIC.size + 28) throw WrongPassword()
        val head = pack.copyOfRange(0, MAGIC.size)
        val v1 = head.contentEquals(MAGIC_V1)
        if (!v1 && !head.contentEquals(MAGIC)) throw WrongPassword()
        var p = MAGIC.size
        val salt = pack.copyOfRange(p, p + 16); p += 16
        val iv = pack.copyOfRange(p, p + 12); p += 12
        val plain = try { AesGcm.decrypt(key(password, salt, v1), iv, pack.copyOfRange(p, pack.size)) }
            catch (e: AesGcm.BadTag) { throw WrongPassword() }
        return Zip.read(plain)
    }

    /** A move file must hold a Lightning wallet. */
    fun hasWallet(entries: List<Pair<String, ByteArray>>) = entries.any { it.first.endsWith("wallet.db") }

    /** No entry may point outside LND's folder ("../", absolute paths). */
    fun safePath(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.startsWith("\\") && name.split('/', '\\').none { it == ".." }

    private fun key(password: String, salt: ByteArray, v1: Boolean): ByteArray {
        val pw = password.encodeToByteArray()
        return try { if (v1) Kab.deriveKey(password, salt) else Scrypt.derive(pw, salt, SCRYPT_N, 8, 1, 32) } finally { pw.fill(0) }
    }
}
