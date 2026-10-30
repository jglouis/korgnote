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

/** What changed after a generation, and the generation those changes bring a client up to. */
data class JournalDelta(val generation: Long, val changes: List<ChangeEntry>)

/**
 * A bounded, in-memory log of recent changes, and the generation counter clients diff against.
 *
 * It is deliberately an *optimisation*, never the source of truth. A client asking `?since=` a
 * generation the journal no longer covers — because the server restarted or the log was trimmed —
 * gets a full manifest instead, so losing this costs bandwidth and nothing else. That is what keeps
 * it safe to hold only in memory.
 *
 * For a restart to be noticed, a new journal must never hand out a generation an old one did: one
 * counting from zero again would soon pass the number a client kept, and answer for it with only
 * the changes after it. So the counter starts at the time the journal was made, in milliseconds,
 * which is past every generation a previous run reached unless it saw more changes than it ran
 * milliseconds.
 */
class ChangeJournal(
    private val capacity: Int = 512,
    start: Long = System.currentTimeMillis(),
) {

    private val counter = AtomicLong(start)
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
     * What changed after [since], or null when the journal cannot answer for that generation and
     * the caller must fall back to a full manifest.
     *
     * One entry per path, its latest: a note deleted and made again is there, not gone. The
     * generation is read under the same lock as the changes, so it covers exactly them; read apart,
     * a change landing in between would be covered by the generation and missing from the changes.
     */
    fun changesSince(since: Long): JournalDelta? = synchronized(lock) {
        val generation = counter.get()
        if (since > generation) return null
        val oldest = entries.firstOrNull()
            ?: return if (since == generation) JournalDelta(generation, emptyList()) else null
        // The client is further behind than the log reaches.
        if (since < oldest.generation - 1) return null
        val latest = entries.filter { it.generation > since }.associateBy { it.path }.values
        JournalDelta(generation, latest.sortedBy { it.generation })
    }
}
