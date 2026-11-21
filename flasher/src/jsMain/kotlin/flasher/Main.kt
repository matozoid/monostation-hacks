package flasher

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.files.File
import kotlin.js.Promise

private fun <T> el(id: String) = document.getElementById(id).unsafeCast<T>()

private val fileInput = el<HTMLInputElement>("file")
private val fileInfo = el<HTMLElement>("file-info")
private val buildList = el<HTMLElement>("builds")
private val flashButton = el<HTMLButtonElement>("flash")
private val progressBar = el<HTMLProgressElement>("progress")
private val progressText = el<HTMLElement>("progress-text")
private val log = el<HTMLElement>("log")
private val manualHelp = el<HTMLElement>("manual-help")
private val backupButton = el<HTMLButtonElement>("backup")
private val backupProgress = el<HTMLProgressElement>("backup-progress")
private val backupText = el<HTMLElement>("backup-text")
private val packFile = el<HTMLInputElement>("pack-file")
private val packInfo = el<HTMLElement>("pack-info")
private val packPatches = el<HTMLElement>("pack-patches")
private val packList = el<HTMLElement>("pack-list")
private val placeSame = el<HTMLInputElement>("place-same")
private val placeFrom = el<HTMLInputElement>("place-from")
private val placeSlot = el<HTMLInputElement>("place-slot")
private val packBackup = el<HTMLInputElement>("pack-backup")
private val packSessions = el<HTMLElement>("pack-sessions")
private val sessionList = el<HTMLElement>("session-list")
private val splaceFrom = el<HTMLInputElement>("splace-from")
private val splaceSlot = el<HTMLInputElement>("splace-slot")
private val packSendOptions = el<HTMLElement>("pack-send-options")
private val packPlan = el<HTMLElement>("pack-plan")
private val packSend = el<HTMLButtonElement>("pack-send")
private val packProgress = el<HTMLProgressElement>("pack-progress")
private val packText = el<HTMLElement>("pack-text")
private val TABS = listOf("firmware", "packs")

/** What the page can flash: one of our diffs applied to stock 1.2, or a file as is. */
private class Build(val title: String, val description: String, val diff: String?)

/* Builds (docs/release-test.md): the current release and the current test build, which is
 * rebuilt under the same number until it is released as test + 1. */
private val BUILDS = listOf(
    Build("Test build (firmware 751)",
        "Being tested for the first release: it may change or have bugs. Mutate nudges one " +
            "field in the Velocity, Gate, Glide and Expand views of every track, the notes of both Osc " +
            "tracks in the dual view, and the patch in the Patches " +
            "view (gliding to it); held, it repeats (steps once a loop, also the stock shuffle in the Note view; the patch once a beat). Shift + key in the Note or Expand view transposes the Osc tracks live; in the dual view each one on its own. " +
            "Pattern sequences: while stopped, hold Shift in the Patterns view and tap patterns or chains " +
            "to play them in that order (Shift + Patterns: Append, lit orange; adds also while playing); saved in the session. " +
            "Stronger Overdrive and Distortion.",
        "builds/mods-751.fwdiff"),
    Build("Stock firmware 1.2", "Novation's firmware, unchanged: restores the unit.", null),
)

/** The dropped file; isStock when it is exactly monostation-firmware-800.syx. */
private var dropped: ByteArray? = null
private var droppedFw: FirmwareFile? = null
private var isStock = false
private var selected: Build? = null
private var busy = false

private fun say(message: String, cls: String? = null) {
    val line = document.createElement("div")
    line.textContent = message
    if (cls != null) line.className = cls
    log.appendChild(line)
    log.scrollTop = log.scrollHeight.toDouble()
}

private fun updateButton() {
    flashButton.disabled = busy || dropped == null || (isStock && selected == null)
    backupButton.disabled = busy || !WebMidiAccess.isSupported
    packSend.disabled = busy || !WebMidiAccess.isSupported || plan() !is Plan.Ready
}

