package com.korgnote.common.index

/** iOS gives a process no fixed heap, and takes it down well above this on any phone it runs on. */
internal actual fun heapLimitBytes(): Long = 512L * 1024 * 1024

internal actual fun cacheHeapOverride(): Long? = null
