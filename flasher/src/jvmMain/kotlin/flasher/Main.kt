package flasher

import dev.atsushieno.ktmidi.AlsaMidiAccess
import dev.atsushieno.ktmidi.JvmMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.RtMidiAccess
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: flasher FIRMWARE.syx [--port NAME] [--backend alsa|raw|rtmidi|jvm] [--manual] [--yes] [--list] [--check]
       flasher --backup OUT.monostationpack [--chunk BYTES] [--port NAME] [--backend …] [--manual]
       flasher --upload PACK|SYX [--from SLOT] [--only patches|sessions] [--sessions LIST] [--session-from N]
                [--no-backup] [--port NAME] [--backend …] [--yes]

Flash a Circuit Mono Station firmware .syx through its bootloader (the Kotlin port of
tools/flash.py, #63). With the app running, it reboots the unit into the bootloader with
SysEx; --manual waits for the button entry instead (Scales + Note + Velocity at power-on).
--backup reads the unit's 64 patches and 32 sessions through the bootloader into a pack;
on Linux it reads through ALSA rawmidi (--backend raw), since the sequencer loses blocks.
--upload stores a pack's (or a .syx's) patches and sessions through the bootloader and reads
everything back to check. Patches go to their own slots, or one after another from --from
SLOT (1–64). Sessions: all of the pack's, or --sessions LIST (e.g. 1,3,5-8,init), to their
own numbers or one after another from --session-from N (1–32); the INIT session only ever
goes to INIT. --only sends just one kind. It backs up the unit first into
monostation-backup-DATE-TIME.monostationpack unless --no-backup."""

/** --backup and --upload give up this soon when no unit is on USB. */
private const val NO_UNIT_WAIT_MS = 10_000L

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun access(backend: String): MidiAccess = when (backend) {
    "alsa" -> AlsaMidiAccess()
    "rtmidi" -> RtMidiAccess()
    "jvm" -> JvmMidiAccess()
    "raw" -> RawMidiAccess()
    else -> fail("unknown backend $backend")
}

private class ConsoleUi(private val yes: Boolean) : FlashUi, BackupUi, UploadUi {
    private var progressShown = false   // a progress line is open on stderr

    override fun info(message: String) {
        if (progressShown) { System.err.println(); progressShown = false }
        println(message)
    }

    override fun progress(sent: Int, total: Int) {
        if (sent == total || sent % 100 == 0) { System.err.print("\r  $sent/$total"); progressShown = true }
        if (sent == total) { System.err.println(); progressShown = false }
    }

    override fun manualEntry(appRunning: Boolean) {
        println(if (appRunning) "The application is running. Enter the bootloader by hand:" else "Waiting for the bootloader:")
        println("  1. switch the Mono Station off")
        println("  2. hold Scales + Note + Velocity")
        println("  3. switch it on, then let go")
    }

    override suspend fun confirm(fw: FirmwareFile, versions: Pair<Int, Int>?): Boolean = ask("Flash firmware ${fw.version} now?")

    fun ask(question: String): Boolean {
        if (yes) return true
        print("$question Type yes: ")
        return readlnOrNull()?.trim()?.lowercase() == "yes"
    }
}

fun main(args: Array<String>) = runBlocking {
    var syx: String? = null
    var port: String? = null
    var backend: String? = null
    var manual = false
    var yes = false
    var list = false
    var checkOnly = false
    var backup: String? = null
    var chunk = 4096
    var upload: String? = null
    var from: Int? = null
    var noBackup = false
    var only: String? = null
    var sessionList: String? = null
    var sessionFrom: Int? = null
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--port" -> port = it.next()
            "--backend" -> backend = it.next()
            "--manual" -> manual = true
            "--yes" -> yes = true
            "--list" -> list = true
            "--check" -> checkOnly = true
            "--backup" -> backup = it.next()
            "--chunk" -> chunk = it.next().toInt()
            "--upload" -> upload = it.next()
            "--from" -> from = it.next().toInt() - 1
            "--no-backup" -> noBackup = true
            "--only" -> only = it.next().also { o -> if (o != "patches" && o != "sessions") fail("--only takes patches or sessions") }
            "--sessions" -> sessionList = it.next()
            "--session-from" -> sessionFrom = it.next().toInt() - 1
            "-h", "--help" -> { println(USAGE); return@runBlocking }
            else -> if (syx == null) syx = a else fail(USAGE)
        }
    }
    val linux = File("/dev/snd/seq").exists()
    val midi = access(backend ?: if (!linux) "rtmidi" else if (backup != null || upload != null) "raw" else "alsa")
    if (list) {
        println("${midi.name} ports (input + output pairs):")
        portPairs(midi).forEach { println("  ${it.name}  [in ${it.input.id}, out ${it.output.id}]") }
        return@runBlocking
    }
    backup?.let { out ->
        if (File(out).exists()) fail("$out exists; not overwriting it")
        println("MIDI backend: ${midi.name}")
        try {
            val b = backupUnit(midi, ConsoleUi(yes), FlashOptions(port = port, manual = manual, noUnitWaitMs = NO_UNIT_WAIT_MS), chunk)
            val now = java.time.LocalDateTime.now()
            File(out).writeBytes(b.pack(ZipDate(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)))
            println("wrote $out")
        } catch (e: FlashError) {
            fail("backup failed: ${e.message}")
        }
        return@runBlocking
    }
    upload?.let { file ->
        val ui = ConsoleUi(yes)
        val pack = try { Pack.read(File(file).readBytes()) } catch (e: FlashError) { fail("$file: ${e.message}") }
        println("$file: ${pack.summary}")
        val patches = if (only == "sessions") emptyList() else pack.patches
        val chosen = if (only == "patches") emptyList() else sessionList?.let { selectSessions(pack.sessions, it) } ?: pack.sessions
        if (patches.isEmpty() && chosen.isEmpty()) fail("nothing to send")
        val writes = try { Pack.place(patches, from) } catch (e: FlashError) { fail("${e.message}") }
        val sessionWrites = try { Pack.placeSessions(chosen, sessionFrom) } catch (e: FlashError) { fail("${e.message}") }
        for ((slot, p) in writes) println("  slot ${(slot + 1).toString().padStart(2)} <- ${p.name}")
        for ((index, image) in sessionWrites) println("  ${sessionLabel(index)} <- ${sessionLabel(image.index)} of the pack (${image.kind}, ${image.name})")
        val what = listOfNotNull(writes.size.takeIf { it > 0 }?.let { "$it patch slots" },
            sessionWrites.size.takeIf { it > 0 }?.let { "$it sessions" }).joinToString(" and ")
        if (!ui.ask("Overwrite these $what on the unit?")) fail("not sent")
        println("MIDI backend: ${midi.name}")
        val options = FlashOptions(port = port, noUnitWaitMs = NO_UNIT_WAIT_MS)
        try {
            if (!noBackup) {
                val now = java.time.LocalDateTime.now()
                val out = File("monostation-backup-%04d-%02d-%02d-%02d%02d%02d.monostationpack".format(
                    now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second))
                val b = backupUnit(midi, ui, options, chunk)
                out.writeBytes(b.pack(ZipDate(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)))
                println("backup: wrote ${out.absolutePath}")
            }
            uploadToUnit(midi, ui, writes, sessionWrites, options)
        } catch (e: FlashError) {
            fail("upload failed: ${e.message}")
        }
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

/** The pack's sessions named in LIST: 1-based numbers and ranges, and "init". */
private fun selectSessions(sessions: List<SessionImage>, list: String): List<SessionImage> {
    val wanted = mutableSetOf<Int>()
    for (part in list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        when {
            part.equals("init", ignoreCase = true) -> wanted += Storage.INIT_SESSION
            '-' in part -> {
                val (a, b) = part.split('-', limit = 2).map { it.toIntOrNull() ?: fail("bad session range '$part'") }
                for (n in a..b) wanted += n - 1
            }
            else -> wanted += (part.toIntOrNull() ?: fail("bad session number '$part'")) - 1
        }
    }
    wanted.firstOrNull { w -> sessions.none { it.index == w } }?.let { fail("the pack has no ${sessionLabel(it)}") }
    return sessions.filter { it.index in wanted }
}
