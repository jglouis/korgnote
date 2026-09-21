package com.korgnote.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/** One thing that happened to the library, in order. */
@Serializable
data class ChangeEntry(
    val generation: Long,
    val path: String,
    val etag: String? = null,
    val deleted: Boolean = false,
)

/**
 * A bounded, in-memory log of recent changes, and the generation counter clients diff against.
 *
 * It is deliberately an *optimisation*, never the source of truth. A client asking `?since=` a
 * generation the journal no longer covers — because the server restarted or the log was trimmed —
 * gets a full manifest instead, so losing this costs bandwidth and nothing else. That is what keeps
 * it safe to hold only in memory.
 */
class ChangeJournal(private val capacity: Int = 512) {

    private val counter = AtomicLong(0)
    private val entries = ArrayDeque<ChangeEntry>()
    private val lock = Any()

    private val _events = MutableSharedFlow<ChangeEntry>(extraBufferCapacity = 256)

    /** Changes as they happen, for the SSE stream. */
    val events: SharedFlow<ChangeEntry> = _events.asSharedFlow()

    val generation: Long get() = counter.get()

    /** Records a change and returns its generation. */
    suspend fun record(path: String, etag: String?, deleted: Boolean = false): ChangeEntry {
        val entry = synchronized(lock) {
            val next = ChangeEntry(counter.incrementAndGet(), path, etag, deleted)
            entries.addLast(next)
            while (entries.size > capacity) entries.removeFirst()
            next
        }
        _events.emit(entry)
        return entry
    }

    /**
     * Changes after [since], or null when the journal cannot answer for that generation and the
     * caller must fall back to a full manifest.
     */
    fun changesSince(since: Long): List<ChangeEntry>? = synchronized(lock) {
        if (since > counter.get()) return null
        val oldest = entries.firstOrNull() ?: return if (since == counter.get()) emptyList() else null
        // The client is further behind than the log reaches.
        if (since < oldest.generation - 1) return null
        entries.filter { it.generation > since }
    }
}
