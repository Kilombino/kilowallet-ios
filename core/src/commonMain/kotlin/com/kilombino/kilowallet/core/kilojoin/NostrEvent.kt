package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A NIP-01 event, as the Android app's `coinjoin/NostrEvent.kt`. [id] and [sig] are hex; [pubkey] the x-only key in hex. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JsonObject = J.obj(
        "id" to id, "pubkey" to pubkey, "created_at" to createdAt, "kind" to kind,
        "tags" to tags, "content" to content, "sig" to sig,
    )

    /** True when the id is the hash of the content and the signature is the author's. */
    fun verify(): Boolean = runCatching {
        val h = hash(pubkey, createdAt, kind, tags, content)
        h.toHex() == id && Ec.schnorrVerify(Hashes.hexToBytes(pubkey), h, Hashes.hexToBytes(sig))
    }.getOrDefault(false)

    companion object {
        /** The NIP-01 serialisation `[0,pubkey,created_at,kind,tags,content]`, written by hand as the Android app does. */
        internal fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val sb = StringBuilder("[0,")
            str(sb, pubkey); sb.append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append('['); t.forEachIndexed { j, v -> if (j > 0) sb.append(','); str(sb, v) }; sb.append(']')
            }
            sb.append("],"); str(sb, content); sb.append(']')
            return sb.toString()
        }

        private fun str(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
            sb.append('"')
        }

        fun hash(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray =
            Hashes.sha256(serialize(pubkey, createdAt, kind, tags, content).encodeToByteArray())

        @OptIn(kotlin.time.ExperimentalTime::class)
        fun nowSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

        /** [aux] only for test vectors (BIP-340's auxiliary randomness). */
        fun sign(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String,
                 createdAt: Long = nowSeconds(), aux: ByteArray = secureRandomBytes(32)): NostrEvent {
            val pub = pubOf(secret)
            val h = hash(pub, createdAt, kind, tags, content)
            return NostrEvent(h.toHex(), pub, createdAt, kind, tags, content, Ec.schnorrSign(secret, h, aux).toHex())
        }

        fun parse(o: JsonObject): NostrEvent {
            val tags = o.arr("tags").map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            return NostrEvent(o.str("id"), o.str("pubkey"), o.long("created_at"), o.int("kind"), tags, o.str("content"), o.str("sig"))
        }

        /** A fresh random key (32 bytes): every pool and every role in it gets its own identity. */
        fun newSecret(): ByteArray {
            while (true) {
                val k = secureRandomBytes(32)
                if (Ec.isValidSecret(k)) return k
            }
        }

        fun pubOf(secret: ByteArray): String = Ec.xOnly(secret).toHex()

        /** A secret kept as hex: Android writes it without leading zeros (BigInteger.toString(16)). */
        fun secretOf(hex: String): ByteArray = Hashes.hexToBytes(hex.padStart(64, '0'))
    }
}
