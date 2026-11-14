package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.kilojoin.BigNat
import fr.acinq.secp256k1.Secp256k1

/**
 * PoDLE, the taker's anti-snooping commitment (`jmcore/podle.py`): proof that `P = xG` and
 * `P2 = xJ_i` share the discrete log `x` of a UTXO key, committed as `sha256(P2)` in `!fill`
 * and revealed in `!auth`. Points are 33-byte compressed keys; the curve arithmetic is
 * libsecp256k1's, the scalar arithmetic [BigNat] modulo n.
 */
object Podle {
    private val N = BigNat.fromBytes(Ec.N)
    private val NONCE_DOMAIN = "JoinMarket/PoDLE/nonce/v1".encodeToByteArray()
    private val G_COMPRESSED = Hashes.hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    private val G_UNCOMPRESSED = Hashes.hexToBytes(
        "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8")
    private val nums = HashMap<Int, ByteArray>()

    /** Makers accept indices `0 until taker_utxo_retries` (default 3). */
    val DEFAULT_INDICES = 0 until 3

    /** [k]·[point] (compressed); throws if k is not a valid scalar. */
    internal fun mul(point: ByteArray, k: ByteArray): ByteArray =
        Secp256k1.pubKeyCompress(Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(point), k))

    /** [a] + [b] (compressed), or null at infinity. */
    internal fun add(a: ByteArray, b: ByteArray): ByteArray? =
        runCatching { Secp256k1.pubKeyCompress(Secp256k1.pubKeyCombine(arrayOf(Secp256k1.pubkeyParse(a), Secp256k1.pubkeyParse(b)))) }.getOrNull()

    /**
     * NUMS point `J_i`: for G compressed then uncompressed, for ctr 0..255, the first valid
     * point `0x02 || sha256(G_ser || u8(i) || u8(ctr))`.
     */
    fun numsPoint(index: Int): ByteArray {
        require(index in 0..255) { "NUMS index must be 0..255" }
        nums[index]?.let { return it }
        for (g in listOf(G_COMPRESSED, G_UNCOMPRESSED)) for (ctr in 0..255) {
            val cand = byteArrayOf(2) + Hashes.sha256(g + byteArrayOf(index.toByte(), ctr.toByte()))
            val p = JmSig.point(cand) ?: continue
            nums[index] = p
            return p
        }
        error("no NUMS point for index $index") // unreachable in practice
    }

    /** A generated commitment and its opening. */
    class Commitment(
        val p: ByteArray, val p2: ByteArray, val s: ByteArray, val e: ByteArray,
        val commitment: ByteArray, val utxo: String, val index: Int,
    ) {
        /** `"P" + hex(sha256(P2))`, the last field of `!fill`. */
        val wire: String get() = "P" + commitment.toHex()

        /**
         * The `!auth` plaintext `utxo|P|P2|s|e`. With [scriptPubKeyHex] and [height] (makers
         * advertising `neutrino_compat`, i.e. every JoinBlaket maker) the utxo is the extended
         * `txid:vout:spk:height`.
         */
        fun revelation(scriptPubKeyHex: String? = null, height: Int? = null): String {
            val u = if (scriptPubKeyHex != null && height != null) "$utxo:$scriptPubKeyHex:$height" else utxo
            return listOf(u, p.toHex(), p2.toHex(), s.toHex(), e.toHex()).joinToString("|")
        }
    }

    private fun b32(v: BigNat): ByteArray = v.toBytes(32)

    /**
     * RFC 6979 (HMAC-SHA256) nonce candidates for key [x] over [hash], `_rfc6979_nonce_candidates`;
     * [skip] drops that many valid candidates first (the retry rule).
     */
    internal fun nonce(x: ByteArray, hash: ByteArray, skip: Int = 0): ByteArray {
        val seed = b32(BigNat.fromBytes(x)) + b32(BigNat.fromBytes(hash).mod(N))
        var v = ByteArray(32) { 1 }
        var k = ByteArray(32)
        k = Hashes.hmacSha256(k, v + byteArrayOf(0) + seed); v = Hashes.hmacSha256(k, v)
        k = Hashes.hmacSha256(k, v + byteArrayOf(1) + seed); v = Hashes.hmacSha256(k, v)
        var left = skip
        while (true) {
            v = Hashes.hmacSha256(k, v)
            val c = BigNat.fromBytes(v)
            if (!c.isZero && c < N) { if (left == 0) return v.copyOf(); left-- }
            k = Hashes.hmacSha256(k, v + byteArrayOf(0)); v = Hashes.hmacSha256(k, v)
        }
    }

    /** The message the deterministic nonce signs: legacy `txid:vout` [utxo], index, P, P2. */
    internal fun nonceHash(utxo: String, index: Int, p: ByteArray, p2: ByteArray): ByteArray {
        val u = utxo.encodeToByteArray()
        return Hashes.sha256(NONCE_DOMAIN + Wire.le(u.size.toLong(), 4).reversedArray() + u + byteArrayOf(index.toByte()) + p + p2)
    }

    /**
     * `generate_podle` for UTXO key [x] spending [utxo] (`txid:vout`, legacy form) at NUMS
     * [index]. Deterministic: the same inputs give the vectors' proof byte for byte.
     */
    fun generate(x: ByteArray, utxo: String, index: Int): Commitment {
        require(Ec.isValidSecret(x)) { "invalid private key" }
        val j = numsPoint(index)
        val p = Ec.publicKey(x)
        val p2 = mul(j, x)
        val h = nonceHash(utxo, index, p, p2)
        val xInt = BigNat.fromBytes(x)
        var attempt = 0
        while (true) {
            val k = nonce(x, h, attempt++)
            val kg = Ec.publicKey(k)
            val kj = mul(j, k)
            val e = Hashes.sha256(kg + kj + p + p2)
            val eInt = BigNat.fromBytes(e).mod(N)
            if (eInt.isZero) continue
            val s = (BigNat.fromBytes(k) + (eInt * xInt).mod(N)).mod(N)
            if (s.isZero) continue
            return Commitment(p, p2, b32(s), e, Hashes.sha256(p2), utxo, index)
        }
    }

    /**
     * `verify_podle`: `sha256(P2) == commitment`, `0 < s < n`, and for some index in [indices]
     * `sha256((sG - eP) || (sJ - eP2) || P || P2) == e`.
     */
    fun verify(p: ByteArray, p2: ByteArray, s: ByteArray, e: ByteArray, commitment: ByteArray, indices: IntRange = DEFAULT_INDICES): Boolean {
        if (p.size != 33 || p2.size != 33 || s.size != 32 || e.size != 32 || commitment.size != 32) return false
        if (!Hashes.sha256(p2).contentEquals(commitment)) return false
        val pp = JmSig.point(p) ?: return false
        val pp2 = JmSig.point(p2) ?: return false
        val sInt = BigNat.fromBytes(s)
        if (sInt.isZero || sInt >= N) return false
        val eMod = BigNat.fromBytes(e).mod(N)
        if (eMod.isZero) return false
        val minusE = b32(N - eMod)
        val kg = add(Ec.publicKey(s), mul(pp, minusE)) ?: return false
        val ep2 = mul(pp2, minusE)
        for (i in indices) {
            val kj = add(mul(numsPoint(i), s), ep2) ?: continue
            if (Hashes.sha256(kg + kj + p + p2).contentEquals(e)) return true
        }
        return false
    }

    /** A parsed `!auth` revelation. */
    class Revelation(
        val txid: String, val vout: Long, val scriptPubKeyHex: String?, val height: Int?,
        val p: ByteArray, val p2: ByteArray, val s: ByteArray, val e: ByteArray,
    )

    /** Parse `utxo|P|P2|s|e` (field lengths 66/66/64/64 hex), legacy or extended utxo. */
    fun parseRevelation(text: String): Revelation {
        val f = text.split("|")
        require(f.size == 5) { "revelation needs 5 fields" }
        require(f[1].length == 66 && f[2].length == 66 && f[3].length == 64 && f[4].length == 64) { "bad revelation field length" }
        val u = Utxos.parse(f[0])
        return Revelation(u.txid, u.vout, u.scriptPubKeyHex, u.height, Wire.hex(f[1]), Wire.hex(f[2]), Wire.hex(f[3]), Wire.hex(f[4]))
    }

    /**
     * `verify_podle_binding`: P controls [scriptPubKey] as P2WPKH, P2PKH or P2SH-P2WPKH (the
     * taker only ever uses P2WPKH, but makers check all three).
     */
    fun bindsTo(p: ByteArray, scriptPubKey: ByteArray): Boolean {
        if (p.size != 33) return false
        val h = Hashes.hash160(p)
        val spk = scriptPubKey
        fun u(i: Int) = spk[i].toInt() and 0xFF
        return when {
            spk.size == 22 && u(0) == 0x00 && u(1) == 0x14 -> spk.copyOfRange(2, 22).contentEquals(h)
            spk.size == 25 && u(0) == 0x76 && u(1) == 0xa9 && u(2) == 0x14 && u(23) == 0x88 && u(24) == 0xac ->
                spk.copyOfRange(3, 23).contentEquals(h)
            spk.size == 23 && u(0) == 0xa9 && u(1) == 0x14 && u(22) == 0x87 ->
                spk.copyOfRange(2, 22).contentEquals(Hashes.hash160(byteArrayOf(0x00, 0x14) + h))
            else -> false
        }
    }
}

