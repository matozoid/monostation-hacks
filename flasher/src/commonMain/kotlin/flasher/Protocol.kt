package flasher

/** Bootloader and app SysEx (docs/update-protocol.md, docs/sysex-handlers.md). */
object Protocol {
    val VERSION_REQUEST = bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x70, 0xF7)
    val REBOOT_TO_BOOTLOADER = bytes(0xF0, 0x00, 0x20, 0x29, 0x00, 0x71, 0x00, 0x7F, 0x00, 0x00, 0xF7)

    val STATUS_FAILED = mapOf(
        16 to "user cancelled",
        124 to "upload failed, composition not viable",
        126 to "upload failed, composition viable",
    )
    const val STATUS_SUCCESS = 127
    val STATUS_CONFIRM = setOf(1, 17)  // waiting for user confirmation

    const val PACKET_TIMEOUT_MS = 10_000L     // Components' FIRMWARE_UPDATE_TIMEOUT
}

/** (counter, short form) of a bootloader 0x72 ack, or null. */
fun parseAck(msg: ByteArray): Pair<Int, Boolean>? {
    if (!msg.startsWith(Container.NOVATION_HEADER.copyOf(4)) || msg.size < 6 || msg.u(5) != Container.CMD_DATA)
        return null
    return when (msg.size) {
        10 -> (msg.u(6) + (msg.u(7) shl 7) + (msg.u(8) shl 14)) to true
        15 -> (msg.u(13) + (msg.u(12) shl 4) + (msg.u(11) shl 8)) to false
        else -> null
    }
}

/** Status code of a 10-byte 0x73 status report, or null. */
fun parseStatus(msg: ByteArray): Int? =
    if (msg.startsWith(Container.NOVATION_HEADER.copyOf(4)) && msg.size == 10 && msg.u(5) == Container.CMD_DATA_FIRST)
        msg.u(6) else null

/** (bootloader, firmware) from a 0x70 reply: 5 decimal digits each. */
fun decodeVersion(msg: ByteArray): Pair<Int, Int>? {
    if (msg.size < 17 || !msg.startsWith(Protocol.VERSION_REQUEST.copyOf(6))) return null
    fun digits(from: Int) = (from until from + 5).joinToString("") { msg.u(it).toString() }.toInt()
    return digits(6) to digits(11)
}

/** Components' test: the bootloader's port name says Bootloader or BL. */
fun isBootloaderPort(name: String) =
    "Circuit Mono Station Bootloader" in name || "Circuit Mono Station BL" in name

/**
 * Collects complete F0..F7 messages from MIDI input chunks. Backends may split one SysEx
 * over several callbacks (ALSA sequencer events) or deliver it whole (Web MIDI).
 * Real-time bytes (F8..FF) inside a SysEx are dropped.
 */
class SysexAssembler(private val onMessage: (ByteArray) -> Unit) {
    private val buf = mutableListOf<Byte>()
    private var inSysex = false

    fun feed(data: ByteArray, start: Int, length: Int) {
        for (i in start until start + length) {
            val b = data.u(i)
            when {
                b >= 0xF8 -> {}
                b == 0xF0 -> { buf.clear(); buf += data[i]; inSysex = true }
                !inSysex -> {}
                b == 0xF7 -> { buf += data[i]; inSysex = false; onMessage(buf.toByteArray()); buf.clear() }
                b >= 0x80 -> { inSysex = false; buf.clear() }   // a status byte aborts the SysEx
                else -> buf += data[i]
            }
        }
    }
}
