package flasher

import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiPortDetails
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class FlashError(message: String) : Exception(message)

/** An input + output pair with the same name, e.g. "Circuit Mono Station BL MIDI 1". */
class PortPair(val name: String, val input: MidiPortDetails, val output: MidiPortDetails)

fun portPairs(access: MidiAccess): List<PortPair> {
    val outs = access.outputs.toList()
    return access.inputs.mapNotNull { i ->
        val name = i.name ?: return@mapNotNull null
        outs.firstOrNull { it.name == name }?.let { PortPair(name, i, it) }
    }
}

fun matchingPorts(access: MidiAccess, wanted: String?, bootloader: Boolean): List<PortPair> =
    portPairs(access)
        .filter { p -> if (wanted != null) wanted.lowercase() in p.name.lowercase() else "circuit mono station" in p.name.lowercase() }
        .filter { isBootloaderPort(it.name) == bootloader }

/** A MidiAccess whose port list only changes when asked to look again (Web MIDI in Firefox). */
interface Rescannable {
    suspend fun rescan()
}

/** Polls up to waitMs for exactly one app (or bootloader) port. */
suspend fun findPort(access: MidiAccess, wanted: String?, bootloader: Boolean, waitMs: Long): PortPair? {
    val start = TimeSource.Monotonic.markNow()
    while (true) {
        val hits = try { matchingPorts(access, wanted, bootloader) } catch (e: Exception) { emptyList() }
        if (hits.size == 1) return hits[0]
        if (hits.size > 1) throw FlashError("need exactly one matching port, found ${hits.map { it.name }} (use --port)")
        if (start.elapsedNow().inWholeMilliseconds >= waitMs) return null
        delay(250)
        (access as? Rescannable)?.let { runCatching { it.rescan() } }
    }
}

/** SysEx out, complete SysEx messages in: what the storage transfers need (a fake in tests). */
interface Link {
    fun send(msg: ByteArray)
    suspend fun next(timeoutMs: Long): ByteArray?
    fun drain()
}

/** One open MIDI device: SysEx out, complete SysEx messages in through a channel. */
class Device private constructor(val name: String, private val input: MidiInput, private val output: MidiOutput) : Link {
    private val messages = Channel<ByteArray>(Channel.UNLIMITED)
    private val assembler = SysexAssembler { messages.trySend(it) }

    init {
        input.setMessageReceivedListener { data, start, length, _ -> assembler.feed(data, start, length) }
    }

    override fun send(msg: ByteArray) = output.send(msg, 0, msg.size, 0)

    override suspend fun next(timeoutMs: Long): ByteArray? =
        if (timeoutMs <= 0) messages.tryReceive().getOrNull()
        else withTimeoutOrNull(timeoutMs.milliseconds) { messages.receive() }

    override fun drain() { while (messages.tryReceive().isSuccess) { } }

    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    suspend fun queryVersion(): Pair<Int, Int>? {
        drain()
        send(Protocol.VERSION_REQUEST)
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val left = 2000 - start.elapsedNow().inWholeMilliseconds
            val msg = next(left) ?: return null
            decodeVersion(msg)?.let { return it }
        }
    }

    companion object {
        suspend fun open(access: MidiAccess, port: PortPair) =
            Device(port.name, access.openInput(port.input.id), access.openOutput(port.output.id))
    }
}

/**
 * Send packets with Components' flow control (tools/flash.py send_firmware): after each
 * packet except the 0x73 one (or the last), wait for an ack whose counter is above the last
 * accepted one (or 0, short form), or a success status. Status 16/124/126 aborts.
 */
suspend fun sendFirmware(
    packets: List<ByteArray>,
    device: Device,
    timeoutMs: Long = Protocol.PACKET_TIMEOUT_MS,
    onConfirm: () -> Unit = {},
    progress: (Int, Int) -> Unit = { _, _ -> },
) {
    var last = -1
    for ((i, packet) in packets.withIndex()) {
        device.send(packet)
        progress(i + 1, packets.size)
        if (packet.u(5) == Container.CMD_DATA_FIRST || i == packets.size - 1) return
        var deadline = TimeSource.Monotonic.markNow()
        acked@ while (true) {
            val left = timeoutMs - deadline.elapsedNow().inWholeMilliseconds
            val msg = (if (left > 0) device.next(left) else null)
                ?: throw FlashError("no ack within ${timeoutMs / 1000} s after packet $i of ${packets.size}")
            val ack = parseAck(msg)
            if (ack != null) {
                val (counter, short) = ack
                if (counter > last || (short && counter == 0)) { last = counter; break@acked }
                continue
            }
            when (val status = parseStatus(msg)) {
                in Protocol.STATUS_FAILED -> throw FlashError(
                    "bootloader reports ${Protocol.STATUS_FAILED[status]} ($status) after packet $i")
                Protocol.STATUS_SUCCESS -> break@acked
                in Protocol.STATUS_CONFIRM -> { onConfirm(); deadline = TimeSource.Monotonic.markNow() }
            }
        }
    }
}
