package com.korgnote.korgnote

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform