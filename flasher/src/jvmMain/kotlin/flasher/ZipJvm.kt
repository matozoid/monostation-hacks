package flasher

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual suspend fun deflateRaw(data: ByteArray): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION, true)
    d.setInput(data)
    d.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(65536)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

actual suspend fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    val i = Inflater(true)
    i.setInput(data)
    val out = ByteArray(size)
    var n = 0
    while (n < size && !i.finished()) {
        val got = i.inflate(out, n, size - n)
        if (got == 0 && (i.needsInput() || i.needsDictionary())) break
        n += got
    }
    i.end()
    require(n == size) { "deflate data ended after $n of $size bytes" }
    return out
}
