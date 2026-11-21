package flasher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

private val PREFIX = bytes(0xF0, 0x00, 0x20, 0x29, 0x01, 0x61, 0x7C)
private fun n(v: Int, k: Int) = ModSettings.nibbles(v, k)
private fun str(s: String) = ModSettings.string(s)
private fun reply(op: Int, body: ByteArray) = PREFIX + bytes(op) + body + bytes(0xF7)

/**
 * The fixture of tests/test_modsettings.py as the firmware describes it (the replies there
 * come from overlays/settings/settings.c compiled for the host).
 */
private val DESCRIBE = listOf(
    n(0x0101, 4) + bytes(2, 0) + n(0, 2) + n(0, 4) + n(100, 4) + n(0, 4) + str("Overdrive amount"),
    n(0x0201, 4) + bytes(0, 1) + n(1, 2) + bytes(1) + str("Wide pads"),
    n(0x0202, 4) + bytes(1, 0) + n(1, 2) + n(0xFFF4, 4) + n(12, 4) + n(0xFFFD, 4) + str("Offset"),
    n(0x0203, 4) + bytes(3, 0) + n(1, 2) + n(3, 2) + n(1, 2) + str("Off") + str("Soft") + str("Hard") + str("Mode"),
    n(0x0204, 4) + bytes(4, 0) + n(1, 2) + n(8, 2) + str("me") + str("Owner"),
)
private val BUILD = reply(3, n(1, 2) + n(5, 2) + bytes(0) + n(2, 2) + str("drive") + str("pads"))

/** A running build that answers 0x7C like settings.c, holding values in a map. */
private class FakeApp : Link {
    private val inbox = ArrayDeque<ByteArray>()
    val values = mutableMapOf<Int, ByteArray>(
        0x0101 to n(0, 4), 0x0201 to n(1, 4), 0x0202 to n(0xFFFD, 4), 0x0203 to n(1, 4), 0x0204 to str("me"))

    override fun send(msg: ByteArray) {
        if (!msg.startsWith(PREFIX)) return
        val body = msg.copyOfRange(8, msg.size - 1)
        fun num(from: Int, k: Int) = (0 until k).fold(0) { a, i -> (a shl 4) or body.u(from + i) }
        inbox += reply(0x70, bytes())            // noise the parser must skip
        when (msg.u(7)) {
            3 -> inbox += BUILD
            0 -> { val i = num(0, 2); inbox += if (i < 5) reply(0, n(i, 2) + n(5, 2) + DESCRIBE[i]) else reply(0, bytes(0x7F)) }
            1 -> { val id = num(0, 4); inbox += reply(1, n(id, 4) + (values[id]?.let { bytes(0) + it } ?: bytes(1))) }
            2 -> { val id = num(0, 4); values[id] = body.copyOfRange(4, body.size); inbox += reply(2, n(id, 4) + bytes(0)) }
        }
    }

    override suspend fun next(timeoutMs: Long) = inbox.removeFirstOrNull()
    override fun drain() = inbox.clear()
}

class ModSettingsTest {
    @Test
    fun storeMatchesPython() {
        val entries = listOf(0x0101 to bytes(0x32, 0x00), 0x0204 to "abc".encodeToByteArray())
        val data = ModSettings.encodeStore(entries)
        assertEquals(ModSettings.STORE_BYTES, data.size)
        assertContentEquals(hex("4d4f445301000b000101023200040203616263a6d5e783"), data.copyOf(23))   // tools/modsettings.py
        assertContentEquals(ByteArray(ModSettings.STORE_BYTES - 23) { -1 }, data.copyOfRange(23, data.size))
        val back = ModSettings.decodeStore(data)!!
        assertEquals(entries.map { it.first }, back.map { it.first })
        assertContentEquals(entries[1].second, back[1].second)
        assertEquals(emptyList(), ModSettings.decodeStore(ModSettings.encodeStore(emptyList())))
        assertNull(ModSettings.decodeStore(ByteArray(ModSettings.STORE_BYTES) { -1 }))
        assertNull(ModSettings.decodeStore(data.copyOf().also { it[9] = (it[9] + 1).toByte() }))
    }

