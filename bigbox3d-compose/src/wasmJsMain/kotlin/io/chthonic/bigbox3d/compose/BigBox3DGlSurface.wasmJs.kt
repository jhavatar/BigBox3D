@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.chthonic.bigbox3d.compose

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
    onGestureActive: (Boolean) -> Unit,
) {
    val glCanvas = remember { jsCreateCanvas() }
    val glCtx    = remember { jsGetWebGL2Ctx(glCanvas) }
    val glApi    = remember { GlApiImpl(glCtx) }
    val renderer = remember(atlas) { CuboidRenderer(atlas) }

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
    // Gestures are handled by Modifier.pointerInput on the Box below, which lets the
    // LazyColumn scroll freely and lets detectDragGestures claim the drag for rotation.
    val glReady = remember { mutableStateOf(false) }
    val lastPw  = remember { mutableStateOf(0) }
    val lastPh  = remember { mutableStateOf(0) }

    DisposableEffect(glCanvas) {
        jsAppendToHtml(glCanvas)
        onDispose {
            if (glReady.value) renderer.release(glApi)
            glReady.value = false
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
                val windowPos = coords.localToWindow(Offset.Zero)
                val x = windowPos.x.toInt()
                val y = windowPos.y.toInt()
                val w = coords.size.width
                val h = coords.size.height
                if (w > 0 && h > 0) {
                    jsStyleCanvas(glCanvas, x, y, w, h)
                    val dpr = jsDevicePixelRatio()
                    val pw = (w * dpr).toInt(); val ph = (h * dpr).toInt()
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
                    jsStyleCanvas(glCanvas, x, y, 0, 0)
                }
            }
            .pointerInput(Unit) {
                // Drag to rotate. detectDragGestures claims the gesture so the
                // LazyColumn doesn't scroll while rotating.
                detectDragGestures(
                    onDragStart  = { onGestureActive(true) },
                    onDragEnd    = { onGestureActive(false) },
                    onDragCancel = { onGestureActive(false) },
                ) { _, dragAmount ->
                    renderer.handleTouchDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(Unit) {
                // Scroll-wheel zoom with list-scroll detection via timestamp:
                // - If two scroll events arrive within 300 ms the list is "in motion"
                //   and zoom is suppressed.
                // - Once scroll is idle for 300 ms the next wheel tick zooms.
                // The event is never consumed so LazyColumn always receives it.
                // Using a timestamp avoids allocating a new coroutine on every scroll tick.
                var scaleFactor = 1f
                var lastScrollMs = 0.0
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scrollY = event.changes.fold(Offset.Zero) { acc, c ->
                            acc + c.scrollDelta
                        }.y
                        if (scrollY != 0f) {
                            val now = jsDateNow()
                            val listScrolling = (now - lastScrollMs) < 300.0
                            lastScrollMs = now
                            if (!listScrolling) {
                                scaleFactor = (scaleFactor * if (scrollY > 0) 0.9f else 1.1f)
                                    .coerceIn(0.5f, 3f)
                                renderer.zoomFactor = scaleFactor
                            }
                        }
                    }
                }
            }
    )
}

// ── Canvas / DOM ─────────────────────────────────────────────────────────────

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

private fun jsStyleCanvas(canvas: JsAny, x: Int, y: Int, w: Int, h: Int): Unit = js("""
    (canvas.style.left   = x + 'px',
     canvas.style.top    = y + 'px',
     canvas.style.width  = w + 'px',
     canvas.style.height = h + 'px')
""")

private fun jsResizeCanvas(canvas: JsAny, w: Int, h: Int): Unit =
    js("(canvas.width = w, canvas.height = h)")

private fun jsDevicePixelRatio(): Double = js("window.devicePixelRatio || 1.0")
private fun jsDateNow(): Double = js("Date.now()")
