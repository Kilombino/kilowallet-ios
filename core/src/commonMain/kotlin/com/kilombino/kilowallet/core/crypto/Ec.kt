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

    /**
     * The same, ground to a low R as Bitcoin Core does (the Android app's `Ecdsa.sign(…, grindLowR =
     * true)`, used for every pool input): while r's top bit is set, sign again with a 32-byte
     * little-endian counter as the RFC 6979 extra data. The DER signature is then at most 71 bytes,
     * and byte for byte the one Core and the Android app make for the same key and message.
     */
    fun signDerLowR(secret: ByteArray, hash: ByteArray): ByteArray {
        require(hash.size == 32) { "message hash must be 32 bytes" }
        require(isValidSecret(secret)) { "private key out of range" }
        var counter = 0L
        while (true) {
            val extra = if (counter == 0L) null else ByteArray(32).also { for (i in 0 until 8) it[i] = (counter ushr (8 * i)).toByte() }
            val compact = Secp256k1.sign(hash, secret, extra)
            if ((compact[0].toInt() and 0x80) == 0) {
                check(Secp256k1.verify(compact, hash, Secp256k1.pubkeyCreate(secret))) { "signature self-check failed" }
                return Secp256k1.compact2der(compact)
            }
            counter++
        }
    }

    /** True when [der] (no sighash byte) is a valid signature of [hash] by the 33-byte [pubkey]. */
    fun verifyDer(pubkey: ByteArray, hash: ByteArray, der: ByteArray): Boolean = runCatching {
        if (hash.size != 32) return false
        val compact = Secp256k1.der2compact(der)
        // libsecp256k1 only accepts low-S; a high-S signature is normalised first, as the
        // Android app's own verifier takes either.
        val (normal, _) = Secp256k1.signatureNormalize(compact)
        Secp256k1.verify(normal, hash, Secp256k1.pubkeyParse(pubkey))
    }.getOrDefault(false)

    /** The 32-byte x-only (BIP-340) public key of [secret]. */
    fun xOnly(secret: ByteArray): ByteArray = publicKey(secret).copyOfRange(1, 33)

    /** BIP-340 signature of a 32-byte message, with [aux] (32 random bytes unless a test pins them). */
    fun schnorrSign(secret: ByteArray, msg: ByteArray, aux: ByteArray = com.kilombino.kilowallet.core.platform.secureRandomBytes(32)): ByteArray {
        require(msg.size == 32 && aux.size == 32)
        require(isValidSecret(secret)) { "private key out of range" }
        val sig = Secp256k1.signSchnorr(msg, secret, aux)
        check(schnorrVerify(xOnly(secret), msg, sig)) { "Schnorr self-check failed" }
        return sig
    }

    fun schnorrVerify(pubX: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        sig.size == 64 && pubX.size == 32 && msg.size == 32 && Secp256k1.verifySchnorr(sig, msg, pubX)
    }.getOrDefault(false)

    /** The x coordinate of [secret]·P, P the even-y point of x-only [pubX] (NIP-44's shared secret). */
    fun sharedX(secret: ByteArray, pubX: ByteArray): ByteArray {
        require(pubX.size == 32) { "x-only key must be 32 bytes" }
        val p = Secp256k1.pubkeyParse(byteArrayOf(0x02) + pubX)
        return Secp256k1.pubKeyCompress(Secp256k1.pubKeyTweakMul(p, secret)).copyOfRange(1, 33)
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
