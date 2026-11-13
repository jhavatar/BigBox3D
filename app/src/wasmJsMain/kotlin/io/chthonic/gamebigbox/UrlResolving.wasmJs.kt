@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.chthonic.gamebigbox

// External declarations for window/Location/URL caused a compiler crash in
// Kotlin 2.2.x (ExperimentalWasmJsInterop). Using js() intrinsics avoids
// external declarations entirely while keeping identical runtime behaviour.

private fun jsHostname(): String = js("window.location.hostname")
private fun jsUrlPathname(url: String): String = js("new URL(url).pathname")
private fun jsEncodeURIComponent(url: String): String = js("encodeURIComponent(url)")

// The dev server is reached as localhost on the Mac, or by its local network address (or
// <name>.local) from another device such as a phone; anything else is the deployed demo.
private val devHost = Regex(
    """^(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|.+\.local)$"""
)

private fun isDev(): Boolean = devHost.matches(jsHostname())

// Dev: the webpack dev server proxies same-path requests (see devServerProxy.js) — strip to
// the pathname so the fetch stays same-origin. Prod: route through the deployed /api/proxy
// CORS proxy, passing the full external URL as a query param.
actual fun resolveExternalUrl(url: String): String =
    if (isDev()) jsUrlPathname(url) else "/api/proxy?url=" + jsEncodeURIComponent(url)
