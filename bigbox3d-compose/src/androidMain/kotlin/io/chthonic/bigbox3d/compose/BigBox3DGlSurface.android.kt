package io.chthonic.bigbox3d.compose

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import io.chthonic.bigbox3d.core.AmbientBrightness
import io.chthonic.bigbox3d.core.BoxTextureAtlas
import io.chthonic.bigbox3d.core.Brightness
import io.chthonic.bigbox3d.core.CuboidRenderer
import io.chthonic.bigbox3d.core.GlApiImpl
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

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
    val glApi = remember { GlApiImpl() }
    val renderer = remember(atlas) { CuboidRenderer(atlas) }
    val glViewRef = remember { mutableStateOf<GLSurfaceView?>(null) }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                setZOrderOnTop(true)
                setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) =
                        renderer.onSurfaceCreated(glApi)

                    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) =
                        renderer.onSurfaceChanged(glApi, width, height)

                    override fun onDrawFrame(gl: GL10?) =
                        renderer.onDrawFrame(glApi)
                })
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                // No touch handling here — gestures are handled at the Compose level
                // via Modifier.pointerInput below so that the LazyColumn can intercept
                // vertical scroll gestures before the box claims them.
            }.also { glViewRef.value = it }
        },
        update = { glView ->
            if (paused) glView.onPause() else glView.onResume()
            glView.queueEvent {
                renderer.glossLevel = glossLevel
                renderer.rotationSpeed = rotationSpeed
                renderer.shadowOpacity = shadowOpacity
                renderer.shadowFade = shadowFade
                renderer.shadowXOffsetRatio = shadowXOffsetRatio
                renderer.shadowYOffsetRatio = shadowYOffsetRatio
                renderer.ambientBrightness = ambientBrightness
                renderer.brightness = brightness
            }
        },
        modifier = modifier.pointerInput(Unit) {
            val touchSlop = viewConfiguration.touchSlop
            var scaleFactor = 1f

            awaitEachGesture {
                // ── Wait for the first finger down ─────────────────────────────
                val down = awaitFirstDown(requireUnconsumed = false)
                var prevX = down.position.x
                var prevY = down.position.y
                var claimedRotation = false

                while (true) {
                    val event = awaitPointerEvent()
                    val anyPressed = event.changes.any { it.pressed }
                    if (!anyPressed) {
                        if (claimedRotation) onGestureActive(false)
                        break
                    }

                    // ── Pinch-to-zoom (two or more fingers) ───────────────────
                    if (event.changes.size >= 2) {
                        if (!claimedRotation) {
                            claimedRotation = true
                            onGestureActive(true)
                        }
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) {
                            scaleFactor = (scaleFactor * zoom).coerceIn(0.5f, 3f)
                            val sf = scaleFactor
                            glViewRef.value?.queueEvent { renderer.zoomFactor = sf }
                        }
                        event.changes.forEach { it.consume() }
                        // Update prevX/prevY to the centroid so single-touch
                        // resumption doesn't produce a position jump.
                        val centroid = event.calculateCentroidSize()
                        prevX = event.changes.map { it.position.x }.average().toFloat()
                        prevY = event.changes.map { it.position.y }.average().toFloat()
                        continue
                    }

                    // ── Single-finger drag ─────────────────────────────────────
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dx = change.position.x - prevX
                    val dy = change.position.y - prevY

                    if (!claimedRotation) {
                        val dist = hypot(dx, dy)
                        if (dist > touchSlop) {
                            if (abs(dx) >= abs(dy)) {
                                // Horizontal-dominant → claim for rotation.
                                claimedRotation = true
                                onGestureActive(true)
                            } else {
                                // Vertical-dominant → release so LazyColumn can scroll.
                                break
                            }
                        }
                    }

                    if (claimedRotation) {
                        change.consume()
                        val fdx = dx;
                        val fdy = dy
                        glViewRef.value?.queueEvent { renderer.handleTouchDrag(fdx, fdy) }
                        prevX = change.position.x
                        prevY = change.position.y
                    }
                }
            }
        },
        onRelease = { glView ->
            glView.queueEvent { renderer.release(glApi) }
            glView.onPause()
        },
    )
}
