package io.chthonic.bigbox3d.core

import kotlin.math.sin
import kotlin.random.Random

data class RgbColor(val r: Int, val g: Int, val b: Int)

data class CardboardPalette(
    val shadow: RgbColor,
    val base: RgbColor,
    val highlight: RgbColor,
)

/**
 * Extracts a three-color cardboard palette (shadow / base / highlight) from this image.
 *
 * Samples every [sampleStep]-th pixel, discards near-black and near-white pixels
 * (likely text), histogram-quantizes the rest into 32-level RGB buckets, takes the
 * top [topPct] fraction by pixel count (dominant background colors), then assigns
 * shadow / base / highlight by luminance order.
 * A minimum luminance spread of 25 is enforced so the cardboard texture is always visible.
 */
fun RawImage.extractCardboardPalette(
    sampleStep: Int = 6,
    minLum: Float = 20f,
    maxLum: Float = 220f,
    topPct: Float = 0.30f,
): CardboardPalette {
    // key = quantized bucket index, value = [rSum, gSum, bSum, count]
    val buckets = HashMap<Int, IntArray>()

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val i = (y * width + x) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            if (lum in minLum..maxLum) {
                val key = (r / 32) * 1024 + (g / 32) * 32 + (b / 32)
                val bucket = buckets.getOrPut(key) { intArrayOf(0, 0, 0, 0) }
                bucket[0] += r; bucket[1] += g; bucket[2] += b; bucket[3]++
            }
            x += sampleStep
        }
        y += sampleStep
    }

    if (buckets.isEmpty()) {
        return CardboardPalette(RgbColor(60,50,40), RgbColor(90,75,60), RgbColor(120,100,80))
    }

    // Sort by frequency, accumulate until topPct of total pixels covered
    val sorted = buckets.values.sortedByDescending { it[3] }
    val cutoff = (sorted.sumOf { it[3] } * topPct).toInt()
    var accumulated = 0
    val candidates = mutableListOf<RgbColor>()
    for (v in sorted) {
        candidates.add(RgbColor(v[0] / v[3], v[1] / v[3], v[2] / v[3]))
        accumulated += v[3]
        if (accumulated >= cutoff) break
    }

    fun lum(c: RgbColor) = 0.299f * c.r + 0.587f * c.g + 0.114f * c.b
    candidates.sortBy { lum(it) }
    val shadow    = candidates.first()
    val base      = candidates[candidates.size / 2]
    val highlight = candidates.last()

    // Enforce minimum visible spread between shadow and highlight
    return if (lum(highlight) - lum(shadow) >= 25f) {
        CardboardPalette(shadow, base, highlight)
    } else {
        CardboardPalette(
            shadow    = RgbColor(maxOf(0,   base.r - 25), maxOf(0,   base.g - 25), maxOf(0,   base.b - 25)),
            base      = base,
            highlight = RgbColor(minOf(255, base.r + 25), minOf(255, base.g + 25), minOf(255, base.b + 25)),
        )
    }
}

/**
 * Generates a procedural cardboard texture at [width] x [height] using [palette].
 *
 * Three stacked layers:
 * 1. Sine-wave corrugation (subtle ridges, α=6) running perpendicular to [horizontalGrain]
 * 2. Fiber flecks — sparse shadow/highlight dots from a deterministic noise field
 * 3. Stretched grain — long faint streaks along the grain direction via bilinear-scaled noise + box blur
 *
 * [horizontalGrain] should be true for cap faces (landscape), false for side faces (portrait).
 * [seed] controls the deterministic random fields — same palette + seed = same texture.
 */