/** Show the tab named in the URL (#packs), else the last one used, else Firmware. */
private fun showTab() {
    val hash = window.location.hash.removePrefix("#")
    val tab = if (hash in TABS) hash else (runCatching { window.localStorage.getItem("tab") }.getOrNull()?.takeIf { it in TABS } ?: "firmware")
    runCatching { window.localStorage.setItem("tab", tab) }
    for (t in TABS) {
        el<HTMLElement>("tab-$t").hidden = t != tab
        el<HTMLElement>("tab-link-$t").setAttribute("aria-selected", (t == tab).toString())
    }
}

private object BackupPageUi : BackupUi {
    override fun info(message: String) = say(message)

    override fun progress(sent: Int, total: Int) {
        backupProgress.max = total.toDouble()
        backupProgress.value = sent.toDouble()
        backupText.textContent = "${sent / 1024} / ${total / 1024} KB"
    }

    override fun manualEntry(appRunning: Boolean) {
        say(if (appRunning) "The unit didn't restart into its bootloader. Switch it off, hold Scales + Note + Velocity, " +
                "switch it on, then let go." else "No unit found; waiting for one in bootloader mode " +
                "(switch it off, hold Scales + Note + Velocity, switch it on).")
    }
}

/** The chosen pack's patches and sessions, with their tick boxes. */
private var packPatchesLoaded: List<Patch> = emptyList()
private val packTicks = mutableListOf<HTMLInputElement>()
private var packSessionsLoaded: List<SessionImage> = emptyList()
private val sessionTicks = mutableListOf<HTMLInputElement>()

private sealed class Plan {
    class Ready(val patches: List<Pair<Int, Patch>>, val sessions: List<Pair<Int, SessionImage>>) : Plan() {
        val summary: String get() = listOfNotNull(
            patches.takeIf { it.isNotEmpty() }?.let { w -> "patch slot${if (w.size > 1) "s" else ""} ${slotRanges(w.map { it.first })}" },
            sessions.filter { it.first != Storage.INIT_SESSION }.takeIf { it.isNotEmpty() }
                ?.let { w -> "session${if (w.size > 1) "s" else ""} ${slotRanges(w.map { it.first })}" },
            "the INIT session".takeIf { sessions.any { it.first == Storage.INIT_SESSION } },
        ).let { parts -> if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last() }
    }
    class Problem(val message: String) : Plan()
}

/** What Send would write, or why it can't. */
private fun plan(): Plan {
    val patches = packPatchesLoaded.filterIndexed { i, _ -> packTicks.getOrNull(i)?.checked == true }
    val sessions = packSessionsLoaded.filterIndexed { i, _ -> sessionTicks.getOrNull(i)?.checked == true }
    if (patches.isEmpty() && sessions.isEmpty()) return Plan.Problem("Tick what to send.")
    val from = if (placeFrom.checked) (placeSlot.value.toIntOrNull() ?: return Plan.Problem("Enter the first slot (1–64).")) - 1 else null
    val sfrom = if (splaceFrom.checked) (splaceSlot.value.toIntOrNull() ?: return Plan.Problem("Enter the first session (1–32).")) - 1 else null
    return try { Plan.Ready(Pack.place(patches, from), Pack.placeSessions(sessions, sfrom)) }
           catch (e: FlashError) { Plan.Problem(e.message ?: "can't place them") }
}

private fun showPlan() {
    val p = plan()
    packPlan.className = if (p is Plan.Problem && !p.message.startsWith("Tick")) "error" else ""
    packPlan.textContent = when (p) {
        is Plan.Ready -> "Overwrites ${p.summary} on the unit."
        is Plan.Problem -> p.message
    }
    updateButton()
}

/** 1-based slot numbers as ranges: 1–8, 12, 20–21. */
private fun slotRanges(slots: List<Int>): String {
    val sorted = slots.sorted()
    val parts = mutableListOf<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        parts += if (j == i) "${sorted[i] + 1}" else "${sorted[i] + 1}–${sorted[j] + 1}"
        i = j + 1
    }
    return parts.joinToString(", ")
}

