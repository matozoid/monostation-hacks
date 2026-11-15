package flasher

/**
 * Circuit Mono Station firmware .syx container (docs/firmware-container.md), ported from
 * tools/fwsyx.py and the checks in tools/flash.py. The Python tools stay the reference.
 */
object Container {
    val NOVATION_HEADER = bytes(0xF0, 0x00, 0x20, 0x29, 0x00)

    const val CMD_START = 0x71
    const val CMD_DATA = 0x72
    const val CMD_DATA_FIRST = 0x73   // image offset 0, sent last
    const val CMD_TRAILER = 0x76

    val MONOSTATION_PRODUCT_ID = listOf(0x00, 0x7F)
    const val VERSION_DIGITS = 6
    val TRAILER_NAME = "Firmware".encodeToByteArray()
    const val CHECKSUM_NIBBLES = 8

    const val BLOCK_BYTES = 32
    const val BLOCK_SEPTETS = 37      // 259 bits: 256 payload + 3 look-ahead bits
    val DATA_LENGTH = NOVATION_HEADER.size + 1 + BLOCK_SEPTETS + 1

    const val LOAD_ADDRESS = 0x0800C000L
    const val FLASH_END = 0x08080000L // end of sector 7, the bootloader's erase limit
    const val HEADER_OFFSET = 0x1AC   // "MAIN" + BCD version, little-endian
    val STACK_RANGES = listOf(0x10000000L..0x10010000L, 0x20000000L..0x20020000L)
}

fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

fun ByteArray.u32le(i: Int) =
    u(i).toLong() or (u(i + 1).toLong() shl 8) or (u(i + 2).toLong() shl 16) or (u(i + 3).toLong() shl 24)

fun ByteArray.startsWith(prefix: ByteArray) =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Split a .syx byte string into its complete F0..F7 messages. */
fun splitMessages(data: ByteArray): List<ByteArray> {
    val messages = mutableListOf<ByteArray>()
    var pos = 0
    while (true) {
        val start = (pos until data.size).firstOrNull { data.u(it) == 0xF0 } ?: break
        val end = (start until data.size).firstOrNull { data.u(it) == 0xF7 }
            ?: throw IllegalArgumentException("unterminated SysEx message at offset $start")
        messages += data.copyOfRange(start, end + 1)
        pos = end + 1
    }
    return messages
}

fun command(message: ByteArray): Int {
    require(message.startsWith(Container.NOVATION_HEADER)) {
        "not a Novation firmware message: ${message.copyOf(minOf(8, message.size)).hex()}"
    }
    return message.u(Container.NOVATION_HEADER.size)
}

/** Unpack 37 septets (an MSB-first bitstream) into the 32 payload bytes. */
fun decodeBlock(septets: ByteArray): ByteArray {
    require(septets.size == Container.BLOCK_SEPTETS) { "expected ${Container.BLOCK_SEPTETS} septets, got ${septets.size}" }
    val out = ByteArray(Container.BLOCK_BYTES)
    var acc = 0
    var bits = 0
    var n = 0
    for (i in septets.indices) {
        val s = septets.u(i)
        require(s <= 0x7F) { "SysEx data byte has the high bit set" }
        acc = (acc shl 7) or s
        bits += 7
        while (bits >= 8 && n < out.size) {
            bits -= 8
            out[n++] = (acc shr bits).toByte()
            acc = acc and ((1 shl bits) - 1)
        }
    }
    return out
}

/** Two's complement of the little-endian 32-bit word sum. */
fun checksum(image: ByteArray): Long {
    require(image.size % 4 == 0) { "image length must be a multiple of 4" }
    var sum = 0L
    for (i in image.indices step 4) sum += image.u32le(i)
    return (-sum) and 0xFFFFFFFFL
}

fun parseStart(message: ByteArray): Pair<List<Int>, Int> {
    val body = message.copyOfRange(Container.NOVATION_HEADER.size + 1, message.size - 1)
    require(command(message) == Container.CMD_START && body.size == 2 + Container.VERSION_DIGITS) {
        "not a firmware start message: ${message.hex()}"
    }
    return listOf(body.u(0), body.u(1)) to body.drop(2).joinToString("") { it.toString() }.toInt()
}

fun parseTrailer(message: ByteArray): Long {
    val h = Container.NOVATION_HEADER.size + 1
    val body = message.copyOfRange(h, message.size - 1)
    val name = Container.TRAILER_NAME
    require(command(message) == Container.CMD_TRAILER && body.isNotEmpty() && body[0] == 0.toByte() &&
            body.copyOfRange(1, minOf(body.size, 1 + name.size)).contentEquals(name) &&
            body.size == 1 + name.size + Container.CHECKSUM_NIBBLES) {
        "not a firmware trailer message: ${message.hex()}"
    }
    return body.takeLast(Container.CHECKSUM_NIBBLES).fold(0L) { v, nib -> (v shl 4) or nib.toLong() }
}

