package io.chthonic.bigbox3d.core

import io.chthonic.bigbox3d.core.GlApi.Companion.GL_BACK
import io.chthonic.bigbox3d.core.GlApi.Companion.GL_COLOR_BUFFER_BIT
import io.chthonic.bigbox3d.core.GlApi.Companion.GL_CULL_FACE
import io.chthonic.bigbox3d.core.GlApi.Companion.GL_DEPTH_BUFFER_BIT
import io.chthonic.bigbox3d.core.GlApi.Companion.GL_DEPTH_TEST

private const val ROTATION_SENSITIVITY = 0.4f
private const val DEFAULT_ZOOM = 5f
private const val DEFAULT_ZOOM_FACTOR = 1f
private const val STALE_ZOOM = -1f

/**
 * Platform-independent rendering state for a [Cuboid].
 * The platform layer (e.g. GLSurfaceView.Renderer on Android) drives the lifecycle
 * by calling [onSurfaceCreated], [onSurfaceChanged], and [onDrawFrame] on the GL thread.
 */
class CuboidRenderer(private val atlas: BoxTextureAtlas) {

    var ambientBrightness: AmbientBrightness = AmbientBrightness.NORMAL
    var brightness: Brightness = Brightness.NORMAL
    var glossLevel: GlossLevel = GlossLevel.SEMI_GLOSS
    var rotationSpeed: RotationSpeed = RotationSpeed.VERY_SLOW
    var zoomFactor: Float = DEFAULT_ZOOM_FACTOR
    var shadowOpacity: ShadowOpacity = ShadowOpacity.STRONG
    var shadowFade: ShadowFade = ShadowFade.REALISTIC
    var shadowXOffsetRatio: Float = 0f
    var shadowYOffsetRatio: Float = 0f

    private var cuboid: Cuboid? = null
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix   = FloatArray(16)
    private var angleX = if (atlas.supportsFullXAxisRotation) 20f else 0f
    private var angleY = 30f
    private var lastZoomFactor = STALE_ZOOM

    fun onSurfaceCreated(gl: GlApi, onTexturesUploaded: (() -> Unit)? = null) {
        gl.glClearColor(0f, 0f, 0f, 0f)
        gl.glEnable(GL_DEPTH_TEST)
        gl.glEnable(GL_CULL_FACE)
        gl.glCullFace(GL_BACK)
        cuboid = Cuboid(gl, atlas, onTexturesUploaded)
    }

    fun onSurfaceChanged(gl: GlApi, width: Int, height: Int) {
        gl.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        Matrix4.perspectiveM(projMatrix, 0, 35f, ratio, 0.1f, 100f)
        lastZoomFactor = STALE_ZOOM
    }

    fun onDrawFrame(gl: GlApi) {
        gl.glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        if (rotationSpeed != RotationSpeed.NONE) rotate(rotationSpeed.deltaX, 0f)

        if (zoomFactor != lastZoomFactor) {
            val cameraZ = DEFAULT_ZOOM / zoomFactor
            Matrix4.setLookAtM(viewMatrix, 0, 0f, 0f, cameraZ, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix4.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            lastZoomFactor = zoomFactor
        }

        if (shadowOpacity != ShadowOpacity.NONE) {
            cuboid?.drawProjectedShadow(
                gl = gl,
                vp = vpMatrix,
                rotX = angleX,
                rotY = angleY,
                shadowOpacity = shadowOpacity.alpha,
                shadowFadeStartRatio = shadowFade.startRatio,
                shadowFadeEndRatio = shadowFade.endRatio,
                xOffsetRatio = shadowXOffsetRatio,
                yOffsetRatio = shadowYOffsetRatio,
            )
        }
        cuboid?.draw(
            gl = gl,
            vp = vpMatrix,
            rotX = angleX,
            rotY = angleY,
            cameraZ = lastZoomFactor,
            gloss = glossLevel.glossValue,
            ambient = ambientBrightness.ambientValue,
            brightness = brightness.brightnessValue,
        )
    }

    fun handleTouchDrag(deltaX: Float, deltaY: Float) = rotate(deltaX, deltaY)

    fun release(gl: GlApi) {
        cuboid?.release(gl)
        cuboid = null
    }

    private fun rotate(deltaX: Float, deltaY: Float) {
        angleX = (angleX + deltaY * ROTATION_SENSITIVITY).let {
            if (atlas.supportsFullXAxisRotation) it.coerceIn(-90f, 90f) else it.coerceIn(-7f, 7f)
        }
        angleY += deltaX * ROTATION_SENSITIVITY
    }
}
