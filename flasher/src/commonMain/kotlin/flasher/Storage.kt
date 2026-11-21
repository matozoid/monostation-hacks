package flasher

/**
 * External-flash transfers through the bootloader (docs/session-transport.md, tools/sessionsyx.py):
 * a 0x78 request, then a 0x77 header (start, length), 0x79 blocks of 256 bytes, a 0x7A CRC-32.
 */
object Storage {
    const val CMD_HEADER = 0x77
    const val CMD_REQUEST = 0x78
    const val CMD_DATA = 0x79
    const val CMD_TRAILER = 0x7A
    const val CMD_LEAVE_BOOTLOADER = 0x61
    const val CMD_FLOW = 0x7B            // upload flow control, 8-nibble counter
    const val CMD_FLOW_SHORT = 0x75      // upload flow control, 16-bit counter at bytes 7–8

    const val BLOCK_BYTES = 256
    const val BLOCK_SEPTETS = 293
    private const val GROUP = 7          // data bytes per high-bit septet
    private const val NIBBLES = 8

    const val PATCHES_START = 0x2000
    const val PATCH_SLOTS = 64            // what packs and the panel use; the store has 128
    const val PATCH_SLOT_BYTES = 512
    const val PATCH_BYTES = 126
    const val PATCHES_LENGTH = PATCH_SLOTS * PATCH_SLOT_BYTES

    const val SESSIONS_START = 0x12000
    const val SESSION_BYTES = 90_112
    const val SESSIONS = 33               // 32 user sessions and the INIT image, as Components dumps them
    const val USER_SESSIONS = 32
    const val INIT_SESSION = 32           // index of the INIT image, after the 32 sessions

    fun sessionStart(index: Int) = SESSIONS_START + index * SESSION_BYTES

    val LEAVE_BOOTLOADER = bytes(0xF0, 0x00, 0x20, 0x29, 0x00, CMD_LEAVE_BOOTLOADER, 0xF7)

    fun message(cmd: Int, body: ByteArray) = Container.NOVATION_HEADER + byteArrayOf(cmd.toByte()) + body + bytes(0xF7)

    fun nibbles(value: Int) = ByteArray(NIBBLES) { ((value ushr (4 * (NIBBLES - 1 - it))) and 0xF).toByte() }

    fun fromNibbles(body: ByteArray, from: Int): Int {
        var v = 0
        for (i in from until from + NIBBLES) {
            val n = body.u(i)
            require(n <= 0xF) { "bad nibble field" }
            v = (v shl 4) or n
        }
        return v
    }

    fun request(start: Int, length: Int) = message(CMD_REQUEST, nibbles(start) + nibbles(length))
    fun header(start: Int, length: Int) = message(CMD_HEADER, nibbles(start) + nibbles(length))

    /** (start, length) of a 0x77 header, or null. */
    fun parseHeader(msg: ByteArray): Pair<Int, Int>? {
        if (!isCommand(msg, CMD_HEADER) || msg.size != 23) return null
        return fromNibbles(msg, 6) to fromNibbles(msg, 14)
    }

    /** The CRC of a 0x7A trailer, or null. */
    fun parseTrailer(msg: ByteArray): Int? =
        if (isCommand(msg, CMD_TRAILER) && msg.size == 15) fromNibbles(msg, 6) else null

    fun isCommand(msg: ByteArray, cmd: Int) =
        msg.size > 6 && msg.startsWith(Container.NOVATION_HEADER) && msg.u(5) == cmd

    /** Unpack the 293 septets of a 0x79 block: each group's first septet holds bit 7 of the next 7 bytes. */
    fun decodeBlock(septets: ByteArray): ByteArray {
        require(septets.size == BLOCK_SEPTETS) { "expected $BLOCK_SEPTETS septets, got ${septets.size}" }
        val out = ByteArray(BLOCK_BYTES)
        var n = 0
        var g = 0
        while (g < septets.size) {
            val high = septets.u(g)
            for (i in 0 until minOf(GROUP, septets.size - g - 1)) {
                val s = septets.u(g + 1 + i)
                require(s <= 0x7F && high <= 0x7F) { "SysEx data byte has the high bit set" }
                out[n++] = (s or (if ((high shr i) and 1 != 0) 0x80 else 0)).toByte()
            }
            g += GROUP + 1
        }
        return out
    }

    fun encodeBlock(payload: ByteArray): ByteArray {
        require(payload.size == BLOCK_BYTES) { "expected $BLOCK_BYTES bytes, got ${payload.size}" }
        val out = ByteArray(BLOCK_SEPTETS)
        var n = 0
        for (g in payload.indices step GROUP) {
            var high = 0
            val end = minOf(g + GROUP, payload.size)
            for (i in g until end) if (payload.u(i) and 0x80 != 0) high = high or (1 shl (i - g))
            out[n++] = high.toByte()
            for (i in g until end) out[n++] = (payload.u(i) and 0x7F).toByte()
        }
        return out
    }

    /** One whole transfer as the bootloader sends it, e.g. a pack's sessions.syx. */
    fun encodeTransfer(stream: ByteArray, start: Int): ByteArray {
        require(stream.size % BLOCK_BYTES == 0) { "stream length ${stream.size} is not a multiple of $BLOCK_BYTES" }
        val parts = ArrayList<ByteArray>(stream.size / BLOCK_BYTES + 2)
        parts += header(start, stream.size)
        for (i in stream.indices step BLOCK_BYTES)
            parts += message(CMD_DATA, encodeBlock(stream.copyOfRange(i, i + BLOCK_BYTES)))
        parts += message(CMD_TRAILER, nibbles(crc32(stream)))
        return concat(parts)
    }

    /** (start, stream) of a transfer; checks the length and the CRC. */
    fun decodeTransfer(data: ByteArray): Pair<Int, ByteArray> {
        val messages = splitMessages(data)
        require(messages.size >= 2) { "not a storage transfer" }
        val (start, length) = parseHeader(messages.first()) ?: throw IllegalArgumentException("no 0x77 header first")
        val crc = parseTrailer(messages.last()) ?: throw IllegalArgumentException("no 0x7A trailer last")
        val blocks = messages.subList(1, messages.size - 1)
        require(blocks.all { isCommand(it, CMD_DATA) }) { "expected only 0x79 blocks between header and trailer" }
        val stream = concat(blocks.map { decodeBlock(it.copyOfRange(6, it.size - 1)) })
        require(stream.size == length) { "header says $length bytes, blocks hold ${stream.size}" }
        require(crc32(stream) == crc) { "CRC mismatch" }
        return start to stream
    }

    /** A pack's patches.syx from the dump at PATCHES_START: 64 slot writes of the first 126 bytes of each slot. */
    fun patchesSyx(dump: ByteArray): ByteArray {
        require(dump.size >= PATCHES_LENGTH) { "patch dump too short" }
        return concat((0 until PATCH_SLOTS).map { slot ->
            bytes(0xF0, 0x00, 0x20, 0x29, 0x01, 0x61, 0x01, slot, 0x00) +
                dump.copyOfRange(slot * PATCH_SLOT_BYTES, slot * PATCH_SLOT_BYTES + PATCH_BYTES) + bytes(0xF7)
        })
    }
}

fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var n = 0
    for (p in parts) { p.copyInto(out, n); n += p.size }
    return out
}

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

/** CRC-32 (zlib/IEEE), as the bootloader's 0x7A trailer and ZIP use it. */
fun crc32(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
    var c = -1
    for (i in from until to) c = CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}
