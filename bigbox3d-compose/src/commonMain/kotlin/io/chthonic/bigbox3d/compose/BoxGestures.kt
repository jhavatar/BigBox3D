package io.chthonic.bigbox3d.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import io.chthonic.bigbox3d.core.CuboidRenderer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Defaults for [BigBox3D]. */
object BigBox3DDefaults {
    /** The range the box's zoom is kept in: 0.5× to 3×. 1× is the starting zoom. */
    val ZoomRange: ClosedFloatingPointRange<Float> = 0.5f..3f
}

// A mouse-wheel tick zooms in by 10% or out by 10% (as before these settings existed).
private const val WHEEL_ZOOM_IN = 1.1f
private const val WHEEL_ZOOM_OUT = 0.9f

// On the web, wheel ticks less than this far apart are taken as the page scrolling, not zooming.
private const val WHEEL_SCROLLING_GAP_MS = 300L

// A trackpad pinch arrives as many small Ctrl + wheel events; each zooms by e^(-delta × this),
// at most 10% per event. Not yet tuned against real trackpads.
private const val TRACKPAD_PINCH_SENSITIVITY = 0.02f
private const val TRACKPAD_PINCH_MAX_STEP = 1.1f

/** What [BigBox3D]'s gesture and view parameters are set to, passed down to the platform surface. */
internal class BoxControls(
    val zoom: Float?,
    val rotationX: Float?,
    val rotationY: Float?,
    val dragToRotate: Boolean,
    val wheelToZoom: Boolean,
    val pinchToZoom: Boolean,
    val trackpadPinchToZoom: Boolean,
    val onZoomChange: (Float) -> Unit,
    val onRotationChange: (x: Float, y: Float) -> Unit,
    val onGestureActive: (Boolean) -> Unit,
)

/** How a platform's surface reaches its renderer (directly, or on Android's GL thread). */
internal interface RendererAccess {
    fun setZoom(zoom: Float)

    /** Sets either angle that isn't null; the other keeps its current value. */
    fun setRotation(x: Float?, y: Float?)

    /** Rotates by a drag, then reports the new angles to [report] on the main thread. */
    fun rotateBy(dx: Float, dy: Float, report: (x: Float, y: Float) -> Unit)
}

/** [RendererAccess] for platforms whose gestures may touch the renderer directly. */
internal class DirectRendererAccess(private val renderer: CuboidRenderer) : RendererAccess {
    override fun setZoom(zoom: Float) {
        renderer.zoomFactor = zoom
    }

    override fun setRotation(x: Float?, y: Float?) =
        renderer.setRotation(x ?: renderer.rotationX, y ?: renderer.rotationY)

    override fun rotateBy(dx: Float, dy: Float, report: (x: Float, y: Float) -> Unit) {
        renderer.handleTouchDrag(dx, dy)
        report(renderer.rotationX, renderer.rotationY)
    }
}

/** Which mouse-wheel handling a platform's surface wants. */
internal enum class WheelHandling {
    /** No wheel handling (touch platforms). */
    NONE,

    /** [BigBox3D]'s `wheelToZoom` zooms on every wheel tick (desktop). */
    ZOOM,

    /** [BigBox3D]'s `wheelToZoom` zooms unless the ticks come fast, i.e. the page is scrolling (web). */
    ZOOM_UNLESS_SCROLLING,
}

/**
 * The gesture and view state shared by every platform's surface, used on the main thread only.
 * It keeps the box's current zoom, so gestures and the app's requests agree on it.
 */
internal class BoxInput(controls: BoxControls) {
    var controls: BoxControls = controls
        private set

    private var zoom = 1f
    private var access: RendererAccess? = null
    private var requestedZoom: Float? = null
    private var requestedRotationX: Float? = null
    private var requestedRotationY: Float? = null

    /**
     * Takes the latest [controls], applies the app's zoom and rotation when they change, and the
     * current view to a new renderer (a new [access]), so a rebuilt renderer keeps the zoom.
     */
    fun update(controls: BoxControls, access: RendererAccess) {
        this.controls = controls
        val newRenderer = access !== this.access
        this.access = access

        val z = controls.zoom?.coerceIn(BigBox3DDefaults.ZoomRange)
        val zoomRequested = z != null && z != requestedZoom
        if (z != null && zoomRequested) {
            requestedZoom = z
            zoom = z
        }
        if (newRenderer || zoomRequested) access.setZoom(zoom)

        val x = controls.rotationX
        val y = controls.rotationY
        val rotationRequested = x != requestedRotationX || y != requestedRotationY
        if ((x != null || y != null) && (newRenderer || rotationRequested)) access.setRotation(x, y)
        requestedRotationX = x
        requestedRotationY = y
    }

