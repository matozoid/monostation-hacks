package flasher

import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiPortConnectionState
import dev.atsushieno.ktmidi.MidiPortDetails
import dev.atsushieno.ktmidi.OnMidiReceivedEventListener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Linux ALSA rawmidi devices (/dev/snd/midiCcDd) read and written as files. Big bootloader
 * dumps lose blocks through the ALSA sequencer (docs/session-transport.md); rawmidi doesn't.
 * Each device has one reader thread for as long as it exists, shared by every input opened on
 * it: a blocked read can't be interrupted, and a second reader would take bytes from the first.
 */
class RawMidiAccess : MidiAccess() {
    override val name = "ALSA rawmidi"

    private class Port(override val id: String, override val name: String) : MidiPortDetails {
        override val manufacturer: String? = null
        override val version: String? = null
        override val midiTransportProtocol = 1
    }

    /** midiCcDd devices with the name ALSA gives them in /proc/asound/cardC/midiD. */
    private fun ports(): List<Port> =
        (File("/dev/snd").listFiles() ?: emptyArray()).mapNotNull { f ->
            val m = Regex("midiC(\\d+)D(\\d+)").matchEntire(f.name) ?: return@mapNotNull null
            val (card, dev) = m.destructured
            val name = runCatching { File("/proc/asound/card$card/midi$dev").useLines { it.first() } }.getOrNull()
                ?: return@mapNotNull null
            Port(f.name, name.trim())
        }.sortedBy { it.id }

    override val inputs: Iterable<MidiPortDetails> get() = ports()
    override val outputs: Iterable<MidiPortDetails> get() = ports()

    private class Reader(val id: String, input: FileInputStream) {
        @Volatile var listener: OnMidiReceivedEventListener? = null
        @Volatile var alive = true
        val thread = Thread({
            val buf = ByteArray(4096)
            try {
                input.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) listener?.onEventReceived(buf, 0, n, 0)
                    }
                }
            } catch (_: IOException) {
                // the device went away (the unit rebooted)
            }
            alive = false
        }, "rawmidi $id").apply { isDaemon = true }
    }

    private val readers = mutableMapOf<String, Reader>()

    /**
     * Open /dev/snd/<id>. A fresh device node may not be accessible yet (udev sets its permissions
     * a moment later), and while another program has the device open, open() blocks: the kernel
     * opens it for a sequencer client too, e.g. a browser tab with MIDI access. A blocked open
     * can't be interrupted, so it runs on a daemon thread that is left behind on timeout.
     */
    private fun <T : Any> openFile(id: String, open: (String) -> T): T {
        val result = java.util.concurrent.CompletableFuture<T>()
        Thread({
            try { result.complete(open("/dev/snd/$id")) } catch (e: Throwable) { result.completeExceptionally(e) }
        }, "open $id").apply { isDaemon = true }.start()
        return try {
            result.get(OPEN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw FlashError("/dev/snd/$id is busy: another program has it open (a browser tab with MIDI access, " +
                "a DAW, amidi…); close it and try again")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw FlashError("can't open /dev/snd/$id: ${e.cause?.message}")
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 3_000L
    }

    override suspend fun openInput(portId: String): MidiInput {
        val port = ports().firstOrNull { it.id == portId } ?: throw FlashError("MIDI input $portId is gone")
        val reader = synchronized(readers) {
            readers[portId]?.takeIf { it.alive } ?: Reader(portId, openFile(portId) { FileInputStream(it) })
                .also { readers[portId] = it; it.thread.start() }
        }
        return object : MidiInput {
            override val details: MidiPortDetails = port
            override var connectionState = MidiPortConnectionState.OPEN
            override fun setMessageReceivedListener(listener: OnMidiReceivedEventListener) { reader.listener = listener }
            override fun close() {
                reader.listener = null
                connectionState = MidiPortConnectionState.CLOSED
            }
        }
    }

    override suspend fun openOutput(portId: String): MidiOutput {
        val port = ports().firstOrNull { it.id == portId } ?: throw FlashError("MIDI output $portId is gone")
        val out = openFile(portId) { FileOutputStream(it) }
        return object : MidiOutput {
            override val details: MidiPortDetails = port
            override var connectionState = MidiPortConnectionState.OPEN
            override fun send(mevent: ByteArray, offset: Int, length: Int, timestampInNanoseconds: Long) {
                out.write(mevent, offset, length)
                out.flush()
            }
            override fun close() {
                runCatching { out.close() }
                connectionState = MidiPortConnectionState.CLOSED
            }
        }
    }
}
