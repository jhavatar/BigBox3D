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
import io.chthonic.bigbox3d.core.AmbientBrightness
import io.chthonic.bigbox3d.core.BoxTextureAtlas
import io.chthonic.bigbox3d.core.Brightness
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RawImage
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity
import io.chthonic.bigbox3d.core.averageLuminancePercent
import io.chthonic.bigbox3d.core.buildAtlas2x3
import io.chthonic.bigbox3d.core.cuboidDimensions
import io.chthonic.bigbox3d.core.cuboidDimensionsFromTop
import kotlinx.coroutines.CancellationException
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

private fun Float.toBrightness(): Brightness = when {
    this < 15f -> Brightness.STRONG_BOOST
    this < 25f -> Brightness.BOOST
    else -> Brightness.NORMAL
}

// Atlas cache keyed by BoxTexture.boxKey(). When a BigBox3D instance is created for
// textures that were previously built, it starts with the cached atlas immediately —
// isLoading is false from the first frame, onLoadingChange(true) is never fired, and
// no spinner is shown. This replicates the "keep atlas warm" benefit that movableContentOf
// provided, without requiring composition-level slot movement.
//
// The cache holds CPU-side RawImage data (not GL handles), so entries are valid across
// GL context recreation — the surface just re-uploads the pixels on onSurfaceCreated.
//
// BoxRawImages keys are unique per instance ("raw_N"), so the pool's fixed BoxRawImages
// object maps to a single stable cache entry regardless of how many pool slots exist.
// BoxTextureUrls keys encode the full URL set, so different boxes never collide.
private const val ATLAS_CACHE_MAX = 5
private val atlasCache = HashMap<String, Pair<BoxTextureAtlas, Float>>(ATLAS_CACHE_MAX + 1)

private fun putAtlasInCache(key: String, atlas: BoxTextureAtlas, luminance: Float) {
    if (atlasCache.size >= ATLAS_CACHE_MAX) {
        atlasCache.remove(atlasCache.keys.first())
    }
    atlasCache[key] = atlas to luminance
}

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
 * @param ambientBrightness floor brightness for shadowed faces — raises the minimum brightness
 *   of faces in shadow; [AmbientBrightness.NORMAL] is the default
 * @param brightness pre-lighting texture multiplier; [Brightness.AUTO] detects from front face
 *   luminance — very dark boxes get [Brightness.STRONG_BOOST], moderately dark get [Brightness.BOOST],
 *   normal/light boxes keep [Brightness.NORMAL]
 * @param onFrontLuminance fires once with the front face average luminance (0–100) when the atlas
 *   is ready — use to adapt surrounding UI to the box's brightness. Rough guide:
 *   `< 15` very dark (e.g. black game art), `15–30` dark, `30–50` mid-range, `> 50` light/bright
 * @param onGestureActive fires when a touch gesture starts or ends
 * @param onLoadingChange fires with `true` when atlas loading begins and `false` when it completes or the composable leaves composition
 * @param imageUrlResolver applied to each [BoxTextureUrls] face URL before fetching — use to route
 *   through a CORS proxy or rewrite to a same-origin path. Defaults to the identity function (no
 *   rewriting); ignored for [BoxRawImages].
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
    ambientBrightness: AmbientBrightness = AmbientBrightness.NORMAL,
    brightness: Brightness = Brightness.AUTO,
    onFrontLuminance: ((Float) -> Unit)? = null,
    onGestureActive: (Boolean) -> Unit = {},
    onLoadingChange: (Boolean) -> Unit = {},
    imageUrlResolver: (String) -> String = { it },
) {
    val platformContext = LocalPlatformContext.current

    // Initialise from cache — if this textures key was built before, atlas is non-null
    // immediately and the composable renders without a loading state on the first frame.
    // remember(textures) resets to the new key's cached value whenever textures changes.
    var atlas by remember(textures) {
        mutableStateOf<BoxTextureAtlas?>(atlasCache[textures.boxKey()]?.first)
    }
    var autoBrightness by remember(textures) {
        mutableStateOf(atlasCache[textures.boxKey()]?.second?.toBrightness())
    }
    val effectiveBrightness = if (brightness != Brightness.AUTO) brightness
    else autoBrightness ?: Brightness.NORMAL

    val isLoading = atlas == null
    LaunchedEffect(isLoading) { onLoadingChange(isLoading) }
    // Ensure the caller removes this item from its loading set if the composable leaves
    // composition while still loading (e.g. scrolled out of a LazyColumn viewport).
    DisposableEffect(Unit) { onDispose { onLoadingChange(false) } }

    LaunchedEffect(textures) {
        if (atlas != null) {
            // Cache hit: atlas and autoBrightness already restored via remember.
            // Fire the luminance callback so callers can react even on cache hits.
            atlasCache[textures.boxKey()]?.second?.let { onFrontLuminance?.invoke(it) }
            return@LaunchedEffect
        }

        try {
            // var so we can null it out immediately after the atlas is built, releasing
            // the source image ByteArrays for GC before the coroutine scope ends.
            var rawImages: List<RawImage> = when (textures) {
                is BoxTextureUrls -> withContext(ioDispatcher) {
                    textures.toRawImages { url -> loadRawImageFromUrl(imageUrlResolver(url), platformContext) }
                }

                is BoxRawImages -> listOf(
                    textures.front, textures.back,
                    textures.left, textures.right,
                    textures.top, textures.bottom,
                )
            }
            val builtAtlas = atlasBuildSemaphore.withPermit {
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
                    // Always compute luminance — needed for Brightness.AUTO on any subsequent
                    // cache hit regardless of what brightness was set when the entry was built.
                    val luminance = rawImages[0].averageLuminancePercent()
                    val meta = rawImages.buildAtlas2x3(
                        halfW = dims.halfWidth,
                        halfH = dims.halfHeight,
                        halfD = dims.halfDepth,
                    )
                    // Source images fully blitted — release for GC before scope exit.
                    rawImages = emptyList<RawImage>()
                    luminance to BoxTextureAtlas(
                        image = meta.image,
                        regions = meta.regions,
                        supportsFullXAxisRotation = true,
                        halfWidth = dims.halfWidth,
                        halfHeight = dims.halfHeight,
                        halfDepth = dims.halfDepth,
                    )
                }
            }
            putAtlasInCache(textures.boxKey(), builtAtlas.second, builtAtlas.first)
            autoBrightness = builtAtlas.first.toBrightness()
            atlas = builtAtlas.second
            onFrontLuminance?.invoke(builtAtlas.first)
        } catch (e: CancellationException) {
            // Always propagate cancellation so Compose can restart the effect
            // if textures changed while this build was in flight.
            throw e
        } catch (e: Throwable) {
            // Clear the cache on any unexpected failure — covers OutOfMemoryError on
            // JVM/Android and frees cached atlas memory on all targets.
            atlasCache.clear()
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
            ambientBrightness = ambientBrightness,
            brightness = effectiveBrightness,
            onGestureActive = onGestureActive,
        )
        // While loading, occupy the modifier's space but show nothing —
        // the caller overlays loading content at a stable call site outside BigBox3D.
    } ?: Box(modifier = modifier)
}
