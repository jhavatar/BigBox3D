@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.chthonic.gamebigbox

// External declarations for window/Location/URL caused a compiler crash in
// Kotlin 2.2.x (ExperimentalWasmJsInterop). Using js() intrinsics avoids
// external declarations entirely while keeping identical runtime behaviour.

private fun jsHostname(): String = js("window.location.hostname")
private fun jsUrlPathname(url: String): String = js("new URL(url).pathname")
private fun jsEncodeURIComponent(url: String): String = js("encodeURIComponent(url)")

private fun isDev(): Boolean = jsHostname() == "localhost"

// Dev: the webpack dev server proxies same-path requests (see devServerProxy.js) — strip to
// the pathname so the fetch stays same-origin. Prod: route through the deployed /api/proxy
// CORS proxy, passing the full external URL as a query param.
actual fun resolveExternalUrl(url: String): String =
    if (isDev()) jsUrlPathname(url) else "/api/proxy?url=" + jsEncodeURIComponent(url)