/**
 * UTXO references on the wire (`UTXOMetadata` in `jmcore/protocol.py`): legacy `txid:vout` or
 * extended `txid:vout:scriptpubkey_hex:blockheight`, comma-separated in lists.
 */
object Utxos {
    data class Ref(val txid: String, val vout: Long, val scriptPubKeyHex: String? = null, val height: Int? = null) {
        val legacy: String get() = "$txid:$vout"
        val extended: String get() = if (scriptPubKeyHex != null && height != null) "$legacy:$scriptPubKeyHex:$height" else legacy
    }

    /** `UTXOMetadata.from_str` with its validation (64-hex txid, uint32 vout, 4..200 hex-char spk, height >= 0). */
    fun parse(s: String): Ref {
        val parts = s.split(":")
        require(parts.size == 2 || parts.size == 4) { "invalid UTXO format" }
        val txid = parts[0]
        require(txid.length == 64) { "invalid txid length" }
        Wire.hex(txid)
        val vout = parts[1].toLong()
        require(vout in 0..0xffffffffL) { "invalid vout" }
        if (parts.size == 2) return Ref(txid, vout)
        val spk = parts[2]
        require(spk.length in 4..200) { "invalid scriptpubkey" }
        Wire.hex(spk)
        val height = parts[3].toInt()
        require(height >= 0) { "invalid blockheight" }
        return Ref(txid, vout, spk, height)
    }

    /** `parse_utxo_list`: comma-separated refs (empty string = none). */
    fun parseList(s: String): List<Ref> = if (s.isEmpty()) emptyList() else s.split(",").map { parse(it.trim()) }

    /** `format_utxo_list`. */
    fun formatList(refs: List<Ref>, extended: Boolean): String =
        refs.joinToString(",") { if (extended) it.extended else it.legacy }
}