    fun zoomBy(factor: Float) {
        val z = (zoom * factor).coerceIn(BigBox3DDefaults.ZoomRange)
        if (z == zoom) return
        zoom = z
        access?.setZoom(z)
        controls.onZoomChange(z)
    }

    fun rotateBy(dx: Float, dy: Float) {
        access?.rotateBy(dx, dy) { x, y -> controls.onRotationChange(x, y) }
    }
}

/** The surface's [BoxInput], updated with [controls] and [access] after each composition. */
@Composable
internal fun rememberBoxInput(controls: BoxControls, access: RendererAccess): BoxInput {
    val input = remember { BoxInput(controls) }
    SideEffect { input.update(controls, access) }
    return input
}

/** The box's drag, pinch and (per [wheel]) mouse-wheel gestures. */
internal fun Modifier.boxGestures(input: BoxInput, wheel: WheelHandling): Modifier {
    val gestures = pointerInput(input) { dragAndPinch(input) }
    return if (wheel == WheelHandling.NONE) gestures
    else gestures.pointerInput(input, wheel) { wheelZoom(input, wheel) }
}

/**
 * One finger: a drag that starts mostly sideways rotates the box; one that starts mostly up or
 * down is let go, so a scrolling parent scrolls. Two fingers pinch to zoom, and once a gesture
 * has pinched it never rotates: rotating again takes a new drag.
 */
private suspend fun PointerInputScope.dragAndPinch(input: BoxInput) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var prevX = down.position.x
        var prevY = down.position.y
        var claimed = false
        var pinched = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val controls = input.controls

                if (controls.pinchToZoom && event.changes.size >= 2) {
                    if (!claimed) {
                        claimed = true
                        controls.onGestureActive(true)
                    }
                    pinched = true
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) input.zoomBy(zoom)
                    event.changes.forEach { it.consume() }
                    continue
                }

                // A pinch only zooms: the finger left down after it neither rotates the box nor
                // scrolls the parent, until every finger is lifted.
                if (pinched) {
                    event.changes.forEach { it.consume() }
                    continue
                }

                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - prevX
                val dy = change.position.y - prevY

                if (!claimed) {
                    // Within the touch slop: wait for the direction (deltas keep adding up).
                    if (hypot(dx, dy) <= touchSlop) continue
                    // Mostly vertical, or dragging doesn't rotate: let the parent have it.
                    if (!controls.dragToRotate || abs(dx) < abs(dy)) break
                    claimed = true
                    controls.onGestureActive(true)
                }

                if (controls.dragToRotate) {
                    change.consume()
                    input.rotateBy(dx, dy)
                }
                prevX = change.position.x
                prevY = change.position.y
            }
        } finally {
            if (claimed) input.controls.onGestureActive(false)
        }
    }
}

/**
 * The mouse wheel. Ctrl + wheel, how browsers send a trackpad pinch, zooms with
 * `trackpadPinchToZoom` and is consumed. A plain wheel zooms with `wheelToZoom` and is never
 * consumed, as before, so a scrolling parent scrolls too.
 */
private suspend fun PointerInputScope.wheelZoom(input: BoxInput, wheel: WheelHandling) {
    var lastWheelMs: Long? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            var scrollY = 0f
            event.changes.forEach { scrollY += it.scrollDelta.y }
            if (scrollY == 0f) continue
            val controls = input.controls

            if (controls.trackpadPinchToZoom && event.keyboardModifiers.isCtrlPressed) {
                val step = exp(-scrollY * TRACKPAD_PINCH_SENSITIVITY)
                    .coerceIn(1f / TRACKPAD_PINCH_MAX_STEP, TRACKPAD_PINCH_MAX_STEP)
                input.zoomBy(step)
                event.changes.forEach { it.consume() }
                continue
            }

            if (!controls.wheelToZoom) continue
            val now = event.changes.first().uptimeMillis
            val pageScrolling = wheel == WheelHandling.ZOOM_UNLESS_SCROLLING &&
                lastWheelMs?.let { now - it < WHEEL_SCROLLING_GAP_MS } == true
            lastWheelMs = now
            if (!pageScrolling) input.zoomBy(if (scrollY > 0) WHEEL_ZOOM_OUT else WHEEL_ZOOM_IN)
        }
    }
}
