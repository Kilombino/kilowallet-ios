package com.kilombino.kilowallet.core.crypto

/** A growable byte buffer: the multiplatform stand-in for java.io.ByteArrayOutputStream. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var n = buf.size * 2
        while (n < size + extra) n *= 2
        buf = buf.copyOf(n)
    }

    /** Write the low 8 bits of [b]. */
    fun write(b: Int) { ensure(1); buf[size++] = b.toByte() }

    fun write(bytes: ByteArray) { ensure(bytes.size); bytes.copyInto(buf, size); size += bytes.size }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
