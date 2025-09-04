package com.korgnote.app.data

import kotlinx.browser.window

// On WebAssembly/JS, use the current page origin to avoid cross-origin requests in dev.
// This makes the client fetch from the same scheme://host:port as the app is served from,
// so no CORS headers are needed when the server is reverse-proxied or co-hosted.
internal actual fun defaultServerBaseUrl(): String {
    // window.location.origin returns e.g. "http://localhost:8081"
    return window.location.origin
}
