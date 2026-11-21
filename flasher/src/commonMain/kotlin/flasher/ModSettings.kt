package flasher

import dev.atsushieno.ktmidi.MidiAccess
import kotlinx.coroutines.delay

/**
 * Mod settings (docs/mod-settings.md): the build describes its settings over SysEx 0x7C,
 * values change live in RAM, and Save writes the store subsector at 0x7FF000 through the
 * bootloader. tools/modsettings.py and overlays/settings are the reference.
 */
object ModSettings {
    const val CMD = 0x7C
    const val OP_DESCRIBE = 0
    const val OP_GET = 1
    const val OP_SET = 2
    const val OP_BUILD = 3
    const val PAST_END = 0x7F

    const val FORMAT_VERSION = 1
    val KINDS = listOf("flag", "number", "slider", "enum", "text")

    const val SET_OK = 0
    const val SET_UNKNOWN = 1
    const val SET_OUT_OF_RANGE = 2
    const val SET_AT_BOOT = 3

    const val STORE_NONE = 0
    const val STORE_READ = 1
    const val STORE_DAMAGED = 2

    const val STORE_ADDRESS = 0x7FF000
    const val STORE_BYTES = 0x1000
    private val STORE_MAGIC = "MODS".encodeToByteArray()
    private const val STORE_VERSION = 1
    private const val STORE_HEADER = 8

    /** The name of the store in a .monostationpack; Components reads only patches.syx and sessions.syx. */
    const val PACK_FILE = "mod-settings.bin"

    const val REPLY_TIMEOUT_MS = 1_000L

    private val PREFIX = Pack.HEADER + bytes(CMD)

    fun message(op: Int, body: ByteArray = ByteArray(0)) = PREFIX + bytes(op) + body + bytes(0xF7)

    /** `value` as n nibbles, most significant first (two's complement for negative values). */
    fun nibbles(value: Int, n: Int) = ByteArray(n) { ((value ushr (4 * (n - 1 - it))) and 0xF).toByte() }

    fun string(s: String): ByteArray = nibbles(s.length, 2) + s.encodeToByteArray()

    fun encodeValue(setting: Setting, value: SettingValue): ByteArray = when (value) {
        is SettingValue.Text -> string(value.text)
        is SettingValue.Number -> nibbles(value.value, 4)
    }

    fun describe(index: Int) = message(OP_DESCRIBE, nibbles(index, 2))
    fun get(id: Int) = message(OP_GET, nibbles(id, 4))
    fun set(setting: Setting, value: SettingValue) = message(OP_SET, nibbles(setting.id, 4) + encodeValue(setting, value))
    val BUILD = message(OP_BUILD)

    /** The body after `F0 00 20 29 01 61 7C op`, without F7, when msg is a reply to op. */
    private fun body(msg: ByteArray, op: Int): Reader? =
        if (msg.size >= PREFIX.size + 2 && msg.startsWith(PREFIX) && msg.u(PREFIX.size) == op && msg.u(msg.size - 1) == 0xF7)
            Reader(msg.copyOfRange(PREFIX.size + 1, msg.size - 1)) else null

    private class Reader(val data: ByteArray) {
        var at = 0
        fun number(n: Int): Int {
            if (at + n > data.size) throw IllegalArgumentException("reply too short")
            var v = 0
            repeat(n) { v = (v shl 4) or (data.u(at++) and 0xF) }
            return v
        }
        fun signed16() = number(4).let { if (it >= 0x8000) it - 0x10000 else it }
        fun string(): String {
            val n = number(2)
            if (at + n > data.size) throw IllegalArgumentException("reply too short")
            return data.copyOfRange(at, at + n).decodeToString().also { at += n }
        }
        val done get() = at == data.size
    }

    fun parseBuild(msg: ByteArray): ModBuild? = body(msg, OP_BUILD)?.let { r ->
        runCatching {
            val version = r.number(2)
            val count = r.number(2)
            val store = r.number(1)
            val groups = List(r.number(2)) { r.string() }
            ModBuild(version, count, store, groups)
        }.getOrNull()
    }

