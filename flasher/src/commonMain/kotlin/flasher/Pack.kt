package flasher

/** One patch from a file: the 126 bytes after the 9-byte header, and the slot it came from (null for a current patch). */
class Patch(val slot: Int?, val data: ByteArray) {
    init { require(data.size == Storage.PATCH_BYTES) { "a patch has ${Storage.PATCH_BYTES} bytes, not ${data.size}" } }

    /** Name at patch bytes 0–15, space-padded ASCII. The panel can't rename, so many slots share a default name. */
    val name: String get() = data.copyOfRange(0, NAME_BYTES).decodeToString().trimEnd()

    companion object {
        const val NAME_BYTES = 16
    }
}

/** What a .monostationpack or .syx holds: patches and/or a sessions transfer (kept as is). */
class Pack(val patches: List<Patch>, val sessions: ByteArray?) {
    val summary: String get() = listOfNotNull(
        if (patches.isEmpty()) "no patches" else "${patches.size} patches",
        if (sessions == null) "no sessions" else "${sessions.size / Storage.SESSION_BYTES} session images",
    ).joinToString(", ")

    companion object {
        /** A .monostationpack (ZIP with patches.syx and/or sessions.syx) or a .syx of 136-byte patches. */
        suspend fun read(data: ByteArray): Pack {
            if (data.size >= 4 && data.u(0) == 0x50 && data.u(1) == 0x4B) {
                val files = try { readZip(data) } catch (e: IllegalArgumentException) { throw FlashError("bad pack: ${e.message}") }
                val patches = files["patches.syx"]?.let(::parsePatches) ?: emptyList()
                val sessions = files["sessions.syx"]?.let { syx ->
                    val (start, stream) = try { Storage.decodeTransfer(syx) } catch (e: IllegalArgumentException) {
                        throw FlashError("bad sessions.syx: ${e.message}")
                    }
                    if (start != Storage.SESSIONS_START) throw FlashError("sessions.syx starts at 0x${start.toString(16)}, not at the sessions")
                    stream
                }
                if (patches.isEmpty() && sessions == null) throw FlashError("the pack holds neither patches.syx nor sessions.syx")
                return Pack(patches, sessions)
            }
            return Pack(parsePatches(data), null)
        }

        /** 136-byte Mono Station patch messages (type 0 current patch or type 1 slot); anything else is an error. */
        fun parsePatches(syx: ByteArray): List<Patch> {
            val messages = try { splitMessages(syx) } catch (e: IllegalArgumentException) { throw FlashError("not a SysEx file: ${e.message}") }
            if (messages.isEmpty()) throw FlashError("no SysEx messages")
            return messages.mapIndexed { i, m ->
                if (m.size != MESSAGE_BYTES || !m.startsWith(HEADER) || m.u(6) !in setOf(TYPE_CURRENT, TYPE_SLOT))
                    throw FlashError("message ${i + 1} isn't a Mono Station patch (${m.size} bytes)")
                Patch(if (m.u(6) == TYPE_SLOT) m.u(7) else null, m.copyOfRange(9, 9 + Storage.PATCH_BYTES))
            }
        }

        val HEADER = bytes(0xF0, 0x00, 0x20, 0x29, 0x01, 0x61)
        const val MESSAGE_BYTES = 136
        const val TYPE_CURRENT = 0x00
        const val TYPE_SLOT = 0x01

        /** The type-1 message that stores a patch in a slot (docs/sysex-spec.md). */
        fun slotWrite(slot: Int, data: ByteArray): ByteArray {
            require(slot in 0 until Storage.PATCH_SLOTS) { "slot $slot is outside 0–${Storage.PATCH_SLOTS - 1}" }
            require(data.size == Storage.PATCH_BYTES && data.all { it >= 0 }) { "not a 126-byte SysEx-safe patch" }
            return HEADER + bytes(TYPE_SLOT, slot, 0x00) + data + bytes(0xF7)
        }

        /**
         * (slot, patch) pairs to write: each to its own slot (from = null), or one after another
         * from slot `from`. Slots count from 0; the panel shows them as 1–64.
         */
        fun place(patches: List<Patch>, from: Int?): List<Pair<Int, Patch>> {
            if (from == null) {
                val missing = patches.count { it.slot == null }
                if (missing > 0) throw FlashError("$missing patch(es) have no slot; choose a first slot")
                val slots = patches.map { it.slot!! }
                if (slots.toSet().size != slots.size) throw FlashError("the file has a slot more than once; choose a first slot")
                slots.firstOrNull { it >= Storage.PATCH_SLOTS }?.let { throw FlashError("slot ${it + 1} is beyond slot ${Storage.PATCH_SLOTS}") }
                return patches.map { it.slot!! to it }
            }
            if (from !in 0 until Storage.PATCH_SLOTS) throw FlashError("first slot must be 1–${Storage.PATCH_SLOTS}")
            if (from + patches.size > Storage.PATCH_SLOTS)
                throw FlashError("${patches.size} patches from slot ${from + 1} run past slot ${Storage.PATCH_SLOTS}")
            return patches.mapIndexed { i, p -> from + i to p }
        }
    }
}
