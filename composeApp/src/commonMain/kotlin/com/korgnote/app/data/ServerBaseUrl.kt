package com.korgnote.app.data


// Provide platform-specific implementations via expect/actual.
// Common code declares expect; platforms provide actuals.
internal expect fun defaultServerBaseUrl(): String

