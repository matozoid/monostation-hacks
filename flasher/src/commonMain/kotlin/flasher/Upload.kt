package flasher

import dev.atsushieno.ktmidi.MidiAccess
import kotlinx.coroutines.delay

/** What the upload flow reports to its front end. */
interface UploadUi : DeviceUi {
    fun progress(sent: Int, total: Int)   // bytes sent so far
}

/**
 * Write `stream` to the external flash at `start` with the bootloader's upload, as Components
 * does it: the 0x77 header and the first 0x79 block at once, then one more message (the next
 * block, finally the 0x7A trailer with our CRC-32) each time the unit asks with a flow-control
 * message (0x7B with an 8-nibble counter, or 0x75 with a 16-bit one; a repeated counter is
 * ignored). The unit ends with its own 0x7A, whose CRC must match ours.
 */
suspend fun uploadRange(dev: Link, start: Int, stream: ByteArray, timeoutMs: Long = 5_000, onBytes: (Int) -> Unit = {}) {
    require(stream.size % Storage.BLOCK_BYTES == 0) { "length must be a multiple of ${Storage.BLOCK_BYTES}" }
    val transfer = splitMessages(Storage.encodeTransfer(stream, start))
    val crc = crc32(stream)
    dev.drain()
    dev.send(transfer[0])
    dev.send(transfer[1])
    var next = 2
    var blocks = 1
    onBytes(Storage.BLOCK_BYTES)
    var lastCounter: Long? = null
    while (true) {
        val msg = dev.next(timeoutMs) ?: throw FlashError(
            "the unit stopped asking for data after ${blocks * Storage.BLOCK_BYTES} of ${stream.size} bytes")
        Storage.parseTrailer(msg)?.let { unitCrc ->
            if (next < transfer.size) throw FlashError("the unit ended the upload after ${blocks * Storage.BLOCK_BYTES} of ${stream.size} bytes")
            if (unitCrc != crc) throw FlashError("the unit's CRC doesn't match what we sent (damaged blocks)")
            return
        }
        val counter = flowCounter(msg) ?: continue
        if (counter == lastCounter) continue
        lastCounter = counter
        if (next == transfer.size) continue   // trailer sent; waiting for the unit's CRC
        dev.send(transfer[next++])
        if (next < transfer.size) onBytes(++blocks * Storage.BLOCK_BYTES)
    }
}

/** The counter of a 0x7B or 0x75 flow-control message, or null. */
fun flowCounter(msg: ByteArray): Long? = when {
    Storage.isCommand(msg, Storage.CMD_FLOW) && msg.size >= 15 ->
        (0 until 8).fold(0L) { acc, i -> (acc shl 4) or (msg.u(6 + i).toLong() and 0xF) }
    Storage.isCommand(msg, Storage.CMD_FLOW_SHORT) && msg.size >= 10 -> (msg.u(7) + (msg.u(8) shl 8)).toLong()
    else -> null
}

/**
 * The patch area (slots 1–64, 512 bytes each) with `writes` put in: each slot gets its 126 bytes
 * and zeros after them, as Components pads them. Slots not written keep their patch; their
 * tails, which hold leftovers, are zeroed too.
 */
fun mergePatches(current: ByteArray, writes: List<Pair<Int, Patch>>): ByteArray {
    require(current.size == Storage.PATCHES_LENGTH) { "patch area must be ${Storage.PATCHES_LENGTH} bytes" }
    val out = ByteArray(Storage.PATCHES_LENGTH)
    for (slot in 0 until Storage.PATCH_SLOTS) {
        val at = slot * Storage.PATCH_SLOT_BYTES
        current.copyInto(out, at, at, at + Storage.PATCH_BYTES)
    }
    for ((slot, p) in writes) p.data.copyInto(out, slot * Storage.PATCH_SLOT_BYTES)
    return out
}

/**
 * Store patches in slots through the bootloader: read the patch area, put the patches in,
 * write the whole area back (0x2000–0xA000, eight whole 4 KB subsectors; the hidden slots
 * 65–128 after it stay untouched), read it again to check, and leave the bootloader.
 */
suspend fun uploadPatches(midi: MidiAccess, ui: UploadUi, writes: List<Pair<Int, Patch>>, options: FlashOptions = FlashOptions()) {
    if (writes.isEmpty()) return
    val bl = reachBootloader(midi, ui, options)
    delay(100)
    val dev = Device.open(midi, bl)
    try {
        ui.info("bootloader port '${bl.name}'; versions (bootloader, firmware): ${dev.queryVersion()}")
        val total = 3 * Storage.PATCHES_LENGTH
        val current = dumpChunked(dev, Storage.PATCHES_START, Storage.PATCHES_LENGTH, 4096, ui) { ui.progress(it, total) }
        val area = mergePatches(current, writes)
        uploadRange(dev, Storage.PATCHES_START, area) { ui.progress(Storage.PATCHES_LENGTH + it, total) }
        ui.info("wrote slots ${writes.joinToString(" ") { (it.first + 1).toString() }}; reading them back")
        val back = dumpChunked(dev, Storage.PATCHES_START, Storage.PATCHES_LENGTH, 4096, ui) {
            ui.progress(2 * Storage.PATCHES_LENGTH + it, total)
        }
        if (!back.contentEquals(area)) {
            val bad = (0 until Storage.PATCH_SLOTS).filter { s ->
                val at = s * Storage.PATCH_SLOT_BYTES
                !back.copyOfRange(at, at + Storage.PATCH_SLOT_BYTES).contentEquals(area.copyOfRange(at, at + Storage.PATCH_SLOT_BYTES))
            }
            throw FlashError("read-back differs in slots ${bad.joinToString(" ") { (it + 1).toString() }}")
        }
        ui.info("read-back matches")
    } finally {
        dev.send(Storage.LEAVE_BOOTLOADER)
        dev.close()
    }
    val versions = waitForApp(midi, options.port, options.rebootWaitMs)
    ui.info(if (versions != null) "back in the application, (bootloader, firmware) $versions"
            else "the unit is still in the bootloader: press Play to leave it")
}
