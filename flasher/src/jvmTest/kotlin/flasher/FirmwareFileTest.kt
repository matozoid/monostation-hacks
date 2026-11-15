package flasher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Checks against the private stock files; skipped when private/ is absent. */
class FirmwareFileTest {
    private val dir = File("../private/firmware")

    @Test
    fun stock800() {
        val syx = File(dir, "monostation-firmware-800.syx")
        val bin = File(dir, "monostation-firmware-800.bin")
        if (!syx.exists() || !bin.exists()) return
        val fw = checkSyx(syx.readBytes())
        assertEquals(800, fw.version)
        assertEquals(800, fw.containerVersion)
        assertEquals(4058, fw.packets.size)        // 0x71 + 4056 x 0x72 + 0x73
        val image = decodeImage(splitMessages(syx.readBytes()))
        val stock = bin.readBytes()
        assertContentEquals(stock, image.copyOf(stock.size))
    }
}
