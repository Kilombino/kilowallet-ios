package com.kilombino.kilowallet.core.ln

import com.kilombino.kilowallet.core.crypto.Base58
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex

/**
 * Where the Lightning node's keys come from: the same recovery words as the rest of the
 * wallet, but a master key of its own. LND's on-chain wallet uses the BIP-84 and BIP-86 paths
 * the hot wallet already uses, so the root it gets is not the words' BIP-32 root (both would
 * claim the same coins) but one tagged apart from it, deterministically: the words alone bring
 * the node back. Ported from the Android app's `ln/LnSeed.kt` (its key derivation; the wallet
 * password lives in the platform's secure store), checked against it by tools/android-vectors.
 */
object LnSeed {
    private const val TAG = "Kilowallet Lightning v1"

    /** The node's BIP-32 master key (mainnet xprv, depth 0), from the BIP-39 seed bytes. */
    fun masterXprv(bip39Seed: ByteArray): String {
        val i = Hashes.hmacSha512(TAG.encodeToByteArray(), bip39Seed)
        require(Ec.isValidSecret(i.copyOfRange(0, 32))) { "invalid Lightning master key" }
        val out = ByteArray(78)
        byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte()).copyInto(out, 0)
        // depth 0, parent fingerprint 0, child number 0 (bytes 4..12 stay zero)
        i.copyInto(out, 13, 32, 64)            // chain code
        out[45] = 0
        i.copyInto(out, 46, 0, 32)             // key
        i.fill(0)
        return Base58.encodeChecked(out)
    }

    /** The node's BIP-32 root (the key [masterXprv] encodes): what its channel.backup is encrypted under. */
    fun root(bip39Seed: ByteArray): Bip32Priv.ExtendedPrivKey {
        val i = Hashes.hmacSha512(TAG.encodeToByteArray(), bip39Seed)
        return Bip32Priv.ExtendedPrivKey(i.copyOfRange(0, 32), i.copyOfRange(32, 64), 0).also { i.fill(0) }
    }

    /**
     * The node's identity key, hex: LND's m/1017'/0'/6'/0/0 under [masterXprv]'s root, the pubkey
     * peers see. Compared with the running node's, it tells whether that node comes from these words.
     */
    fun nodePubkey(bip39Seed: ByteArray): String {
        val i = Hashes.hmacSha512(TAG.encodeToByteArray(), bip39Seed)
        val master = Bip32Priv.ExtendedPrivKey(i.copyOfRange(0, 32), i.copyOfRange(32, 64), 0)
        i.fill(0)
        val node = Bip32Priv.derivePath(master, "m/1017'/0'/6'/0/0")
        val pub = node.publicKey().toHex()
        master.wipe(); node.wipe()
        return pub
    }
}
