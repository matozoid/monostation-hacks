package flasher

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise

// The browser's (and Node's) CompressionStream / DecompressionStream with 'deflate-raw'.
private suspend fun pipe(data: ByteArray, transform: dynamic): ByteArray {
    val stream = js("new Blob([data])").stream().pipeThrough(transform)
    val buffer = js("new Response(stream)").arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
    return Int8Array(buffer).unsafeCast<ByteArray>()
}

actual suspend fun deflateRaw(data: ByteArray): ByteArray = pipe(data, js("new CompressionStream('deflate-raw')"))

actual suspend fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    val out = pipe(data, js("new DecompressionStream('deflate-raw')"))
    require(out.size == size) { "deflate data gave ${out.size} bytes, expected $size" }
    return out
}
