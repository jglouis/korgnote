package com.korgnote.common.index

/** A browser says nothing about its limit; this is well within what they give a tab. */
internal actual fun heapLimitBytes(): Long = 512L * 1024 * 1024

internal actual fun cacheHeapOverride(): Long? = null
