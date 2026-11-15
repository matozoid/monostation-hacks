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
private val manualBox = el<HTMLInputElement>("manual")
private val progressBar = el<HTMLProgressElement>("progress")
private val progressText = el<HTMLElement>("progress-text")
private val log = el<HTMLElement>("log")
private val manualHelp = el<HTMLElement>("manual-help")

/** What the page can flash: one of our diffs applied to stock 1.2, or a file as is. */
private class Build(val title: String, val description: String, val diff: String?)

private val BUILDS = listOf(
    Build("Mods (firmware 756)",
        "Mutate randomises one field in the Velocity, Gate, Glide and Expand views; Mutate in " +
            "the Patches view nudges the patch; Shift + key in the Note view transposes the Osc " +
            "tracks live; stronger Overdrive and Distortion.",
        "builds/mods-756.fwdiff"),
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
        val now = versions?.let { " The bootloader is ${it.first}." } ?: ""
        return window.confirm("Flash firmware ${fw.version} now?$now Don't unplug the unit until it restarts.")
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
        if (!WebMidiAccess.isSupported) throw FlashError("this browser has no Web MIDI; use Firefox")
        val fw = firmwareToSend()
        val midi = WebMidiAccess.request()
        midi.onPortChange { say("MIDI: $it") }
        say("MIDI ports: ${portPairs(midi).map { it.name }}")
        val versions = flashFirmware(midi, fw, PageUi, FlashOptions(manual = manualBox.checked))
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
    if (!WebMidiAccess.isSupported) say("This browser has no Web MIDI. Use Firefox.", "error")
    el<org.w3c.dom.HTMLAnchorElement>("stock-link").href = STOCK_800_URL
    updateButton()
    loadSelected(scope)   // a file the browser kept across a reload
}
