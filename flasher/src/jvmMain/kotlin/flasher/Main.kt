package flasher

import dev.atsushieno.ktmidi.AlsaMidiAccess
import dev.atsushieno.ktmidi.JvmMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.RtMidiAccess
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: flasher FIRMWARE.syx [--port NAME] [--backend alsa|rtmidi|jvm] [--manual] [--yes] [--list] [--check]

Flash a Circuit Mono Station firmware .syx through its bootloader (the Kotlin port of
tools/flash.py, #63). With the app running, it reboots the unit into the bootloader with
SysEx; --manual waits for the button entry instead (Scales + Note + Velocity at power-on)."""

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

private class ConsoleUi(private val yes: Boolean) : FlashUi {
    override fun info(message: String) = println(message)

    override fun progress(sent: Int, total: Int) {
        if (sent == total || sent % 100 == 0) System.err.print("\r  $sent/$total packets")
        if (sent == total) System.err.println()
    }

    override fun manualEntry(appRunning: Boolean) {
        println(if (appRunning) "The application is running. Enter the bootloader by hand:" else "Waiting for the bootloader:")
        println("  1. switch the Mono Station off")
        println("  2. hold Scales + Note + Velocity")
        println("  3. switch it on, then let go")
    }

    override suspend fun confirm(fw: FirmwareFile, versions: Pair<Int, Int>?): Boolean {
        if (yes) return true
        print("Flash firmware ${fw.version} now? Type yes: ")
        return readlnOrNull()?.trim()?.lowercase() == "yes"
    }
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
    println("$path: ${fw.summary()}: OK")
    if (fw.version != fw.containerVersion) println("warning: the image header and the start message give different versions")
    if (checkOnly) return@runBlocking
    println("MIDI backend: ${midi.name}")
    try {
        flashFirmware(midi, fw, ConsoleUi(yes), FlashOptions(port = port, manual = manual))
    } catch (e: FlashError) {
        fail("flash failed: ${e.message}")
    }
}
