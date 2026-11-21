package flasher

import dev.atsushieno.ktmidi.MidiAccess
import kotlinx.coroutines.delay

/** What the backup flow reports to its front end. */
interface BackupUi : DeviceUi {
    fun progress(sent: Int, total: Int)   // bytes read so far
}

/**
 * The unit's 64 patch slots (512 bytes each, from 0x2000), 33 session images (from 0x12000)
 * and the mod settings store (0x7FF000), null when it holds none.
 */
class Backup(val patches: ByteArray, val sessions: ByteArray, val settings: ByteArray? = null) {
    /**
     * A .monostationpack as Components writes a v1 pack: patches.syx and sessions.syx, plus
     * mod-settings.bin when the unit has a store (Components reads only the first two).
     */
    suspend fun pack(date: ZipDate): ByteArray = writeZip(listOfNotNull(
        "patches.syx" to Storage.patchesSyx(patches),
        "sessions.syx" to Storage.encodeTransfer(sessions, Storage.SESSIONS_START),
        settings?.let { ModSettings.PACK_FILE to it },
    ), date)
}

/**
 * Read start..start+length from the external flash with the bootloader's 0x78 dump.
 * The blocks carry no index, so a lost block shows up as a short dump or a bad CRC.
 */
suspend fun dumpRange(dev: Device, start: Int, length: Int, onBytes: (Int) -> Unit = {}, timeoutMs: Long = 5_000): ByteArray {
    require(length % Storage.BLOCK_BYTES == 0) { "length must be a multiple of ${Storage.BLOCK_BYTES}" }
    dev.drain()
    dev.send(Storage.request(start, length))
    val out = ByteArray(length)
    var n = 0
    var header = false
    while (true) {
        val msg = dev.next(timeoutMs) ?: throw FlashError(
            if (!header) "no reply to the dump request" else "the dump stopped after $n of $length bytes")
        if (!header) {
            val h = Storage.parseHeader(msg) ?: continue
            if (h != start to length) throw FlashError("the dump header says $h, asked for ${start to length}")
            header = true
            continue
        }
        if (Storage.isCommand(msg, Storage.CMD_DATA)) {
            if (n == length) throw FlashError("more data than asked for")
            Storage.decodeBlock(msg.copyOfRange(6, msg.size - 1)).copyInto(out, n)
            n += Storage.BLOCK_BYTES
            onBytes(n)
            continue
        }
        val crc = Storage.parseTrailer(msg) ?: continue
        if (n != length) throw FlashError("the dump ended after $n of $length bytes (lost blocks)")
        if (crc32(out) != crc) throw FlashError("the dump's CRC doesn't match (damaged blocks)")
        return out
    }
}

/** dumpRange, tried up to `attempts` times. */
suspend fun dumpWithRetry(dev: Device, start: Int, length: Int, ui: DeviceUi, attempts: Int = 3, onBytes: (Int) -> Unit): ByteArray {
    var last: FlashError? = null
    repeat(attempts) { attempt ->
        try {
            return dumpRange(dev, start, length, onBytes)
        } catch (e: FlashError) {
            last = e
            if (attempt < attempts - 1) ui.info("${e.message}; reading 0x${start.toString(16)} again")
            delay(500)
        }
    }
    throw last!!
}

/** start..start+length in dumps of at most `chunk` bytes, each retried. */
suspend fun dumpChunked(dev: Device, start: Int, length: Int, chunk: Int, ui: DeviceUi, onBytes: (Int) -> Unit): ByteArray {
    val out = ByteArray(length)
    var at = 0
    while (at < length) {
        val n = minOf(chunk, length - at)
        val base = at
        dumpWithRetry(dev, start + at, n, ui) { onBytes(base + it) }.copyInto(out, at)
        at += n
    }
    return out
}

/**
 * Read the unit's patches, sessions and mod settings through the bootloader, then leave it again.
 * Read in small dumps: through the ALSA sequencer a 32 KB dump already loses blocks, and the
 * blocks carry no index, so a lost one costs the retry of one chunk.
 */
suspend fun backupUnit(midi: MidiAccess, ui: BackupUi, options: FlashOptions = FlashOptions(), chunk: Int = 4096): Backup {
    require(chunk > 0 && chunk % Storage.BLOCK_BYTES == 0) { "chunk must be a multiple of ${Storage.BLOCK_BYTES}" }
    val bl = reachBootloader(midi, ui, options)
    delay(100)
    val dev = Device.open(midi, bl)
    val backup = try {
        ui.info("bootloader port '${bl.name}'; versions (bootloader, firmware): ${dev.queryVersion()}")
        val sessionsLength = Storage.SESSIONS * Storage.SESSION_BYTES
        val total = Storage.PATCHES_LENGTH + sessionsLength + ModSettings.STORE_BYTES
        val patches = dumpChunked(dev, Storage.PATCHES_START, Storage.PATCHES_LENGTH, chunk, ui) { ui.progress(it, total) }
        ui.info("read ${Storage.PATCH_SLOTS} patches")
        val sessions = dumpChunked(dev, Storage.SESSIONS_START, sessionsLength, chunk, ui) {
            ui.progress(Storage.PATCHES_LENGTH + it, total)
        }
        ui.info("read ${Storage.SESSIONS - 1} sessions and the INIT session")
        val store = dumpModStore(dev, ui).takeIf { ModSettings.decodeStore(it) != null }
        ui.progress(total, total)
        ui.info(if (store != null) "read the mod settings" else "no mod settings stored")
        Backup(patches, sessions, store)
    } finally {
        dev.send(Storage.LEAVE_BOOTLOADER)
        dev.close()
    }
    val versions = waitForApp(midi, options.port, options.rebootWaitMs)
    ui.info(if (versions != null) "back in the application, (bootloader, firmware) $versions"
            else "the unit is still in the bootloader: press Play to leave it")
    return backup
}
