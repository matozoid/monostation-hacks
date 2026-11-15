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
private val flashButton = el<HTMLButtonElement>("flash")
private val manualBox = el<HTMLInputElement>("manual")
private val progressBar = el<HTMLProgressElement>("progress")
private val progressText = el<HTMLElement>("progress-text")
private val log = el<HTMLElement>("log")
private val manualHelp = el<HTMLElement>("manual-help")

private var firmware: FirmwareFile? = null
private var busy = false

private fun say(message: String, cls: String? = null) {
    val line = document.createElement("div")
    line.textContent = message
    if (cls != null) line.className = cls
    log.appendChild(line)
    log.scrollTop = log.scrollHeight.toDouble()
}

private fun updateButton() {
    flashButton.disabled = busy || firmware == null
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

private suspend fun loadFile(file: File) {
    firmware = null
    updateButton()
    val buffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
    try {
        val fw = checkSyx(Int8Array(buffer).unsafeCast<ByteArray>())
        firmware = fw
        fileInfo.textContent = "${file.name}: ${fw.summary()}"
        fileInfo.className = "ok"
    } catch (e: Throwable) {
        fileInfo.textContent = "${file.name}: ${e.message ?: e.toString()}"
        fileInfo.className = "error"
    }
    updateButton()
}

private fun loadSelected(scope: kotlinx.coroutines.CoroutineScope) {
    val file = fileInput.files?.item(0) ?: return
    fileInfo.textContent = "${file.name}: reading…"
    fileInfo.className = ""
    fileInput.value = ""   // so picking the same file again fires change; the name shows in file-info
    scope.launch { loadFile(file) }
}

private suspend fun flash() {
    val fw = firmware ?: return
    busy = true
    updateButton()
    log.textContent = ""
    progressBar.value = 0.0
    progressText.textContent = ""
    try {
        if (!WebMidiAccess.isSupported) throw FlashError("this browser has no Web MIDI; use Firefox")
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
    updateButton()
    loadSelected(scope)   // a file the browser kept across a reload
}
