@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.chthonic.bigbox3d.compose

import coil3.PlatformContext
import io.chthonic.bigbox3d.core.RawImage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun loadRawImageFromUrl(url: String, context: PlatformContext): RawImage =
    imageDataToRawImage(fetchImageData(url))

@OptIn(ExperimentalEncodingApi::class)
actual suspend fun loadRawImageFromBytes(bytes: ByteArray): RawImage {
    // Encode as a data URL so the browser's existing fetch → createImageBitmap pipeline
    // handles all formats (WebP, PNG, JPEG, etc.) without extra JS helpers.
    val dataUrl = "data:application/octet-stream;base64,${Base64.encode(bytes)}"
    return imageDataToRawImage(fetchImageData(dataUrl))
}

private suspend fun imageDataToRawImage(imageData: JsAny): RawImage {
    val width = jsWidth(imageData)
    val height = jsHeight(imageData)
    val pixelCount = width * height
    val pixels = ByteArray(pixelCount * 4)
    // Pack all 4 RGBA bytes into one Int per bridge crossing (1M crossings for 1024×1024
    // instead of 4M). JS << operates on signed 32-bit ints so alpha=255 gives a negative
    // packed value; ushr in Kotlin extracts the unsigned byte correctly.
    for (p in 0 until pixelCount) {
        val rgba = jsGetPixelRgba(imageData, p)
        val base = p * 4
        pixels[base]     = rgba.toByte()
        pixels[base + 1] = (rgba ushr 8).toByte()
        pixels[base + 2] = (rgba ushr 16).toByte()
        pixels[base + 3] = (rgba ushr 24).toByte()
    }
    return RawImage(width, height, pixels)
}

/**
 * Suspends until the browser has fetched, decoded, and rasterised [url] into an ImageData object
 * (RGBA, capped at 1024 px on the longest side). Supports https://, data:, and blob: URLs.
 */
private suspend fun fetchImageData(url: String): JsAny =
    suspendCancellableCoroutine { cont ->
        val promise = jsBuildFetchPromise(url)
        jsPromiseThen(
            promise = promise,
            onFulfilled = { v: JsAny? ->
                if (v != null) cont.resume(v)
                else cont.resumeWithException(ImageLoadException())
            },
            onRejected = { _: JsAny? ->
                cont.resumeWithException(ImageLoadException())
            },
        )
    }

// Returns Promise<ImageData>: fetch → blob → ImageBitmap → OffscreenCanvas → ImageData.
private fun jsBuildFetchPromise(url: String): JsAny = js(
    """
    fetch(url)
        .then(function(r) { return r.blob(); })
        .then(function(b) { return createImageBitmap(b); })
        .then(function(bmp) {
            var maxPx = 1024;
            var s = Math.min(1.0, maxPx / Math.max(bmp.width, bmp.height));
            var w = Math.max(1, Math.round(bmp.width  * s));
            var h = Math.max(1, Math.round(bmp.height * s));
            var canvas = new OffscreenCanvas(w, h);
            var ctx = canvas.getContext('2d');
            ctx.drawImage(bmp, 0, 0, w, h);
            bmp.close();
            return ctx.getImageData(0, 0, w, h);
        })
"""
)

// Attaches resolve/reject callbacks to a JS Promise.
// Returns the chained Promise (ignored by callers).
private fun jsPromiseThen(
    promise: JsAny,
    onFulfilled: (JsAny?) -> Unit,
    onRejected: (JsAny?) -> Unit,
): JsAny = js("promise.then(onFulfilled, onRejected)")

// ImageData accessors — js() auto-converts JS number → Kotlin Int.
private fun jsWidth(data: JsAny): Int = js("data.width")
private fun jsHeight(data: JsAny): Int = js("data.height")
// Pack one full RGBA pixel (4 bytes) into a single Int: one bridge crossing per pixel.
private fun jsGetPixelRgba(data: JsAny, pixelIndex: Int): Int =
    js("(data.data[pixelIndex*4] | (data.data[pixelIndex*4+1] << 8) | (data.data[pixelIndex*4+2] << 16) | (data.data[pixelIndex*4+3] << 24))")
