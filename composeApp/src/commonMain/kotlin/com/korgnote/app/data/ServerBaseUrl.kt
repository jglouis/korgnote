package com.korgnote.app.data


// Provide platform-specific implementations via expect/actual.
// Common code declares expect; platforms provide actuals.
internal expect fun defaultServerBaseUrl(): String

// expect/actual declaration for platform name
expect fun getPlatformName(): String
