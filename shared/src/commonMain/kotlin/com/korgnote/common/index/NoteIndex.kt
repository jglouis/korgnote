package com.korgnote.common.index

import com.korgnote.common.org.edit.LastClocked
import com.korgnote.common.org.edit.RunningClock
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Where a [NoteIndex] is kept between runs, a line at a time: a big library's facts should not have to
 * fit in memory twice to be read or written.
 *
 * It is a cache: nothing in it is only there, so losing it costs a scan of the notes and nothing else.
 */
interface NoteIndexFile {
    /** Hands over each line of [part]; nothing when there is none. */
    suspend fun read(part: IndexPart, line: (String) -> Unit)

    /** Replaces [part] with [lines], whole or not at all. */
    suspend fun write(part: IndexPart, lines: Sequence<String>)
}

/**
 * The two parts of a kept index. The calendar has to be there as soon as the app opens, and needs only
 * the notes with something dated in them, which in a big library are often a small part of it: a folder
 * of generated logs has a great many headings, and no appointments.
 */
enum class IndexPart { Dated, Rest }

/** Keeps nothing, where there is nowhere to: the web, and most tests. */
object NoNoteIndexFile : NoteIndexFile {
    override suspend fun read(part: IndexPart, line: (String) -> Unit) = Unit
    override suspend fun write(part: IndexPart, lines: Sequence<String>) = Unit
}

/**
 * Each note's [NoteFacts], with the [FileStamp] its file had when they were worked out, so that only a
 * note whose file moved has to be read again. That is what makes the calendar ready as soon as the app
 * opens, whatever size the notes are, and what lets a reminder be planned without reading them.
 *
 * Not thread-safe: it belongs to one coroutine at a time, as the screen's state does.
 */
