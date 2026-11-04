package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.sync.conflictsIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.Node as SharedNode

/**
 * The notes as last read from the replica, parsed, and the tree the drawer shows them in.
 *
 * Everything else the app shows about the notes as a whole — search, links, the agenda, completion
 * — is computed from this, and rebuilt by [NotesViewModel] whenever it changes.
 */
class NoteLibrary(private val store: DocumentStore) {

    // Parsed org documents keyed by path, as the replica holds them.
    private val parsed: MutableMap<String, OrgDocument> = mutableMapOf()

    val documents: Map<String, OrgDocument> get() = parsed

    operator fun get(path: String): OrgDocument? = parsed[path]

    operator fun contains(path: String): Boolean = path in parsed

    /** Every note's text by path, which is what the corpus-wide engines in `shared` take. */
    fun texts(): Map<String, String> = parsed.mapValues { (_, doc) -> doc.text }

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

    /**
     * Set once the replica has been read. Until then the notes are empty because nothing has
     * arrived, not because there are none, and nothing should act on that emptiness.
     */
    var loaded = false
        private set

    /**
     * Rereads the notes from the replica, on [parseOn]: every one, or with [only] just those, and any
     * not read yet. A note whose text has not changed keeps its parse: a sync usually brings one note,
     * and parsing all of them again took seconds on a phone, with the screen frozen while the main
     * thread did it.
     *
     * Each note's headings are read with its own keywords, or Org's own `TODO` and `DONE` when it
     * declares none, as Org reads a file without a `#+TODO:` line: another note's declarations are
     * that note's alone, so `EVENT` or `FINISHED` is a keyword only where it is declared.
     */
    suspend fun reload(parseOn: CoroutineDispatcher = Dispatchers.Default, only: Set<String>? = null) = reloading.withLock {
        val changedMeanwhile = mutableMapOf<String, OrgDocument>()
        changedDuringReload = changedMeanwhile
        try {
            val previous = parsed.toMap()
            val paths = store.paths()
            val fresh = withContext(parseOn) {
                buildMap {
                    for (path in paths) {
                        val kept = previous[path]
                        if (only != null && path !in only && kept != null) {
                            put(path, kept)
                        } else {
                            val text = store.read(path) ?: continue
                            put(path, kept?.takeIf { it.text == text } ?: text.toOrgDocument())
                        }
                    }
                }
            }
            parsed.clear()
            parsed.putAll(fresh)
            parsed.putAll(changedMeanwhile)
            this.paths = parsed.keys.toSet()
            val allPaths = (paths + changedMeanwhile.keys).distinct()
            tree = mapRootToUiFolder(TreeUtils.buildFromPaths(allPaths, rootName = "Documents"))
            conflicts = conflictsIn(allPaths)
            loaded = true
            revision++
        } finally {
            changedDuringReload = null
        }
    }

    /** One reload at a time: one that began earlier holds older text, and must not land after a later one. */
    private val reloading = Mutex()

    /**
     * What [put] was given during the reload under way, if one is: that reload may have read the
     * note before the change reached the replica, and must not undo it.
     */
    private var changedDuringReload: MutableMap<String, OrgDocument>? = null

    /** Replaces one note with [document], as just saved; the tree is unchanged since the path is. */
    fun put(path: String, document: OrgDocument) {
        parsed[path] = document
        changedDuringReload?.put(path, document)
        revision++
    }

    private fun mapSharedToUi(node: SharedNode): Node {
        return when (node) {
            is SharedFile -> File(node.name, node.path)
            is SharedFolder -> Folder(
                name = node.name,
                path = node.path,
                children = node.children.map { mapSharedToUi(it) }
            )
        }
    }

    private fun mapRootToUiFolder(shared: SharedFolder): Folder = mapSharedToUi(shared) as Folder
}
