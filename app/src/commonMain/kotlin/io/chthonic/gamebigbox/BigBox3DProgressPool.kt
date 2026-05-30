package io.chthonic.gamebigbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.chthonic.bigbox3d.compose.BigBox3DProgress
import io.chthonic.bigbox3d.compose.BoxRawImages

// ═══════════════════════════════════════════════════════════════════════════════════════════════
// WHY THIS IS SIMPLER THAN THE ORIGINAL movableContentOf APPROACH
// ═══════════════════════════════════════════════════════════════════════════════════════════════
//
// The original implementation used movableContentOf to move BigBox3DProgress slots between
// ParkingSpots (outside the LazyColumn) and LoadingOverlay (inside each item), preserving
// the GL context and texture atlas across moves to avoid reloading.
//
// Compose Multiplatform 1.10.x has a bug where movableContentOf deactivation under fast
// scroll leaves deactivated layout nodes with pending NeedsRemeasure state, causing:
//   "java.lang.IllegalArgumentException: measure is called on a deactivated node"
//
// A position-tracking workaround (ParkingSpots using Modifier.offset based on
// onGloballyPositioned bounds) was attempted but had a fundamental 1-frame scroll lag —
// GLSurfaceView/MTKView renders bypass Compose's layout, so the spinner never tracked
// scroll correctly.
//
// The solution is to render BigBox3DProgress directly inside the LazyColumn item so it
// scrolls naturally. The atlas rebuild cost (the original reason for movableContentOf) is
// eliminated by the atlas cache in BigBox3D.kt: the first build is cached by boxKey() and
// every subsequent creation of BigBox3DProgress for the same textures starts with the cached
// atlas immediately — isLoading is false on frame 1, no spinner shown at all.
//
// REVERT RECOMMENDATION: if a future Compose Multiplatform upgrade fixes the
// deactivated-node bug, consider reverting to movableContentOf. It is architecturally
// cleaner for platforms where the workaround matters. The git history contains both
// the movableContentOf implementation and the position-tracking attempt.
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Holds state for [BigBox3DProgress] loading overlays shown while box textures load.
 * Obtain via [rememberBigBox3DProgressPool].
 */
class BigBox3DProgressPool internal constructor(
    internal val textures: BoxRawImages?,
    internal val loadingSet: Set<Int>,
    private val setLoading: (Int, Boolean) -> Unit,
) {
    /** Returns the [BigBox3D.onLoadingChange] callback for the item at [idx]. */
    fun onLoadingChange(idx: Int): (Boolean) -> Unit = { loading ->
        setLoading(idx, loading)
    }
}

/**
 * Creates and remembers a [BigBox3DProgressPool].
 */
@Composable
fun rememberBigBox3DProgressPool(
    textures: BoxRawImages?,
): BigBox3DProgressPool {
    var loadingSet by remember { mutableStateOf(emptySet<Int>()) }
    return BigBox3DProgressPool(
        textures = textures,
        loadingSet = loadingSet,
        setLoading = { idx, loading ->
            loadingSet = if (loading) loadingSet + idx else loadingSet - idx
        },
    )
}

/**
 * No-op — kept for call-site compatibility with the movableContentOf implementation.
 */
@Composable
fun BigBox3DProgressPool.ParkingSpots() = Unit

/**
 * Renders a [BigBox3DProgress] overlay for [idx] while it is loading.
 *
 * The spinner lives inside the LazyColumn item so it scrolls naturally with the content.
 * Atlas rebuild cost is eliminated by the atlas cache in BigBox3D: the first build for
 * a given [BoxRawImages] is cached and reused on every subsequent appearance, so the
 * spinner appears instantly (isLoading = false from frame 1) on re-entry.
 *
 * Falls back to [CircularProgressIndicator] if no textures are configured.
 */
@Composable
fun BigBox3DProgressPool.LoadingOverlay(idx: Int) {
    if (idx !in loadingSet) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val t = textures
        if (t != null) {
            BigBox3DProgress(textures = t, visible = true, size = 140.dp)
        } else {
            CircularProgressIndicator()
        }
    }
}
