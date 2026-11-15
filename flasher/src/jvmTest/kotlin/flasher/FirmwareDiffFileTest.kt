package flasher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The shipped diffs against the private stock files; skipped when private/ is absent. */
class FirmwareDiffFileTest {
    private val firmware = File("../private/firmware")
    private val builds = File("src/jsMain/resources/builds")

    @Test
    fun stockReencodesByteExact() {
        val syx = File(firmware, "monostation-firmware-800.syx").takeIf { it.exists() }?.readBytes() ?: return
        assertEquals(STOCK_800_SHA256, sha256(syx).toHexString())
        assertContentEquals(syx, encodeFirmware(decodeImage(splitMessages(syx)), 800))
    }

    @Test
    fun mods756ByteExact() {
        val stock = File(firmware, "monostation-firmware-800.syx").takeIf { it.exists() }?.readBytes() ?: return
        val mods = File(firmware, "mods-756.syx").takeIf { it.exists() }?.readBytes() ?: return
        val diff = FirmwareDiff.parse(File(builds, "mods-756.fwdiff").readText())
        assertContentEquals(mods, diff.apply(stock))
    }
}
