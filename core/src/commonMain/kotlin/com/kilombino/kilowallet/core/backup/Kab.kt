package com.kilombino.kilowallet.core.backup

import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * The backup file (.kab), byte-compatible with the Android app's `ark/ArkBackup.kt`: a file made on
 * one phone restores on the other. Layout: "KARKBAK1", one flag byte (1 = password), a 16-byte
 * salt, a 12-byte IV, then AES-256-GCM over gzip(JSON), the key from PBKDF2-HMAC-SHA256 × 310 000.
 *
 * The JSON holds the words and, only when there is one, their passphrase, and the app's settings
 * ([Snapshot.app], Android's `AppBackup` JSON). This iOS build has no Ark wallet: a file with one
 * restores the words and settings, and says the Ark part was left out.
 */
object Kab {
    private val MAGIC = "KARKBAK1".encodeToByteArray()
    const val ITERATIONS = 310_000
    /** The shortest password a new backup file takes (Android app value). */
    const val MIN_PASSWORD = 8

    class Snapshot(
        val words: List<String>,
        val passphrase: String = "",
        val created: Long = 0,
        val movements: Int = 0,
        /** Settings, as the Android app's `AppBackup` JSON; null in older files. */
        val app: JsonObject? = null,
        /** The contacts list as JSON text, or null. */
        val contacts: String? = null,
        /** The file carries an Ark wallet (its config and database), which this build cannot restore. */
        val hasArk: Boolean = false,
    )

    class WrongPassword : Exception("Wrong password.")

    fun isBackup(file: ByteArray): Boolean = file.size > MAGIC.size + 1 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** The file, always encrypted (a backup without a password would hand the words to whoever gets it). */
    fun encode(s: Snapshot, password: String, salt: ByteArray = secureRandomBytes(16), iv: ByteArray = secureRandomBytes(12)): ByteArray {
        require(password.isNotEmpty()) { "A backup file needs a password." }
        val json = buildJsonObject {
            put("format", "kilombino-ark-backup")
            // As the Android app: 3 when there is no Ark wallet in it (an older app could not restore it).
            put("version", 3)
            put("network", "mainnet")
            put("created", s.created)
            put("movements", s.movements)
            put("mnemonic", s.words.joinToString(" "))
            if (s.passphrase.isNotEmpty()) put("passphrase", s.passphrase)
            s.contacts?.let { put("contacts", Json.parseToJsonElement(it)) }
            s.app?.let { put("app", it) }
        }
        val payload = Gzip.gzip(json.toString().encodeToByteArray())
        return MAGIC + byteArrayOf(1) + salt + iv + AesGcm.encrypt(deriveKey(password, salt), iv, payload)
    }

    fun decode(file: ByteArray, password: String?): Snapshot {
        require(isBackup(file)) { "Not a Kilombino backup file." }
        var pos = MAGIC.size + 1
        val payload = if (file[MAGIC.size].toInt() == 1) {
            if (password.isNullOrEmpty()) throw WrongPassword()
            val salt = file.copyOfRange(pos, pos + 16); pos += 16
            val iv = file.copyOfRange(pos, pos + 12); pos += 12
            try { AesGcm.decrypt(deriveKey(password, salt), iv, file.copyOfRange(pos, file.size)) }
            catch (e: AesGcm.BadTag) { throw WrongPassword() }
        } else file.copyOfRange(pos, file.size)
        val j = Json.parseToJsonElement(Gzip.gunzip(payload).decodeToString()) as JsonObject
        fun str(k: String) = (j[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(str("format") == "kilombino-ark-backup") { "Not a Kilombino backup file." }
        require((j["version"]?.jsonPrimitive?.intOrNull ?: 0) in 1..4) { "This backup was made by a newer version of the app." }
        require(str("network") == "mainnet") { "This backup is not for BTC mainnet." }
        return Snapshot(
            words = (str("mnemonic") ?: error("The backup has no recovery words.")).trim().split(Regex("\\s+")),
            passphrase = str("passphrase") ?: "",
            created = j["created"]?.jsonPrimitive?.longOrNull ?: 0,
            movements = j["movements"]?.jsonPrimitive?.intOrNull ?: 0,
            app = j["app"] as? JsonObject,
            contacts = j["contacts"]?.toString(),
            hasArk = !str("config").isNullOrEmpty() && !str("db").isNullOrEmpty(),
        )
    }

    /** PBKDF2-HMAC-SHA256 for a 32-byte key (one block), as the JVM's PBKDF2WithHmacSHA256 makes it. */
    internal fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val mac = HmacSHA256(password.encodeToByteArray())
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        return t
    }
}
