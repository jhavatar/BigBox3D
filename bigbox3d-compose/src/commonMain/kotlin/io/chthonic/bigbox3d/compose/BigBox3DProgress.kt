package io.chthonic.bigbox3d.compose

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.chthonic.bigbox3d.core.GlossLevel
import io.chthonic.bigbox3d.core.RotationSpeed
import io.chthonic.bigbox3d.core.ShadowFade
import io.chthonic.bigbox3d.core.ShadowOpacity

/**
 * A convenience wrapper around [BigBox3D] intended for use as a loading indicator.
 *
 * The widget stays permanently in the composition so its GL state and texture atlas are
 * preserved across show/hide cycles. When [visible] becomes false the render loop is
 * paused immediately (zero GPU cost), the last rendered frame fades out over
 * [fadeDurationMs] milliseconds, and the composable collapses to zero size only after
 * the fade completes so it no longer occupies layout space or intercepts touch events.
 *
 * @param textures face textures for the box
 * @param visible whether the progress indicator is shown
 * @param modifier positioning modifier — size is managed internally
 * @param size rendered size of the box when visible
 * @param fadeDurationMs duration of the fade-in / fade-out animation in milliseconds
 * @param rotationSpeed auto-rotation speed; defaults to [RotationSpeed.VERY_FAST]
 * @param glossLevel surface glossiness
 * @param shadowOpacity opacity of the projected shadow
 * @param shadowFade softness of the shadow falloff
 */
@Composable
fun BigBox3DProgress(
    textures: BoxTexture,
    visible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    fadeDurationMs: Int = 150,
    rotationSpeed: RotationSpeed = RotationSpeed.VERY_FAST,
    glossLevel: GlossLevel = GlossLevel.SEMI_GLOSS,
    shadowOpacity: ShadowOpacity = ShadowOpacity.NONE,
    shadowFade: ShadowFade = ShadowFade.SUPER_SOFT,
) {
    // On iOS, movableContentOf slots cross a SubcomposeLayout boundary (LazyColumn) when
    // moving between ParkingSpots and LoadingOverlay. In the same frame that the slot is
    // deactivated at the old location, visible changes — which would immediately invalidate
    // the .size() modifier and schedule a remeasure. The Metal DisplayLink can fire
    // measureAndLayout during the brief gap before the slot is re-activated at the new
    // location, causing "measure is called on a deactivated node".
    //
    // Delaying all layout-affecting state by one frame (withFrameNanos) ensures the slot
    // has fully settled at its new position before any remeasure is scheduled. The 16 ms
    // delay is imperceptible to users.
    var layoutVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        withFrameNanos { }
        layoutVisible = visible
    }

    val transition = updateTransition(layoutVisible, label = "BigBox3DProgress")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(fadeDurationMs) },
        label = "alpha",
    ) { if (it) 1f else 0f }
    val isAnimating = transition.currentState != transition.targetState

    BigBox3D(
        textures = textures,
        paused = !visible || !layoutVisible,
        rotationSpeed = rotationSpeed,
        glossLevel = glossLevel,
        shadowOpacity = shadowOpacity,
        shadowFade = shadowFade,
        modifier = modifier
            .size(if (layoutVisible || isAnimating) size else 0.dp)
            .alpha(alpha),
    )
}
