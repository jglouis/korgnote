package com.korgnote.common.index

/**
 * The most recently used values, up to [maxSize] by [sizeOf]. Whatever is [pinned] stays, however big,
 * and pushes the rest out if it has to: the note on screen has to be at hand.
 *
 * Not thread-safe: it belongs to whoever made it.
 */
class SizedCache<V : Any>(private val maxSize: Long, private val sizeOf: (V) -> Long) {
    // Insertion-ordered: the first entry is the one used longest ago.
    private val entries = LinkedHashMap<String, V>()
    private var size = 0L

    /** The key that is never evicted, if any. */
    var pinned: String? = null
        set(value) {
            field = value
            evict()
        }

    operator fun get(key: String): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /** The value at [key], without counting as a use of it. */
    fun peek(key: String): V? = entries[key]

    operator fun contains(key: String): Boolean = key in entries

    /** Keeps [value], unless it could never fit with others; [key] then holds nothing older either. */
    operator fun set(key: String, value: V) {
        remove(key)
        if (sizeOf(value) > maxSize && key != pinned) return
        entries[key] = value
        size += sizeOf(value)
        evict()
    }

    fun remove(key: String) {
        val old = entries.remove(key) ?: return
        size -= sizeOf(old)
    }

    fun retain(keys: Set<String>) {
        for (key in entries.keys.filter { it !in keys }) remove(key)
    }

    fun clear() {
        entries.clear()
        size = 0
    }

    /** Whether [more] would fit without pushing anything out. */
    fun hasRoomFor(more: Long): Boolean = size + more <= maxSize

    private fun evict() {
        if (size <= maxSize) return
        val oldestFirst = entries.keys.iterator()
        while (size > maxSize && oldestFirst.hasNext()) {
            val key = oldestFirst.next()
            if (key == pinned) continue
            size -= sizeOf(entries.getValue(key))
            oldestFirst.remove()
        }
    }
}
