package com.kilombino.kilowallet.core.crypto

/** Which script type an extended public key implies, per SLIP-132 version bytes. */
enum class ScriptType(val label: String, val explain: String) {
    P2PKH("Legacy", "Addresses that start with 1. Bitcoin's original format."),
    P2SH_P2WPKH("Nested SegWit", "Addresses that start with 3. SegWit wrapped for older wallets."),
    P2WPKH("Native SegWit", "Addresses that start with bc1q. The cheapest to spend."),
}

/**
 * BIP-32 public derivation (CKDpub), ported from the Android app's `crypto/Bip32.kt`. The point
 * addition `parse256(IL)·G + Kpar` is libsecp256k1's pubkey tweak-add instead of the app's
 * BigInteger affine arithmetic; the result is the same point.
 */
object Bip32 {

    private val VERSIONS = mapOf(
        0x0488B21E to ScriptType.P2PKH,        // xpub
        0x049D7CB2 to ScriptType.P2SH_P2WPKH,  // ypub
        0x04B24746 to ScriptType.P2WPKH,       // zpub
    )

    class ExtendedPubKey(
        /** 33-byte compressed SEC encoding of this node's public key. */
        val pubkey: ByteArray,
        val chainCode: ByteArray,
        val depth: Int,
        val scriptType: ScriptType,
    ) {
        fun pubkey(): ByteArray = pubkey

        override fun equals(other: Any?): Boolean =
            other is ExtendedPubKey && pubkey.contentEquals(other.pubkey) && chainCode.contentEquals(other.chainCode)

        override fun hashCode(): Int = pubkey.contentHashCode() * 31 + chainCode.contentHashCode()
    }

    /** Parse an xpub / ypub / zpub, with readable errors (this string is typed or pasted by a human). */
    fun parseExtendedPubKey(encoded: String): ExtendedPubKey {
        val raw = try {
            Base58.decodeChecked(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a valid extended key: ${e.message}")
        }
        require(raw.size == 78) { "Unexpected length (${raw.size} bytes, expected 78)." }

        val version = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val scriptType = VERSIONS[version]
            ?: throw IllegalArgumentException("Unknown prefix. Use xpub, ypub or zpub (not an xprv private key).")

        val depth = raw[4].toInt() and 0xFF
        val chainCode = raw.copyOfRange(13, 45)
        val keyBytes = raw.copyOfRange(45, 78)
        require(keyBytes[0].toInt() == 0x02 || keyBytes[0].toInt() == 0x03) {
            "This looks like an extended PRIVATE key. Never enter it here: use the public one (xpub/ypub/zpub)."
        }
        return ExtendedPubKey(Ec.parsePublicKey(keyBytes), chainCode, depth, scriptType)
    }

    /**
     * [encoded] (an xpub, ypub or zpub) with the version bytes of [type]: the same key, read as
     * making that type's addresses. A plain xpub says nothing about them (Android 1.2.4 asks for
     * the type on import), so the watch-only wallet keeps the key under its chosen type's prefix.
     */
    fun withScriptType(encoded: String, type: ScriptType): String {
        parseExtendedPubKey(encoded) // the same readable errors first
        val raw = Base58.decodeChecked(encoded.trim())
        val v = VERSIONS.entries.first { it.value == type }.key
        val out = raw.copyOf()
        out[0] = (v ushr 24).toByte(); out[1] = (v ushr 16).toByte(); out[2] = (v ushr 8).toByte(); out[3] = v.toByte()
        return Base58.encodeChecked(out)
    }

    /** CKDpub: I = HMAC-SHA512(cpar, serP(Kpar) ‖ ser32(i));  Ki = point(IL) + Kpar;  ci = IR */
    fun deriveChild(parent: ExtendedPubKey, index: Int): ExtendedPubKey {
        require(index >= 0) { "hardened derivation is impossible from a public key" }
        val data = parent.pubkey + byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(),
        )
        val i = Hashes.hmacSha512(parent.chainCode, data)
        val child = Ec.pointAddTweak(parent.pubkey, i.copyOfRange(0, 32))
        return ExtendedPubKey(child, i.copyOfRange(32, 64), parent.depth + 1, parent.scriptType)
    }

    /** Derive `parent/chain/index`, e.g. `0/5` for the sixth receive address. */
    fun derivePath(parent: ExtendedPubKey, chain: Int, index: Int): ExtendedPubKey =
        deriveChild(deriveChild(parent, chain), index)
}
