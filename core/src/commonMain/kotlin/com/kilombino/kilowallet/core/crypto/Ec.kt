package com.kilombino.kilowallet.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * secp256k1, through ACINQ's secp256k1-kmp: Bitcoin Core's libsecp256k1 (constant time) on
 * every target — the JVM through JNI, iOS through cinterop.
 *
 * The Android app does its PUBLIC curve arithmetic in its own BigInteger code and its secret-key
 * operations through libsecp256k1 too (NativeSecp), falling back to RFC 6979 in Kotlin; both
 * paths are deterministic, so for the same key and message all of them produce the same bytes.
 * `AndroidVectorsTest` checks exactly that against the Android app's output.
 *
 * Keys here are plain byte arrays: 32-byte secret scalars, 33-byte compressed public keys.
 */
object Ec {
    /** The group order, big-endian. */
    val N: ByteArray = Hashes.hexToBytes("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")

    /** True for a valid secret key: 0 < k < N. */
    fun isValidSecret(k: ByteArray): Boolean = k.size == 32 && Secp256k1.secKeyVerify(k)

    /** The 33-byte compressed public key k·G. */
    fun publicKey(secret: ByteArray): ByteArray = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret))

    /** Validate a 33-byte SEC point (throws if it is not on the curve) and return it compressed. */
    fun parsePublicKey(sec: ByteArray): ByteArray {
        require(sec.size == 33) { "compressed point must be 33 bytes, got ${sec.size}" }
        val p = sec[0].toInt() and 0xFF
        require(p == 0x02 || p == 0x03) { "bad SEC prefix" }
        return try {
            Secp256k1.pubKeyCompress(Secp256k1.pubkeyParse(sec))
        } catch (e: Exception) {
            throw IllegalArgumentException("point is not on the curve")
        }
    }

    /** (k + t) mod N; throws when t ≥ N or the result is zero (BIP-32 says: skip that index). */
    fun secretAdd(k: ByteArray, t: ByteArray): ByteArray {
        require(lessThanN(t)) { "derived scalar out of range" }
        return Secp256k1.privKeyTweakAdd(k, t)
    }

    /** P + t·G, compressed; throws when t ≥ N or the result is the point at infinity. */
    fun pointAddTweak(pub: ByteArray, t: ByteArray): ByteArray {
        require(lessThanN(t)) { "derived scalar out of range" }
        return Secp256k1.pubKeyCompress(Secp256k1.pubKeyTweakAdd(pub, t))
    }

    /** Unsigned big-endian comparison of a 32-byte scalar against N. */
    fun lessThanN(t: ByteArray): Boolean = compareUnsigned(t, N) < 0

    /**
     * ECDSA over a 32-byte hash: RFC 6979 nonce (libsecp256k1's default, no extra entropy), low-S,
     * DER-encoded without the sighash byte. This is the Android app's `Ecdsa.der(Ecdsa.sign(k, h))`.
     */
    fun signDer(secret: ByteArray, hash: ByteArray): ByteArray {
        require(hash.size == 32) { "message hash must be 32 bytes" }
        require(isValidSecret(secret)) { "private key out of range" }
        val compact = Secp256k1.sign(hash, secret)
        // Belt and braces: never hand out a signature that does not verify under its own key.
        check(Secp256k1.verify(compact, hash, Secp256k1.pubkeyCreate(secret))) { "signature self-check failed" }
        return Secp256k1.compact2der(compact)
    }

    internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size)
        for (i in a.indices) {
            val x = a[i].toInt() and 0xFF; val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return 0
    }
}
