package flasher

import dev.atsushieno.ktmidi.AlsaMidiAccess
import dev.atsushieno.ktmidi.JvmMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.RtMidiAccess
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private const val USAGE = """usage: flasher FIRMWARE.syx [--port NAME] [--backend alsa|rtmidi|jvm] [--manual] [--yes] [--list] [--check]

Flash a Circuit Mono Station firmware .syx through its bootloader (the Kotlin port of
tools/flash.py, #63). With the app running, it reboots the unit into the bootloader with
SysEx; --manual waits for the button entry instead (Scales + Note + Velocity at power-on)."""

private const val BOOTLOADER_WAIT_MS = 8_000L       // after the reboot message (Components)
private const val MANUAL_WAIT_MS = 600_000L
private const val REBOOT_WAIT_MS = 20_000L

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun access(backend: String): MidiAccess = when (backend) {
    "alsa" -> AlsaMidiAccess()
    "rtmidi" -> RtMidiAccess()
    "jvm" -> JvmMidiAccess()
    else -> fail("unknown backend $backend")
}

fun main(args: Array<String>) = runBlocking {
    var syx: String? = null
    var port: String? = null
    var backend = if (File("/dev/snd/seq").exists()) "alsa" else "rtmidi"
    var manual = false
    var yes = false
    var list = false
    var checkOnly = false
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--port" -> port = it.next()
            "--backend" -> backend = it.next()
            "--manual" -> manual = true
            "--yes" -> yes = true
            "--list" -> list = true
            "--check" -> checkOnly = true
            "-h", "--help" -> { println(USAGE); return@runBlocking }
            else -> if (syx == null) syx = a else fail(USAGE)
        }
    }
    val midi = access(backend)
    if (list) {
        println("${midi.name} ports (input + output pairs):")
        portPairs(midi).forEach { println("  ${it.name}  [in ${it.input.id}, out ${it.output.id}]") }
        return@runBlocking
    }
    val path = syx ?: fail(USAGE)

    val fw = try { checkSyx(File(path).readBytes()) } catch (e: IllegalArgumentException) { fail("$path: ${e.message}") }
    println("$path: firmware ${fw.version} (start message says ${fw.containerVersion}), ${fw.imageBytes} bytes, " +
            "${fw.blocks} blocks, checksum 0x${fw.checksum.toString(16).padStart(8, '0')}: OK")
    if (fw.version != fw.containerVersion) println("warning: the image header and the start message give different versions")
    if (checkOnly) return@runBlocking
    println("MIDI backend: ${midi.name}")

    val app = findPort(midi, port, bootloader = false, waitMs = 0)
    val bl: PortPair
    if (app != null && !manual) {
        val dev = Device.open(midi, app)
        println("application port '${app.name}' reports (bootloader, firmware) ${dev.queryVersion()}")
        println("rebooting into the bootloader")
        dev.send(Protocol.REBOOT_TO_BOOTLOADER)
        dev.close()
        bl = findPort(midi, port, bootloader = true, waitMs = BOOTLOADER_WAIT_MS)
            ?: fail("no bootloader port within ${BOOTLOADER_WAIT_MS / 1000} s; try --manual")
    } else {
        println(if (app != null) "The application is running. Enter the bootloader by hand:" else "Waiting for the bootloader:")
        println("  1. switch the Mono Station off")
        println("  2. hold Scales + Note + Velocity")
        println("  3. switch it on, then let go")
        bl = findPort(midi, port, bootloader = true, waitMs = MANUAL_WAIT_MS) ?: fail("no bootloader port appeared")
    }
    kotlinx.coroutines.delay(100)
    val dev = Device.open(midi, bl)
    println("bootloader port '${bl.name}'; versions (bootloader, firmware): ${dev.queryVersion()}")

    if (!yes) {
        print("Flash firmware ${fw.version} now? Type yes: ")
        if (readlnOrNull()?.trim()?.lowercase() != "yes") { dev.close(); fail("not flashed") }
    }

    dev.drain()
    val start = TimeSource.Monotonic.markNow()
    try {
        sendFirmware(fw.packets, dev,
            onConfirm = { System.err.println("\nthe device asks for confirmation") },
            progress = { i, n -> if (i == n || i % 100 == 0) System.err.print("\r  $i/$n packets") })
    } catch (e: FlashError) {
        dev.close()
        fail("\nflash failed: ${e.message}\nThe bootloader is still there: power-cycle into it " +
                "and run this again, or flash stock firmware.")
    }
    println("\nsent in ${start.elapsedNow().inWholeSeconds} s; waiting for the firmware to start")
    dev.close()

    val wait = TimeSource.Monotonic.markNow()
    while (wait.elapsedNow().inWholeMilliseconds < REBOOT_WAIT_MS) {
        val p = findPort(midi, port, bootloader = false, waitMs = 1000) ?: continue
        val d = Device.open(midi, p)
        val versions = d.queryVersion()
        d.close()
        if (versions != null) {
            if (versions.second != fw.version) fail("the application reports $versions, expected firmware ${fw.version}")
            println("OK: application port '${p.name}' reports (bootloader, firmware) $versions")
            return@runBlocking
        }
    }
    fail("the application didn't report a version within ${REBOOT_WAIT_MS / 1000} s; " +
            "check the unit, or enter the bootloader and flash again")
}
