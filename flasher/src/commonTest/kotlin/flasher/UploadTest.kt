package flasher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A bootloader taking an upload: asks for each message after the first block with a flow-control message, then answers the trailer with its CRC. */
private class FakeBootloader(val shortFlow: Boolean = false, val repeatEach: Boolean = false, val stopAfter: Int = Int.MAX_VALUE, val corrupt: Boolean = false) : Link {
    private val inbox = ArrayDeque<ByteArray>()
    var start = -1
    var length = -1
    var received = ByteArray(0)
    private var counter = 0

    override fun send(msg: ByteArray) {
        Storage.parseHeader(msg)?.let { (s, l) -> start = s; length = l; return }
        if (Storage.isCommand(msg, Storage.CMD_DATA)) {
            received += Storage.decodeBlock(msg.copyOfRange(6, msg.size - 1))
            if (received.size / Storage.BLOCK_BYTES >= stopAfter) return
            ask()
            if (repeatEach) ask(again = true)
            return
        }
        if (Storage.parseTrailer(msg) != null) {
            if (corrupt) received[0] = (received[0] + 1).toByte()
            inbox += Storage.message(Storage.CMD_TRAILER, Storage.nibbles(crc32(received)))
        }
    }

    private fun ask(again: Boolean = false) {
        if (!again) counter++
        inbox += if (shortFlow) Storage.message(Storage.CMD_FLOW_SHORT, bytes(0x00, counter and 0x7F, counter shr 7))
                 else Storage.message(Storage.CMD_FLOW, Storage.nibbles(counter))
    }

    override suspend fun next(timeoutMs: Long) = inbox.removeFirstOrNull()
    override fun drain() = inbox.clear()
}

class UploadTest {
    private val stream = ByteArray(8 * Storage.BLOCK_BYTES) { (it * 7 + it / 256).toByte() }

    @Test fun uploadsWithLongFlowControl() = runTest {
        val bl = FakeBootloader()
        var shown = 0
        uploadRange(bl, Storage.PATCHES_START, stream) { shown = it }
        assertEquals(Storage.PATCHES_START to stream.size, bl.start to bl.length)
        assertContentEquals(stream, bl.received)
        assertEquals(stream.size, shown)
    }

    @Test fun uploadsWithShortFlowControlAndRepeats() = runTest {
        val bl = FakeBootloader(shortFlow = true, repeatEach = true)
        uploadRange(bl, Storage.PATCHES_START, stream)
        assertContentEquals(stream, bl.received)
    }

    @Test fun stalledUploadFails() = runTest {
        assertFailsWith<FlashError> { uploadRange(FakeBootloader(stopAfter = 3), 0x2000, stream) }
    }

    @Test fun crcMismatchFails() = runTest {
        assertFailsWith<FlashError> { uploadRange(FakeBootloader(corrupt = true), 0x2000, stream) }
    }

    @Test fun mergeKeepsOtherSlotsAndZeroesTails() {
        val current = ByteArray(Storage.PATCHES_LENGTH) { 0x55 }
        val p = Patch(null, ByteArray(Storage.PATCH_BYTES) { 0x11 })
        val area = mergePatches(current, listOf(3 to p))
        val slot = { s: Int -> area.copyOfRange(s * 512, s * 512 + 512) }
        assertContentEquals(ByteArray(126) { 0x11 } + ByteArray(386), slot(3))
        assertContentEquals(ByteArray(126) { 0x55 } + ByteArray(386), slot(2))
    }
}
