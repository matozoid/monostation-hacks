package flasher

import dev.atsushieno.ktmidi.MidiAccess
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** What the flash flow reports to its front end (the CLI or the web page). */
interface FlashUi {
    fun info(message: String)
    fun progress(sent: Int, total: Int)
    /** Shown while waiting for the button entry (Scales + Note + Velocity at power-on). */
    fun manualEntry(appRunning: Boolean)
    /** Last chance to stop before the first packet; false aborts. */
    suspend fun confirm(fw: FirmwareFile, versions: Pair<Int, Int>?): Boolean
}

class FlashOptions(
    val port: String? = null,
    /** Wait for the button entry instead of rebooting the running app with SysEx. */
    val manual: Boolean = false,
    val bootloaderWaitMs: Long = 8_000,      // after the reboot message (Components)
    val manualWaitMs: Long = 600_000,
    val rebootWaitMs: Long = 20_000,
)

/**
 * The whole update: reach the bootloader (reboot SysEx or button entry), send the packets
 * with per-packet acks, then wait for the app and check that it reports the image's version.
 * Returns the (bootloader, firmware) versions the app reports. Throws FlashError.
 */
suspend fun flashFirmware(midi: MidiAccess, fw: FirmwareFile, ui: FlashUi, options: FlashOptions = FlashOptions()): Pair<Int, Int> {
    val app = findPort(midi, options.port, bootloader = false, waitMs = 0)
    val bl: PortPair
    if (app != null && !options.manual) {
        val dev = Device.open(midi, app)
        ui.info("application port '${app.name}' reports (bootloader, firmware) ${dev.queryVersion()}")
        ui.info("rebooting into the bootloader")
        dev.send(Protocol.REBOOT_TO_BOOTLOADER)
        dev.close()
        bl = findPort(midi, options.port, bootloader = true, waitMs = options.bootloaderWaitMs) ?: run {
            // The unit may be in the bootloader while the MIDI layer hasn't shown the port yet.
            ui.info("no bootloader port after ${options.bootloaderWaitMs / 1000} s; ports now: " +
                    "${portPairs(midi).map { it.name }}. Still waiting; if the unit shows bootloader mode, " +
                    "unplug and replug USB, else enter it by hand")
            ui.manualEntry(appRunning = true)
            findPort(midi, options.port, bootloader = true, waitMs = options.manualWaitMs)
                ?: throw FlashError("no bootloader port appeared")
        }
    } else {
        ui.manualEntry(appRunning = app != null)
        bl = findPort(midi, options.port, bootloader = true, waitMs = options.manualWaitMs)
            ?: throw FlashError("no bootloader port appeared")
    }
    delay(100)
    val dev = Device.open(midi, bl)
    val blVersions = dev.queryVersion()
    ui.info("bootloader port '${bl.name}'; versions (bootloader, firmware): $blVersions")
    if (!ui.confirm(fw, blVersions)) {
        dev.close()
        throw FlashError("not flashed")
    }

    dev.drain()
    val start = TimeSource.Monotonic.markNow()
    try {
        sendFirmware(fw.packets, dev, onConfirm = { ui.info("the device asks for confirmation") }, progress = ui::progress)
    } catch (e: FlashError) {
        throw FlashError("${e.message}. The bootloader is still there: power-cycle into it and flash again, or flash stock firmware.")
    } finally {
        dev.close()
    }
    ui.info("sent in ${start.elapsedNow().inWholeSeconds} s; waiting for the firmware to start")

    val wait = TimeSource.Monotonic.markNow()
    while (wait.elapsedNow().inWholeMilliseconds < options.rebootWaitMs) {
        val p = findPort(midi, options.port, bootloader = false, waitMs = 1000) ?: continue
        val d = Device.open(midi, p)
        val versions = d.queryVersion()
        d.close()
        if (versions != null) {
            if (versions.second != fw.version)
                throw FlashError("the application reports $versions, expected firmware ${fw.version}")
            ui.info("OK: application port '${p.name}' reports (bootloader, firmware) $versions")
            return versions
        }
    }
    throw FlashError("the application didn't report a version within ${options.rebootWaitMs / 1000} s; " +
            "check the unit, or enter the bootloader and flash again")
}
