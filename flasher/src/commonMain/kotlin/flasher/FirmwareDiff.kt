package flasher

/**
 * Builds a firmware .syx from the stock file plus one of our diffs (tools/fwdiff.py,
 * docs/firmware-diff.md), so the page ships no Novation bytes.
 */

const val STOCK_800_SHA256 = "960db2d0950123fca9aea13cac52844a8da26cc90e047faae5d9a589ec616a71"
const val STOCK_800_URL =
    "https://s3-eu-west-1.amazonaws.com/circuit-content/f53741d5f0a40a808d29ac4f137222a4e0974ce3/monostation-firmware-800.syx"

private const val SPARE_BITS = Container.BLOCK_SEPTETS * 7 - Container.BLOCK_BYTES * 8
private const val ERASED_BYTE = 0xFF.toByte()

/** Pack 32 bytes plus 3 spare bits into 37 septets, MSB first (inverse of decodeBlock). */
fun encodeBlock(payload: ByteArray, spare: Int): ByteArray {
    require(payload.size == Container.BLOCK_BYTES)
    val out = ByteArray(Container.BLOCK_SEPTETS)
    var acc = 0
    var bits = 0
    var n = 0
    fun push(value: Int, count: Int) {
        acc = (acc shl count) or value
        bits += count
        while (bits >= 7) {
            bits -= 7
            out[n++] = ((acc shr bits) and 0x7F).toByte()
            acc = acc and ((1 shl bits) - 1)
        }
    }
    for (i in payload.indices) push(payload.u(i), 8)
    push(spare, SPARE_BITS)
    return out
}

/** tools/fwsyx.py encode_firmware: start, the 0x72 blocks, the 0x73 block, trailer. */
fun encodeFirmware(image: ByteArray, version: Int, productId: List<Int> = Container.MONOSTATION_PRODUCT_ID): ByteArray {
    require(image.isNotEmpty()) { "image is empty" }
    val pad = (Container.BLOCK_BYTES - image.size % Container.BLOCK_BYTES) % Container.BLOCK_BYTES
    val padded = image + ByteArray(pad) { ERASED_BYTE }
    // Spare bits look ahead to the next byte in image order; past the end, the last byte repeats.
    fun lookahead(i: Int) = padded.u(minOf(i, padded.size - 1))
    val blocks = (padded.indices step Container.BLOCK_BYTES).map { i ->
        encodeBlock(padded.copyOfRange(i, i + Container.BLOCK_BYTES), lookahead(i + Container.BLOCK_BYTES) shr (8 - SPARE_BITS))
    }
    fun message(cmd: Int, body: ByteArray) = Container.NOVATION_HEADER + bytes(cmd) + body + bytes(0xF7)

    val digits = version.toString().padStart(Container.VERSION_DIGITS, '0')
    require(digits.length == Container.VERSION_DIGITS) { "version $version doesn't fit" }
    val start = message(Container.CMD_START, ByteArray(2 + digits.length) {
        if (it < 2) productId[it].toByte() else (digits[it - 2] - '0').toByte()
    })
    val sum = checksum(padded)
    val trailer = message(Container.CMD_TRAILER, bytes(0) + Container.TRAILER_NAME +
            ByteArray(Container.CHECKSUM_NIBBLES) { ((sum shr (4 * (Container.CHECKSUM_NIBBLES - 1 - it))) and 0xF).toByte() })

    val parts = mutableListOf(start)
    blocks.drop(1).forEach { parts += message(Container.CMD_DATA, it) }
    parts += message(Container.CMD_DATA_FIRST, blocks[0])
    parts += trailer
    val out = ByteArray(parts.sumOf { it.size })
    var pos = 0
    for (p in parts) { p.copyInto(out, pos); pos += p.size }
    return out
}

sealed class DiffOp {
    class Copy(val offset: Int, val length: Int) : DiffOp()
    class Data(val bytes: ByteArray) : DiffOp()
}

class FirmwareDiff(
    val baseSyxSha256: String,
    val targetVersion: Int,
    val targetLength: Int,
    val targetSha256: String,
    val ops: List<DiffOp>,
) {
    companion object {
        const val MAGIC = "fwdiff 1"

        fun parse(text: String): FirmwareDiff {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            require(lines.firstOrNull() == MAGIC) { "not a firmware diff (expected '$MAGIC')" }
            val header = mutableMapOf<String, String>()
            val ops = mutableListOf<DiffOp>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore(' ')
                val rest = line.substringAfter(' ', "")
                when {
                    key == "copy" -> {
                        val (src, n) = rest.split(' ').filter { it.isNotEmpty() }
                        ops += DiffOp.Copy(src.toInt(16), n.toInt())
                    }
                    key == "data" -> ops += DiffOp.Data(unhex(rest))
                    ops.isNotEmpty() -> throw IllegalArgumentException("header line after the ops: $line")
                    else -> header[key] = rest
                }
            }
            fun field(name: String) = header[name] ?: throw IllegalArgumentException("missing $name")
            return FirmwareDiff(field("base-syx-sha256"), field("target-version").toInt(),
                field("target-length").toInt(), field("target-sha256"), ops)
        }

        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd hex length" }
            return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }

    /** The target .syx, after checking the stock file and the rebuilt image. */
    fun apply(baseSyx: ByteArray): ByteArray {
        require(sha256(baseSyx).toHexString() == baseSyxSha256) {
            "this isn't the stock firmware file the build was made for (monostation-firmware-800.syx, firmware 1.2)"
        }
        val base = decodeImage(splitMessages(baseSyx))
        val out = ByteArray(targetLength)
        var pos = 0
        for (op in ops) {
            val chunk = when (op) {
                is DiffOp.Copy -> {
                    require(op.offset + op.length <= base.size) { "copy past the stock image" }
                    base.copyOfRange(op.offset, op.offset + op.length)
                }
                is DiffOp.Data -> op.bytes
            }
            require(pos + chunk.size <= out.size) { "the diff is longer than its target" }
            chunk.copyInto(out, pos)
            pos += chunk.size
        }
        require(pos == targetLength && sha256(out).toHexString() == targetSha256) {
            "the rebuilt image doesn't match the diff's checksum"
        }
        return encodeFirmware(out, targetVersion)
    }
}

/** SHA-256 (FIPS 180-4); common code has no digest API. */
@OptIn(ExperimentalUnsignedTypes::class)
fun sha256(data: ByteArray): ByteArray {
    val k = uintArrayOf(
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    ).asIntArray()
    val h = uintArrayOf(
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au, 0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    ).asIntArray()
    val bitLength = data.size.toLong() * 8
    val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (chunk in padded.indices step 64) {
        for (t in 0 until 16) {
            val o = chunk + 4 * t
            w[t] = (padded.u(o) shl 24) or (padded.u(o + 1) shl 16) or (padded.u(o + 2) shl 8) or padded.u(o + 3)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var (a, b, c, d) = h
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
}