    /** (index, count, setting) of a describe reply, (index, count, null) past the end. */
    fun parseDescribe(msg: ByteArray, groups: List<String>): Setting? {
        val r = body(msg, OP_DESCRIBE) ?: return null
        return runCatching {
            r.number(2); r.number(2)
            val id = r.number(4)
            val kind = r.number(1)
            val boot = r.number(1) == 1
            val group = groups.getOrElse(r.number(2)) { "?" }
            var min = 0; var max = 1; var default = 0
            var labels = emptyList<String>()
            var textDefault = ""
            when (kind) {
                0 -> default = r.number(1)
                1, 2 -> { min = r.signed16(); max = r.signed16(); default = r.signed16() }
                3 -> { val n = r.number(2); default = r.number(2); labels = List(n) { r.string() }; max = n - 1 }
                4 -> { max = r.number(2); textDefault = r.string() }
                else -> throw IllegalArgumentException("kind $kind")
            }
            val name = r.string()
            if (!r.done) throw IllegalArgumentException("trailing bytes")
            Setting(id, name, KINDS[kind], boot, group, min, max,
                if (kind == 4) SettingValue.Text(textDefault) else SettingValue.Number(default), labels)
        }.getOrNull()
    }

    fun isPastEnd(msg: ByteArray) = msg.size == PREFIX.size + 3 && msg.startsWith(PREFIX) &&
        msg.u(PREFIX.size) == OP_DESCRIBE && msg.u(PREFIX.size + 1) == PAST_END

    /** (id, value) of a get reply; value null for an unknown id. */
    fun parseGet(msg: ByteArray, text: Boolean): Pair<Int, SettingValue?>? {
        val r = body(msg, OP_GET) ?: return null
        return runCatching {
            val id = r.number(4)
            if (r.number(1) != 0) return id to null
            id to if (text) SettingValue.Text(r.string()) else SettingValue.Number(r.signed16())
        }.getOrNull()
    }

    /** (id, status) of a set reply. */
    fun parseSet(msg: ByteArray): Pair<Int, Int>? = body(msg, OP_SET)?.let { r ->
        runCatching { r.number(4) to r.number(1) }.getOrNull()
    }

    // The store: MODS, version, 0, entries length u16 LE, entries {id u16 LE, length u8, value},
    // CRC-32 LE over everything before it, FF to the end of the subsector.

    fun storeValue(value: SettingValue): ByteArray = when (value) {
        is SettingValue.Text -> value.text.encodeToByteArray()
        is SettingValue.Number -> bytes(value.value and 0xFF, (value.value shr 8) and 0xFF)
    }

    fun encodeStore(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val body = concat(entries.map { (id, v) ->
            require(v.size <= 0xFF) { "value of ${id.toString(16)} too long" }
            bytes(id and 0xFF, id shr 8, v.size) + v
        })
        val head = STORE_MAGIC + bytes(STORE_VERSION, 0, body.size and 0xFF, body.size shr 8) + body
        val crc = crc32(head)
        val data = head + bytes(crc and 0xFF, (crc ushr 8) and 0xFF, (crc ushr 16) and 0xFF, crc ushr 24)
        require(data.size <= STORE_BYTES) { "the store has ${data.size} bytes, more than $STORE_BYTES" }
        return data + ByteArray(STORE_BYTES - data.size) { -1 }
    }

    /** The (id, value bytes) entries, or null for no store (bad magic, version, length or CRC). */
    fun decodeStore(data: ByteArray): List<Pair<Int, ByteArray>>? {
        if (data.size < STORE_HEADER + 4 || !data.startsWith(STORE_MAGIC) || data.u(4) != STORE_VERSION) return null
        val end = STORE_HEADER + (data.u(6) or (data.u(7) shl 8))
        if (end + 4 > data.size) return null
        val stored = data.u(end) or (data.u(end + 1) shl 8) or (data.u(end + 2) shl 16) or (data.u(end + 3) shl 24)
        if (stored != crc32(data, 0, end)) return null
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var at = STORE_HEADER
        while (at + 3 <= end) {
            val id = data.u(at) or (data.u(at + 1) shl 8)
            val n = data.u(at + 2)
            if (at + 3 + n > end) return null
            out += id to data.copyOfRange(at + 3, at + 3 + n)
            at += 3 + n
        }
        return if (at == end) out else null
    }