/** A ticked row: checkbox, dim number, name. */
private fun tickRow(list: HTMLElement, ticks: MutableList<HTMLInputElement>, number: String, name: String) {
    val label = document.createElement("label").unsafeCast<HTMLElement>()
    val box = document.createElement("input").unsafeCast<HTMLInputElement>()
    box.type = "checkbox"
    box.checked = true
    box.onchange = { showPlan() }
    val slot = document.createElement("span")
    slot.className = "slot"
    slot.textContent = "$number "
    label.title = name
    label.append(box, slot, name)
    list.appendChild(label)
    ticks += box
}

private suspend fun loadPack(file: File) {
    packPatchesLoaded = emptyList()
    packSessionsLoaded = emptyList()
    packTicks.clear()
    sessionTicks.clear()
    packList.textContent = ""
    sessionList.textContent = ""
    packPatches.hidden = true
    packSessions.hidden = true
    packSendOptions.hidden = true
    val buffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
    try {
        val pack = Pack.read(Int8Array(buffer).unsafeCast<ByteArray>())
        packPatchesLoaded = pack.patches
        for (p in pack.patches)
            tickRow(packList, packTicks, p.slot?.let { (it + 1).toString().padStart(2, '0') } ?: "—", p.name.ifEmpty { "(no name)" })
        if (pack.patches.any { it.slot == null }) placeFrom.checked = true
        packSessionsLoaded = pack.sessions
        for (s in pack.sessions)
            tickRow(sessionList, sessionTicks, if (s.isInit) "INIT" else (s.index + 1).toString().padStart(2, '0'), s.name.ifEmpty { "(no name)" })
        packInfo.textContent = "${file.name}: ${pack.summary}"
        packInfo.className = "ok"
        packPatches.hidden = pack.patches.isEmpty()
        packSessions.hidden = pack.sessions.isEmpty()
        packSendOptions.hidden = false
    } catch (e: Throwable) {
        packInfo.textContent = "${file.name}: ${e.message ?: e.toString()}"
        packInfo.className = "error"
    }
    showPlan()
}

private object UploadPageUi : UploadUi, BackupUi {
    var phase = ""
    override fun info(message: String) = say(message)

    override fun progress(sent: Int, total: Int) {
        packProgress.max = total.toDouble()
        packProgress.value = sent.toDouble()
        packText.textContent = "$phase: ${sent / 1024} / ${total / 1024} KB"
    }

    override fun manualEntry(appRunning: Boolean) = BackupPageUi.manualEntry(appRunning)
}

private suspend fun sendPack() {
    val p = plan() as? Plan.Ready ?: return
    if (!window.confirm("Overwrite ${p.summary} on the unit?")) return
    busy = true
    updateButton()
    log.textContent = ""
    packProgress.value = 0.0
    packText.textContent = ""
    try {
        if (!WebMidiAccess.isSupported) throw FlashError("this browser has no Web MIDI")
        val midi = WebMidiAccess.request()
        midi.onPortChange { say("MIDI: $it") }
        say("MIDI ports: ${portPairs(midi).map { it.name }}")
        if (packBackup.checked) {
            UploadPageUi.phase = "Backup"
            saveBackup(backupUnit(midi, UploadPageUi, FlashOptions()))
        }
        UploadPageUi.phase = "Sending"
        uploadToUnit(midi, UploadPageUi, p.patches, p.sessions, FlashOptions())
        packText.textContent = "Done"
        say("Done: ${p.summary} written and checked.", "ok")
    } catch (e: FlashError) {
        say("Send failed: ${e.message}", "error")
    } catch (e: Throwable) {
        say("Error: ${e.message ?: e.toString()}", "error")
    } finally {
        busy = false
        updateButton()
    }
}

/** Download a backup as monostation-backup-DATE.monostationpack. */
private suspend fun saveBackup(b: Backup) {
    val now = kotlin.js.Date()
    val date = ZipDate(now.getFullYear(), now.getMonth() + 1, now.getDate(), now.getHours(), now.getMinutes())
    val name = "monostation-backup-${now.getFullYear()}-${(now.getMonth() + 1).toString().padStart(2, '0')}-" +
        "${now.getDate().toString().padStart(2, '0')}-${now.getHours().toString().padStart(2, '0')}" +
        "${now.getMinutes().toString().padStart(2, '0')}.monostationpack"
    download(b.pack(date), name)
    say("Saved the backup as $name.", "ok")
}

