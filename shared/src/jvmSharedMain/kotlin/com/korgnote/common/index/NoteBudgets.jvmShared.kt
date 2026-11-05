package com.korgnote.common.index

internal actual fun heapLimitBytes(): Long = Runtime.getRuntime().maxMemory()

internal actual fun cacheHeapOverride(): Long? = System.getProperty("korgnote.cacheHeap")?.toLongOrNull()