fun generateCardboard(
    width: Int,
    height: Int,
    palette: CardboardPalette,
    horizontalGrain: Boolean = true,
    seed: Int = 42,
): RawImage {
    val shadow    = palette.shadow
    val base      = palette.base
    val highlight = palette.highlight

    val out = ByteArray(width * height * 4)

    // Precompute corrugation blend per axis index — avoids per-pixel sin calls
    val axisLen = if (horizontalGrain) width else height
    val corrR = IntArray(axisLen)
    val corrG = IntArray(axisLen)
    val corrB = IntArray(axisLen)
    for (i in 0 until axisLen) {
        val sv = (sin(i * 0.06) + 1.0) / 2.0
        val t  = if (sv < 0.5) sv * 2.0 else (sv - 0.5) * 2.0
        val c1 = if (sv < 0.5) shadow else base
        val c2 = if (sv < 0.5) base else highlight
        corrR[i] = (c1.r * (1.0 - t) + c2.r * t).toInt()
        corrG[i] = (c1.g * (1.0 - t) + c2.g * t).toInt()
        corrB[i] = (c1.b * (1.0 - t) + c2.b * t).toInt()
    }

    // Layer 1 + 2: base fill blended with corrugation (α = 6/255 ≈ 2.4%)
    val corrAlphaF = 6f / 255f
    val baseAlphaF = 1f - corrAlphaF
    for (y in 0 until height) {
        for (x in 0 until width) {
            val ai = if (horizontalGrain) x else y
            val o  = (y * width + x) * 4
            out[o]     = (base.r * baseAlphaF + corrR[ai] * corrAlphaF).toInt().coerceIn(0, 255).toByte()
            out[o + 1] = (base.g * baseAlphaF + corrG[ai] * corrAlphaF).toInt().coerceIn(0, 255).toByte()
            out[o + 2] = (base.b * baseAlphaF + corrB[ai] * corrAlphaF).toInt().coerceIn(0, 255).toByte()
            out[o + 3] = 255.toByte()
        }
    }

    // Layer 3: fiber flecks — small noise field, nearest-neighbour scale up, alpha-composite
    val fw = maxOf(width / 4, 1)
    val fh = maxOf(height / 4, 1)
    val fiberPx = ByteArray(fw * fh * 4)
    val rng1 = Random(seed)
    for (i in 0 until fw * fh) {
        val v = rng1.nextFloat()
        if (v > 0.87f) {
            val t     = minOf(1f, (v - 0.87f) / 0.13f)
            val fleck = if (v < 0.93f) shadow else highlight
            val alpha = (t * 160f).toInt()
            val o     = i * 4
            fiberPx[o]     = fleck.r.toByte()
            fiberPx[o + 1] = fleck.g.toByte()
            fiberPx[o + 2] = fleck.b.toByte()
            fiberPx[o + 3] = alpha.toByte()
        }
        // else: transparent (alpha already 0 from ByteArray init)
    }
    alphaCompositeNNScaled(out, width, height, fiberPx, fw, fh)

    // Layer 4: stretched grain — tiny noise, bilinear scale up, box blur, alpha-composite
    val gw = if (horizontalGrain) maxOf(width / 60, 2) else maxOf(width / 2, 2)
    val gh = if (horizontalGrain) maxOf(height / 2, 2) else maxOf(height / 60, 2)
    val grainPx = ByteArray(gw * gh * 4)
    val rng2 = Random(seed + 1)
    for (i in 0 until gw * gh) {
        val v = rng2.nextFloat()
        val o = i * 4
        when {
            v < 0.30f -> { grainPx[o]=shadow.r.toByte();    grainPx[o+1]=shadow.g.toByte();    grainPx[o+2]=shadow.b.toByte();    grainPx[o+3]=40 }
            v > 0.70f -> { grainPx[o]=highlight.r.toByte(); grainPx[o+1]=highlight.g.toByte(); grainPx[o+2]=highlight.b.toByte(); grainPx[o+3]=40 }
            // else: transparent
        }
    }
    val grainScaled = RawImage(gw, gh, grainPx).bilinearScale(width, height).separableBoxBlur(3)
    alphaComposite(out, width, height, grainScaled.pixels)

    return RawImage(width, height, out)
}

// Alpha-composites [src] (RGBA) over [dst] (opaque RGBA) in-place.
private fun alphaComposite(dst: ByteArray, width: Int, height: Int, src: ByteArray) {
    for (i in 0 until width * height) {
        val o  = i * 4
        val sa = src[o + 3].toInt() and 0xFF
        if (sa == 0) continue
        val sf = sa / 255f;  val df = 1f - sf
        dst[o]     = ((dst[o].toInt()     and 0xFF) * df + (src[o].toInt()     and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
        dst[o + 1] = ((dst[o+1].toInt()   and 0xFF) * df + (src[o+1].toInt()   and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
        dst[o + 2] = ((dst[o+2].toInt()   and 0xFF) * df + (src[o+2].toInt()   and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
    }
}

// Alpha-composites [src] (fw×fh RGBA) nearest-neighbour scaled to [width]×[height] over [dst] in-place.
private fun alphaCompositeNNScaled(dst: ByteArray, width: Int, height: Int, src: ByteArray, fw: Int, fh: Int) {
    val xRatio = fw.toFloat() / width
    val yRatio = fh.toFloat() / height
    for (y in 0 until height) {
        val sy = (y * yRatio).toInt().coerceIn(0, fh - 1)
        for (x in 0 until width) {
            val sx = (x * xRatio).toInt().coerceIn(0, fw - 1)
            val si = (sy * fw + sx) * 4
            val sa = src[si + 3].toInt() and 0xFF
            if (sa == 0) continue
            val sf = sa / 255f;  val df = 1f - sf
            val di = (y * width + x) * 4
            dst[di]     = ((dst[di].toInt()     and 0xFF) * df + (src[si].toInt()     and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
            dst[di + 1] = ((dst[di+1].toInt()   and 0xFF) * df + (src[si+1].toInt()   and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
            dst[di + 2] = ((dst[di+2].toInt()   and 0xFF) * df + (src[si+2].toInt()   and 0xFF) * sf).toInt().coerceIn(0, 255).toByte()
        }
    }
}