private fun download(data: ByteArray, name: String) {
    val blob = org.w3c.files.Blob(arrayOf(data), org.w3c.files.BlobPropertyBag(type = "application/zip"))
    val url = org.w3c.dom.url.URL.createObjectURL(blob)
    val a = document.createElement("a").unsafeCast<org.w3c.dom.HTMLAnchorElement>()
    a.href = url
    a.download = name
    document.body!!.appendChild(a)
    a.click()
    a.remove()
    window.setTimeout({ org.w3c.dom.url.URL.revokeObjectURL(url) }, 10_000)
}

private suspend fun backup() {
    busy = true
    updateButton()
    log.textContent = ""
    backupProgress.value = 0.0
    backupText.textContent = ""
    try {
        if (!WebMidiAccess.isSupported) throw FlashError("this browser has no Web MIDI")
        val midi = WebMidiAccess.request()
        midi.onPortChange { say("MIDI: $it") }
        say("MIDI ports: ${portPairs(midi).map { it.name }}")
        saveBackup(backupUnit(midi, BackupPageUi, FlashOptions()))
        say("Done.", "ok")
    } catch (e: FlashError) {
        say("Backup failed: ${e.message}", "error")
    } catch (e: Throwable) {
        say("Error: ${e.message ?: e.toString()}", "error")
    } finally {
        busy = false
        updateButton()
    }
}

private object PageUi : FlashUi {
    override fun info(message: String) = say(message)

    override fun progress(sent: Int, total: Int) {
        progressBar.max = total.toDouble()
        progressBar.value = sent.toDouble()
        progressText.textContent = "$sent / $total packets"
    }

    override fun manualEntry(appRunning: Boolean) {
        say(if (appRunning) "Enter the bootloader by hand (see the steps below)." else "No unit running; waiting for the bootloader (see the steps below).")
        manualHelp.hidden = false
    }

    override suspend fun confirm(fw: FirmwareFile, versions: Pair<Int, Int>?): Boolean {
        manualHelp.hidden = true
        return window.confirm("Flash firmware ${fw.version} now? Don't unplug the unit until it restarts.")
    }
}

private fun showBuilds() {
    buildList.textContent = ""
    selected = null
    if (dropped == null) return
    if (!isStock) {
        val p = document.createElement("p")
        p.textContent = "This isn't the stock firmware 1.2 file, so the page can only flash it as it is."
        buildList.appendChild(p)
        return
    }
    for (b in BUILDS) {
        val label = document.createElement("label").unsafeCast<HTMLElement>()
        label.className = "build"
        val radio = document.createElement("input").unsafeCast<HTMLInputElement>()
        radio.type = "radio"
        radio.name = "build"
        radio.onchange = { selected = b; updateButton() }
        val title = document.createElement("strong")
        title.textContent = b.title
        val text = document.createElement("span")
        text.textContent = b.description
        label.append(radio, title, text)
        buildList.appendChild(label)
    }
}

private suspend fun loadFile(file: File) {
    dropped = null
    droppedFw = null
    isStock = false
    val buffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
    try {
        val data = Int8Array(buffer).unsafeCast<ByteArray>()
        val fw = checkSyx(data)
        dropped = data
        droppedFw = fw
        isStock = sha256(data).toHexString() == STOCK_800_SHA256
        fileInfo.textContent = if (isStock) "${file.name}: stock firmware 1.2 (800), checked"
                               else "${file.name}: ${fw.summary()}"
        fileInfo.className = "ok"
    } catch (e: Throwable) {
        fileInfo.textContent = "${file.name}: ${e.message ?: e.toString()}"
        fileInfo.className = "error"
    }
    showBuilds()
    updateButton()
}

