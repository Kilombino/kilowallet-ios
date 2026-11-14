package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.kilojoin.BigNat
import com.kilombino.kilowallet.core.platform.secureRandomBytes

/**
 * NaCl `crypto_box` as PyNaCl's `nacl.public.Box` does it (libsodium underneath), which is what
 * JoinMarket's `jmcore/encryption.py` uses between taker and maker:
 *
 * * keys: raw 32-byte X25519 scalars (clamped when used), public key = X25519(sk, 9);
 * * `beforenm`: shared key = HSalsa20(X25519(sk, their_pk), nonce 0^16); an all-zero X25519
 *   result is refused, as libsodium refuses it;
 * * `afternm` / secretbox: XSalsa20 keystream under the nonce, its first 32 bytes are the
 *   Poly1305 key, output `MAC16 || ciphertext`;
 * * wire: `base64(nonce24 || MAC16 || ciphertext)` (`encrypt_encode` / `decode_decrypt`).
 *
 * Pure Kotlin, the Android app's code over [BigNat] (Kotlin/Native has no BigInteger). The field
 * arithmetic (X25519, Poly1305) does not run in constant time; the keys it handles are per-round
 * ephemerals, never wallet keys. Pinned to libsodium by `nacl-kat.json` in the tests.
 */
object Nacl {
    private val P25519: BigNat = (BigNat.ONE shl 255) - BigNat.of(19)
    private val A24 = BigNat.of(121665)
    private val P_MINUS_2 = P25519 - BigNat.of(2)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    /** A fresh random secret key (any 32 bytes are valid; clamping happens on use). */
    fun generateSecret(): ByteArray = secureRandomBytes(32)

    /** `crypto_scalarmult_base`: the public key of [secret]. */
    fun publicKey(secret: ByteArray): ByteArray = x25519(secret, BASE)

    private fun leInt(b: ByteArray) = BigNat.fromBytes(b.reversedArray())

