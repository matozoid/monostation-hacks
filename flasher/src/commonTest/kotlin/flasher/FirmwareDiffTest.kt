package flasher

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirmwareDiffTest {
    @Test
    fun sha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).toHexString())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHexString())
        // 56 bytes: the length no longer fits in the first block.
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHexString())
    }

    @Test
    fun encodeBlockInvertsDecode() {
        val payload = ByteArray(32) { (it * 37 + 5).toByte() }
        assertContentEquals(payload, decodeBlock(encodeBlock(payload, 5)))
    }

    private fun image(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    @Test
    fun encodeFirmwareRoundTrip() {
        val img = image(100)                       // not a whole block: padded with FF
        val syx = encodeFirmware(img, 756)
        val messages = splitMessages(syx)
        assertEquals(Container.MONOSTATION_PRODUCT_ID to 756, parseStart(messages[0]))
        val decoded = decodeImage(messages)
        assertContentEquals(img, decoded.copyOf(100))
        assertEquals(checksum(decoded), parseTrailer(messages.last()))
    }

    @Test
    fun diffApplyChecksBaseAndResult() {
        val base = image(256)
        val baseSyx = encodeFirmware(base, 800)
        val target = base.copyOf(288).also { t ->
            for (i in 256 until 288) t[i] = i.toByte()
            t[10] = 0x55
        }
        val text = """
            fwdiff 1
            base-syx-sha256 ${sha256(baseSyx).toHexString()}
            target-version 756
            target-length 288
            target-sha256 ${sha256(target).toHexString()}
            copy 0 10
            data 55
            copy b 245
            data ${target.copyOfRange(256, 288).toHexString()}
        """.trimIndent()
        val diff = FirmwareDiff.parse(text)
        assertContentEquals(encodeFirmware(target, 756), diff.apply(baseSyx))
        assertFailsWith<IllegalArgumentException> { diff.apply(baseSyx + bytes(0)) }
        assertFailsWith<IllegalArgumentException> { FirmwareDiff.parse(text.replace("data 55", "data 56")).apply(baseSyx) }
    }
}
