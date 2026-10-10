package com.kilombino.kilowallet.core.crypto

import com.kilombino.kilowallet.core.platform.nfkd
import com.kilombino.kilowallet.core.platform.secureRandomBytes

/**
 * BIP-39: entropy → mnemonic, mnemonic → 64-byte seed. Ported from the Android app's
 * `crypto/Bip39.kt` (same word mapping and checksum code); the seed stretch is
 * PBKDF2-HMAC-SHA512 × 2048 from [Hashes.pbkdf2HmacSha512] instead of the JVM's provider.
 */
object Bip39 {

    private val WORDS = Bip39Wordlist.WORDS
    private val INDEX = Bip39Wordlist.INDEX

    /** A fresh mnemonic of [words] (12 or 24) words from the system CSPRNG. */
    fun generate(words: Int): List<String> {
        require(words == 12 || words == 24) { "12 or 24 words" }
        val entropy = secureRandomBytes(if (words == 12) 16 else 32)
        return try { fromEntropy(entropy) } finally { entropy.fill(0) }
    }

    /** Encode entropy (16/20/24/28/32 bytes) as a mnemonic with its checksum word(s). */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size in intArrayOf(16, 20, 24, 28, 32)) {
            "entropy must be 128–256 bits in 32-bit steps, got ${entropy.size * 8} bits"
        }
        val checksumBits = entropy.size * 8 / 32
        val hash = Hashes.sha256(entropy)
        val totalBits = entropy.size * 8 + checksumBits
        val words = ArrayList<String>(totalBits / 11)
        var acc = 0
        var bits = 0
        fun feed(byte: Int, take: Int) {
            for (i in 7 downTo 8 - take) {
                acc = (acc shl 1) or ((byte ushr i) and 1)
                bits++
                if (bits == 11) { words.add(WORDS[acc]); acc = 0; bits = 0 }
            }
        }
        for (b in entropy) feed(b.toInt() and 0xFF, 8)
        var remaining = checksumBits
        var hi = 0
        while (remaining > 0) {
            val take = minOf(8, remaining)
            feed(hash[hi].toInt() and 0xFF, take)
            remaining -= take; hi++
        }
        return words
    }

    /** True when the word is in the BIP-39 English list. */
    fun isWord(w: String): Boolean = INDEX.containsKey(w)

    /** Words of the list starting with [prefix], for autocomplete. */
    fun suggestions(prefix: String, max: Int = 4): List<String> =
        if (prefix.isEmpty()) emptyList() else WORDS.filter { it.startsWith(prefix) }.take(max)

    /** True when the words are all in the list and the checksum matches. */
    fun isValid(mnemonic: List<String>): Boolean {
        if (mnemonic.size !in intArrayOf(12, 15, 18, 21, 24)) return false
        val bits = StringBuilder()
        for (w in mnemonic) {
            val idx = INDEX[w] ?: return false
            bits.append(idx.toString(2).padStart(11, '0'))
        }
        val entBits = mnemonic.size * 11 * 32 / 33
        val csBits = mnemonic.size * 11 - entBits
        val entropy = ByteArray(entBits / 8)
        for (i in entropy.indices) {
            entropy[i] = bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        }
        val hash = Hashes.sha256(entropy)
        val expected = StringBuilder()
        for (i in 0 until csBits) {
            expected.append((hash[i / 8].toInt() ushr (7 - i % 8)) and 1)
        }
        return bits.substring(entBits) == expected.toString()
    }

    /** PBKDF2-HMAC-SHA512(NFKD(mnemonic), "mnemonic"+NFKD(passphrase), 2048) → 64-byte seed. */
    fun toSeed(mnemonic: List<String>, passphrase: String = ""): ByteArray {
        val phrase = nfkd(mnemonic.joinToString(" ")).encodeToByteArray()
        val salt = "mnemonic${nfkd(passphrase)}".encodeToByteArray()
        return Hashes.pbkdf2HmacSha512(phrase, salt, 2048).also { phrase.fill(0) }
    }
}
