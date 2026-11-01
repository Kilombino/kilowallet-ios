package com.kilombino.kilowallet.core.backup

/**
 * The zip files the Lightning move file holds (Android's java.util.zip): entries are written
 * stored (no compression, with their CRC-32 and sizes up front) and read stored or deflated,
 * found through the central directory (Android's ZipOutputStream puts the sizes of a deflated
 * entry after its data, so the local headers alone don't say where an entry ends). No zip64:
 * no entry or file of 4 GB or more, which a phone's node never comes near.
 */
object Zip {
    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = Bytes(entries.sumOf { it.second.size + it.first.length * 2 + 100 } + 64)
        val central = Bytes(entries.size * 64 + 64)
        fun le16(b: Bytes, v: Int) { b.add(v.toByte()); b.add((v ushr 8).toByte()) }
        fun le32(b: Bytes, v: Int) { le16(b, v and 0xffff); le16(b, v ushr 16) }
        for ((name, data) in entries) {
            val n = name.encodeToByteArray()
            val crc = Gzip.crc32(data)
            val offset = out.size
            le32(out, 0x04034b50); le16(out, 20); le16(out, 0x0800); le16(out, 0); le16(out, 0); le16(out, 0x21)
            le32(out, crc); le32(out, data.size); le32(out, data.size); le16(out, n.size); le16(out, 0)
            out.addAll(n, 0, n.size); out.addAll(data, 0, data.size)
            le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0x0800); le16(central, 0)
            le16(central, 0); le16(central, 0x21); le32(central, crc); le32(central, data.size); le32(central, data.size)
            le16(central, n.size); le16(central, 0); le16(central, 0); le16(central, 0); le16(central, 0); le32(central, 0)
            le32(central, offset); central.addAll(n, 0, n.size)
        }
        val cdStart = out.size
        val cd = central.toByteArray()
        out.addAll(cd, 0, cd.size)
        le32(out, 0x06054b50); le16(out, 0); le16(out, 0); le16(out, entries.size); le16(out, entries.size)
        le32(out, cd.size); le32(out, cdStart); le16(out, 0)
        return out.toByteArray()
    }

    /** Every file entry: (name, contents); directories are left out. */
    fun read(zip: ByteArray): List<Pair<String, ByteArray>> {
        fun u16(p: Int) = (zip[p].toInt() and 0xff) or ((zip[p + 1].toInt() and 0xff) shl 8)
        fun u32(p: Int) = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        var e = zip.size - 22
        while (e >= 0 && u32(e) != 0x06054b50L) e--
        require(e >= 0) { "Not a zip file." }
        val count = u16(e + 10)
        var p = u32(e + 16).toInt()
        val out = ArrayList<Pair<String, ByteArray>>()
        repeat(count) {
            require(u32(p) == 0x02014b50L) { "The zip file is damaged." }
            val method = u16(p + 10)
            val crc = u32(p + 16).toInt()
            val comp = u32(p + 20).toInt()
            val size = u32(p + 24).toInt()
            val nl = u16(p + 28); val xl = u16(p + 30); val cl = u16(p + 32)
            val local = u32(p + 42).toInt()
            val name = zip.copyOfRange(p + 46, p + 46 + nl).decodeToString()
            p += 46 + nl + xl + cl
            if (name.endsWith("/")) return@repeat
            require(u32(local) == 0x04034b50L) { "The zip file is damaged." }
            val start = local + 30 + u16(local + 26) + u16(local + 28)
            val data = when (method) {
                0 -> zip.copyOfRange(start, start + comp)
                8 -> Gzip.inflateRaw(zip, start)
                else -> error("Unknown zip compression ($method).")
            }
            require(data.size == size && Gzip.crc32(data) == crc) { "The zip file is damaged ($name)." }
            out += name to data
        }
        return out
    }
}
