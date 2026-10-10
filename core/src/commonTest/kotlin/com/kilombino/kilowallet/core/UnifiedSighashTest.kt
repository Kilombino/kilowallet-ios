package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.TxBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The unified message against Knots' reference vectors (ported from the Android app's UnifiedSighashTest). */
class UnifiedSighashTest {
    private class Reader(val b: ByteArray) {
        var o = 0
        fun u8(): Int = b[o++].toInt() and 0xFF
        fun take(n: Int): ByteArray = b.copyOfRange(o, o + n).also { o += n }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); o += 4; return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); o += 8; return v }
        fun varint(): Long = when (val n = u8()) {
            in 0..0xfc -> n.toLong()
            0xfd -> (u8().toLong()) or (u8().toLong() shl 8)
            0xfe -> u32()
            else -> u64()
        }
    }

    @Test
    fun everyScriptType0And1Vector() {
        val rows = KnotsUnifiedVectors.TSV.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(142, rows.size)
        for (row in rows) {
            val c = row.split('\t')
            val r = Reader(Hashes.hexToBytes(c[1]))
            val version = r.u32()
            val segwit = r.b[r.o] == 0.toByte() && r.b[r.o + 1] == 1.toByte()
            if (segwit) r.o += 2
            val nIn = r.varint().toInt()
            val prevouts = ArrayList<ByteArray>(); val sequences = ArrayList<Long>()
            repeat(nIn) { prevouts.add(r.take(36)); r.take(r.varint().toInt()); sequences.add(r.u32()) }
            val outputs = ArrayList<TxBuilder.Output>()
            repeat(r.varint().toInt()) { val v = r.u64(); outputs.add(TxBuilder.Output(r.take(r.varint().toInt()), v)) }
            if (segwit) repeat(nIn) { repeat(r.varint().toInt()) { r.take(r.varint().toInt()) } }
            val locktime = r.u32()
            val spent = c[5].split('|').map { val (v, s) = it.split(':'); v.toLong() to Hashes.hexToBytes(s) }
            val got = TxBuilder.unifiedMessage(
                version, locktime, c[3].toInt(), c[4].toInt(), prevouts, spent.map { it.first },
                spent.map { it.second }, sequences, outputs, c[2].toInt(), Hashes.hexToBytes(c[0]),
            )
            assertEquals(c[6], got.toHex(), "vector: ${c[1].take(32)}…")
        }
    }
}
