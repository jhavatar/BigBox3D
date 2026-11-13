@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.chthonic.bigbox3d.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import io.chthonic.bigbox3d.core.AmbientBrightness
import io.chthonic.bigbox3d.core.BoxTextureAtlas
import io.chthonic.bigbox3d.core.Brightness
import io.chthonic.bigbox3d.core.CuboidRenderer
import io.chthonic.bigbox3d.core.GlApiImpl
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity
import io.chthonic.bigbox3d.core.WebGl2Ctx
import kotlin.math.roundToInt

@Composable
internal actual fun BigBox3DGlSurface(
    atlas: BoxTextureAtlas,
    modifier: Modifier,
    paused: Boolean,
    rotationSpeed: RotationSpeed,
    glossLevel: GlossLevel,
    shadowOpacity: ShadowOpacity,
    shadowFade: ShadowFade,
    shadowXOffsetRatio: Float,
    shadowYOffsetRatio: Float,
    ambientBrightness: AmbientBrightness,
    brightness: Brightness,
    controls: BoxControls,
    webCutouts: List<RoundRect>,
) {
    val glCanvas = remember { jsCreateCanvas() }
    val glCtx    = remember { jsGetWebGL2Ctx(glCanvas) }
    val glApi    = remember { GlApiImpl(glCtx) }
    val renderer = remember(atlas) { CuboidRenderer(atlas) }
    // Gestures and drawing both run on the browser's main thread.
    val input    = rememberBoxInput(controls, remember(renderer) { DirectRendererAccess(renderer) })
    // Read Compose's own density rather than window.devicePixelRatio directly: coords.size/
    // localToWindow() below are computed using THIS density, and on web it's set once when the
    // page loads and does not live-update if the user changes browser zoom without a refresh.
    // Using LocalDensity keeps our CSS-pixel conversion structurally unable to drift from
    // whatever value Compose actually used, instead of trusting two independently-read sources
    // to happen to agree.
    val density = LocalDensity.current.density

    renderer.rotationSpeed      = rotationSpeed
    renderer.glossLevel         = glossLevel
    renderer.shadowOpacity      = shadowOpacity
    renderer.shadowFade         = shadowFade
    renderer.shadowXOffsetRatio = shadowXOffsetRatio
    renderer.shadowYOffsetRatio = shadowYOffsetRatio
    renderer.ambientBrightness  = ambientBrightness
    renderer.brightness         = brightness

    // In Compose MP 1.10.x, DisposableEffect runs BEFORE the first onGloballyPositioned.
    // Setting canvas.width/height (jsResizeCanvas) resets the WebGL context, wiping all
    // GL state created by onSurfaceCreated. So onSurfaceCreated is called inside
    // onGloballyPositioned, after the first backing-buffer resize.
    //
    // The canvas is attached to <html> (not <body>) because Compose MP 1.10.x sets
    // position:relative; overflow:hidden on <body>, which causes a Firefox layout
    // bug where position:fixed children have offsetWidth=0 and are invisible.
    //
    // The canvas uses pointer-events:none so all pointer events pass through to Compose.
    // Gestures are handled by boxGestures on the Box below (BoxGestures.kt): a sideways
    // drag rotates, an up/down one is left to the scrolling parent.
    val glReady = remember { mutableStateOf(false) }
    val clip = remember(glCanvas) { CanvasCutoutClip(glCanvas) }
    // Cutouts are relative to the canvas, so scrolling never changes them: they're applied when
    // they or the canvas's size change, in the same layout callback that positions the canvas.
    SideEffect { clip.update(cutouts = webCutouts, density = density) }
    val lastPw  = remember { mutableStateOf(0) }
    val lastPh  = remember { mutableStateOf(0) }

    DisposableEffect(glCanvas) {
        jsAppendToHtml(glCanvas)
        onDispose {
            if (glReady.value) renderer.release(glApi)
            glReady.value = false
            // Explicitly release the WebGL2 context rather than relying on GC to reclaim it
            // once the canvas is unreferenced. Chrome caps concurrent WebGL contexts per page
            // (16 by default) — a LazyColumn with many BigBox3D items each holding their own
            // context can exceed that during fast scroll, forcing the browser to evict the
            // oldest context to free resources. If that happens to be the main Compose/Skiko
            // canvas's own context (also WebGL-backed), the entire UI goes blank while these
            // still-alive per-item canvases keep rendering — exactly the "cell backgrounds
            // disappear after scrolling" symptom.
            jsLoseContext(glCtx)
            jsRemoveFromParent(glCanvas)
        }
    }

    LaunchedEffect(renderer, paused) {
        if (paused) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (glReady.value) renderer.onDrawFrame(glApi)
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                // Use the composable's full size (not the clipped visible area from
                // boundsInWindow) so the canvas stays 400dp tall while scrolling.
                // localToWindow gives the true window position even when off-screen (negative y).
                //
                // coords.localToWindow()/coords.size are in Compose's raw px unit, which on web
                // already bakes in its density (same as the backing Skia canvas: a 2x-density
                // screen has canvas.width/height at 2x its CSS style width/height). canvas.style.*
                // needs CSS pixels, so divide by density for styling; the backing buffer (canvas.
                // width/height) wants the raw physical pixel count directly, with no further scaling.
                val windowPos = coords.localToWindow(Offset.Zero)
                val pw = coords.size.width
                val ph = coords.size.height
                val cssX = windowPos.x.toCssPx(density)
                val cssY = windowPos.y.toCssPx(density)
                val cssW = pw.toCssPx(density)
                val cssH = ph.toCssPx(density)
                if (pw > 0 && ph > 0) {
                    jsStyleCanvas(glCanvas, cssX, cssY, cssW, cssH)
                    clip.update(cssWidth = cssW, cssHeight = cssH)
                    if (pw != lastPw.value || ph != lastPh.value) {
                        // canvas.width/height reset the WebGL context — release old GL
                        // objects first, then recreate everything after the resize.
                        if (glReady.value) renderer.release(glApi)
                        jsResizeCanvas(glCanvas, pw, ph)
                        lastPw.value = pw; lastPh.value = ph
                        renderer.onSurfaceCreated(glApi)
                        // onSurfaceChanged is only needed when dimensions change — moving
                        // it outside this guard would recalculate the projection matrix
                        // and invalidate the VP cache on every scroll position update.
                        renderer.onSurfaceChanged(glApi, pw, ph)
                        glReady.value = true
                    }
                } else {
                    // The composable collapsed to zero size (e.g. BigBox3DProgress fading
                    // out). Hide the canvas instead of leaving it frozen at its last
                    // nonzero position/size — otherwise it can become a visible "ghost"
                    // if an ancestor is later resized/repositioned and no longer overlaps it.
                    jsStyleCanvas(glCanvas, cssX, cssY, 0, 0)
                }
            }
            // Wheel zoom skips fast ticks (the page scrolling), as before.
            .boxGestures(input, WheelHandling.ZOOM_UNLESS_SCROLLING)
    )
}

