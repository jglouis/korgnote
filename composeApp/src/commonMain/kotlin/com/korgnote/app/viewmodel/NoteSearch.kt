package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.searchOrg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                else -> null
            }
        }
        return if (children.isEmpty()) null else Folder(name = folder.name, path = folder.path, children = children)
    }

    // --- Search over the whole replica ---

    var query by mutableStateOf("")
        private set

    var results by mutableStateOf<List<SearchHit>>(emptyList())
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
        this.query = query
        if (query.isBlank()) results = emptyList() else refreshSearch()
    }

    /** In flight or pending; a new keystroke cancels it rather than queueing another. */
    private var searchJob: Job? = null

    /**
     * Runs the search over text already in memory.
     *
     * Synchronous on purpose: this is a linear scan of a few megabytes, which is milliseconds, and
     * a coroutine would only make the results arrive a frame after the keystroke that asked for
     * them. If a corpus ever makes this visible, the fix is an index, not a thread.
     */
    private fun refreshSearch() {
        results = searchOrg(library.texts(), query)
    }

    /** Opens the file a result is in, at the element containing it. */
    fun openHit(hit: SearchHit) = open(hit.path, hit.offset)

    /** Reruns both against the notes as they now are. */
    fun refresh() {
        refreshFilteredTree()
        refreshSearch()
    }

    private companion object {
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
