package io.chthonic.gamebigbox

/**
 * Rewrites a [BigBox3D] texture URL before it's fetched — only meaningful on wasmJs, where
 * cross-origin texture fetches need routing through a same-origin proxy. Identity elsewhere.
 */
expect fun resolveExternalUrl(url: String): String
