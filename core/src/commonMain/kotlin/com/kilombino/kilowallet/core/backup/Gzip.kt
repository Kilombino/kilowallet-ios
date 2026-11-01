package com.kilombino.kilowallet.core.backup

/**
 * gzip (RFC 1952) for the backup file, in plain Kotlin. [gzip] writes DEFLATE "stored" blocks,
 * which every inflater reads (the Android app's GZIPInputStream included): a backup is a few
 * kilobytes and is encrypted afterwards, so compressing it buys nothing. [gunzip] reads any gzip
 * stream, as the Android app's GZIPOutputStream writes them with real compression.
 */
object Gzip {
    fun gzip(data: ByteArray): ByteArray {
        val out = Bytes(data.size + data.size / 65535 * 5 + 64)
        fun put(vararg b: Int) { for (x in b) out.add(x.toByte()) }
        put(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 255)        // magic, deflate, no flags, no mtime, unknown OS
        var off = 0
        do {
            val n = minOf(65535, data.size - off)
            val last = off + n == data.size
            put(if (last) 1 else 0, n and 0xff, n ushr 8, n.inv() and 0xff, (n.inv() ushr 8) and 0xff)
            out.addAll(data, off, n)
            off += n
        } while (off < data.size)
        val crc = crc32(data)
        put(crc and 0xff, (crc ushr 8) and 0xff, (crc ushr 16) and 0xff, (crc ushr 24) and 0xff)
        val len = data.size
        put(len and 0xff, (len ushr 8) and 0xff, (len ushr 16) and 0xff, (len ushr 24) and 0xff)
        return out.toByteArray()
    }

    /** Raw deflate data (RFC 1951) from [start] of [src], as in a zip entry. */
    fun inflateRaw(src: ByteArray, start: Int): ByteArray = Inflater(src, start).inflate()

    fun gunzip(gz: ByteArray): ByteArray {
        require(gz.size >= 18 && (gz[0].toInt() and 0xff) == 0x1f && (gz[1].toInt() and 0xff) == 0x8b) { "Not gzip data." }
        require(gz[2].toInt() == 8) { "Unknown gzip compression method." }
        val flags = gz[3].toInt() and 0xff
        var pos = 10
        if (flags and 4 != 0) { val xlen = (gz[pos].toInt() and 0xff) or ((gz[pos + 1].toInt() and 0xff) shl 8); pos += 2 + xlen }
        if (flags and 8 != 0) { while (gz[pos].toInt() != 0) pos++; pos++ }
        if (flags and 16 != 0) { while (gz[pos].toInt() != 0) pos++; pos++ }
        if (flags and 2 != 0) pos += 2
        val inf = Inflater(gz, pos)
        val data = inf.inflate()
        val t = inf.bytePos()
        val crc = le32(gz, t)
        require(crc == crc32(data)) { "The backup file is damaged (CRC)." }
        return data
    }