class NoteIndex(
    private val file: NoteIndexFile = NoNoteIndexFile,
    private val budgets: NoteBudgets = NoteBudgets.forThisDevice(),
) {
    private class Indexed(val stamp: FileStamp?, val facts: NoteFacts)

    private val notes = HashMap<String, Indexed>()
    private var weight = 0L
    private var changed = false
    private var snapshot: Map<String, NoteFacts>? = emptyMap()
    private val read = mutableSetOf<IndexPart>()

    /** What is known about each note. A copy, safe to read on any thread. */
    val facts: Map<String, NoteFacts>
        get() = snapshot ?: notes.mapValues { it.value.facts }.also { snapshot = it }

    /** What is known about [path]. Unlike [facts], this costs nothing while a scan is under way. */
    fun factsAbout(path: String): NoteFacts? = notes[path]?.facts

    /** How many notes there are no facts for, for want of room. */
    val noRoomFor: Int get() = notes.values.count { it.facts.leftOut == LeftOut.NoRoom }

    /** Reads the notes with something dated from what was kept from the last run: all the calendar needs. */
    suspend fun loadDated() = load(IndexPart.Dated)

    /** Reads everything kept from the last run; a line that makes no sense is scanned again instead. */
    suspend fun load() {
        load(IndexPart.Dated)
        load(IndexPart.Rest)
    }

    private suspend fun load(part: IndexPart) {
        if (!read.add(part)) return
        val wasChanged = changed
        var first = true
        var current = false
        val found = mutableListOf<Pair<String, Indexed>>()
        file.read(part) { line ->
            if (first) {
                first = false
                current = line == HEADER
            } else if (current) {
                val kept = runCatching { JSON.decodeFromString(KeptNote.serializer(), line) }.getOrNull()
                if (kept != null) found += kept.path to Indexed(kept.stamp, kept.facts.at(kept.path))
            }
        }
        for ((path, indexed) in found) {
            // On a device that now gives the app less: those not taken are scanned, and said to be left out.
            if (path !in notes && weight + indexed.facts.weight <= budgets.facts) put(path, indexed)
        }
        changed = wasChanged
    }

    /** Writes what is known, when anything changed since it was last written. */
    suspend fun save() {
        if (!changed) return
        changed = false
        // Tried again next time: there may be room by then, and memory for what ran out of it.
        val (dated, rest) = notes.toList()
            .filter { (_, indexed) -> indexed.facts.leftOut != LeftOut.NoRoom && indexed.facts.leftOut != LeftOut.Unreadable }
            .partition { (_, indexed) -> indexed.facts.isDated }
        write(IndexPart.Dated, dated)
        write(IndexPart.Rest, rest)
    }

    private suspend fun write(part: IndexPart, notes: List<Pair<String, Indexed>>) {
        file.write(
            part,
            sequenceOf(HEADER) + notes.asSequence().map { (path, indexed) ->
                JSON.encodeToString(KeptNote.serializer(), KeptNote(path, indexed.stamp, indexed.facts.bare()))
            },
        )
    }

    /** Whether [path]'s facts are from its file as it now is. */
    private fun isFresh(path: String, stamp: FileStamp?): Boolean =
        stamp != null && notes[path]?.stamp == stamp

    /**
     * The notes among [stamps] whose facts are out of date. First those known already, whose facts on
     * screen are wrong; then the smallest, which puts the most notes on the calendar the soonest when
     * a big library is scanned for the first time.
     */
    fun staleAmong(stamps: Map<String, FileStamp>): List<String> =
        stamps.keys.filter { !isFresh(it, stamps[it]) }
            .sortedWith(compareBy({ it !in notes }, { stamps.getValue(it).size }, { it }))

    /** Records [facts], with the [stamp] of the file they were read from if it is known. */
    fun record(path: String, stamp: FileStamp?, facts: NoteFacts) {
        val before = notes[path]?.facts?.weight ?: 0L
        val fits = weight - before + facts.weight <= budgets.facts
        put(path, Indexed(stamp, if (fits) facts else NoteFacts(leftOut = LeftOut.NoRoom)))
        changed = true
    }

    /** Whether anything at all is known about every one of [paths], if not from the latest of it. */
    fun knowsAll(paths: Collection<String>): Boolean = paths.all { it in notes }

    /** The clocks [path] now has, as a save makes them, for what cannot wait for the rest of its facts. */
    fun recordClocks(path: String, running: List<RunningClock>, lastClocked: List<LastClocked>) {
        val indexed = notes[path]
        put(path, Indexed(indexed?.stamp, (indexed?.facts ?: NoteFacts()).copy(running = running, lastClocked = lastClocked)))
        changed = true
    }

    private fun put(path: String, indexed: Indexed) {
        weight += indexed.facts.weight - (notes.put(path, indexed)?.facts?.weight ?: 0L)
        snapshot = null
    }

    /** Forgets every note but [paths]. */
    fun retain(paths: Set<String>) {
        val gone = notes.keys.filter { it !in paths }
        for (path in gone) weight -= notes.remove(path)?.facts?.weight ?: 0L
        if (gone.isNotEmpty()) {
            changed = true
            snapshot = null
        }
    }

    /**
     * Brings the facts of [stale] up to date, a few notes at a time, reading each no sooner than it is
     * needed and keeping none of it: however many there are, there are only ever a few in memory.
     *
     * A note that cannot be reached just now is left for the next scan. One that takes the app down with
     * it, which running out of memory on a huge file otherwise would, is left out until the next run.
     *
     * @param read the texts of the paths it is given, those it can read.
     * @param skip a note to leave alone, because something newer than its file is on its way.
     * @param progress called every so often, to show what is known so far.
     */
    suspend fun scan(
        stale: List<String>,
        stamps: Map<String, FileStamp>,
        on: CoroutineDispatcher,
        read: suspend (List<String>) -> Map<String, String>,
        skip: (String) -> Boolean = { false },
        keepGoing: () -> Boolean = { true },
        progress: suspend () -> Unit = {},
    ) {
        var sinceProgress = TimeSource.Monotonic.markNow()
        var sinceSave = TimeSource.Monotonic.markNow()
        for (group in groupsOf(stale, stamps)) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return
            for (path in group.filter { stamps.getValue(it).size > budgets.maxNoteBytes }) {
                record(path, stamps[path], NoteFacts(leftOut = LeftOut.TooLarge))
            }
            val fit = group.filter { stamps.getValue(it).size <= budgets.maxNoteBytes }
            val texts = readSafely(fit, stamps, read)
            for (path in fit) {
                if (skip(path)) continue
                val text = texts[path] ?: continue
                val facts = withContext(on) { scanned(path, text) }
                // Edited, deleted or listed again while that was worked out: it is about an older note.
                if (skip(path) || !keepGoing()) continue
                record(path, stamps[path], facts)
            }
            if (sinceProgress.elapsedNow() >= PROGRESS_EVERY) {
                progress()
                sinceProgress = TimeSource.Monotonic.markNow()
            }
            if (sinceSave.elapsedNow() >= SAVE_EVERY) {
                save()
                sinceSave = TimeSource.Monotonic.markNow()
            }
        }
        save()
    }

    /** The files of [paths], with a failure narrowed down to the note it was: see [scan]. */
    private suspend fun readSafely(
        paths: List<String>,
        stamps: Map<String, FileStamp>,
        read: suspend (List<String>) -> Map<String, String>,
    ): Map<String, String> = try {
        read(paths)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (paths.size > 1) {
            paths.flatMap { readSafely(listOf(it), stamps, read).entries }.associate { it.key to it.value }
        } else {
            println("[DEBUG_LOG][NoteIndex] Reading ${paths.single()} failed: $t")
            for (path in paths) record(path, stamps[path], NoteFacts(leftOut = LeftOut.Unreadable))
            emptyMap()
        }
    }

    private fun scanned(path: String, text: String): NoteFacts = try {
        factsOf(path, text)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        println("[DEBUG_LOG][NoteIndex] Scanning $path failed: $t")
        NoteFacts(leftOut = LeftOut.Unreadable)
    }

    /** [paths] in groups that can be in memory together: a few small notes, or one big one. */
    fun groupsOf(paths: List<String>, stamps: Map<String, FileStamp>): Sequence<List<String>> = sequence {
        val group = mutableListOf<String>()
        var bytes = 0L
        for (path in paths) {
            val size = stamps[path]?.size ?: 0L
            if (group.isNotEmpty() && (group.size >= GROUP_NOTES || bytes + size > budgets.maxNoteBytes)) {
                yield(group.toList())
                group.clear()
                bytes = 0
            }
            group += path
            bytes += size
        }
        if (group.isNotEmpty()) yield(group.toList())
    }

    /**
     * Brings the whole index up to date with [store] and keeps it: what reads the notes with the app
     * closed needs, and all it holds of them afterwards is the facts.
     */
    suspend fun refresh(store: DocumentStore, on: CoroutineDispatcher = Dispatchers.Default) {
        load()
        // Out of reach: what is known stays what is known.
        val stamps = store.stamps() ?: return
        retain(stamps.keys)
        scan(staleAmong(stamps), stamps, on, read = { store.readSeveral(it) })
    }

    @Serializable
    private class KeptNote(val path: String, val stamp: FileStamp? = null, val facts: NoteFacts)

    private companion object {
        /** Change it with what [factsOf] finds, and every note is scanned again. */
        const val HEADER = "korgnote-index-2"

        const val GROUP_NOTES = 8
        val PROGRESS_EVERY: Duration = 2.seconds
        val SAVE_EVERY: Duration = 30.seconds

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Without the path each entry repeats, which [load] puts back: it is most of an entry on disk. */
        fun NoteFacts.bare() = copy(
            agenda = agenda.map { it.copy(file = "") },
            todos = todos.map { it.copy(file = "") },
            clocked = clocked.map { it.copy(file = "") },
        )

        fun NoteFacts.at(path: String) = copy(
            agenda = agenda.map { it.copy(file = path) },
            todos = todos.map { it.copy(file = path) },
            clocked = clocked.map { it.copy(file = path) },
        )
    }
}