/** The .syx to send for the current choice. */
private suspend fun firmwareToSend(): FirmwareFile {
    val data = dropped ?: throw FlashError("no firmware file")
    if (!isStock) return droppedFw!!
    val build = selected ?: throw FlashError("choose what to flash")
    val path = build.diff ?: return droppedFw!!
    val response = try { window.fetch(path).await() } catch (e: Throwable) {
        throw FlashError("couldn't load $path from the page's server (${e.message}); reload the page and try again")
    }
    if (!response.ok) throw FlashError("couldn't load $path (${response.status})")
    val diff = FirmwareDiff.parse(response.text().await())
    val syx = try { diff.apply(data) } catch (e: IllegalArgumentException) { throw FlashError(e.message ?: "bad build") }
    say("${build.title}: built from stock 1.2 and checked")
    return checkSyx(syx)
}

private fun loadSelected(scope: kotlinx.coroutines.CoroutineScope) {
    val file = fileInput.files?.item(0) ?: return
    fileInfo.textContent = "${file.name}: reading…"
    fileInfo.className = ""
    fileInput.value = ""   // so picking the same file again fires change; the name shows in file-info
    scope.launch { loadFile(file) }
}

private suspend fun flash() {
    if (dropped == null) return
    busy = true
    updateButton()
    log.textContent = ""
    progressBar.value = 0.0
    progressText.textContent = ""
    try {
        if (!WebMidiAccess.isSupported) throw FlashError("this browser has no Web MIDI")
        val fw = firmwareToSend()
        val midi = WebMidiAccess.request()
        midi.onPortChange { say("MIDI: $it") }
        say("MIDI ports: ${portPairs(midi).map { it.name }}")
        val versions = flashFirmware(midi, fw, PageUi, FlashOptions())
        say("Done: the unit runs firmware ${versions.second}.", "ok")
    } catch (e: FlashError) {
        say("Flash failed: ${e.message}", "error")
    } catch (e: Throwable) {
        say("Error: ${e.message ?: e.toString()}", "error")
    } finally {
        manualHelp.hidden = true
        busy = false
        updateButton()
    }
}

fun main() {
    val scope = MainScope()
    window.onerror = { message, _, _, _, _ -> say("Page error: $message", "error"); false }
    window.addEventListener("unhandledrejection", { ev -> say("Page error: ${ev.asDynamic().reason}", "error") })
    fileInput.onchange = { loadSelected(scope) }
    flashButton.onclick = { scope.launch { flash() } }
    backupButton.onclick = { scope.launch { backup() } }
    packFile.onchange = {
        packFile.files?.item(0)?.let { f ->
            packInfo.textContent = "${f.name}: reading…"
            packInfo.className = ""
            packFile.value = ""
            scope.launch { loadPack(f) }
        }
    }
    el<HTMLButtonElement>("pack-all").onclick = { packTicks.forEach { it.checked = true }; showPlan() }
    el<HTMLButtonElement>("pack-none").onclick = { packTicks.forEach { it.checked = false }; showPlan() }
    placeSame.onchange = { showPlan() }
    placeFrom.onchange = { showPlan() }
    placeSlot.oninput = { placeFrom.checked = true; showPlan() }
    el<HTMLButtonElement>("session-all").onclick = { sessionTicks.forEach { it.checked = true }; showPlan() }
    el<HTMLButtonElement>("session-none").onclick = { sessionTicks.forEach { it.checked = false }; showPlan() }
    el<HTMLInputElement>("splace-same").onchange = { showPlan() }
    splaceFrom.onchange = { showPlan() }
    splaceSlot.oninput = { splaceFrom.checked = true; showPlan() }
    packSend.onclick = { scope.launch { sendPack() } }
    window.onhashchange = { showTab() }
    showTab()
    if (!WebMidiAccess.isSupported) say("This browser has no Web MIDI.", "error")
    el<org.w3c.dom.HTMLAnchorElement>("stock-link").href = STOCK_800_URL
    updateButton()
    loadSelected(scope)   // a file the browser kept across a reload
}
