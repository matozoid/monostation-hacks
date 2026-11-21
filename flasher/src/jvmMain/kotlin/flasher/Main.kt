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
       flasher --upload PACK|SYX [--from SLOT] [--only patches|sessions|settings] [--sessions LIST] [--session-from N]
                [--no-backup] [--port NAME] [--backend …] [--yes]
       flasher --settings [--set ID=VALUE]… [--save] [--port NAME] [--backend …] [--yes]
       flasher --reset-settings [--port NAME] [--backend …] [--yes]

Flash a Circuit Mono Station firmware .syx through its bootloader (the Kotlin port of
tools/flash.py, #63). With the app running, it reboots the unit into the bootloader with
SysEx; --manual waits for the button entry instead (Scales + Note + Velocity at power-on).
--backup reads the unit's 64 patches and 32 sessions through the bootloader into a pack;
on Linux it reads through ALSA rawmidi (--backend raw), since the sequencer loses blocks.
--upload stores a pack's (or a .syx's) patches and sessions through the bootloader and reads
everything back to check. Patches go to their own slots, or one after another from --from
SLOT (1–64). Sessions: all of the pack's, or --sessions LIST (e.g. 1,3,5-8,init), to their
own numbers or one after another from --session-from N (1–32); the INIT session only ever
goes to INIT. A backup's mod settings go too. --only sends just one kind. It backs up the
unit first into monostation-backup-DATE-TIME.monostationpack unless --no-backup.
--settings lists the running build's mod settings (docs/mod-settings.md) and their values;
--set changes one live (ID as 0x0101 or the setting's name; VALUE a number, on/off, an
enum label or text); --save writes the values to the unit's store through the bootloader,
so they come back at power-on. --reset-settings writes an empty store: everything stock."""

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
    var settings = false
    val sets = mutableListOf<String>()
    var save = false
    var resetSettings = false
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
            "--only" -> only = it.next().also { o -> if (o !in setOf("patches", "sessions", "settings")) fail("--only takes patches, sessions or settings") }
            "--sessions" -> sessionList = it.next()
            "--session-from" -> sessionFrom = it.next().toInt() - 1
            "--settings" -> settings = true
            "--set" -> { settings = true; sets += it.next() }
            "--save" -> { settings = true; save = true }
            "--reset-settings" -> resetSettings = true
            "-h", "--help" -> { println(USAGE); return@runBlocking }
            else -> if (syx == null) syx = a else fail(USAGE)
        }
    }
    val linux = File("/dev/snd/seq").exists()
    val midi = access(backend ?: if (!linux) "rtmidi" else if (backup != null || upload != null || settings || resetSettings) "raw" else "alsa")
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
    if (settings || resetSettings) {
        val ui = ConsoleUi(yes)
        val options = FlashOptions(port = port, noUnitWaitMs = NO_UNIT_WAIT_MS)
        println("MIDI backend: ${midi.name}")
        try {
            if (resetSettings) {
                if (!ui.ask("Write an empty mod settings store (everything stock at the next power-on)?")) fail("not written")
                writeModStore(midi, ui, options) { ModSettings.encodeStore(emptyList()) }
                return@runBlocking
            }
            val state = modSettingsOfApp(midi, port, sets)
            if (save) {
                if (!ui.ask("Store these values on the unit (it restarts into its bootloader and back)?")) fail("not saved")
                writeModStore(midi, ui, options) { ModSettings.mergeStore(it, state.settings, state.values) }
            }
        } catch (e: FlashError) {
            fail("settings: ${e.message}")
        }
        return@runBlocking
    }
    upload?.let { file ->
        val ui = ConsoleUi(yes)
        val pack = try { Pack.read(File(file).readBytes()) } catch (e: FlashError) { fail("$file: ${e.message}") }
        println("$file: ${pack.summary}")
        val patches = if (only == null || only == "patches") pack.patches else emptyList()
        val chosen = if (only == null || only == "sessions") sessionList?.let { selectSessions(pack.sessions, it) } ?: pack.sessions else emptyList()
        val store = if (only == null || only == "settings") pack.settings else null
        if (patches.isEmpty() && chosen.isEmpty() && store == null) fail("nothing to send")
        val writes = try { Pack.place(patches, from) } catch (e: FlashError) { fail("${e.message}") }
        val sessionWrites = try { Pack.placeSessions(chosen, sessionFrom) } catch (e: FlashError) { fail("${e.message}") }
        for ((slot, p) in writes) println("  slot ${(slot + 1).toString().padStart(2)} <- ${p.name}")
        for ((index, image) in sessionWrites) println("  ${sessionLabel(index)} <- ${sessionLabel(image.index)} of the pack (${image.kind}, ${image.name})")
        if (store != null) println("  mod settings <- the pack's (${ModSettings.decodeStore(store)!!.size} stored)")
        val what = listOfNotNull(writes.size.takeIf { it > 0 }?.let { "$it patch slots" },
            sessionWrites.size.takeIf { it > 0 }?.let { "$it sessions" },
            "the mod settings".takeIf { store != null }).joinToString(" and ")
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
            uploadToUnit(midi, ui, writes, sessionWrites, options, store)
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

/** Read the running build's settings, apply `sets` live, and print them all. */
private suspend fun modSettingsOfApp(midi: MidiAccess, port: String?, sets: List<String>): ModState {
    val app = findPort(midi, port, bootloader = false, waitMs = NO_UNIT_WAIT_MS)
        ?: throw FlashError("no running Circuit Mono Station on USB (the settings need the app, not the bootloader)")
    val dev = Device.open(midi, app)
    try {
        val state = readModSettings(dev) ?: throw FlashError("firmware ${dev.queryVersion()?.second} has no mod settings")
        println("${app.name}: ${state.settings.size} settings, ${state.build.storeText}")
        for (arg in sets) {
            val (key, text) = arg.split('=', limit = 2).takeIf { it.size == 2 } ?: fail("--set takes ID=VALUE, not '$arg'")
            val id = if (key.startsWith("0x")) key.removePrefix("0x").toIntOrNull(16) else null
            val s = state.settings.firstOrNull { it.id == id || it.name.equals(key, ignoreCase = true) }
                ?: fail("no setting '$key'; the build has ${state.settings.joinToString { it.name }}")
            val value = s.parse(text) ?: fail("${s.name} takes ${s.range}, not '$text'")
            when (setModSetting(dev, s, value)) {
                ModSettings.SET_OK -> state.values[s.id] = value
                ModSettings.SET_AT_BOOT -> { state.values[s.id] = value; println("${s.name}: takes effect after --save and a restart") }
                ModSettings.SET_OUT_OF_RANGE -> fail("the unit refused ${s.name} = $text (out of range, or the store isn't read yet)")
                else -> fail("the unit doesn't know ${s.name}")
            }
        }
        for (g in state.build.groups) {
            println(g)
            for (s in state.settings.filter { it.group == g }) {
                val v = state.values.getValue(s.id)
                val mark = if (v == s.default) "" else "   (default ${s.show(s.default)})"
                println("  0x${s.id.toString(16).padStart(4, '0')} ${s.name}: ${s.show(v)}  [${s.kind} ${s.range}${if (s.atBoot) ", at boot" else ""}]$mark")
            }
        }
        return state
    } finally {
        dev.close()
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
