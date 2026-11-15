package flasher

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    @Test
    fun assemblerJoinsChunksAndDropsRealtime() {
        val got = mutableListOf<ByteArray>()
        val a = SysexAssembler { got += it }
        val msg = bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x72, 0x01, 0x02, 0x00, 0xF7)
        a.feed(msg, 0, 4)
        a.feed(bytes(0xF8), 0, 1)
        a.feed(msg, 4, msg.size - 4)
        assertEquals(1, got.size)
        assertContentEquals(msg, got[0])
    }

    @Test
    fun acksAndStatus() {
        assertEquals(129 to true, parseAck(bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x72, 0x01, 0x01, 0x00, 0xF7)))
        assertEquals(127, parseStatus(bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x73, 0x7F, 0x00, 0x00, 0xF7)))
        assertNull(parseAck(Protocol.VERSION_REQUEST))
    }

    @Test
    fun version() {
        val reply = bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x70, 0, 0, 7, 4, 0, 0, 0, 7, 5, 6, 0xF7)
        assertEquals(740 to 756, decodeVersion(reply))
    }

    @Test
    fun blockDecodeMatchesBitstream() {
        // 32 bytes 0x00..0x1F with spare bits 0b101, packed MSB-first into 37 septets.
        val payload = ByteArray(32) { it.toByte() }
        val bits = payload.joinToString("") { (it.toInt() and 0xFF).toString(2).padStart(8, '0') } + "101"
        val septets = ByteArray(37) { bits.substring(it * 7, it * 7 + 7).toInt(2).toByte() }
        assertContentEquals(payload, decodeBlock(septets))
    }
}
