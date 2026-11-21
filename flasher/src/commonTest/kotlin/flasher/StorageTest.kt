package flasher

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StorageTest {
    @Test
    fun crc() {
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
        assertEquals(0, crc32(ByteArray(0)))
    }

    @Test
    fun blockRoundTrip() {
        val payload = Random(1).nextBytes(Storage.BLOCK_BYTES)
        val septets = Storage.encodeBlock(payload)
        assertEquals(Storage.BLOCK_SEPTETS, septets.size)
        assertEquals(true, septets.all { it >= 0 })
        assertContentEquals(payload, Storage.decodeBlock(septets))
    }

    @Test
    fun transferRoundTrip() {
        val stream = Random(2).nextBytes(4 * Storage.BLOCK_BYTES)
        val syx = Storage.encodeTransfer(stream, Storage.SESSIONS_START)
        assertContentEquals(bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x77, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0xF7),
            syx.copyOf(23))
        assertEquals(23 + 4 * 300 + 15, syx.size)
        val (start, back) = Storage.decodeTransfer(syx)
        assertEquals(Storage.SESSIONS_START, start)
        assertContentEquals(stream, back)
        assertContentEquals(bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x78, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0xF7),
            Storage.request(Storage.PATCHES_START, Storage.PATCHES_LENGTH))
    }

    @Test
    fun patchesSyx() {
        val dump = ByteArray(Storage.PATCHES_LENGTH) { (it % 0x7F).toByte() }
        val syx = Storage.patchesSyx(dump)
        assertEquals(64 * 136, syx.size)
        val last = syx.copyOfRange(63 * 136, 64 * 136)
        assertContentEquals(bytes(0xF0, 0x00, 0x20, 0x29, 0x01, 0x61, 0x01, 63, 0x00), last.copyOf(9))
        assertContentEquals(dump.copyOfRange(63 * 512, 63 * 512 + 126), last.copyOfRange(9, 135))
        assertEquals(0xF7.toByte(), last[135])
    }

    @Test
    fun zipRoundTrip() = runTest {
        val files = listOf("patches.syx" to Random(3).nextBytes(1000), "sessions.syx" to ByteArray(100_000) { (it / 1000).toByte() })
        val zip = writeZip(files, ZipDate(2026, 11, 20, 12, 30))
        val back = readZip(zip)
        assertEquals(files.map { it.first }, back.keys.toList())
        for ((name, data) in files) assertContentEquals(data, back[name])
    }
}
