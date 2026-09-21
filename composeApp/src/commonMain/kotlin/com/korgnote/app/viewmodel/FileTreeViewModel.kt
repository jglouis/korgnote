package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncEngine
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.korgnote.common.org.*
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.toggleCheckbox
import kotlinx.datetime.LocalDate
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.Node as SharedNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

/**
 * Simple ViewModel that holds a file tree and uses a repository to fetch org contents.
 */


class FileTreeViewModel(
    private val sync: SyncEngine,
    /**
     * The local replica. Reads and writes go here first, which is what lets the app open and edit
     * notes with the server unreachable. The network only populates it (T8); the sync engine that
     * pushes from it arrives in T10.
     */
    private val store: DocumentStore = DocumentStore(InMemoryReplicaStorage()),
) : ViewModel() {
    // Exposed error message when network/server cannot be reached
    var lastErrorMessage by mutableStateOf("")
        private set
    private var currentFile: String? = null


    // File tree populated from server; wrapped in state so UI recomposes
    var tree by mutableStateOf(
        Folder(
            name = "Documents",
            path = "",
            children = emptyList()
        )
    )

    // Filter query for the file tree
    var filterQuery by mutableStateOf("")

    // Computed filtered tree based on the filter query
    val filteredTree: Folder
        get() {
            val q = filterQuery.trim()
            if (q.isEmpty()) return tree
            return when (val filtered = filterNode(tree, q.lowercase())) {
                is Folder -> filtered
                is File -> Folder(name = tree.name, path = tree.path, children = listOf(filtered))
                null -> Folder(name = tree.name, path = tree.path, children = emptyList())
                else -> Folder(name = tree.name, path = tree.path, children = emptyList())
            }
        }

    private fun filterNode(node: Node, queryLower: String): Node? {
        return when (node) {
            is File -> if (node.name.lowercase().contains(queryLower)) node else null
            is Folder -> {
                val childMatches = node.children.mapNotNull { filterNode(it, queryLower) }
                val selfMatches = node.name.lowercase().contains(queryLower)
                if (selfMatches || childMatches.isNotEmpty()) Folder(
                    name = node.name,
                    path = node.path,
                    children = childMatches
                ) else null
            }

            else -> null
        }
    }

    // In-memory parsed org documents keyed by file name, provided by the server
    private val orgContents: MutableMap<String, OrgDocument> = mutableMapOf()

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    var selectedFileContent by mutableStateOf("")
        private set

    fun onContentChanged(newContent: String) {
        selectedFileContent = newContent
    }

    // --- Editing a single element (a heading and its own body) ---

    /**
     * Element to bring into view, set when a file is opened at a particular heading — currently from
     * a calendar entry. Consumed once, via [onScrolledToElement].
     */
    /** Guards against starting a second event stream on recomposition. */
    private var watching = false

    var scrollToElementIndex: Int? by mutableStateOf(null)
        private set

    fun onScrolledToElement() {
        scrollToElementIndex = null
    }

    /**
     * Finds the element rendering [section], or null if it is not in this text.
     *
     * The section comes from the document parsed for the calendar, while elements are cut from the
     * file's raw text, so the two are different objects describing the same heading and have to be
     * matched rather than compared.
     *
     * Preferred match is the section's source range, which T3 gives it: the element containing that
     * character is unambiguously the right one. Titles are not — two headings can share a title, and
     * one can be a suffix of another. The range is only trusted when the heading found there really
     * is the section's, because the file may have changed since the calendar indexed it; otherwise
     * this falls back to searching by title.
     */
    private fun String.elementIndexOf(section: OrgSection): Int? {
        val text = this
        val elements = orgElements()

        fun isSection(element: OrgElement): Boolean {
            if (element.isPreamble || element.level != section.level) return false
            val headingLine = element.sliceOf(text).lineSequence().firstOrNull() ?: return false
            return orgHeadingOf(headingLine).title == section.title
        }

        val offset = section.range.first
        if (!section.range.isEmpty() && offset in text.indices) {
            val atOffset = elements.indexOfFirst { offset >= it.start && offset < it.endExclusive }
            if (atOffset >= 0 && isSection(elements[atOffset])) return atOffset
        }
        return elements.indexOfFirst { isSection(it) }.takeIf { it >= 0 }
    }

    /** Index into [selectedFileContent]'s elements, or null when not editing one. */
    var editingElementIndex: Int? by mutableStateOf(null)
        private set

    /** The element's text as it is being edited. */
    var elementDraft by mutableStateOf("")
        private set

    /** Where the caret should start when the editor opens; after the stars of a new heading. */
    var elementDraftCaret by mutableStateOf(0)
        private set

    /** The element's text when editing began, used to refuse a splice if the document moved. */
    private var elementOriginal: String? = null

    /** Set when editing a heading that was just inserted, so cancelling removes it again. */
    private var textBeforeInsertion: String? = null

    fun beginElementEdit(index: Int) {
        val element = selectedFileContent.orgElements().getOrNull(index) ?: return
        val original = element.sliceOf(selectedFileContent)
        textBeforeInsertion = null
        elementOriginal = original
        elementDraft = original
        elementDraftCaret = original.length
        editingElementIndex = index
    }

    /** Adds a heading at the end of the document and opens it for editing. */
    fun addElementAtEnd() = insertElementAndEdit(selectedFileContent.length, level = 1)

    /** Adds a sibling heading directly after [index] and opens it for editing. */
    fun addElementAfter(index: Int) {
        val element = selectedFileContent.orgElements().getOrNull(index) ?: return
        insertElementAndEdit(element.endExclusive, level = element.level)
    }

    private fun insertElementAndEdit(at: Int, level: Int) {
        val previousText = selectedFileContent
        val insertion = previousText.withNewOrgElement(at, level)
        val elements = insertion.text.orgElements()
        val index = elements.indexOfFirst { it.start == insertion.elementStart }
        if (index < 0) return
        val element = elements[index]

        selectedFileContent = insertion.text
        // Nothing is written to disk yet, and cancelling must not leave an empty heading behind.
        textBeforeInsertion = previousText
        elementOriginal = element.sliceOf(insertion.text)
        elementDraft = elementOriginal.orEmpty()
        elementDraftCaret = insertion.caret - element.start
        editingElementIndex = index
    }

    fun onElementDraftChanged(newDraft: String) {
        elementDraft = newDraft
    }

    fun cancelElementEdit() {
        // A heading that was inserted for this edit never made it to disk, so discarding the edit
        // discards the heading too.
        textBeforeInsertion?.let { selectedFileContent = it }
        clearElementEdit()
    }

    /**
     * Forgets the edit without touching the document.
     *
     * Kept separate from [cancelElementEdit] deliberately: committing must not run that method's
     * undo, or saving a newly added heading would restore the text from before it was inserted.
     */
    private fun clearElementEdit() {
        textBeforeInsertion = null
        editingElementIndex = null
        elementOriginal = null
        elementDraft = ""
        elementDraftCaret = 0
    }

    /** Splices the draft back into the document and saves. Leaves the editor open if it cannot. */
    suspend fun commitElementEdit() {
        val index = editingElementIndex ?: return
        val original = elementOriginal ?: return
        val element = selectedFileContent.orgElements().getOrNull(index)
        val updated = element?.let { selectedFileContent.replaceOrgElement(it, original, elementDraft) }
        if (updated == null) {
            lastErrorMessage = "The document changed while you were editing; nothing was written."
            return
        }
        selectedFileContent = updated
        clearElementEdit()
        saveCurrentFile()
    }

    /**
     * Writes to the replica, then tries to push.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the sync engine (T10) will carry it.
     */
    suspend fun saveCurrentFile() {
        val path = currentFile ?: return
        store.save(path, selectedFileContent)
        val updatedDoc = selectedFileContent.toOrgDocument()
        orgContents[path] = updatedDoc
        selectedOrgNode = updatedDoc
        scheduledByDate = buildScheduledIndex(orgContents)
        try {
            sync.sync()
            clearError()
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            lastErrorMessage = "Saved locally; could not reach the server: ${t.message}"
            t.printStackTrace()
        }
    }

    // Selected node for structural rendering in UI
    var selectedOrgNode: OrgNode? by mutableStateOf(null)
        private set

    // Map of scheduled entries by LocalDate, computed from all contents
    var scheduledByDate by mutableStateOf(buildScheduledIndex(orgContents))

    /**
     * Ticks the checkbox whose source contains [offsetInDocument].
     *
     * The offset identifies the node rather than counting boxes: an ordinal shifts whenever anything
     * above it changes. The edit itself is a range replacement, so every other character in the file
     * — blank lines, trailing whitespace, formatting — is left exactly as it was on disk.
     */
    fun toggleCheckboxAt(offsetInDocument: Int, scope: CoroutineScope? = null) {
        val file = currentFile ?: return
        val document = selectedFileContent.toOrgDocument()
        val item = document.nodeAt<OrgCheckboxItem>(offsetInDocument) ?: return
        val updatedDoc = document.toggleCheckbox(item)
        val updatedText = updatedDoc.text
        selectedFileContent = updatedText
        orgContents[file] = updatedDoc
        selectedOrgNode = updatedDoc

        // Write locally, then push. The local write already happened above, so a failure here
        // leaves the file dirty for the next cycle rather than losing the toggle.
        scope?.launch {
            try {
                store.save(file, selectedFileContent)
                sync.sync()
                scheduledByDate = buildScheduledIndex(orgContents)
                clearError()
            } catch (t: Throwable) {
                // Rethrows if this job was cancelled, so cancellation stays cancellation
                // instead of being reported as a failure.
                currentCoroutineContext().ensureActive()
                lastErrorMessage = "Saved locally; could not reach the server: ${t.message}"
                t.printStackTrace()
            }
        }
    }


    /**
     * Opens [path], and optionally brings [scrollToHeading] into view once its text has arrived.
     *
     * The heading is resolved here rather than by the caller because the text loads asynchronously:
     * resolving it at the call site reads whichever file was open *before* this one and scrolls to
     * the wrong place, or nowhere at all.
     */
    fun onFileSelected(path: String, scrollToHeading: OrgSection? = null) {
        selectedFileLabel = "Selected: $path"
        val doc = orgContents[path]
        selectedOrgNode = doc
        currentFile = path
        scrollToElementIndex = null
        if (doc == null) {
            selectedFileContent = "* ${path}\nNo content yet."
            return
        }
        // From the replica, so opening a note works offline and does not wait on a round trip.
        // The raw text is kept rather than re-serialised from the parse tree, so saving an
        // unchanged file round-trips it byte for byte.
        viewModelScope.launch {
            val raw = store.read(path)
            if (currentFile == path && raw != null) {
                selectedFileContent = raw
                scrollToElementIndex = scrollToHeading?.let { raw.elementIndexOf(it) }
            }
        }
    }

    // Load tree and contents from server repository and update state
    /**
     * Refreshes from the server when it can be reached, then shows whatever the replica holds.
     *
     * A network failure is no longer fatal: it used to clear the tree so as not to show stale
     * content, which meant an unreachable server left the app empty. The replica *is* the content
     * now, so a failure costs freshness, not access.
     */
    /**
     * Runs a sync cycle when the server can be reached, then shows whatever the replica holds.
     *
     * A network failure is not fatal: the replica *is* the content, so being offline costs
     * freshness, not access.
     */
    suspend fun loadFromServer() {
        try {
            val report = sync.sync()
            if (report.conflicted.isNotEmpty()) {
                lastErrorMessage = "Kept your version of ${report.conflicted.joinToString(", ")}; " +
                    "the server has a different one."
            } else {
                clearError()
            }
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation instead of
            // being reported as a failure.
            currentCoroutineContext().ensureActive()
            lastErrorMessage = friendlyErrorMessage(t)
        }
        showFromReplica()
    }

    /** Applies a single change the server announced over SSE. */
    suspend fun onRemoteChange(path: String) {
        if (sync.pull(path)) showFromReplica()
    }

    /**
     * Follows the server's change stream for as long as the app runs, so an edit made in Emacs shows
     * up without a refresh.
     *
     * Reconnects with a backoff, and re-syncs after each reconnection: the stream is a hint, so a
     * gap in it costs latency, never an edit.
     */
    fun watchServer() {
        if (watching) return
        watching = true
        viewModelScope.launch {
            var backoff = 1.seconds
            while (isActive) {
                try {
                    sync.watch { showFromReplica() }
                    // A clean end means the server closed the stream; reconnect promptly.
                    backoff = 1.seconds
                } catch (t: Throwable) {
                    // Cancellation is the app shutting down, not a dropped connection.
                    currentCoroutineContext().ensureActive()
                    println("[DEBUG_LOG][FileTreeViewModel] Event stream dropped: ${t.message}")
                    // Back off so an unreachable server is not hammered, but keep trying: without
                    // this the app silently stops seeing edits made elsewhere.
                    backoff = (backoff * 2).coerceAtMost(30.seconds)
                }
                delay(backoff)
                // Catch up on whatever was missed while disconnected; the stream is only a hint.
                runCatching { sync.sync() }.onSuccess { showFromReplica() }
            }
        }
    }

    /** Rebuilds everything the UI shows out of the replica. */
    private suspend fun showFromReplica() {
        val paths = store.paths()
        orgContents.clear()
        for (path in paths) {
            store.read(path)?.let { orgContents[path] = it.toOrgDocument() }
        }
        tree = mapRootToUiFolder(TreeUtils.buildFromPaths(paths, rootName = "Documents"))
        scheduledByDate = buildScheduledIndex(orgContents)

        val current = currentFile
        if (current == null) {
            val first = paths.minOrNull()
            if (first != null) {
                onFileSelected(first)
            } else {
                selectedOrgNode = null
                selectedFileContent = ""
                selectedFileLabel = "No file selected"
            }
        } else {
            selectedOrgNode = orgContents[current]
            store.read(current)?.let { selectedFileContent = it }
        }
    }

    fun clearError() {
        lastErrorMessage = ""
    }

    private fun friendlyErrorMessage(t: Throwable): String {
        val msg = t.message ?: ""
        val simple = t::class.simpleName ?: "Error"
        // Generic, user-friendly phrasing
        return "Cannot reach the server. Please check Settings and your connection. ($simple: $msg)"
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

    private fun buildScheduledIndex(contents: Map<String, OrgDocument>): Map<LocalDate, List<Pair<String, OrgNode>>> {
        val result = mutableMapOf<LocalDate, MutableList<Pair<String, OrgNode>>>()

        // A planning line is a child of the heading it was written under, so the walk carries the
        // enclosing section down with it instead of guessing from document order. Headings nest, so
        // a SCHEDULED under a sub-heading is attributed to that sub-heading, not to its parent.
        fun visit(fileName: String, node: OrgNode, enclosing: OrgSection?) {
            val section = node as? OrgSection ?: enclosing
            if (node is OrgPlanning && node.planType == "SCHEDULED" && section != null) {
                OrgSyntax.timestampDate.find(node.timestamp)?.let { m ->
                    val date = LocalDate(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                    )
                    result.getOrPut(date) { mutableListOf() }.add(fileName to section)
                }
            }
            node.children.forEach { visit(fileName, it, section) }
        }

        contents.forEach { (fileName, doc) -> visit(fileName, doc, null) }

        return result.mapValues { (_, list) ->
            list.map { it.first to (it.second ) }
        }
    }
}
