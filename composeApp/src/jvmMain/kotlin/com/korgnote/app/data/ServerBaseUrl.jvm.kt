package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT

// The plain port answers this computer only, by this name.
internal actual fun defaultServerBaseUrl(): String = "http://localhost:$SERVER_PORT"

internal actual val usesLocalServer: Boolean = true

internal actual val canPair: Boolean = true

// The notes are on this computer already, where the server keeps them.
internal actual val offersNotesFolder: Boolean = false
