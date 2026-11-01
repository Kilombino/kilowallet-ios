package com.kilombino.kilowallet.core.ark

import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Hashes

/**
 * The Ark wallet's own words, made from the BTC wallet's in one direction only, as the Android app
 * (1.1.4 on, `ark/ArkDerive.kt`): the first 32 bytes of HMAC-SHA512("Kilowallet Ark v1", BIP-39
 * seed of the words and passphrase) as 24 new words, no passphrase. The written words still bring
 * Ark back (they make these again); what Ark keeps without Face ID only ever opens Ark.
 */
object ArkDerive {
    private val KEY = "Kilowallet Ark v1".encodeToByteArray()

    fun words(words: List<String>, passphrase: String): List<String> {
        val seed = Bip39.toSeed(words, passphrase)
        try {
            val h = Hashes.hmacSha512(KEY, seed)
            try { return Bip39.fromEntropy(h.copyOf(32)) } finally { h.fill(0) }
        } finally { seed.fill(0) }
    }
}