    /** The low 32 bytes of [v], little-endian. */
    private fun leBytes(v: BigNat): ByteArray {
        val be = v.toBytes()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun mod(a: BigNat) = a.mod(P25519)
    /** (a − b) mod p for a, b already reduced. */
    private fun sub(a: BigNat, b: BigNat) = mod(a + P25519 - b)

    /** RFC 7748 X25519 (Montgomery ladder over GF(2^255 - 19)). */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32) { "X25519 takes 32-byte inputs" }
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kk = leInt(k)
        val uu = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = mod(leInt(uu))
        var x2 = BigNat.ONE; var z2 = BigNat.ZERO
        var x3 = x1; var z3 = BigNat.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kk.testBit(t)
            if (swap != bit) { var tmp = x2; x2 = x3; x3 = tmp; tmp = z2; z2 = z3; z3 = tmp }
            swap = bit
            val a = mod(x2 + z2); val aa = mod(a * a)
            val b = sub(x2, z2); val bb = mod(b * b)
            val e = sub(aa, bb)
            val c = mod(x3 + z3); val d = sub(x3, z3)
            val da = mod(d * a); val cb = mod(c * b)
            val s1 = mod(da + cb); x3 = mod(s1 * s1)
            val s2 = sub(da, cb); z3 = mod(x1 * mod(s2 * s2))
            x2 = mod(aa * bb)
            z2 = mod(e * mod(aa + mod(A24 * e)))
        }
        if (swap) { x2 = x3; z2 = z3 }
        return leBytes(mod(x2 * z2.modPow(P_MINUS_2, P25519)))
    }

    // ---- Salsa20 family ------------------------------------------------------------

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private val SIGMA = "expand 32-byte k".encodeToByteArray()

    /** The 16-word Salsa20 input for [key], a 16-byte [input] (nonce ‖ counter). */
    private fun salsaState(key: ByteArray, input: ByteArray) = IntArray(16).also { s ->
        s[0] = le32(SIGMA, 0); s[5] = le32(SIGMA, 4); s[10] = le32(SIGMA, 8); s[15] = le32(SIGMA, 12)
        for (i in 0 until 4) { s[1 + i] = le32(key, 4 * i); s[11 + i] = le32(key, 16 + 4 * i) }
        for (i in 0 until 4) s[6 + i] = le32(input, 4 * i)
    }

    private fun salsaRounds(x: IntArray) {
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
            x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
            x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
            x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(5, 9, 13, 1); qr(10, 14, 2, 6); qr(15, 3, 7, 11)
            qr(0, 1, 2, 3); qr(5, 6, 7, 4); qr(10, 11, 8, 9); qr(15, 12, 13, 14)
        }
    }

    private fun words(w: IntArray, idx: IntArray): ByteArray {
        val out = ByteArray(4 * idx.size)
        idx.forEachIndexed { n, i -> for (j in 0 until 4) out[4 * n + j] = (w[i] ushr (8 * j)).toByte() }
        return out
    }

    /** HSalsa20: the subkey for a 32-byte [key] and 16-byte [nonce16]. */
    fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val x = salsaState(key, nonce16); salsaRounds(x)
        return words(x, intArrayOf(0, 5, 10, 15, 6, 7, 8, 9))
    }

    /** [len] bytes of Salsa20 keystream for [key] and an 8-byte [nonce8], counter from 0. */
    private fun salsa20Stream(key: ByteArray, nonce8: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var counter = 0L
        var off = 0
        while (off < len) {
            val input = nonce8 + Wire.le(counter, 8)
            val s = salsaState(key, input); val x = s.copyOf(); salsaRounds(x)
            for (i in 0 until 16) x[i] += s[i]
            val block = words(x, IntArray(16) { it })
            val n = minOf(64, len - off)
            block.copyInto(out, off, 0, n)
            off += n; counter++
        }
        return out
    }

    // ---- Poly1305 -----------------------------------------------------------------

    private val P1305: BigNat = (BigNat.ONE shl 130) - BigNat.of(5)

    /** One-time authenticator of [msg] under the 32-byte [key]. */
    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        // r clamped as 0x0ffffffc0ffffffc0ffffffc0fffffff (little-endian).
        val rb = key.copyOfRange(0, 16)
        for (i in intArrayOf(3, 7, 11, 15)) rb[i] = (rb[i].toInt() and 0x0f).toByte()
        for (i in intArrayOf(4, 8, 12)) rb[i] = (rb[i].toInt() and 0xfc).toByte()
        val r = leInt(rb)
        val s = leInt(key.copyOfRange(16, 32))
        var acc = BigNat.ZERO
        var i = 0
        while (i < msg.size) {
            val n = minOf(16, msg.size - i)
            val block = msg.copyOfRange(i, i + n) + byteArrayOf(1)
            acc = ((acc + leInt(block)) * r).mod(P1305)
            i += n
        }
        return leBytes(acc + s).copyOfRange(0, 16)
    }

    // ---- secretbox / box -----------------------------------------------------------

    /** `crypto_secretbox_easy` with PyNaCl's layout: `MAC16 || ciphertext`. */
    fun secretboxSeal(key: ByteArray, nonce24: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32 && nonce24.size == 24) { "secretbox takes a 32-byte key and a 24-byte nonce" }
        val sub = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val stream = salsa20Stream(sub, nonce24.copyOfRange(16, 24), 32 + plain.size)
        val c = ByteArray(plain.size) { (plain[it].toInt() xor stream[32 + it].toInt()).toByte() }
        return poly1305(stream.copyOfRange(0, 32), c) + c
    }

    /** Inverse of [secretboxSeal]; null if the MAC does not verify. */
    fun secretboxOpen(key: ByteArray, nonce24: ByteArray, boxed: ByteArray): ByteArray? {
        if (key.size != 32 || nonce24.size != 24 || boxed.size < 16) return null
        val sub = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val c = boxed.copyOfRange(16, boxed.size)
        val stream = salsa20Stream(sub, nonce24.copyOfRange(16, 24), 32 + c.size)
        if (!ctEquals(poly1305(stream.copyOfRange(0, 32), c), boxed.copyOfRange(0, 16))) return null
        return ByteArray(c.size) { (c[it].toInt() xor stream[32 + it].toInt()).toByte() }
    }

    /**
     * `nacl.public.Box(our_secret, their_public)`. Both directions of a taker/maker pair derive
     * the same [sharedKey].
     */
    class Box(ourSecret: ByteArray, theirPublic: ByteArray) {
        val sharedKey: ByteArray

        init {
            require(ourSecret.size == 32 && theirPublic.size == 32) { "NaCl keys are 32 bytes" }
            val dh = x25519(ourSecret, theirPublic)
            require(dh.any { it.toInt() != 0 }) { "weak NaCl public key" }
            sharedKey = hsalsa20(dh, ByteArray(16))
        }

        /** `nonce24 || MAC16 || ciphertext`, raw bytes. */
        fun seal(plain: ByteArray, nonce24: ByteArray): ByteArray = nonce24 + secretboxSeal(sharedKey, nonce24, plain)

        /** `encrypt_encode`: base64 of [seal] under [nonce24] (random unless a test pins it). */
        fun encrypt(plain: ByteArray, nonce24: ByteArray = secureRandomBytes(24)): String = B64.encode(seal(plain, nonce24))

        fun encrypt(plain: String, nonce24: ByteArray = secureRandomBytes(24)): String = encrypt(plain.encodeToByteArray(), nonce24)

        /** `decode_decrypt`: base64 `nonce || box` to plaintext; throws on a bad MAC or encoding. */
        fun decrypt(b64: String): ByteArray {
            val raw = B64.decode(b64)
            require(raw.size >= 24 + 16) { "NaCl message too short" }
            return secretboxOpen(sharedKey, raw.copyOfRange(0, 24), raw.copyOfRange(24, raw.size))
                ?: throw IllegalArgumentException("NaCl authentication failed")
        }

        fun decryptString(b64: String): String = decrypt(b64).decodeToString()
    }
}