    private fun le32(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
        ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc32(data: ByteArray): Int {
        var c = -1
        for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }

    /** RFC 1951 inflate: stored, fixed and dynamic Huffman blocks. */
    private class Inflater(private val src: ByteArray, start: Int) {
        private var pos = start
        private var bitBuf = 0
        private var bitCnt = 0
        private val out = Bytes(maxOf(64, src.size * 3))

        fun bytePos(): Int = pos

        private fun bits(n: Int): Int {
            while (bitCnt < n) {
                require(pos < src.size) { "The backup file is cut short." }
                bitBuf = bitBuf or ((src[pos++].toInt() and 0xff) shl bitCnt); bitCnt += 8
            }
            val v = bitBuf and ((1 shl n) - 1)
            bitBuf = bitBuf ushr n; bitCnt -= n
            return v
        }

        private class Huffman(lengths: IntArray) {
            val counts = IntArray(16)
            val symbols: IntArray
            init {
                for (l in lengths) counts[l]++
                counts[0] = 0
                val offs = IntArray(16)
                for (i in 1 until 16) offs[i] = offs[i - 1] + counts[i - 1]
                symbols = IntArray(lengths.size)
                for (s in lengths.indices) if (lengths[s] != 0) symbols[offs[lengths[s]]++] = s
            }
        }

        private fun decode(h: Huffman): Int {
            var code = 0; var first = 0; var index = 0
            for (len in 1 until 16) {
                code = code or bits(1)
                val count = h.counts[len]
                if (code - count < first) return h.symbols[index + (code - first)]
                index += count; first += count; first = first shl 1; code = code shl 1
            }
            error("The backup file is damaged (bad code).")
        }

        fun inflate(): ByteArray {
            do {
                val last = bits(1)
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(FIXED_LIT, FIXED_DIST)
                    2 -> dynamic()
                    else -> error("The backup file is damaged (block type).")
                }
            } while (last == 0)
            bitBuf = 0; bitCnt = 0
            return out.toByteArray()
        }

        private fun stored() {
            bitBuf = 0; bitCnt = 0
            val len = (src[pos].toInt() and 0xff) or ((src[pos + 1].toInt() and 0xff) shl 8)
            pos += 4
            require(pos + len <= src.size) { "The backup file is cut short." }
            out.addAll(src, pos, len)
            pos += len
        }

        private fun codes(lit: Huffman, dist: Huffman) {
            while (true) {
                val sym = decode(lit)
                when {
                    sym < 256 -> out.add(sym.toByte())
                    sym == 256 -> return
                    else -> {
                        val s = sym - 257
                        require(s < 29) { "The backup file is damaged (length)." }
                        val len = LBASE[s] + bits(LEXT[s])
                        val d = decode(dist)
                        require(d < 30) { "The backup file is damaged (distance)." }
                        val back = DBASE[d] + bits(DEXT[d])
                        require(back <= out.size) { "The backup file is damaged (distance too far)." }
                        val from = out.size - back
                        for (i in 0 until len) out.add(out[from + i])
                    }
                }
            }
        }

        private fun dynamic() {
            val nlen = bits(5) + 257; val ndist = bits(5) + 1; val ncode = bits(4) + 4
            val lengths = IntArray(19)
            for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
            val lencode = Huffman(lengths)
            val all = IntArray(nlen + ndist)
            var i = 0
            while (i < nlen + ndist) {
                val sym = decode(lencode)
                when {
                    sym < 16 -> all[i++] = sym
                    else -> {
                        val (value, rep) = when (sym) {
                            16 -> { require(i > 0) { "The backup file is damaged." }; all[i - 1] to 3 + bits(2) }
                            17 -> 0 to 3 + bits(3)
                            else -> 0 to 11 + bits(7)
                        }
                        require(i + rep <= all.size) { "The backup file is damaged." }
                        repeat(rep) { all[i++] = value }
                    }
                }
            }
            codes(Huffman(all.copyOfRange(0, nlen)), Huffman(all.copyOfRange(nlen, nlen + ndist)))
        }

        companion object {
            val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
            val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
            val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
            val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
            val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
            val FIXED_LIT = Huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } })
            val FIXED_DIST = Huffman(IntArray(30) { 5 })
        }
    }
}

/** A growing byte buffer (a list of boxed bytes made several MB of inflate far too slow and big). */
internal class Bytes(initial: Int) {
    private var buf = ByteArray(maxOf(16, initial))
    var size = 0
        private set
    private fun room(n: Int) {
        if (size + n <= buf.size) return
        var cap = buf.size
        while (cap < size + n) cap *= 2
        buf = buf.copyOf(cap)
    }
    fun add(b: Byte) { room(1); buf[size++] = b }
    fun addAll(src: ByteArray, off: Int, n: Int) { room(n); src.copyInto(buf, size, off, off + n); size += n }
    operator fun get(i: Int): Byte = buf[i]
    fun toByteArray(): ByteArray = buf.copyOf(size)
}
