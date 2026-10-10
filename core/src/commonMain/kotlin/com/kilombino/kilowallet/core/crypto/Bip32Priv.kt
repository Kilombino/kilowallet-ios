package com.kilombino.kilowallet.core.crypto

/**
 * BIP-32 PRIVATE derivation, ported from the Android app's `crypto/Bip32Priv.kt`. Scalars are
 * 32-byte arrays and `(IL + kpar) mod n` is libsecp256k1's secret tweak-add.
 */
object Bip32Priv {

    private const val HARDENED = 0x80000000.toInt()

    class ExtendedPrivKey(
        /** The private scalar k, 32 bytes big-endian. */
        val key: ByteArray,
        val chainCode: ByteArray,
        val depth: Int,
    ) {
        /** 33-byte compressed public key for this node. */
        fun publicKey(): ByteArray = Ec.publicKey(key)

        fun keyBytes(): ByteArray = key.copyOf()

        /** BIP-32 fingerprint: the first 4 bytes of hash160 of this node's public key. */
        fun fingerprint(): ByteArray = Hashes.hash160(publicKey()).copyOf(4)

        /** Overwrite the secret material once it is no longer needed. */
        fun wipe() { key.fill(0); chainCode.fill(0) }
    }

    private fun xpubVersion(purpose: Int): Int = when (purpose) {
        84 -> 0x04B24746 // zpub
        49 -> 0x049D7CB2 // ypub
        else -> 0x0488B21E // xpub
    }

    /** The account-level extended PUBLIC key (a zpub for m/84'/0'/0'). */
    fun accountXpub(master: ExtendedPrivKey, purpose: Int, account: Int = 0): String {
        val coinNode = deriveChild(deriveChild(master, purpose, hardened = true), 0, hardened = true)
        val accountNode = deriveChild(coinNode, account, hardened = true)
        val childNumber = account or HARDENED
        val v = xpubVersion(purpose)
        val out = ByteArray(78)
        out[0] = (v ushr 24).toByte(); out[1] = (v ushr 16).toByte()
        out[2] = (v ushr 8).toByte(); out[3] = v.toByte()
        out[4] = 3
        coinNode.fingerprint().copyInto(out, 5)
        out[9] = (childNumber ushr 24).toByte(); out[10] = (childNumber ushr 16).toByte()
        out[11] = (childNumber ushr 8).toByte(); out[12] = childNumber.toByte()
        accountNode.chainCode.copyInto(out, 13)
        accountNode.publicKey().copyInto(out, 45)
        coinNode.wipe(); accountNode.wipe()
        return Base58.encodeChecked(out)
    }

    /** Master key from a BIP-39 seed: I = HMAC-SHA512("Bitcoin seed", seed). */
    fun fromSeed(seed: ByteArray): ExtendedPrivKey {
        val i = Hashes.hmacSha512("Bitcoin seed".encodeToByteArray(), seed)
        val il = i.copyOfRange(0, 32)
        require(Ec.isValidSecret(il)) { "invalid master key (retry with different seed)" }
        return ExtendedPrivKey(il, i.copyOfRange(32, 64), 0)
    }

    /** CKDpriv at [index]. */
    fun deriveChild(parent: ExtendedPrivKey, index: Int, hardened: Boolean = false): ExtendedPrivKey {
        val idx = if (hardened) (index or HARDENED) else index
        val isHardened = (idx and HARDENED) != 0
        val data = if (isHardened) byteArrayOf(0x00) + parent.key + ser32(idx)
                   else parent.publicKey() + ser32(idx)
        val i = Hashes.hmacSha512(parent.chainCode, data)
        data.fill(0)
        val childKey = Ec.secretAdd(parent.key, i.copyOfRange(0, 32))
        return ExtendedPrivKey(childKey, i.copyOfRange(32, 64), parent.depth + 1)
    }

    /** Derive a full path such as "m/84'/0'/0'/0/0". Accepts ' or h for hardened steps. */
    fun derivePath(master: ExtendedPrivKey, path: String): ExtendedPrivKey {
        var node = master
        for (part in path.split('/')) {
            val p = part.trim()
            if (p.isEmpty() || p == "m") continue
            val hardened = p.endsWith("'") || p.endsWith("h") || p.endsWith("H")
            val n = p.trimEnd('\'', 'h', 'H').toInt()
            val next = deriveChild(node, n, hardened)
            if (node !== master) node.wipe()
            node = next
        }
        return node
    }

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(),
    )
}
