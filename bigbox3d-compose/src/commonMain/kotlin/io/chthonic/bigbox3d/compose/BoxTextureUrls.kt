package io.chthonic.bigbox3d.compose

import androidx.compose.ui.graphics.Color
import io.chthonic.bigbox3d.core.RawImage
import io.chthonic.bigbox3d.core.extractCardboardPalette
import io.chthonic.bigbox3d.core.generateCardboard
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Base type accepted by [BigBox3D]. Either [BoxTextureUrls] or [BoxRawImages]. */
sealed interface BoxTexture {
    /** Stable, unique key for this instance — use as a LazyColumn item key. */
    fun boxKey(): String
}

sealed interface SideSource {
    /** Which face image to source from for [Cardboard]. */
    enum class FaceSource { Front, Back }

    /** Explicit left and right face URLs. */
    data class Explicit(val left: String, val right: String) : SideSource
    /** Single spine URL; right face is generated as the horizontal mirror of left. */
    data class Spine(val url: String) : SideSource
    /** Both side faces filled with a solid color; defaults to the front image's edge average. */
    data class ColorFill(val color: Color? = null) : SideSource
    /**
     * Both side faces generated procedurally as a cardboard texture using the dominant
     * background colors extracted from the given face image. Grain runs vertically.
     */
    data class Cardboard(val source: FaceSource = FaceSource.Front) : SideSource
}

sealed interface CapSource {
    /** Which face image to source from for [Cardboard]. Spine falls back to Front when no spine URL is present. */
    enum class FaceSource { Spine, Front, Back }

    /** Explicit top and bottom face URLs. */
    data class Explicit(val top: String, val bottom: String) : CapSource
    /** Both cap faces filled with a solid color; defaults to the front image's edge average. */
    data class ColorFill(val color: Color? = null) : CapSource
    /**
     * Both cap faces generated procedurally as a cardboard texture using the dominant
     * background colors extracted from the given face image. Grain runs horizontally.
     * Spine falls back to Front when no spine URL is present.
     */
    data class Cardboard(val source: FaceSource = FaceSource.Spine) : CapSource
}

data class BoxTextureUrls(
    val front: String,
    val back: String,
    val sides: SideSource,
    val caps: CapSource,
) : BoxTexture {
    override fun boxKey(): String = "$front|$back|${sides}|${caps}"

    val supportsFullXAxisRotation: Boolean = true

    /** Returns images in atlas order: [front, back, left, right, top, bottom]. */
    suspend fun toRawImages(loader: suspend (String) -> RawImage): List<RawImage> = coroutineScope {
        // Kick off all URL fetches concurrently before awaiting any result.
        val frontDef  = async { loader(front) }
        val backDef   = async { loader(back) }
        val leftDef   = (sides as? SideSource.Explicit)?.let { async { loader(it.left) } }
        val rightDef  = (sides as? SideSource.Explicit)?.let { async { loader(it.right) } }
        val spineDef  = (sides as? SideSource.Spine)?.let { async { loader(it.url) } }
        val topDef    = (caps  as? CapSource.Explicit)?.let { async { loader(it.top) } }
        val bottomDef = (caps  as? CapSource.Explicit)?.let { async { loader(it.bottom) } }

        // frontImg must be awaited first: ColorFill derives edge color from it.
        val frontImg = frontDef.await()
        // Computed lazily so edgeAverageColor() runs at most once even when both
        // sides and caps are ColorFill(null).
        val edgeColor: Color by lazy { frontImg.edgeAverageColor() }

        val (leftImg, rightImg) = when (sides) {
            is SideSource.Explicit  -> leftDef!!.await() to rightDef!!.await()
            is SideSource.Spine     -> spineDef!!.await().let { it to it.flipHorizontal() }
            is SideSource.ColorFill -> colorFillRawImage(sides.color ?: edgeColor).let { it to it }
            is SideSource.Cardboard -> {
                val src = resolveSideFaceSource(sides.source, frontImg, backDef)
                val img = generateCardboard(src.width, src.height, src.extractCardboardPalette(), horizontalGrain = false)
                img to img
            }
        }

        // Captured for CapSource.Cardboard(Spine): leftImg equals the spine when
        // sides is SideSource.Spine; null otherwise (falls back to front).
        val spineImg: RawImage? = if (sides is SideSource.Spine) leftImg else null

        val (topImg, bottomImg) = when (caps) {
            is CapSource.Explicit  -> topDef!!.await() to bottomDef!!.await()
            is CapSource.ColorFill -> colorFillRawImage(caps.color ?: edgeColor).let { it to it }
            is CapSource.Cardboard -> {
                val src = resolveCapFaceSource(caps.source, frontImg, backDef, spineImg)
                val img = generateCardboard(src.width, src.height, src.extractCardboardPalette(), horizontalGrain = true)
                img to img
            }
        }

        listOf(frontImg, backDef.await(), leftImg, rightImg, topImg, bottomImg)
    }
}

private suspend fun resolveSideFaceSource(
    source: SideSource.FaceSource,
    frontImg: RawImage,
    backDef: Deferred<RawImage>,
): RawImage = when (source) {
    SideSource.FaceSource.Front -> frontImg
    SideSource.FaceSource.Back  -> backDef.await()
}

private suspend fun resolveCapFaceSource(
    source: CapSource.FaceSource,
    frontImg: RawImage,
    backDef: Deferred<RawImage>,
    spineImg: RawImage?,
): RawImage = when (source) {
    CapSource.FaceSource.Front -> frontImg
    CapSource.FaceSource.Back  -> backDef.await()
    CapSource.FaceSource.Spine -> spineImg ?: frontImg
}

private fun RawImage.flipHorizontal(): RawImage {
    val flipped = ByteArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val src = (y * width + x) * 4
            val dst = (y * width + (width - 1 - x)) * 4
            flipped[dst]     = pixels[src]
            flipped[dst + 1] = pixels[src + 1]
            flipped[dst + 2] = pixels[src + 2]
            flipped[dst + 3] = pixels[src + 3]
        }
    }
    return RawImage(width, height, flipped)
}

private fun colorFillRawImage(color: Color): RawImage {
    val r = (color.red   * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    val b = (color.blue  * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    val a = (color.alpha * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    return RawImage(1, 1, byteArrayOf(r, g, b, a))
}

/**
 * Pre-loaded face images for a box — use when images come from bundled resources
 * or any other source where bytes are already available. No URL loading is performed.
 *
 * Use [loadRawImageFromBytes] to decode resource bytes (e.g. from Compose
 * Multiplatform's `Res.readBytes()`) into [RawImage] on any platform.
 */
data class BoxRawImages(
    val front: RawImage,
    val back: RawImage,
    val left: RawImage,
    val right: RawImage,
    val top: RawImage,
    val bottom: RawImage,
) : BoxTexture {
    // Assigned at construction outside the primary constructor so it is excluded
    // from data class equals/hashCode/copy — two BoxRawImages with identical pixels
    // remain equal but still produce distinct keys.
    private val id: Int = nextId()

    override fun boxKey(): String = "raw_$id"

    companion object {
        private var nextIdValue = 0
        private fun nextId() = ++nextIdValue
    }
}