    /**
     * The store to write: `current`'s entries for ids this build doesn't know (another build's
     * settings stay), plus every known setting whose value isn't its default. A setting at its
     * default is left out, so it follows the default of whatever build runs.
     */
    fun mergeStore(current: ByteArray?, settings: List<Setting>, values: Map<Int, SettingValue>): ByteArray {
        val known = settings.map { it.id }.toSet()
        val kept = (current?.let(::decodeStore) ?: emptyList()).filter { it.first !in known }
        val ours = settings.mapNotNull { s -> values[s.id]?.takeIf { it != s.default }?.let { s.id to storeValue(it) } }
        return encodeStore(kept + ours)
    }
}

sealed class SettingValue {
    data class Number(val value: Int) : SettingValue() { override fun toString() = value.toString() }
    data class Text(val text: String) : SettingValue() { override fun toString() = "\"$text\"" }
}

/** One setting as the build describes it. For flag and enum, min..max is 0..1 and 0..labels-1. */
class Setting(
    val id: Int, val name: String, val kind: String, val atBoot: Boolean, val group: String,
    val min: Int, val max: Int, val default: SettingValue, val labels: List<String>,
) {
    val isText get() = kind == "text"

    /** The value for `input` (a number, an enum label, on/off, or text), or null when it doesn't fit. */
    fun parse(input: String): SettingValue? = when (kind) {
        "text" -> input.takeIf { it.length <= max && it.all { c -> c.code in 0x20..0x7E } }?.let { SettingValue.Text(it) }
        "flag" -> when (input.lowercase()) { "1", "on", "true", "yes" -> 1; "0", "off", "false", "no" -> 0; else -> null }
            ?.let { SettingValue.Number(it) }
        "enum" -> (labels.indexOfFirst { it.equals(input, ignoreCase = true) }.takeIf { it >= 0 } ?: input.toIntOrNull())
            ?.takeIf { it in min..max }?.let { SettingValue.Number(it) }
        else -> input.toIntOrNull()?.takeIf { it in min..max }?.let { SettingValue.Number(it) }
    }

    /** The value as the panel would call it: a label, on/off, a number or quoted text. */
    fun show(value: SettingValue): String = when {
        value is SettingValue.Number && kind == "enum" -> labels.getOrElse(value.value) { value.toString() }
        value is SettingValue.Number && kind == "flag" -> if (value.value != 0) "on" else "off"
        else -> value.toString()
    }

    val range: String get() = when (kind) {
        "flag" -> "on/off"
        "enum" -> labels.joinToString("/")
        "text" -> "up to $max characters"
        else -> "$min..$max"
    }
}

/** A 0x7C 03 reply: what the running build offers. */
class ModBuild(val formatVersion: Int, val count: Int, val store: Int, val groups: List<String>) {
    val storeText: String get() = when (store) {
        ModSettings.STORE_NONE -> "no store (all stock)"
        ModSettings.STORE_READ -> "store read at boot"
        else -> "store damaged (all stock)"
    }
}

/** Everything the running build has: its settings and their current values. */
class ModState(val build: ModBuild, val settings: List<Setting>, val values: MutableMap<Int, SettingValue>)

/** Send `request` and return the first reply `parse` accepts, or null after timeoutMs. */
private suspend fun <T> ask(dev: Link, request: ByteArray, timeoutMs: Long, parse: (ByteArray) -> T?): T? {
    dev.drain()
    dev.send(request)
    val deadline = kotlin.time.TimeSource.Monotonic.markNow()
    while (true) {
        val left = timeoutMs - deadline.elapsedNow().inWholeMilliseconds
        if (left <= 0) return null
        val msg = dev.next(left) ?: return null
        parse(msg)?.let { return it }
    }
}

