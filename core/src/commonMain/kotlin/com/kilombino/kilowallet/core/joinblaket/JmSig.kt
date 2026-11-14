package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.kilojoin.BigNat

/**
 * The ECDSA conventions JoinMarket (and so JoinBlaket) uses for everything that is not a
 * transaction signature: the Bitcoin signed-message hash, RFC 6979 nonces WITHOUT Core's low-R
 * grinding (`_ecdsa_sig_grind_low_r=False` in `jmcore/crypto.py`), low-S, DER, base64 on the
 * wire; and the strict DER / low-S check every received signature must pass
 * (`_is_strict_low_s_der_signature`). Keys are 32-byte secrets; libsecp256k1 signs.
 */
object JmSig {
    private val N = BigNat.fromBytes(Ec.N)
    private val HALF_N = N shr 1

    /** A strictly parsed signature: r and s as 32-byte big-endian numbers. */
    class Signature(val r: ByteArray, val s: ByteArray)

    /** `sha256d("\x18Bitcoin Signed Message:\n" || compactsize(len) || msg)`. */
    fun messageHash(msg: ByteArray): ByteArray {
        val prefix = "\u0018Bitcoin Signed Message:\n".encodeToByteArray()
        return Hashes.doubleSha256(prefix + Wire.compactSize(msg.size.toLong()) + msg)
    }

    /** [messageHash] of the UTF-8 bytes of [msg]. */
    fun messageHash(msg: String): ByteArray = messageHash(msg.encodeToByteArray())

    /** DER signature (no hash-type byte) of [hash]: RFC 6979, no low-R grinding, low-S. */
    fun signHash(privateKey: ByteArray, hash: ByteArray): ByteArray = Ec.signDer(privateKey, hash)

    /** base64(DER) over the signed-message hash of [msg]: `ecdsa_sign` / `sign_bytes`. */
    fun signMessage(privateKey: ByteArray, msg: ByteArray): String = B64.encode(signHash(privateKey, messageHash(msg)))

    /**
     * Parse [sig] only if it is strict DER with 1 <= r < n and 1 <= s <= n/2, exactly the
     * rules of `_is_strict_low_s_der_signature`; null otherwise.
     */
    fun parseStrictDer(sig: ByteArray): Signature? {
        fun u(i: Int) = sig[i].toInt() and 0xFF
        if (sig.size !in 8..72 || u(0) != 0x30 || u(1) != sig.size - 2 || u(2) != 0x02) return null
        val rLen = u(3)
        if (rLen == 0 || 5 + rLen >= sig.size) return null
        if (u(4) and 0x80 != 0) return null
        if (rLen > 1 && u(4) == 0 && u(5) and 0x80 == 0) return null
        val r = BigNat.fromBytes(sig.copyOfRange(4, 4 + rLen))
        val sType = 4 + rLen
        if (u(sType) != 0x02) return null
        val sLen = u(sType + 1)
        val sStart = sType + 2
        if (sLen == 0 || sStart + sLen != sig.size) return null
        if (u(sStart) and 0x80 != 0) return null
        if (sLen > 1 && u(sStart) == 0 && u(sStart + 1) and 0x80 == 0) return null
        val s = BigNat.fromBytes(sig.copyOfRange(sStart, sStart + sLen))
        if (r.isZero || r >= N || s.isZero || s > HALF_N) return null
        return Signature(r.toBytes(32), s.toBytes(32))
    }

    /** Minimal DER of (r, s), 32-byte big-endian each. */
    fun der(r: ByteArray, s: ByteArray): ByteArray {
        fun int(v: ByteArray): ByteArray {
            var i = 0
            while (i < v.size - 1 && v[i] == 0.toByte()) i++
            var b = v.copyOfRange(i, v.size)
            if (b[0].toInt() and 0x80 != 0) b = byteArrayOf(0) + b
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = int(r) + int(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** A 33-byte compressed key as a valid curve point (compressed), or null if it is not one. */
    fun point(pubkey: ByteArray): ByteArray? = runCatching { Ec.parsePublicKey(pubkey) }.getOrNull()

    /** `verify_strict_ecdsa`: strict DER, low-S and a valid signature over [hash] by [pubkey]. */
    fun verifyHash(hash: ByteArray, der: ByteArray, pubkey: ByteArray): Boolean {
        parseStrictDer(der) ?: return false
        point(pubkey) ?: return false
        return Ec.verifyDer(pubkey, hash, der)
    }

    /** `ecdsa_verify`: base64 [sigB64] over the signed-message hash of [msg]. */
    fun verifyMessage(msg: ByteArray, sigB64: String, pubkey: ByteArray): Boolean {
        val der = runCatching { B64.decode(sigB64) }.getOrNull() ?: return false
        return verifyHash(messageHash(msg), der, pubkey)
    }

    fun verifyMessage(msg: String, sigB64: String, pubkey: ByteArray): Boolean = verifyMessage(msg.encodeToByteArray(), sigB64, pubkey)
}
