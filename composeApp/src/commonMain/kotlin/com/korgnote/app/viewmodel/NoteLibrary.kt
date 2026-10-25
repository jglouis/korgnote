package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.complete.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.corpusTodoKeywords
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.sync.conflictsIn
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
     * Set once the replica has been read. Until then the notes are empty because nothing has
     * arrived, not because there are none, and nothing should act on that emptiness.
     */
    var loaded = false
        private set

    /**
     * The keywords a note that declares none is read with: every `#+TODO:` in the notes, standing
     * in for the editor-wide setting the app cannot see. See [corpusTodoKeywords].
     */
    var corpusKeywords: OrgTodoConfig = DEFAULT_TODO_KEYWORDS
        private set

    /** Rereads every note from the replica. */
    suspend fun reload() {
        val paths = store.paths()
        parsed.clear()
        for (path in paths) {
            store.read(path)?.let { parsed[path] = it.toOrgDocument() }
        }
        this.paths = parsed.keys.toSet()
        tree = mapRootToUiFolder(TreeUtils.buildFromPaths(paths, rootName = "Documents"))
        conflicts = conflictsIn(paths)
        loaded = true
        refreshKeywords()
    }

    /** Replaces one note with [document], as just saved; the tree is unchanged since the path is. */
    fun put(path: String, document: OrgDocument) {
        parsed[path] = document
        refreshKeywords()
    }

    private fun refreshKeywords() {
        corpusKeywords = corpusTodoKeywords(parsed.values.map { it.text })
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
