package com.korgnote.app.viewmodel

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.fs.File
import com.korgnote.common.fs.Folder
import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.DEFAULT_SEARCH_LIMIT
import com.korgnote.common.org.search.SearchQuery
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.search.searchOrgFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Searching the notes (T14), and the drawer's filter, which only looks at file names.
 *
 * Search waits for the typing to stop before scanning; see [TYPING_PAUSE].
 */
class NoteSearch(
    private val scope: CoroutineScope,
    private val library: NoteLibrary,
    /** Opens a note at a character offset in it. */
    private val open: (path: String, offset: Int) -> Unit,
    /** Where notes that had to be read are gone through. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** How many characters in memory are searched on the spot; see [refreshSearch]. */
    private val searchedAtOnce: Long = SEARCHED_AT_ONCE,
) {
    // --- Filtering the file tree ---

    // Filter query for the file tree: every word has to appear in a file's name. Only the name —
    // finding what is inside the notes is what search is for.
    var filterQuery by mutableStateOf("")
        private set

    /** The library's tree narrowed to what [filterQuery] keeps; the whole tree when nothing is typed. */
    var filteredTree by mutableStateOf(library.tree)
        private set

    /**
     * Narrows the tree at once: matching names is a walk over the tree, not a scan of the notes, so
     * there is nothing to wait for the typing to stop for.
     */
    fun onFilterQueryChanged(query: String) {
        filterQuery = query
        refreshFilteredTree()
    }

    /** Recomputed when the query or the replica changes, rather than on every recomposition. */
    private fun refreshFilteredTree() {
        val tree = library.tree
        val words = filterQuery.split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            filteredTree = tree
            return
        }
        filteredTree = filterFolder(tree, words) ?: Folder(name = tree.name, path = tree.path, children = emptyList())
    }

    /**
     * The folder with only the files whose names hold every word, and the folders holding those. A
     * folder's own name keeps nothing: a folder that matched would show every note in it, which is
     * filtering on the folder rather than on the file.
     */
    private fun filterFolder(folder: Folder, words: List<String>): Folder? {
        val children = folder.children.mapNotNull { child ->
            when (child) {
                is File -> child.takeIf { file -> words.all { file.name.contains(it, ignoreCase = true) } }
                is Folder -> filterFolder(child, words)
            }
        }
        return if (children.isEmpty()) null else Folder(name = folder.name, path = folder.path, children = children)
    }

    // --- Search over the whole replica ---

    var query by mutableStateOf("")
        private set

    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** Whether more results may still come, from notes being read; see [refreshSearch]. */
    var searching by mutableStateOf(false)
        private set

    /**
     * Records what was typed and searches once the typing stops.
     *
     * The query itself updates on the keystroke, so the field never lags; only the scan waits. It
     * waits because scanning the whole corpus costs tens of milliseconds on a laptop and several
     * times that on a phone, and doing it per character spends nearly all of it on prefixes nobody
     * asked about — "g", "gr", "gro" on the way to "groceries".
     *
     * Results already on screen stay while the next scan is pending: they are what was asked for a
     * moment ago, which is a better thing to show than a blank list that flickers on every letter.
     */
    fun onQueryChanged(query: String) {
        this.query = query
        searchJob?.cancel()
        reading?.cancel()
        searching = false
        if (query.isBlank()) {
            results = emptyList()
            return
        }
        searchJob = scope.launch {
            delay(TYPING_PAUSE)
            refreshSearch()
        }
    }

    /**
     * Searches for [query] at once, as when a saved search is run: it arrives whole, so there is no
     * typing to wait out.
     */
    fun run(query: String) {
        searchJob?.cancel()
        reading?.cancel()
        searching = false
        this.query = query
        if (query.isBlank()) results = emptyList() else refreshSearch()
    }

    /** In flight or pending; a new keystroke cancels it rather than queueing another. */
    private var searchJob: Job? = null

    /** Going through the notes that had to be read; a new search cancels it. */
    private var reading: Job? = null

    /**
     * Runs the search over the text in memory, then over the rest as it is read.
     *
     * A few megabytes in memory are scanned there and then, on purpose: that takes milliseconds, and a
     * coroutine would only make the results arrive a frame after the keystroke that asked for them.
     * With every note at hand, as it is for a library that fits, that is all there is.
     *
     * Past that, on the main thread it would hold up the screen. So what is in memory goes first, away
     * from it, and then the notes that have to be read, a few at a time and let go of again; what they
     * hold comes in below as it is found.
     */
    private fun refreshSearch() {
        reading?.cancel()
        searching = false
        val parsed = SearchQuery.parse(query)
        val atHand = library.atHand()
        val quick = atHand.values.sumOf { it.length.toLong() } <= searchedAtOnce
        // A note too big to read has no text to go through, but it can still be found by its name.
        val named = library.tooLarge().flatMap { searchOrgFile(it, "", parsed, DEFAULT_SEARCH_LIMIT, DEFAULT_TODO_KEYWORDS) }
        val first = (if (quick) searchOrg(atHand, parsed, fallback = DEFAULT_TODO_KEYWORDS) else emptyList()) + named
        // Results already on screen stay until there are some, rather than flicker away: see [onQueryChanged].
        if (quick || named.isNotEmpty()) results = first
        if (first.size >= DEFAULT_SEARCH_LIMIT || parsed.isEmpty) return
        val rest = (if (quick) emptyList() else atHand.keys.sorted()) + library.notAtHand()
        if (rest.isEmpty()) return
        searching = true
        reading = scope.launch {
            var found = first
            try {
                for (group in library.inGroups(rest)) {
                    val hits = try {
                        val texts = group.mapNotNull { path -> atHand[path]?.let { path to it } }.toMap() +
                            library.readThrough(group.filter { it !in atHand })
                        withContext(background) {
                            texts.keys.sorted().flatMap { path ->
                                searchOrgFile(path, texts.getValue(path), parsed, DEFAULT_SEARCH_LIMIT, DEFAULT_TODO_KEYWORDS)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // Too much for this device together, say: the others can still be gone through.
                        println("[DEBUG_LOG][NoteSearch] Going through $group failed: $t")
                        continue
                    }
                    if (hits.isNotEmpty()) {
                        found = (found + hits).take(DEFAULT_SEARCH_LIMIT)
                        results = found
                    }
                    if (found.size >= DEFAULT_SEARCH_LIMIT) break
                }
                // Nothing at all, after all: what was on screen was for the search before.
                if (found.isEmpty()) results = emptyList()
            } finally {
                // A later search has taken over when this one was cancelled.
                if (currentCoroutineContext().isActive) searching = false
            }
        }
    }

    /** Opens the file a result is in, at the element containing it. */
    fun openHit(hit: SearchHit) = open(hit.path, hit.offset)

    /** Reruns both against the notes as they now are. */
    fun refresh() {
        refreshFilteredTree()
        refreshSearch()
    }

    private companion object {
        /** A few megabytes take a fraction of a second, even on a slow phone. */
        const val SEARCHED_AT_ONCE = 8L * 1024 * 1024

        /**
         * How long the typing has to stop before the corpus is scanned.
         *
         * Long enough that a run of keystrokes costs one scan rather than one each, short enough
         * that it reads as immediate: a pause this length is about where typing stops being a
         * stream of characters and becomes a word someone meant.
         */
        val TYPING_PAUSE = 200.milliseconds
    }
}