/**
 * Keeps the canvas's CSS clip-path in step with [BigBox3D]'s `webCutouts`: the whole canvas with each
 * cutout left out (even-odd fill), or no clip without cutouts. Only sets the style when it changes.
 */
private class CanvasCutoutClip(private val canvas: JsAny) {
    private var cutouts: List<RoundRect> = emptyList()
    private var density = 1f
    private var cssWidth = 0
    private var cssHeight = 0
    private var applied: String? = null

    fun update(
        cutouts: List<RoundRect> = this.cutouts,
        density: Float = this.density,
        cssWidth: Int = this.cssWidth,
        cssHeight: Int = this.cssHeight,
    ) {
        // Called on every layout callback (each scroll frame): nothing to do unless something changed.
        if (cutouts == this.cutouts && density == this.density &&
            cssWidth == this.cssWidth && cssHeight == this.cssHeight && applied != null
        ) return
        this.cutouts = cutouts
        this.density = density
        this.cssWidth = cssWidth
        this.cssHeight = cssHeight
        val clipPath = if (cutouts.isEmpty() || cssWidth <= 0 || cssHeight <= 0) "none"
        else cutoutClipPath(cssWidth, cssHeight, cutouts, density)
        if (clipPath != applied) {
            jsSetClipPath(canvas, clipPath)
            applied = clipPath
        }
    }
}