    @Test
    fun parsesDescriptions() {
        val groups = listOf("drive", "pads")
        val b = ModSettings.parseBuild(BUILD)!!
        assertEquals(listOf(1, 5, 0), listOf(b.formatVersion, b.count, b.store))
        assertEquals(groups, b.groups)
        val s = DESCRIBE.mapIndexed { i, d -> ModSettings.parseDescribe(reply(0, n(i, 2) + n(5, 2) + d), groups)!! }
        assertEquals(listOf("slider", "flag", "number", "enum", "text"), s.map { it.kind })
        assertEquals(listOf(0, 100), listOf(s[0].min, s[0].max))
        assertEquals("Overdrive amount", s[0].name)
        assertEquals(true, s[1].atBoot)
        assertEquals("pads", s[1].group)
        assertEquals(SettingValue.Number(1), s[1].default)
        assertEquals(listOf(-12, 12), listOf(s[2].min, s[2].max))
        assertEquals(SettingValue.Number(-3), s[2].default)
        assertEquals(listOf("Off", "Soft", "Hard"), s[3].labels)
        assertEquals(SettingValue.Number(1), s[3].default)
        assertEquals(8, s[4].max)
        assertEquals(SettingValue.Text("me"), s[4].default)
        assertEquals(true, ModSettings.isPastEnd(reply(0, bytes(0x7F))))
    }

    @Test
    fun encodesRequests() {
        val offset = Setting(0x0202, "Offset", "number", false, "pads", -12, 12, SettingValue.Number(-3), emptyList())
        assertContentEquals(reply(2, n(0x0202, 4) + n(0xFFF6, 4)), ModSettings.set(offset, SettingValue.Number(-10)))
        val owner = Setting(0x0204, "Owner", "text", false, "pads", 0, 8, SettingValue.Text("me"), emptyList())
        assertContentEquals(reply(2, n(0x0204, 4) + str("Bob")), ModSettings.set(owner, SettingValue.Text("Bob")))
        assertContentEquals(reply(1, n(0x0202, 4)), ModSettings.get(0x0202))
        assertContentEquals(reply(0, n(3, 2)), ModSettings.describe(3))
        assertEquals(0x0202 to SettingValue.Number(-10), ModSettings.parseGet(reply(1, n(0x0202, 4) + bytes(0) + n(0xFFF6, 4)), false))
        assertEquals(0x0999 to null, ModSettings.parseGet(reply(1, n(0x0999, 4) + bytes(1)), false))
        assertEquals(0x0204 to SettingValue.Text("Bob"), ModSettings.parseGet(reply(1, n(0x0204, 4) + bytes(0) + str("Bob")), true))
        assertEquals(SettingValue.Number(2), Setting(1, "m", "enum", false, "g", 0, 2, SettingValue.Number(0), listOf("Off", "Soft", "Hard")).parse("hard"))
        assertNull(offset.parse("13"))
        assertNull(owner.parse("too long!"))
    }

    @Test
    fun readsAndSetsThroughTheLink() = runTest {
        val app = FakeApp()
        val state = readModSettings(app)!!
        assertEquals(5, state.settings.size)
        assertEquals(SettingValue.Number(-3), state.values[0x0202])
        assertEquals(SettingValue.Text("me"), state.values[0x0204])
        assertEquals(ModSettings.SET_OK, setModSetting(app, state.settings[0], SettingValue.Number(75)))
        assertContentEquals(n(75, 4), app.values[0x0101])
    }

    @Test
    fun backupPackCarriesTheStore() = runTest {
        val store = ModSettings.encodeStore(listOf(0x0101 to bytes(50, 0)))
        val sessions = ByteArray(Storage.SESSIONS * Storage.SESSION_BYTES)
        val zip = Backup(ByteArray(Storage.PATCHES_LENGTH), sessions, store).pack(ZipDate(2026, 11, 21))
        val pack = Pack.read(zip)
        assertContentEquals(store, pack.settings)
        assertEquals(true, "mod settings (1 stored)" in pack.summary)
        assertNull(Pack.read(Backup(ByteArray(Storage.PATCHES_LENGTH), sessions).pack(ZipDate(2026, 11, 21))).settings)
    }

    @Test
    fun saveKeepsOtherBuildsAndDropsDefaults() {
        val groups = listOf("drive", "pads")
        val s = DESCRIBE.take(3).mapIndexed { i, d -> ModSettings.parseDescribe(reply(0, n(i, 2) + n(5, 2) + d), groups)!! }
        val current = ModSettings.encodeStore(listOf(0x0101 to bytes(0x10, 0), 0x0999 to bytes(7), 0x0202 to bytes(5, 0)))
        val values = mapOf(0x0101 to SettingValue.Number(50), 0x0201 to SettingValue.Number(1), 0x0202 to SettingValue.Number(-3))
        val merged = ModSettings.decodeStore(ModSettings.mergeStore(current, s, values))!!
        assertEquals(listOf(0x0999, 0x0101), merged.map { it.first })
        assertContentEquals(bytes(50, 0), merged[1].second)
        assertEquals(emptyList(), ModSettings.decodeStore(ModSettings.mergeStore(null, s, s.associate { it.id to it.default })))
    }
}
