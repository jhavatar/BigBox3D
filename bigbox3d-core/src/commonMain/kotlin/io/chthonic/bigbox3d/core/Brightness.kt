package io.chthonic.bigbox3d.core

/**
 * Pre-lighting brightness multiplier applied to the texture colour on every face.
 * Values above 1.0 lift dark textures; 1.0 leaves colours unchanged.
 *
 * Unlike [AmbientBrightness] (which only raises the shadow floor), this affects all faces
 * uniformly — useful when the texture itself is so dark that even a raised ambient floor
 * produces barely-visible shadow faces.
 */
enum class Brightness(internal val value: Float) {
    /** Detect brightness from the front face luminance at load time — dark boxes get BOOST or
     *  STRONG_BOOST automatically; normal/light boxes keep NORMAL. */
    AUTO(-1f),

    /** Reduced multiplier for overly bright or washed-out textures. */
    DARK(0.7f),

    /** No adjustment — default rendering behaviour. */
    NORMAL(1.0f),

    /** Moderate boost for dark textures (~15–25% luminance). */
    BOOST(1.2f),

    /** Strong boost for very dark textures (<15% luminance). */
    STRONG_BOOST(1.4f),
}

/** Returns the concrete brightness value, resolving [Brightness.AUTO] to [Brightness.NORMAL]. */
internal val Brightness.brightnessValue: Float
    get() = if (this == Brightness.AUTO) Brightness.NORMAL.value else value
