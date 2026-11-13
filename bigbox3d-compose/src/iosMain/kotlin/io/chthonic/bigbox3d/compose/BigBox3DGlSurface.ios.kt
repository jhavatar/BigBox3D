@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.chthonic.bigbox3d.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.viewinterop.UIKitView
import io.chthonic.bigbox3d.core.AmbientBrightness
import io.chthonic.bigbox3d.core.BoxTextureAtlas
import io.chthonic.bigbox3d.core.Brightness
import io.chthonic.bigbox3d.core.CuboidRenderer
import io.chthonic.bigbox3d.core.GlApiImpl
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity
import kotlinx.cinterop.CValue
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Metal.MTLClearColor
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLLoadActionClear
import platform.Metal.MTLPixelFormatDepth32Float
import platform.UIKit.UIColor
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.darwin.NSObject

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
    val device = remember {
        MTLCreateSystemDefaultDevice() ?: error("Metal is not available on this device")
    }
    // cmdQueue and glApi depend only on the Metal device, not on the atlas.
    // Keying them on atlas would recreate Metal pipelines, depth/stencil states, and
    // uniform buffers unnecessarily if the atlas ever changed while composed.
    val cmdQueue    = remember { device.newCommandQueue()!! }
    val glApi       = remember { GlApiImpl(device, cmdQueue) }
    val renderer    = remember(atlas) { CuboidRenderer(atlas) }
    val delegate    = remember(atlas) { BigBox3DMetalDelegate(glApi, renderer, cmdQueue) }
    // Gestures and drawing both run on the main thread, so the renderer is touched directly.
    val input       = rememberBoxInput(controls, remember(renderer) { DirectRendererAccess(renderer) })

    // Release the previous renderer's GPU handles (VBOs, texture) from glApi when the
    // atlas changes, and when the composable leaves composition. Without this, the old
    // MTLBuffer/MTLTexture objects would be orphaned in glApi's handle maps.
    DisposableEffect(atlas) {
        onDispose { renderer.release(glApi) }
    }

    renderer.rotationSpeed      = rotationSpeed
    renderer.glossLevel         = glossLevel
    renderer.shadowOpacity      = shadowOpacity
    renderer.shadowFade         = shadowFade
    renderer.shadowXOffsetRatio = shadowXOffsetRatio
    renderer.shadowYOffsetRatio = shadowYOffsetRatio
    renderer.ambientBrightness  = ambientBrightness
    renderer.brightness         = brightness

    UIKitView(
        factory = {
            MTKView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), device = device).apply {
                colorPixelFormat         = MTLPixelFormatBGRA8Unorm
                depthStencilPixelFormat  = MTLPixelFormatDepth32Float
                preferredFramesPerSecond = 60L
                enableSetNeedsDisplay    = false
                // framebufferOnly = false allows the compositor to blend Metal pixels
                // with the content behind this layer (the Compose canvas).
                framebufferOnly          = false
                // Set transparent clear colour in the factory block so every frame —
                // including frames rendered before onSurfaceCreated sets glApi.clearColor*
                // — starts with alpha=0, not the MTKView default of opaque black.
                clearColor = cValue<MTLClearColor> {
                    red = 0.0; green = 0.0; blue = 0.0; alpha = 0.0
                }
                // Non-opaque so the Compose background shows through cleared pixels
                setOpaque(false)
                setBackgroundColor(UIColor.clearColor())
                layer.setOpaque(false)
                // All touches are handled by Compose via pointerInput below
                setUserInteractionEnabled(false)
            }
        },
        update = { view ->
            // Guard both calls: update runs on every recomposition (e.g. every frame during
            // alpha animation), but delegate only changes with atlas and paused rarely changes.
            if (view.delegate !== delegate) view.delegate = delegate
            if (view.isPaused() != paused) view.setPaused(paused)
        },
        onRelease = { view ->
            view.setPaused(true)
            view.delegate = null
            // GPU resource cleanup (renderer.release) is handled by DisposableEffect.onDispose.
        },
        // Shared gestures (BoxGestures.kt); no mouse-wheel handling on iOS.
        modifier = modifier.boxGestures(input, WheelHandling.NONE),
    )
}

// ── Metal render delegate ───────────────────────────────────────────────────

private class BigBox3DMetalDelegate(
    private val glApi: GlApiImpl,
    private val renderer: CuboidRenderer,
    private val commandQueue: MTLCommandQueueProtocol,
) : NSObject(), MTKViewDelegateProtocol {

    private var surfaceCreated = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var pendingW = 0
    private var pendingH = 0

    private fun ensureCreated(view: MTKView) {
        if (surfaceCreated) return
        renderer.onSurfaceCreated(glApi)
        surfaceCreated = true
        if (pendingW > 0 && pendingH > 0) {
            surfaceW = pendingW; surfaceH = pendingH
            renderer.onSurfaceChanged(glApi, pendingW, pendingH)
        }
        // View is now in the hierarchy — walk parent containers and clear any opaque
        // backgrounds that would block the Compose canvas showing through.
        var parent = view.superview
        repeat(5) {
            parent?.setOpaque(false)
            parent?.setBackgroundColor(UIColor.clearColor())
            parent = parent?.superview
        }
    }

    override fun drawInMTKView(view: MTKView) {
        ensureCreated(view)
        if (surfaceW <= 0 || surfaceH <= 0) return

        val drawable = view.currentDrawable ?: return
        val desc     = view.currentRenderPassDescriptor ?: return

        // Override clearColor directly on the attachment so alpha=0 is guaranteed.
        // Setting MTKView.clearColor (a C-struct property) alone is unreliable via K/N —
        // currentRenderPassDescriptor may have already captured the prior value.
        desc.colorAttachments.objectAtIndexedSubscript(0uL).let { att ->
            att.clearColor = cValue<MTLClearColor> {
                red = glApi.clearColorR; green = glApi.clearColorG
                blue = glApi.clearColorB; alpha = glApi.clearColorA
            }
            att.loadAction = MTLLoadActionClear
        }

        val cmdBuf = commandQueue.commandBuffer() ?: return
        val enc    = cmdBuf.renderCommandEncoderWithDescriptor(desc) ?: return

        glApi.beginFrame(enc)
        renderer.onDrawFrame(glApi)
        glApi.endFrame()

        cmdBuf.presentDrawable(drawable)
        cmdBuf.commit()
    }

    override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) {
        val (w, h) = drawableSizeWillChange.useContents { width.toInt() to height.toInt() }
        if (!surfaceCreated) {
            pendingW = w; pendingH = h
        } else if (w > 0 && h > 0) {
            surfaceW = w; surfaceH = h
            renderer.onSurfaceChanged(glApi, w, h)
        }
    }
}