/** The build's 0x7C 03 answer, or null: stock and older mod builds don't answer. */
suspend fun queryModBuild(dev: Link, timeoutMs: Long = ModSettings.REPLY_TIMEOUT_MS): ModBuild? =
    ask(dev, ModSettings.BUILD, timeoutMs, ModSettings::parseBuild)

/** Every description and value of the running build, or null when it has no settings. */
suspend fun readModSettings(dev: Link, timeoutMs: Long = ModSettings.REPLY_TIMEOUT_MS): ModState? {
    val build = queryModBuild(dev, timeoutMs) ?: return null
    if (build.formatVersion != ModSettings.FORMAT_VERSION)
        throw FlashError("the firmware describes its settings in format ${build.formatVersion}; this page knows ${ModSettings.FORMAT_VERSION}")
    val settings = (0 until build.count).map { i ->
        ask(dev, ModSettings.describe(i), timeoutMs) { msg ->
            if (ModSettings.isPastEnd(msg)) throw FlashError("the firmware has no setting $i")
            ModSettings.parseDescribe(msg, build.groups)
        } ?: throw FlashError("no description of setting $i")
    }
    val values = mutableMapOf<Int, SettingValue>()
    for (s in settings) {
        val (_, v) = ask(dev, ModSettings.get(s.id), timeoutMs) { msg ->
            ModSettings.parseGet(msg, s.isText)?.takeIf { it.first == s.id }
        } ?: throw FlashError("no value for ${s.name}")
        values[s.id] = v ?: s.default
    }
    return ModState(build, settings, values)
}

/** Set one value live; returns the status (ModSettings.SET_*). */
suspend fun setModSetting(dev: Link, setting: Setting, value: SettingValue, timeoutMs: Long = ModSettings.REPLY_TIMEOUT_MS): Int {
    val (_, status) = ask(dev, ModSettings.set(setting, value), timeoutMs) { msg ->
        ModSettings.parseSet(msg)?.takeIf { it.first == setting.id }
    } ?: throw FlashError("no answer to setting ${setting.name}")
    return status
}

/** Read the store subsector through the bootloader (erased or damaged ones too). */
suspend fun dumpModStore(dev: Device, ui: DeviceUi): ByteArray =
    dumpWithRetry(dev, ModSettings.STORE_ADDRESS, ModSettings.STORE_BYTES, ui) {}

/**
 * Write the store through the bootloader and read it back, then go back to the app.
 * `store(current)` builds the subsector from what is there now (null when it holds no store).
 */
suspend fun writeModStore(
    midi: MidiAccess, ui: UploadUi, options: FlashOptions = FlashOptions(), store: (ByteArray?) -> ByteArray,
) {
    val bl = reachBootloader(midi, ui, options)
    delay(100)
    val dev = Device.open(midi, bl)
    try {
        ui.info("bootloader port '${bl.name}'; versions (bootloader, firmware): ${dev.queryVersion()}")
        val total = 3 * ModSettings.STORE_BYTES
        val current = dumpModStore(dev, ui)
        ui.progress(ModSettings.STORE_BYTES, total)
        val data = store(current.takeIf { ModSettings.decodeStore(it) != null })
        require(data.size == ModSettings.STORE_BYTES)
        if (data.contentEquals(current)) {
            ui.info("the store holds these values already")
        } else {
            uploadRange(dev, ModSettings.STORE_ADDRESS, data) { ui.progress(ModSettings.STORE_BYTES + it, total) }
            val back = dumpModStore(dev, ui)
            if (!back.contentEquals(data)) throw FlashError("read-back of the settings store differs")
            ui.info("settings store: written and read back")
        }
        ui.progress(total, total)
    } finally {
        dev.send(Storage.LEAVE_BOOTLOADER)
        dev.close()
    }
    val versions = waitForApp(midi, options.port, options.rebootWaitMs)
    ui.info(if (versions != null) "back in the application, (bootloader, firmware) $versions"
            else "the unit is still in the bootloader: press Play to leave it")
}
