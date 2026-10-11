package com.kilombino.kilowallet.core.crypto

/** A raw transaction read back into its parts: enough to recognise a Lightning channel close. */
object TxParse {

    class In(val txid: String, val vout: Int, val scriptSig: ByteArray, val sequence: Long, val witness: List<ByteArray>)
    class Out(val value: Long, val scriptPubKey: ByteArray)
    class Tx(val version: Long, val inputs: List<In>, val outputs: List<Out>, val locktime: Long)

    fun parse(hex: String): Tx {
        require(hex.length in 20..2_000_000 && hex.length % 2 == 0) { "Invalid transaction size" }
        val b = Hashes.hexToBytes(hex)
        var o = 0
        fun need(n: Int) { require(n >= 0 && n <= b.size - o) { "Truncated transaction" } }
        fun u8(): Int { need(1); return b[o++].toInt() and 0xFF }
        fun le(n: Int): Long {
            need(n)
            var v = 0L
            for (i in 0 until n) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
            o += n
            return v
        }
        fun varint(): Int {
            val prefix = u8()
            val n = when (prefix) { 0xfd -> le(2); 0xfe -> le(4); 0xff -> le(8); else -> prefix.toLong() }
            require(n >= 0 && n <= b.size - o) { "Invalid transaction count or length" }
            require(when (prefix) { 0xfd -> n >= 253; 0xfe -> n > 65535; 0xff -> n > 0xffffffffL; else -> true }) { "Non-canonical transaction count" }
            return n.toInt()
        }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(o, o + n).also { o += n } }
        val version = le(4)
        need(2)
        val segwit = b[o] == 0.toByte() && b[o + 1] == 1.toByte()
        if (segwit) o += 2
        val nInputs = varint()
        require(nInputs > 0 && nInputs <= (b.size - o) / 41) { "Invalid input count" }
        val raw = (0 until nInputs).map {
            val txid = with(Hashes) { bytes(32).reversedArray().toHex() }
            val vout = le(4).toInt()
            val ss = bytes(varint())
            Triple(txid to vout, ss, le(4))
        }
        val nOutputs = varint()
        require(nOutputs > 0 && nOutputs <= (b.size - o) / 9) { "Invalid output count" }
        var total = 0L
        val outs = (0 until nOutputs).map {
            val v = le(8)
            require(v >= 0 && total <= Long.MAX_VALUE - v) { "Invalid output amount" }
            total += v
            Out(v, bytes(varint()))
        }
        val wit = if (segwit) raw.map { (0 until varint()).map { bytes(varint()) } } else raw.map { emptyList() }
        require(!segwit || wit.any { it.isNotEmpty() }) { "Superfluous witness marker" }
        val locktime = le(4)
        require(o == b.size) { "trailing bytes in transaction" }
        return Tx(version, raw.mapIndexed { i, r -> In(r.first.first, r.first.second, r.second, r.third, wit[i]) }, outs, locktime)
    }

    /**
     * The txid of [tx]: double SHA-256 of its serialisation without witnesses, shown reversed.
     * Lets the wallet check that a transaction a server hands back is the one it asked for.
     */
    fun txid(tx: Tx): String = with(Hashes) { doubleSha256(baseBytes(tx)).reversedArray().toHex() }

    /** Virtual size of [rawHex]: its weight (non-witness bytes × 3 + all bytes) over 4, rounded up. */
    fun vsize(rawHex: String): Int {
        val tx = parse(rawHex)
        val total = rawHex.length / 2
        val base = baseBytes(tx).size
        return (base * 3 + total + 3) / 4
    }

    /** The serialisation without witnesses (what the txid hashes). */
    private fun baseBytes(tx: Tx): ByteArray {
        val out = ByteWriter()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun varint(n: Long) = when {
            n < 0xfd -> out.write(n.toInt())
            n <= 0xffff -> { out.write(0xfd); le(n, 2) }
            n <= 0xffffffffL -> { out.write(0xfe); le(n, 4) }
            else -> { out.write(0xff); le(n, 8) }
        }
        le(tx.version, 4)
        varint(tx.inputs.size.toLong())
        for (i in tx.inputs) {
            out.write(Hashes.hexToBytes(i.txid).reversedArray()); le(i.vout.toLong(), 4)
            varint(i.scriptSig.size.toLong()); out.write(i.scriptSig); le(i.sequence, 4)
        }
        varint(tx.outputs.size.toLong())
        for (o in tx.outputs) { le(o.value, 8); varint(o.scriptPubKey.size.toLong()); out.write(o.scriptPubKey) }
        le(tx.locktime, 4)
        return out.toByteArray()
    }

    /** A 2-of-2 P2WSH multisig witness script: OP_2 <33> <33> OP_2 OP_CHECKMULTISIG. */
    private fun isTwoOfTwo(ws: ByteArray) = ws.size == 71 && ws[0] == 0x52.toByte() && ws[1] == 0x21.toByte() &&
        ws[35] == 0x21.toByte() && ws[69] == 0x52.toByte() && ws[70] == 0xae.toByte()

    /** Every input spends a Lightning funding output (2-of-2 P2WSH multisig). */
    fun spendsChannelFunding(tx: Tx): Boolean = tx.inputs.isNotEmpty() && tx.inputs.all { i ->
        i.scriptSig.isEmpty() && i.witness.size == 4 && i.witness[0].isEmpty() && isTwoOfTwo(i.witness[3])
    }

    /**
     * A commitment (force close) hides the commitment number in the locktime (upper byte 0x20)
     * and the sequence (upper byte 0x80); a cooperative close does not.
     */
    fun isCommitment(tx: Tx): Boolean =
        (tx.locktime ushr 24) == 0x20L && tx.inputs.all { (it.sequence ushr 24) == 0x80L }
}
