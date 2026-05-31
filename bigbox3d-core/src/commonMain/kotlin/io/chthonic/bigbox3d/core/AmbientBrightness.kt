package io.chthonic.bigbox3d.core

/**
 * Controls the ambient (minimum) brightness of the 3D box surfaces.
 * Higher values lift the floor, making dark-textured boxes more visible.
 *
 * The ambient term sets how bright a face is when fully in shadow.
 * The complementary diffuse term `(1 - ambient)` keeps fully-lit faces at full brightness.
 */
enum class AmbientBrightness(internal val value: Float) {
    /** Reduced floor for light or high-contrast textures. */
    DARK(0.25f),
    /** Default — matches the original rendering behaviour. */
    NORMAL(0.4f),
    /** Raised floor for moderately dark textures. */
    BRIGHT(0.55f),
    /** High floor for very dark textures. */
    VERY_BRIGHT(0.7f),
}

internal val AmbientBrightness.ambientValue: Float get() = value