/** A CSS `path(evenodd, …)` of the [width]×[height] canvas with each cutout (Compose px) as a hole. */
private fun cutoutClipPath(width: Int, height: Int, cutouts: List<RoundRect>, density: Float): String {
    fun Float.css() = (this / density).toString()
    val d = StringBuilder("M0 0 H$width V$height H0 Z")
    for (c in cutouts) {
        val l = c.left; val t = c.top; val r = c.right; val b = c.bottom
        val tl = c.topLeftCornerRadius; val tr = c.topRightCornerRadius
        val br = c.bottomRightCornerRadius; val bl = c.bottomLeftCornerRadius
        d.append(" M${(l + tl.x).css()} ${t.css()}")
        d.append(" H${(r - tr.x).css()} A${tr.x.css()} ${tr.y.css()} 0 0 1 ${r.css()} ${(t + tr.y).css()}")
        d.append(" V${(b - br.y).css()} A${br.x.css()} ${br.y.css()} 0 0 1 ${(r - br.x).css()} ${b.css()}")
        d.append(" H${(l + bl.x).css()} A${bl.x.css()} ${bl.y.css()} 0 0 1 ${l.css()} ${(b - bl.y).css()}")
        d.append(" V${(t + tl.y).css()} A${tl.x.css()} ${tl.y.css()} 0 0 1 ${(l + tl.x).css()} ${t.css()} Z")
    }
    return "path(evenodd, '$d')"
}

// ── Canvas / DOM ─────────────────────────────────────────────────────────────

private fun jsSetClipPath(canvas: JsAny, clipPath: String): Unit = js("(canvas.style.clipPath = clipPath)")

private fun jsCreateCanvas(): JsAny = js("document.createElement('canvas')")

private fun jsGetWebGL2Ctx(canvas: JsAny): WebGl2Ctx =
    js("canvas.getContext('webgl2', {alpha: true, antialias: false})")

// Attaches to <html> with pointer-events:none so Compose receives all pointer
// events and the LazyColumn can scroll freely.
private fun jsAppendToHtml(canvas: JsAny): Unit = js("""
    (canvas.style.position = 'fixed',
     canvas.style.pointerEvents = 'none',
     canvas.style.zIndex = '1',
     document.documentElement.appendChild(canvas))
""")

private fun jsRemoveFromParent(canvas: JsAny): Unit =
    js("(canvas.parentNode && canvas.parentNode.removeChild(canvas))")

// Forces immediate release of the WebGL2 context via the WEBGL_lose_context extension,
// rather than waiting for GC to reclaim it once the canvas is unreferenced.
private fun jsLoseContext(gl: WebGl2Ctx): Unit = js("""
    (function() {
        var ext = gl.getExtension('WEBGL_lose_context');
        if (ext) ext.loseContext();
    })()
""")

private fun jsStyleCanvas(canvas: JsAny, x: Int, y: Int, w: Int, h: Int): Unit = js("""
    (canvas.style.left   = x + 'px',
     canvas.style.top    = y + 'px',
     canvas.style.width  = w + 'px',
     canvas.style.height = h + 'px')
""")

private fun jsResizeCanvas(canvas: JsAny, w: Int, h: Int): Unit =
    js("(canvas.width = w, canvas.height = h)")

// Converts a Compose raw-px value (already density-scaled) to a CSS pixel value for canvas.style.*.
private fun Float.toCssPx(density: Float): Int = (this / density).roundToInt()
private fun Int.toCssPx(density: Float): Int = (this / density).roundToInt()
