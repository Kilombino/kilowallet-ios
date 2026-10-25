package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.platform.secureRandomBytes
import org.kotlincrypto.hash.sha2.SHA384

/**
 * RSA blind signatures, RFC 9474, variant RSABSSA-SHA384-PSS-Randomized (Kilojoin v3); the Android
 * app's `coinjoin/BlindRsa.kt` over [BigNat].
 *
 * The pool's creator holds a key pair made for the round. Each member blinds its output with a
 * random factor and has the creator sign it without seeing it; later the member unblinds the
 * signature and hands in the output with it from a fresh identity. The signature proves a seat
 * paid for it, but not which one. Checked against the RFC's own test vector and the Android
 * app's output.
 */
object BlindRsa {
    private const val H_LEN = 48          // SHA-384
    const val SALT_LEN = 48
    const val PREFIX_LEN = 32
    /** Round keys live minutes; 2048 bits keeps key generation quick on a phone. */
    const val KEY_BITS = 2048
    private val E = BigNat.of(65537)

    class PublicKey(val n: BigNat, val e: BigNat) {
        val modBits get() = n.bitLength()
        val modLen get() = (n.bitLength() + 7) / 8
        /** "n:e" in hex, to publish in the pool (as java.math.BigInteger.toString(16) writes them). */
        fun encode(): String = n.toHex() + ":" + e.toHex()
        companion object {
            fun decode(s: String): PublicKey {
                val p = s.split(':').also { require(it.size == 2) { "bad blind-signing key" } }
                val k = PublicKey(BigNat.fromHex(p[0]), BigNat.fromHex(p[1]))
                require(k.modBits in 2048..4096 && k.e == E) { "unsupported blind-signing key" }
                return k
            }
        }
    }

    class KeyPair(val public: PublicKey, val d: BigNat) {
        fun encodePrivate(): String = public.encode() + ":" + d.toHex()
        companion object {
            fun decode(s: String): KeyPair {
                val p = s.split(':').also { require(it.size == 3) { "bad blind-signing key" } }
                return KeyPair(PublicKey(BigNat.fromHex(p[0]), BigNat.fromHex(p[1])), BigNat.fromHex(p[2]))
            }
        }
    }

    fun generate(bits: Int = KEY_BITS): KeyPair {
        while (true) {
            val p = BigNat.probablePrime(bits / 2)
            val q = BigNat.probablePrime(bits - bits / 2)
            if (p == q) continue
            val n = p * q
            if (n.bitLength() != bits) continue
            val phi = (p - BigNat.ONE) * (q - BigNat.ONE)
            if (phi.gcd(E) != BigNat.ONE) continue
            return KeyPair(PublicKey(n, E), E.modInverse(phi))
        }
    }

    /** The randomized preparation: a 32-byte random prefix before the message. */
    fun prepare(msg: ByteArray): ByteArray = secureRandomBytes(PREFIX_LEN) + msg

    class Blinded(val blindedMsg: ByteArray, val inv: BigNat)

    /** Blind [prepared] for [pk]; [salt] and [r] only for test vectors. */
    fun blind(pk: PublicKey, prepared: ByteArray, salt: ByteArray? = null, r: BigNat? = null): Blinded {
        val em = pssEncode(prepared, pk.modBits - 1, salt ?: secureRandomBytes(SALT_LEN))
        val m = BigNat.fromBytes(em)
        require(m.gcd(pk.n) == BigNat.ONE) { "invalid input" }
        var rr = r
        while (rr == null) {
            val c = BigNat.random(pk.modBits)
            if (!c.isZero && c < pk.n && c.gcd(pk.n) == BigNat.ONE) rr = c
        }
        val inv = rr.modInverse(pk.n)
        val z = (m * rr.modPow(pk.e, pk.n)).mod(pk.n)
        return Blinded(z.toBytes(pk.modLen), inv)
    }

