package io.chthonic.bigbox3d.core

/**
 * Rotates this image 90° clockwise.
 * Output dimensions are swapped: new width = old height, new height = old width.
 */
fun RawImage.rotate90(): RawImage {
    val newW = height
    val newH = width
    val out = ByteArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val src = (y * width + x) * 4
            // 90° CW mapping: new_x = (height - 1 - y), new_y = x
            val dst = (x * newW + (height - 1 - y)) * 4
            out[dst]     = pixels[src]
            out[dst + 1] = pixels[src + 1]
            out[dst + 2] = pixels[src + 2]
            out[dst + 3] = pixels[src + 3]
        }
    }
    return RawImage(newW, newH, out)
}

/**
 * Applies a separable box blur (horizontal pass then vertical pass) with the given [radius].
 * O(W×H) regardless of radius — uses a sliding-window sum per row/column.
 * Border pixels are clamped (repeated) at edges.
 * For strong blurring call multiple times or use a large radius.
 */
fun RawImage.separableBoxBlur(radius: Int): RawImage {
    if (radius <= 0) return this
    val k = 2 * radius + 1

    // --- Horizontal pass ---
    val hBuf = ByteArray(pixels.size)
    for (y in 0 until height) {
        var r = 0; var g = 0; var b = 0; var a = 0
        for (dx in -radius..radius) {
            val sx = dx.coerceIn(0, width - 1)
            val i = (y * width + sx) * 4
            r += pixels[i].toInt() and 0xFF
            g += pixels[i + 1].toInt() and 0xFF
            b += pixels[i + 2].toInt() and 0xFF
            a += pixels[i + 3].toInt() and 0xFF
        }
        val row = y * width
        hBuf[row * 4]     = (r / k).toByte()
        hBuf[row * 4 + 1] = (g / k).toByte()
        hBuf[row * 4 + 2] = (b / k).toByte()
        hBuf[row * 4 + 3] = (a / k).toByte()
        for (x in 1 until width) {
            val addI = (y * width + (x + radius).coerceAtMost(width - 1)) * 4
            val remI = (y * width + (x - radius - 1).coerceAtLeast(0)) * 4
            r += (pixels[addI].toInt() and 0xFF) - (pixels[remI].toInt() and 0xFF)
            g += (pixels[addI + 1].toInt() and 0xFF) - (pixels[remI + 1].toInt() and 0xFF)
            b += (pixels[addI + 2].toInt() and 0xFF) - (pixels[remI + 2].toInt() and 0xFF)
            a += (pixels[addI + 3].toInt() and 0xFF) - (pixels[remI + 3].toInt() and 0xFF)
            val o = (row + x) * 4
            hBuf[o]     = (r / k).toByte()
            hBuf[o + 1] = (g / k).toByte()
            hBuf[o + 2] = (b / k).toByte()
            hBuf[o + 3] = (a / k).toByte()
        }
    }

    // --- Vertical pass on hBuf ---
    val out = ByteArray(pixels.size)
    for (x in 0 until width) {
        var r = 0; var g = 0; var b = 0; var a = 0
        for (dy in -radius..radius) {
            val sy = dy.coerceIn(0, height - 1)
            val i = (sy * width + x) * 4
            r += hBuf[i].toInt() and 0xFF
            g += hBuf[i + 1].toInt() and 0xFF
            b += hBuf[i + 2].toInt() and 0xFF
            a += hBuf[i + 3].toInt() and 0xFF
        }
        out[x * 4]     = (r / k).toByte()
        out[x * 4 + 1] = (g / k).toByte()
        out[x * 4 + 2] = (b / k).toByte()
        out[x * 4 + 3] = (a / k).toByte()
        for (y in 1 until height) {
            val addI = ((y + radius).coerceAtMost(height - 1) * width + x) * 4
            val remI = ((y - radius - 1).coerceAtLeast(0) * width + x) * 4
            r += (hBuf[addI].toInt() and 0xFF) - (hBuf[remI].toInt() and 0xFF)
            g += (hBuf[addI + 1].toInt() and 0xFF) - (hBuf[remI + 1].toInt() and 0xFF)
            b += (hBuf[addI + 2].toInt() and 0xFF) - (hBuf[remI + 2].toInt() and 0xFF)
            a += (hBuf[addI + 3].toInt() and 0xFF) - (hBuf[remI + 3].toInt() and 0xFF)
            val o = (y * width + x) * 4
            out[o]     = (r / k).toByte()
            out[o + 1] = (g / k).toByte()
            out[o + 2] = (b / k).toByte()
            out[o + 3] = (a / k).toByte()
        }
    }

    return RawImage(width, height, out)
}

/**
 * Returns the average luminance of the image as a percentage (0 = black, 100 = white).
 * Uses the Rec. 601 formula: lum = 0.299·R + 0.587·G + 0.114·B.
 * Samples up to [maxSamples] pixels on a uniform grid for O(1) cost relative to image size.
 */
fun RawImage.averageLuminancePercent(maxSamples: Int = 10_000): Float {
    val step = maxOf(1, kotlin.math.sqrt((width * height).toDouble() / maxSamples).toInt())
    var sum = 0f
    var count = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val i = (y * width + x) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            sum += 0.299f * r + 0.587f * g + 0.114f * b
            count++
            x += step
        }
        y += step
    }
    return if (count > 0) sum / count / 255f * 100f else 0f
}

/**
 * Bilinear interpolation scale to [targetW] x [targetH].
 * Produces smooth results for both upscaling and downscaling — used by the
 * cardboard grain layer to avoid blocky nearest-neighbour artefacts.
 */
fun RawImage.bilinearScale(targetW: Int, targetH: Int): RawImage {
    if (width == targetW && height == targetH) return this
    val out = ByteArray(targetW * targetH * 4)
    val xScale = (width  - 1).toFloat() / maxOf(targetW - 1, 1).toFloat()
    val yScale = (height - 1).toFloat() / maxOf(targetH - 1, 1).toFloat()

    for (ty in 0 until targetH) {
        val srcY = ty * yScale
        val y0 = srcY.toInt().coerceIn(0, height - 2)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fy = srcY - y0;  val fy1 = 1f - fy

        for (tx in 0 until targetW) {
            val srcX = tx * xScale
            val x0 = srcX.toInt().coerceIn(0, width - 2)
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val fx = srcX - x0;  val fx1 = 1f - fx

            val i00 = (y0 * width + x0) * 4
            val i01 = (y0 * width + x1) * 4
            val i10 = (y1 * width + x0) * 4
            val i11 = (y1 * width + x1) * 4
            val o   = (ty * targetW + tx) * 4

            for (c in 0..3) {
                val v = (pixels[i00+c].toInt() and 0xFF) * fx1 * fy1 +
                        (pixels[i01+c].toInt() and 0xFF) * fx  * fy1 +
                        (pixels[i10+c].toInt() and 0xFF) * fx1 * fy  +
                        (pixels[i11+c].toInt() and 0xFF) * fx  * fy
                out[o+c] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
    }
    return RawImage(targetW, targetH, out)
}
