package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT

// Unused: a phone reaches its server by pairing, never by this.
internal actual fun defaultServerBaseUrl(): String = "http://localhost:$SERVER_PORT"

internal actual val usesLocalServer: Boolean = false

internal actual val canPair: Boolean = true
