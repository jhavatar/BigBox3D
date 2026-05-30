package io.chthonic.bigbox3d.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import io.chthonic.bigbox3d.core.BoxTextureAtlas
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RawImage
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity
import io.chthonic.bigbox3d.core.buildAtlas2x3
import io.chthonic.bigbox3d.core.cuboidDimensions
import io.chthonic.bigbox3d.core.cuboidDimensionsFromTop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Limits concurrent atlas builds across all BigBox3D instances.
// Each build allocates a large pixel buffer (the atlas ByteArray — typically 10–20 MB).
// Without a cap, fast-scrolling through a LazyColumn triggers many simultaneous builds
// whose combined allocations can exhaust the heap. URL fetching in toRawImages() is
// unaffected and still runs concurrently — only the CPU/memory-intensive buildAtlas2x3
// step is gated. 3 permits allows meaningful parallelism while bounding peak atlas memory.
private val atlasBuildSemaphore = Semaphore(3)

/**
 * Compose Multiplatform widget that renders a 3D PC game big box.
 *
 * @param textures face textures — either [BoxTextureUrls] (loaded from URLs) or
 *   [BoxRawImages] (pre-loaded, e.g. from bundled resources via [loadRawImageFromBytes])
 * @param rotationSpeed auto-rotation speed; [RotationSpeed.NONE] stops rotation
 * @param glossLevel surface glossiness
 * @param shadowOpacity opacity of the projected shadow
 * @param shadowFade softness of the shadow falloff
 * @param shadowXOffsetRatio shadow center X offset relative to box width (+right)
 * @param shadowYOffsetRatio shadow center Y offset relative to box height (+up)
 * @param paused when true the render loop is suspended — no GPU work, GL state preserved
 * @param onGestureActive fires when a touch gesture starts or ends
 * @param onLoadingChange fires with `true` when atlas loading begins and `false` when it completes or the composable leaves composition
 */
@Composable
fun BigBox3D(
    textures: BoxTexture,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    rotationSpeed: RotationSpeed = RotationSpeed.VERY_SLOW,
    glossLevel: GlossLevel = GlossLevel.SEMI_GLOSS,
    shadowOpacity: ShadowOpacity = ShadowOpacity.STRONG,
    shadowFade: ShadowFade = ShadowFade.REALISTIC,
    shadowXOffsetRatio: Float = 0f,
    shadowYOffsetRatio: Float = 0f,
    onGestureActive: (Boolean) -> Unit = {},
    onLoadingChange: (Boolean) -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    var atlas by remember { mutableStateOf<BoxTextureAtlas?>(null) }

    val isLoading = atlas == null
    LaunchedEffect(isLoading) { onLoadingChange(isLoading) }
    // Ensure the caller removes this item from its loading set if the composable leaves
    // composition while still loading (e.g. scrolled out of a LazyColumn viewport).
    DisposableEffect(Unit) { onDispose { onLoadingChange(false) } }

    LaunchedEffect(textures) {
        atlas = null
        try {
            // var so we can null it out immediately after the atlas is built, releasing
            // the source image ByteArrays for GC before the coroutine scope ends.
            var rawImages: List<RawImage> = when (textures) {
                is BoxTextureUrls -> withContext(ioDispatcher) {
                    textures.toRawImages { url -> loadRawImageFromUrl(url, platformContext) }
                }

                is BoxRawImages -> listOf(
                    textures.front, textures.back,
                    textures.left, textures.right,
                    textures.top, textures.bottom,
                )
            }
            atlas = atlasBuildSemaphore.withPermit {
                withContext(Dispatchers.Default) {
                    val dims = when (textures) {
                        is BoxTextureUrls -> when {
                            textures.sides is SideSource.Explicit || textures.sides is SideSource.Spine ->
                                cuboidDimensions(front = rawImages[0], side = rawImages[2])

                            textures.caps is CapSource.Explicit ->
                                cuboidDimensionsFromTop(front = rawImages[0], top = rawImages[4])

                            else ->
                                cuboidDimensions(front = rawImages[0], depthRatio = 0.18f)
                        }
                        // BoxRawImages always provides all 6 faces; derive depth from the side image.
                        is BoxRawImages -> cuboidDimensions(
                            front = rawImages[0],
                            side = rawImages[2]
                        )
                    }
                    val meta = rawImages.buildAtlas2x3(
                        halfW = dims.halfWidth,
                        halfH = dims.halfHeight,
                        halfD = dims.halfDepth,
                    )
                    // Source images have been fully blitted into the atlas — release them
                    // immediately so the GC can reclaim their ByteArrays before this coroutine
                    // scope ends. Each image can be several MB; releasing here rather than
                    // waiting for scope exit meaningfully reduces peak heap under concurrent loads.
                    rawImages = emptyList<RawImage>()
                    BoxTextureAtlas(
                        image = meta.image,
                        regions = meta.regions,
                        supportsFullXAxisRotation = true,
                        halfWidth = dims.halfWidth,
                        halfHeight = dims.halfHeight,
                        halfDepth = dims.halfDepth,
                    )
                }
            }
        } catch (e: Exception) {
            // Never swallow coroutine cancellation — propagate it so Compose can
            // restart the effect with the new key if textures changed.
            if (e is kotlinx.coroutines.CancellationException) throw e
            atlas = null
        }
    }

    atlas?.let { a ->
        BigBox3DGlSurface(
            atlas = a,
            modifier = modifier,
            paused = paused,
            rotationSpeed = rotationSpeed,
            glossLevel = glossLevel,
            shadowOpacity = shadowOpacity,
            shadowFade = shadowFade,
            shadowXOffsetRatio = shadowXOffsetRatio,
            shadowYOffsetRatio = shadowYOffsetRatio,
            onGestureActive = onGestureActive,
        )
        // While loading, occupy the modifier's space but show nothing —
        // the caller overlays loading content at a stable call site outside BigBox3D.
    } ?: Box(modifier = modifier)
}
