package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.fs.Folder
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.index.LeftOut
import com.korgnote.common.index.NoteBudgets
import com.korgnote.common.index.NoteFacts
import com.korgnote.common.index.NoteIndex
import com.korgnote.common.index.SizedCache
import com.korgnote.common.index.factsOf
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.edit.lastClocked
import com.korgnote.common.org.edit.runningClocks
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.sync.conflictsIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The notes: which there are, what is known about each, and those at hand.
 *
 * How many there are, and how big, is anybody's guess, so nothing here depends on them all fitting in
 * memory. Three things are kept, each for what needs it:
 *
 * - The [facts] about every note, which are small and are all that the calendar, the pickers, completion
 *   and the backlinks show. They come from [NoteIndex], which keeps them between runs, so they are there
 *   as soon as the app opens and only a note whose file moved is read to bring them up to date.
 * - The texts last read, which search goes through first, up to [NoteBudgets.texts]. A library that fits
 *   is kept whole, and searched as if there were no limit at all.
 * - The notes last parsed, up to [NoteBudgets.parses], for what edits them. The open one always stays.
 *
 * All of it belongs to one thread, the main one in the app: only reading and scanning go elsewhere.
 */
class NoteLibrary(
    private val store: DocumentStore,
    private val index: NoteIndex = NoteIndex(),
    private val budgets: NoteBudgets = NoteBudgets.forThisDevice(),
    /** Where notes are parsed and scanned, away from the screen. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    /** A text, with the stamp of the file it came from; null when it is on its way to the file. */
    private class KeptText(val stamp: FileStamp?, val text: String)

    private val texts = SizedCache<KeptText>(budgets.texts) { it.text.length * 2L + TEXT_OVERHEAD }

    private val parses = SizedCache<OrgDocument>(budgets.parses) { it.text.length * PARSE_BYTES_PER_CHAR + TEXT_OVERHEAD }

    /** How each file looked when last listed. */
    private var stamps: Map<String, FileStamp> = emptyMap()

    /** A text saved here, and the how-manieth save it was. */
    private class Saved(val text: String, val number: Int)

    /** Texts saved here since the files were last listed, which are newer than what those may hold. */
    private val saved = mutableMapOf<String, Saved>()

    /** How many saves there have been, for [list] to be told which of them have reached the files. */
    var savesSoFar = 0
        private set

    /** Those of [saved] that [facts] has not caught up with; see [catchUp]. */
    private val toIndex = mutableMapOf<String, OrgDocument>()

    /** Notes to be read again, whatever their files look like. */
    private val readAgain = mutableSetOf<String>()

    /** What is known about each note: all that what shows the notes as a whole needs. */
    val facts: Map<String, NoteFacts> get() = index.facts

    /** How many notes are not in [facts], there being no room for any more on this device. */
    val noRoomFor: Int get() = index.noRoomFor

    operator fun contains(path: String): Boolean = path in paths || path in saved

    /** Whether [path] is too big for this device to hold in memory: it is listed, and no more. */
    fun isTooLarge(path: String): Boolean = (stamps[path]?.size ?: 0) > budgets.maxNoteBytes

    /** [path]'s text if it is at hand, and up to date as far as is known. */
    fun textAtHand(path: String): String? = saved[path]?.text ?: current(path)?.text

    private fun current(path: String): KeptText? =
        texts.peek(path)?.takeIf { it.stamp != null && it.stamp == stamps[path] }

    /** Every note whose text is at hand, for going through what is in memory first. */
    fun atHand(): Map<String, String> =
        paths.mapNotNull { path -> textAtHand(path)?.let { path to it } }.toMap()

    /** Every note whose text would have to be read, and can be. */
    fun notAtHand(): List<String> =
        paths.filter { textAtHand(it) == null && !isTooLarge(it) }.sorted()

    /** The texts of [group], read to go through and let go of again: no more than a few at a time. */
    suspend fun readThrough(group: List<String>): Map<String, String> = store.readSeveral(group)

    /** [paths], in groups that can be in memory together. */
    fun inGroups(paths: List<String>): Sequence<List<String>> = index.groupsOf(paths, stamps)

    /** [path]'s text, read if it has to be. Null when it cannot be, or is too big to be: see [isTooLarge]. */
    suspend fun text(path: String): String? {
        textAtHand(path)?.let { text ->
            texts[path]
            return text
        }
        if (isTooLarge(path)) return null
        return read(path)
    }

    /**
     * [path]'s text as its file now holds it, whatever is at hand. If it cannot be reached, what the
     * storage last saw of it, which is shown but not taken for what the file holds.
     */
    suspend fun read(path: String): String? {
        val stamp = stamps[path]
        val text = store.readSeveral(listOf(path))[path]
        if (text != null) keep(path, stamp, text)
        // Saved here while that was read, it is newer.
        return saved[path]?.text ?: text ?: store.read(path)
    }

    /**
     * Whether an offset from what is on screen is good in [text], just got for [path], which is what an
     * edit from outside the open note goes by. With the text kept since it was shown, it always is: what
     * came in since is merged with the edit. With the text read for the edit, what came in is in it, and
     * the offset may be on another heading altogether.
     */
    fun isAsShown(path: String, text: String): Boolean =
        path in saved || path in toIndex || shown[path]?.isFrom(text) != false

    /** The [facts] to put on screen, which are from then on what [isAsShown] goes by. */
    fun factsToShow(): Map<String, NoteFacts> = facts.also { shown = it }

    private var shown: Map<String, NoteFacts> = emptyMap()

    /** Brings what is known about [path] up to date with [text], for [isAsShown] to hold again. */
    suspend fun catchUpWith(path: String, text: String) {
        val facts = withContext(background) { factsOf(path, text) }
        index.record(path, stamps[path], facts)
    }

    /** Takes note of [text], read from [path]'s file when it looked like [stamp]. */
    private fun keep(path: String, stamp: FileStamp?, text: String) {
        if (path !in saved) texts[path] = KeptText(stamp, text)
    }

    /** [path] parsed, read first if it has to be. */
    suspend fun document(path: String, parseOn: CoroutineDispatcher = background): OrgDocument? {
        val text = text(path) ?: return null
        parses[path]?.takeIf { it.text == text }?.let { return it }
        return withContext(parseOn) { text.toOrgDocument() }.also { parses[path] = it }
    }

    /** [path] parsed, if it is at hand: the open note is, once its text has arrived. */
    fun parsedAtHand(path: String): OrgDocument? = parses.peek(path)?.takeIf { it.text == textAtHand(path) }

    /**
     * Whether every note has at least been read once: what a plan for the whole library, such as the
     * reminders, has to wait for. A first scan takes minutes, and a plan made from a part of the notes
     * would cancel the reminders of the rest.
     */
    val everyNoteKnown: Boolean get() = loaded && (scanFinished || index.knowsAll(stamps.keys))

    /**
     * Whether the scan of what was last listed went through all of it: a note it could not read, which
     * may stay that way, must not hold up everything planned from the others.
     */
    private var scanFinished = false

    /** The note that has to stay at hand, whatever else is pushed out: the one on screen. */
    fun keepAtHand(path: String?) {
        texts.pinned = path
        parses.pinned = path
    }

    /** The file tree, wrapped in state so the drawer recomposes when it changes. */
    var tree by mutableStateOf(Folder(name = "Documents", path = "", children = emptyList()))
        private set

    /** Every note's path, as state, for what has to redraw when a note comes or goes. */
    var paths by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Notes with conflict copies beside them, each with its copies newest first. Read off the paths
     * rather than remembered, so it holds across restarts and devices, covers Syncthing's copies,
     * and clears itself when a copy is deleted. See [conflictsIn].
     */
    var conflicts by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    /**
     * Counts every change to the notes' texts, as state, for what shows a note's text without
     * holding it — the comparison of a note with its conflict copy.
     */
    var revision by mutableStateOf(0)
        private set

    /** How many notes are left to scan, while a big library is being scanned; see [scan]. */
    var leftToScan by mutableStateOf(0)
        private set

    /**
     * Set once the notes have been listed. Until then what is known may be from the last run, or there
     * may be nothing because nothing has arrived rather than because there are no notes, and nothing
     * should act on that.
     */
    var loaded = false
        private set

    /** One listing at a time: one that began earlier is older, and must not land after a later one. */
    private val listing = Mutex()

    /** The scan that should be going on; one that finds it is not this one stops. */
    private var scanNumber = 0

    /**
     * Whether what is known is still short of all the notes, so that nothing dated on a day says little:
     * they have not been listed and nothing was kept from the last run, or they are being scanned.
     */
    val stillReading: Boolean get() = leftToScan > 0 || (!loaded && !datedKnown)

    /** Set once the part of the index that the calendar needs has been read. */
    private var datedKnown by mutableStateOf(false)

    /** Reads what the calendar needs from what was kept from the last run: the first thing done. */
    suspend fun loadDated() = listing.withLock {
        if (loaded) return@withLock
        index.loadDated()
        datedKnown = facts.isNotEmpty()
    }

    /** Shows the rest of what was kept from the last run, until the notes have been listed. */
    suspend fun loadIndex() = listing.withLock {
        if (loaded) return@withLock
        index.load()
        show(index.facts.keys)
        revision++
    }

    /**
     * Lists the notes again, to see which there are and which moved: all it takes to show them, and
     * cheap, since it reads none. [only] are read again in any case. What the listing finds out of
     * date is brought up to date by [scan], which the caller is to start: one under way stops.
     *
     * @param landed how many saves the caller has seen reach the files: what is there now is as new as
     * those. A later one may still be on its way, and its text is still the note's.
     */
    suspend fun list(only: Set<String>? = null, landed: Int = savesSoFar) = listing.withLock {
        scanNumber++
        scanFinished = false
        // All of what was kept, or every note it holds would count as never having been scanned.
        index.load()
        saved.entries.removeAll { it.value.number <= landed }
        val listed = store.stamps()
        if (listed == null) {
            // Out of reach: what was known from the last run is all there is, and it stays.
            show(index.facts.keys + saved.keys)
            revision++
            return@withLock
        }
        stamps = listed
        // Saved while that was listed, and maybe not written yet; anything else not there is gone.
        toIndex.keys.retainAll(listed.keys + saved.keys)
        index.retain(listed.keys + saved.keys)
        texts.retain(listed.keys + saved.keys)
        parses.retain(listed.keys + saved.keys)
        only?.let { readAgain += it.filter { path -> path in listed } }
        for (path in readAgain) texts.remove(path)
        show(listed.keys + saved.keys)
        loaded = true
        revision++
    }

    private fun show(listed: Set<String>) {
        if (listed == paths) return
        paths = listed
        val all = listed.toList()
        tree = TreeUtils.buildFromPaths(all, rootName = "Documents")
        conflicts = conflictsIn(all)
    }

    /** How many bytes of notes [scan] has to read. */
    fun bytesToScan(): Long = stale().sumOf { stamps[it]?.size ?: 0L }

    private fun stale(): List<String> =
        (readAgain.filter { it in stamps } + index.staleAmong(stamps)).distinct().filter { it !in toIndex && it !in saved }

    /**
     * Brings [facts] up to date with what the last [list] found, reading only what moved. A later scan
     * takes over from this one, which stops, what it found so far being kept.
     *
     * @param progress called every so often, to show what is known so far.
     */
    suspend fun scan(parseOn: CoroutineDispatcher = background, progress: suspend () -> Unit = {}) {
        val number = ++scanNumber
        val stale = stale()
        if (stale.isEmpty()) {
            leftToScan = 0
            scanFinished = true
            return
        }
        val listed = stamps
        leftToScan = stale.size
        try {
            index.scan(
                stale,
                listed,
                parseOn,
                read = { group ->
                    val found = mutableMapOf<String, String>()
                    val toRead = group.filter { path ->
                        val text = textAtHand(path)
                        if (text != null) found[path] = text
                        text == null
                    }
                    if (toRead.isNotEmpty()) {
                        for ((path, text) in store.readSeveral(toRead)) {
                            found[path] = text
                            // Without pushing anything out: the smallest notes come first, and with them
                            // the most notes search will find at hand.
                            if (path == texts.pinned || texts.hasRoomFor(text.length * 2L + TEXT_OVERHEAD)) keep(path, listed[path], text)
                        }
                    }
                    leftToScan = (leftToScan - group.size).coerceAtLeast(0)
                    found
                },
                skip = { it in toIndex || it in saved },
                keepGoing = { number == scanNumber },
                progress = progress,
            )
            if (number == scanNumber) {
                scanFinished = true
                // Only now: a scan that stops short leaves these for the next.
                readAgain.removeAll(stale.toSet())
            }
        } finally {
            if (number == scanNumber) leftToScan = 0
        }
    }

    /**
     * Reads the notes not at hand, as many as there is room for and the latest first, so that a search
     * finds them there. What a run that already knows the [facts] has yet to do.
     */
    suspend fun fillUp() {
        val number = scanNumber
        val listed = stamps
        val wanted = notAtHand().sortedByDescending { listed[it]?.modified ?: 0L }
        for (group in inGroups(wanted)) {
            if (number != scanNumber) return
            val bytes = group.sumOf { (stamps[it]?.size ?: 0L) * 2 + TEXT_OVERHEAD }
            if (!texts.hasRoomFor(bytes)) return
            for ((path, text) in store.readSeveral(group)) keep(path, listed[path], text)
        }
    }

    /** [list] and [scan] in one, with the texts read up to what there is room for. */
    suspend fun reload(parseOn: CoroutineDispatcher = background, only: Set<String>? = null) {
        list(only)
        scan(parseOn)
        fillUp()
    }

    /** Replaces one note with [document], as just saved; [catchUp] brings its facts up to date. */
    fun put(path: String, document: OrgDocument) {
        saved[path] = Saved(document.text, ++savesSoFar)
        toIndex[path] = document
        texts[path] = KeptText(null, document.text)
        parses[path] = document
        // At once, unlike the rest of the facts: the bar shows them, and the next clock stops this one.
        val had = index.factsAbout(path)
        if (document.text.contains("CLOCK:") || !had?.running.isNullOrEmpty() || !had?.lastClocked.isNullOrEmpty()) {
            index.recordClocks(path, document.runningClocks(), document.lastClocked())
        }
        revision++
    }

    /** Brings [facts] up to date with what [put] was given, which takes reading nothing. */
    suspend fun catchUp(parseOn: CoroutineDispatcher = background) {
        if (toIndex.isEmpty()) return
        val waiting = toIndex.toMap()
        val found = withContext(parseOn) {
            waiting.mapValues { (path, document) ->
                try {
                    factsOf(path, document.text, document)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("[DEBUG_LOG][NoteLibrary] Scanning $path failed: $t")
                    NoteFacts(leftOut = LeftOut.Unreadable)
                }
            }
        }
        for ((path, facts) in found) {
            // Saved again meanwhile, deleted, or renamed: these are not about the note as it now is.
            if (toIndex[path] !== waiting[path]) continue
            // No stamp: the file may not have been written yet, and the next listing has it read once.
            index.record(path, null, facts)
            toIndex.remove(path)
        }
    }

    /** Lets go of everything that can be read again: these notes are no longer on screen. */
    fun letGo() {
        scanNumber++
        texts.clear()
        parses.clear()
    }

    /** Writes the facts to where they outlast this run. */
    suspend fun saveIndex() = index.save()

    private companion object {
        /** What keeping a text costs on top of its characters, so that many tiny ones still add up. */
        const val TEXT_OVERHEAD = 128L

        /** What a parsed note takes on the heap for each character of its text: measured at 2.5 to 3.5. */
        const val PARSE_BYTES_PER_CHAR = 4L
    }
}
