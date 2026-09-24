package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.SearchQuery
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.search.searchOrgFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Searching the notes (T14), and the drawer's filter, which speaks the same query language.
 *
 * Both wait for the typing to stop before scanning; see [TYPING_PAUSE].
 */
class NoteSearch(
    private val scope: CoroutineScope,
    private val library: NoteLibrary,
    /** Opens a note at a character offset in it. */
    private val open: (path: String, offset: Int) -> Unit,
) {
    // --- Filtering the file tree ---

    // Filter query for the file tree, in the same language as search: words match the file name,
    // and a `tag:` term keeps the files that have a heading carrying it.
    var filterQuery by mutableStateOf("")
        private set

    /** The library's tree narrowed to what [filterQuery] keeps; the whole tree when nothing is typed. */
    var filteredTree by mutableStateOf(library.tree)
        private set

    fun onFilterQueryChanged(query: String) {
        filterQuery = query
        filterJob?.cancel()
        // Clearing the box is not a search, so it takes effect at once rather than after a pause
        // that would leave a filtered tree on screen for no reason.
        if (query.isBlank()) {
            filteredTree = library.tree
            return
        }
        filterJob = scope.launch {
            delay(TYPING_PAUSE)
            refreshFilteredTree()
        }
    }

    /**
     * Recomputed when the query or the replica changes, rather than on every recomposition: a
     * `tag:` term has to look inside the files, and the drawer is redrawn far more often than
     * either of those two things moves.
     */
    private fun refreshFilteredTree() {
        val tree = library.tree
        val query = SearchQuery.parse(filterQuery)
        if (query.isEmpty) {
            filteredTree = tree
            return
        }
        // Only "does this file have anything to show", so the scan stops at each file's first hit.
        // The path goes in empty on purpose: whether the *name* matches is decided by [filterNode]
        // against the node's own name, and passing the real path here would match the file twice.
        val matching = library.documents
            .filterValues { searchOrgFile("", it.text, query, limit = 1).isNotEmpty() }
            .keys
        filteredTree = when (val filtered = filterNode(tree, query, matching)) {
            is Folder -> filtered
            is File -> Folder(name = tree.name, path = tree.path, children = listOf(filtered))
            else -> Folder(name = tree.name, path = tree.path, children = emptyList())
        }
    }

    private fun filterNode(node: Node, query: SearchQuery, matchingPaths: Set<String>): Node? {
        return when (node) {
            is File -> {
                val nameMatches = query.tags.isEmpty() &&
                    query.text.all { node.name.contains(it, ignoreCase = true) }
                if (nameMatches || node.path in matchingPaths) node else null
            }

            is Folder -> {
                val childMatches = node.children.mapNotNull { filterNode(it, query, matchingPaths) }
                // A folder name says nothing about tags, so only words can keep one on its own.
                val selfMatches = query.tags.isEmpty() &&
                    query.text.all { node.name.contains(it, ignoreCase = true) }
                if (selfMatches || childMatches.isNotEmpty()) Folder(
                    name = node.name,
                    path = node.path,
                    children = childMatches
                ) else null
            }

            else -> null
        }
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

    /** In flight or pending; a new keystroke cancels it rather than queueing another. */
    private var searchJob: Job? = null
    private var filterJob: Job? = null

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
