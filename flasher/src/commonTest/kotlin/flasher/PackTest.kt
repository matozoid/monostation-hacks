package flasher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PackTest {
    private fun patch(name: String, fill: Int = 0x10) =
        (name.padEnd(Patch.NAME_BYTES).encodeToByteArray() + ByteArray(Storage.PATCH_BYTES - Patch.NAME_BYTES) { fill.toByte() })

    private val bank = concat((0 until 3).map { Pack.slotWrite(it, patch("p$it", it)) })

    @Test fun slotWriteFraming() {
        val m = Pack.slotWrite(5, patch("Acid"))
        assertEquals(136, m.size)
        assertContentEquals(bytes(0xF0, 0x00, 0x20, 0x29, 0x01, 0x61, 0x01, 0x05, 0x00), m.copyOf(9))
        assertEquals(0xF7, m.u(135))
        assertFailsWith<IllegalArgumentException> { Pack.slotWrite(64, patch("x")) }
    }

    @Test fun parsesSyx() = runTest {
        val pack = Pack.read(bank)
        assertEquals(0, pack.sessions.size)
        assertEquals(listOf(0, 1, 2), pack.patches.map { it.slot })
        assertEquals(listOf("p0", "p1", "p2"), pack.patches.map { it.name })
        assertContentEquals(patch("p1", 1), pack.patches[1].data)
    }

    @Test fun currentPatchHasNoSlot() {
        val m = Pack.slotWrite(9, patch("cur")).also { it[6] = 0 }
        val p = Pack.parsePatches(m).single()
        assertNull(p.slot)
        assertFailsWith<FlashError> { Pack.place(listOf(p), null) }
        assertEquals(listOf(20), Pack.place(listOf(p), 20).map { it.first })
    }

    @Test fun rejectsOtherSysex() {
        assertFailsWith<FlashError> { Pack.parsePatches(bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x70, 0xF7)) }
    }

    @Test fun placement() {
        val ps = Pack.parsePatches(bank)
        assertEquals(listOf(0, 1, 2), Pack.place(ps, null).map { it.first })
        assertEquals(listOf(61, 62, 63), Pack.place(ps, 61).map { it.first })
        assertFailsWith<FlashError> { Pack.place(ps, 62) }
        assertFailsWith<FlashError> { Pack.place(ps + ps, null) }   // a slot twice
    }

    @Test fun readsPackZip() = runTest {
        val sessions = ByteArray(2 * Storage.SESSION_BYTES) { (it % 251).toByte() }
        val zip = writeZip(listOf("patches.syx" to bank, "sessions.syx" to Storage.encodeTransfer(sessions, Storage.SESSIONS_START)),
            ZipDate(2026, 11, 21))
        val pack = Pack.read(zip)
        assertEquals(3, pack.patches.size)
        assertEquals(listOf(0, 1), pack.sessions.map { it.index })
        assertContentEquals(sessions.copyOfRange(Storage.SESSION_BYTES, 2 * Storage.SESSION_BYTES), pack.sessions[1].data)
        assertEquals("3 patches, 2 sessions", pack.summary)
    }

    private fun image(index: Int) = SessionImage(index, ByteArray(Storage.SESSION_BYTES))

    @Test fun sessionPlacement() {
        val all = (0..Storage.INIT_SESSION).map(::image)
        assertEquals((0..32).toList(), Pack.placeSessions(all, null).map { it.first })
        val some = listOf(image(4), image(9), image(Storage.INIT_SESSION))
        assertEquals(listOf(4, 9, 32), Pack.placeSessions(some, null).map { it.first })
        assertEquals(listOf(30, 31, 32), Pack.placeSessions(some, 30).map { it.first })   // INIT stays INIT
        assertFailsWith<FlashError> { Pack.placeSessions(some, 31) }
        assertEquals(Storage.SESSIONS_START + 32 * 0x16000, Storage.sessionStart(32))
    }
}