    /** The signer's side: signs what it cannot read, and checks its own result (fault attacks). */
    fun blindSign(kp: KeyPair, blindedMsg: ByteArray): ByteArray {
        val pk = kp.public
        require(blindedMsg.size == pk.modLen) { "unexpected input size" }
        val m = BigNat.fromBytes(blindedMsg)
        require(m < pk.n) { "invalid message length" }
        val s = m.modPow(kp.d, pk.n)
        check(s.modPow(pk.e, pk.n) == m) { "signing failure" }
        return s.toBytes(pk.modLen)
    }

    /** Unblinds [blindSig] into a signature on [prepared], and checks it. */
    fun finalize(pk: PublicKey, prepared: ByteArray, blindSig: ByteArray, inv: BigNat): ByteArray {
        require(blindSig.size == pk.modLen) { "unexpected input size" }
        val z = BigNat.fromBytes(blindSig)
        val sig = (z * inv).mod(pk.n).toBytes(pk.modLen)
        require(verify(pk, prepared, sig)) { "invalid signature" }
        return sig
    }

    /** RSASSA-PSS-VERIFY with SHA-384, MGF1-SHA-384 and a 48-byte salt. */
    fun verify(pk: PublicKey, prepared: ByteArray, sig: ByteArray): Boolean = runCatching {
        if (sig.size != pk.modLen) return false
        val s = BigNat.fromBytes(sig)
        if (s >= pk.n) return false
        val emBits = pk.modBits - 1
        val emLen = (emBits + 7) / 8
        val m = s.modPow(pk.e, pk.n)
        if (m.bitLength() > emLen * 8) return false
        pssVerify(prepared, m.toBytes(emLen), emBits)
    }.getOrDefault(false)

    // ---------------------------------------------------------------- EMSA-PSS (RFC 8017 9.1)

    private fun sha384(vararg parts: ByteArray): ByteArray {
        val d = SHA384()
        parts.forEach { d.update(it) }
        return d.digest()
    }

    private fun mgf1(seed: ByteArray, len: Int): ByteArray {
        var out = ByteArray(0)
        var counter = 0
        while (out.size < len) {
            out += sha384(seed, byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            counter++
        }
        return out.copyOf(len)
    }

    internal fun pssEncode(msg: ByteArray, emBits: Int, salt: ByteArray): ByteArray {
        val emLen = (emBits + 7) / 8
        require(emLen >= H_LEN + salt.size + 2) { "encoding error" }
        val h = sha384(ByteArray(8), sha384(msg), salt)
        val db = ByteArray(emLen - H_LEN - 1)
        db[db.size - salt.size - 1] = 1
        salt.copyInto(db, db.size - salt.size)
        val mask = mgf1(h, db.size)
        for (i in db.indices) db[i] = (db[i].toInt() xor mask[i].toInt()).toByte()
        db[0] = (db[0].toInt() and (0xFF ushr (8 * emLen - emBits))).toByte()
        return db + h + byteArrayOf(0xBC.toByte())
    }

    private fun pssVerify(msg: ByteArray, em: ByteArray, emBits: Int): Boolean {
        val emLen = em.size
        if (emLen < H_LEN + SALT_LEN + 2 || em.last() != 0xBC.toByte()) return false
        val masked = em.copyOfRange(0, emLen - H_LEN - 1)
        val h = em.copyOfRange(emLen - H_LEN - 1, emLen - 1)
        val topBits = 8 * emLen - emBits
        if (masked[0].toInt() and (0xFF shl (8 - topBits)) and 0xFF != 0) return false
        val mask = mgf1(h, masked.size)
        val db = ByteArray(masked.size) { (masked[it].toInt() xor mask[it].toInt()).toByte() }
        db[0] = (db[0].toInt() and (0xFF ushr topBits)).toByte()
        val psLen = emLen - H_LEN - SALT_LEN - 2
        for (i in 0 until psLen) if (db[i] != 0.toByte()) return false
        if (db[psLen] != 1.toByte()) return false
        val salt = db.copyOfRange(db.size - SALT_LEN, db.size)
        return constantTimeEquals(h, sha384(ByteArray(8), sha384(msg), salt))
    }
}
