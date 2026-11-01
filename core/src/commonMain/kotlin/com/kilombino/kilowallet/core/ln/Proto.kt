package com.kilombino.kilowallet.core.ln

import com.kilombino.kilowallet.core.crypto.ByteWriter

/**
 * Just enough protocol buffers to talk to the embedded LND: it takes and returns its gRPC
 * messages as serialized protobuf. Ported from the Android app's `ln/Proto.kt` (same encoder,
 * same reader; tools/android-vectors checks the bytes against it).
 *
 * Field numbers come from the lnrpc .proto files of the LND fork the engine is built from.
 */
class Pb {
    private val out = ByteWriter()

    private fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Long): Pb { if (v != 0L) { key(field, 0); varint(v) }; return this }
    fun int(field: Int, v: Int): Pb = int(field, v.toLong())
    fun bool(field: Int, v: Boolean): Pb { if (v) { key(field, 0); varint(1) }; return this }
    fun bytes(field: Int, v: ByteArray?): Pb {
        if (v == null || v.isEmpty()) return this
        key(field, 2); varint(v.size.toLong()); out.write(v); return this
    }
    fun string(field: Int, v: String?): Pb = bytes(field, v?.encodeToByteArray())
    fun msg(field: Int, m: Pb): Pb { key(field, 2); val b = m.build(); varint(b.size.toLong()); out.write(b); return this }
    /** A map<string,int64> entry, as protobuf encodes maps: a message with key=1, value=2. */
    fun mapEntry(field: Int, k: String, v: Long): Pb = msg(field, Pb().string(1, k).int(2, v))

    fun build(): ByteArray = out.toByteArray()
}

/** The fields of one decoded message, by number: varints as Long, length-delimited as bytes. */
class PbMsg(private val fields: Map<Int, List<Any>>) {
    fun long(n: Int): Long = (fields[n]?.lastOrNull() as? Long) ?: 0L
    fun int(n: Int): Int = long(n).toInt()
    fun bool(n: Int): Boolean = long(n) != 0L
    fun bytes(n: Int): ByteArray = (fields[n]?.lastOrNull() as? ByteArray) ?: ByteArray(0)
    fun string(n: Int): String = bytes(n).decodeToString()
    fun msg(n: Int): PbMsg = parse(bytes(n))
    fun msgs(n: Int): List<PbMsg> = fields[n].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }
    fun strings(n: Int): List<String> = fields[n].orEmpty().filterIsInstance<ByteArray>().map { it.decodeToString() }
    fun has(n: Int): Boolean = fields.containsKey(n)

    companion object {
        fun parse(b: ByteArray): PbMsg {
            val f = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun varint(): Long {
                var shift = 0; var r = 0L
                while (true) {
                    val c = b[i++].toInt() and 0xff
                    r = r or ((c and 0x7f).toLong() shl shift)
                    if (c and 0x80 == 0) return r
                    shift += 7
                }
            }
            while (i < b.size) {
                val k = varint(); val field = (k ushr 3).toInt()
                when ((k and 7).toInt()) {
                    0 -> f.getOrPut(field) { mutableListOf() } += varint()
                    1 -> { i += 8 }                               // fixed64: none of ours
                    2 -> { val len = varint().toInt(); f.getOrPut(field) { mutableListOf() } += b.copyOfRange(i, i + len); i += len }
                    5 -> { i += 4 }                               // fixed32: none of ours
                    else -> throw IllegalArgumentException("unsupported protobuf wire type")
                }
            }
            return PbMsg(f)
        }
    }
}