/** The raw flash image at LOAD_ADDRESS: the 0x73 block first, then the 0x72 blocks. */
fun decodeImage(messages: List<ByteArray>): ByteArray {
    val h = Container.NOVATION_HEADER.size + 1
    val blocks = messages.filter { command(it) == Container.CMD_DATA || command(it) == Container.CMD_DATA_FIRST }
    val first = blocks.filter { command(it) == Container.CMD_DATA_FIRST }
    require(first.size == 1) { "expected one 0x73 block, found ${first.size}" }
    val ordered = first + blocks.filter { command(it) == Container.CMD_DATA }
    val image = ByteArray(ordered.size * Container.BLOCK_BYTES)
    ordered.forEachIndexed { i, m ->
        decodeBlock(m.copyOfRange(h, m.size - 1)).copyInto(image, i * Container.BLOCK_BYTES)
    }
    return image
}

/** Version from the image header: "MAIN" 00 08 -> 800. */
fun headerVersion(image: ByteArray): Int {
    val o = Container.HEADER_OFFSET
    require(image.size >= o + 6 && image.copyOfRange(o, o + 4).decodeToString() == "MAIN") {
        "no MAIN tag at image offset 0x${o.toString(16)}"
    }
    val bcd = image.u(o + 4) or (image.u(o + 5) shl 8)
    val text = bcd.toString(16)
    require(text.all { it.isDigit() }) { "image header version 0x${text} is not BCD" }
    return text.toInt()
}

class FirmwareFile(
    /** The messages to send: 0x71, the 0x72 blocks and the 0x73 block (not the trailer). */
    val packets: List<ByteArray>,
    val productId: List<Int>,
    val containerVersion: Int,
    val version: Int,
    val imageBytes: Int,
    val checksum: Long,
    val blocks: Int,
) {
    fun summary() = "firmware $version (start message says $containerVersion), $imageBytes bytes, " +
            "$blocks blocks, checksum 0x${checksum.toString(16).padStart(8, '0')}"
}

/** Decode and check a firmware .syx before anything is sent (tools/flash.py check_syx). */
fun checkSyx(data: ByteArray): FirmwareFile {
    val messages = splitMessages(data)
    require(messages.isNotEmpty()) { "no SysEx messages" }
    val commands = messages.map(::command)
    require(commands[0] == Container.CMD_START) { "first message is not the 0x71 start" }
    val (productId, containerVersion) = parseStart(messages[0])
    require(productId == Container.MONOSTATION_PRODUCT_ID) {
        "product id $productId is not the Mono Station's ${Container.MONOSTATION_PRODUCT_ID}"
    }
    val trailer = if (commands.last() == Container.CMD_TRAILER) messages.last() else null
    val body = if (trailer != null) commands.subList(1, commands.size - 1) else commands.drop(1)
    require(body.isNotEmpty() && body.last() == Container.CMD_DATA_FIRST &&
            body.count { it == Container.CMD_DATA_FIRST } == 1 &&
            body.dropLast(1).all { it == Container.CMD_DATA }) {
        "expected 0x71, then 0x72 blocks, one 0x73 block, then an optional 0x76"
    }
    for (m in messages.subList(1, 1 + body.size))
        require(m.size == Container.DATA_LENGTH) { "data message of ${m.size} bytes, expected ${Container.DATA_LENGTH}" }

    val image = decodeImage(messages)
    requireNotNull(trailer) { "no 0x76 trailer, so the checksum can't be checked" }
    val sum = checksum(image)
    require(parseTrailer(trailer) == sum) { "trailer checksum doesn't match the image" }
    require(Container.LOAD_ADDRESS + image.size <= Container.FLASH_END) {
        "image of ${image.size} bytes runs past 0x${Container.FLASH_END.toString(16)}"
    }
    val sp = image.u32le(0)
    val reset = image.u32le(4)
    require(Container.STACK_RANGES.any { sp in it }) { "initial stack pointer 0x${sp.toString(16)} is not in RAM" }
    require(reset and 1L == 1L && (reset and 1L.inv()) in Container.LOAD_ADDRESS until Container.LOAD_ADDRESS + image.size) {
        "reset vector 0x${reset.toString(16)} is not Thumb code inside the image"
    }
    return FirmwareFile(
        packets = messages.subList(0, 1 + body.size),
        productId = productId,
        containerVersion = containerVersion,
        version = headerVersion(image),
        imageBytes = image.size,
        checksum = sum,
        blocks = body.size,
    )
}
