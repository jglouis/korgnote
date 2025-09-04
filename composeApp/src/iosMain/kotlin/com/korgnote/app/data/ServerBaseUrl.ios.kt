package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT

internal actual fun defaultServerBaseUrl(): String {
    return "http://192.168.129.36:$SERVER_PORT"
}
