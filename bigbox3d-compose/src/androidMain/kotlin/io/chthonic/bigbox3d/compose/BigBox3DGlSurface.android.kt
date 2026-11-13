package io.chthonic.bigbox3d.compose

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
) {
    val glApi = remember { GlApiImpl() }
    val renderer = remember(atlas) { CuboidRenderer(atlas) }
    val glViewRef = remember { mutableStateOf<GLSurfaceView?>(null) }
    val input = rememberBoxInput(controls, remember(renderer) { GlThreadRendererAccess(renderer) { glViewRef.value } })

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
        // Shared gestures (BoxGestures.kt); no mouse-wheel handling on Android.
        modifier = modifier.boxGestures(input, WheelHandling.NONE),
        onRelease = { glView ->
            glView.queueEvent { renderer.release(glApi) }
            glView.onPause()
        },
    )
}

/**
 * The renderer is drawn on GLSurfaceView's GL thread, so changes go there with queueEvent, and
 * a drag's new angles come back to the main thread with post. Before the view exists (its GL
 * thread hasn't started) the renderer is set directly.
 */
private class GlThreadRendererAccess(
    private val renderer: CuboidRenderer,
    private val glView: () -> GLSurfaceView?,
) : RendererAccess {
    private val direct = DirectRendererAccess(renderer)

    override fun setZoom(zoom: Float) {
        glView()?.queueEvent { direct.setZoom(zoom) } ?: direct.setZoom(zoom)
    }

    override fun setRotation(x: Float?, y: Float?) {
        glView()?.queueEvent { direct.setRotation(x, y) } ?: direct.setRotation(x, y)
    }

    override fun rotateBy(dx: Float, dy: Float, report: (x: Float, y: Float) -> Unit) {
        val view = glView() ?: return
        view.queueEvent {
            renderer.handleTouchDrag(dx, dy)
            val x = renderer.rotationX
            val y = renderer.rotationY
            view.post { report(x, y) }
        }
    }
}
