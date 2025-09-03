package com.korgnote.common

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform