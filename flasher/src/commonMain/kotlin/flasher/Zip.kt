package flasher

/** Raw deflate (no zlib header), per platform: CompressionStream in the browser, java.util.zip on the JVM. */
expect suspend fun deflateRaw(data: ByteArray): ByteArray
expect suspend fun inflateRaw(data: ByteArray, size: Int): ByteArray

/** A ZIP's file date: DOS time and date fields. */
class ZipDate(val year: Int, val month: Int, val day: Int, val hour: Int = 0, val minute: Int = 0) {
    val dosTime get() = (hour shl 11) or (minute shl 5)
    val dosDate get() = ((year - 1980) shl 9) or (month shl 5) or day
}

/**
 * A ZIP with deflated files, enough for .monostationpack (patches.syx, sessions.syx).
 * No ZIP64, so each file and the whole archive stay below 4 GB.
 */
suspend fun writeZip(files: List<Pair<String, ByteArray>>, date: ZipDate): ByteArray {
    val local = mutableListOf<ByteArray>()
    val central = mutableListOf<ByteArray>()
    var offset = 0
    for ((name, data) in files) {
        val packed = deflateRaw(data)
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        val head = Le().apply {
            u32(0x04034B50); u16(20); u16(0); u16(8); u16(date.dosTime); u16(date.dosDate)
            u32(crc); u32(packed.size); u32(data.size); u16(nameBytes.size); u16(0)
        }.bytes() + nameBytes
        central += Le().apply {
            u32(0x02014B50); u16(20); u16(20); u16(0); u16(8); u16(date.dosTime); u16(date.dosDate)
            u32(crc); u32(packed.size); u32(data.size); u16(nameBytes.size); u16(0); u16(0)
            u16(0); u16(0); u32(0); u32(offset)
        }.bytes() + nameBytes
        local += head
        local += packed
        offset += head.size + packed.size
    }
    val dir = concat(central)
    val end = Le().apply {
        u32(0x06054B50); u16(0); u16(0); u16(files.size); u16(files.size); u32(dir.size); u32(offset); u16(0)
    }.bytes()
    return concat(local + dir + end)
}

/** Files of a ZIP by name (stored or deflated; directories skipped). Checks each file's CRC. */
suspend fun readZip(zip: ByteArray): Map<String, ByteArray> {
    var end = zip.size - 22
    while (end >= 0 && zip.u32le(end) != 0x06054B50L) end--
    require(end >= 0) { "not a ZIP file" }
    val count = zip.u16le(end + 10)
    var p = zip.u32le(end + 16).toInt()
    val out = linkedMapOf<String, ByteArray>()
    repeat(count) {
        require(zip.u32le(p) == 0x02014B50L) { "bad ZIP directory" }
        val method = zip.u16le(p + 10)
        val crc = zip.u32le(p + 16).toInt()
        val packedSize = zip.u32le(p + 20).toInt()
        val size = zip.u32le(p + 24).toInt()
        val nameLen = zip.u16le(p + 28)
        val skip = nameLen + zip.u16le(p + 30) + zip.u16le(p + 32)
        val headerAt = zip.u32le(p + 42).toInt()
        val name = zip.copyOfRange(p + 46, p + 46 + nameLen).decodeToString()
        p += 46 + skip
        if (name.endsWith("/")) return@repeat
        require(zip.u32le(headerAt) == 0x04034B50L) { "bad ZIP entry $name" }
        val dataAt = headerAt + 30 + zip.u16le(headerAt + 26) + zip.u16le(headerAt + 28)
        val packed = zip.copyOfRange(dataAt, dataAt + packedSize)
        val data = when (method) {
            0 -> packed
            8 -> inflateRaw(packed, size)
            else -> throw IllegalArgumentException("$name: unsupported ZIP compression $method")
        }
        require(data.size == size && crc32(data) == crc) { "$name: damaged (size or CRC)" }
        out[name] = data
    }
    return out
}

private fun ByteArray.u16le(i: Int) = u(i) or (u(i + 1) shl 8)

private class Le {
    private val out = mutableListOf<Byte>()
    fun u16(v: Int) { out += v.toByte(); out += (v shr 8).toByte() }
    fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
    fun bytes() = out.toByteArray()
}
