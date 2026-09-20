package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.korgnote.common.org.*
import kotlinx.datetime.LocalDate
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.Node as SharedNode

/**
 * Simple ViewModel that holds a file tree and uses a repository to fetch org contents.
 */


class FileTreeViewModel(private val repository: OrgRepository) : ViewModel() {
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

    suspend fun saveCurrentFile() {
        val path = currentFile ?: return
        try {
            repository.saveContent(path, selectedFileContent)
            // Re-parse the saved content into orgContents map
            val updatedDoc = selectedFileContent.toOrgDocument()
            orgContents[path] = updatedDoc
            selectedOrgNode = updatedDoc
            scheduledByDate = buildScheduledIndex(orgContents)
            clearError()
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            lastErrorMessage = "Failed to save: ${t.message}"
            t.printStackTrace()
        }
    }

    // Selected node for structural rendering in UI
    var selectedOrgNode: OrgNode? by mutableStateOf(null)
        private set

    // Map of scheduled entries by LocalDate, computed from all contents
    var scheduledByDate by mutableStateOf(buildScheduledIndex(orgContents))

    fun toggleCheckboxByOrdinal(ordinal: Int, checked: Boolean, scope: CoroutineScope? = null) {
        val file = currentFile ?: return
        // Patch only the toggled checkbox's marker in the raw text; every other character in the
        // file (blank lines, trailing whitespace, formatting) is left exactly as it was on disk.
        val updatedText = selectedFileContent.toggleNthCheckbox(ordinal, checked) ?: return
        selectedFileContent = updatedText
        val updatedDoc = updatedText.toOrgDocument()
        orgContents[file] = updatedDoc
        selectedOrgNode = updatedDoc

        // Persist change to server
        scope?.launch {
            try {
                repository.saveContent(file, selectedFileContent)
                scheduledByDate = buildScheduledIndex(orgContents)
                clearError()
            } catch (t: Throwable) {
                // Rethrows if this job was cancelled, so cancellation stays cancellation
                // instead of being reported as a failure.
                currentCoroutineContext().ensureActive()
                lastErrorMessage = "Failed to save: ${t.message}"
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
        // Fetch the file's exact raw text to edit, rather than a re-serialized version of the
        // parsed document, so saving without changes round-trips the file byte-for-byte.
        viewModelScope.launch {
            try {
                val raw = repository.fetchRawContent(path)
                if (currentFile == path) {
                    selectedFileContent = raw
                    scrollToElementIndex = scrollToHeading?.let { raw.elementIndexOf(it) }
                }
            } catch (t: Throwable) {
                // Rethrows if this job was cancelled, so cancellation stays cancellation
                // instead of being reported as a failure.
                currentCoroutineContext().ensureActive()
                lastErrorMessage = "Failed to load file: ${t.message}"
                t.printStackTrace()
            }
        }
    }

    // Load tree and contents from server repository and update state
    suspend fun loadFromServer() {
        try {
            val sharedTree = repository.fetchTree()
            val data = repository.fetchAllContents()
            orgContents.clear()
            orgContents.putAll(data.mapValues { (_, text) -> text.toOrgDocument() })
            tree = mapRootToUiFolder(sharedTree)
            scheduledByDate = buildScheduledIndex(orgContents)
            // clear error on success
            clearError()
            if (currentFile == null) {
                val first = data.keys.minOrNull()
                if (first != null) onFileSelected(first) else {
                    selectedOrgNode = null
                    selectedFileContent = ""
                }
            } else {
                val current = currentFile
                if (current != null) {
                    val doc = orgContents[current]
                    if (doc != null) {
                        selectedOrgNode = doc
                        try {
                            val raw = repository.fetchRawContent(current)
                            if (currentFile == current) selectedFileContent = raw
                        } catch (t: Throwable) {
                            // Rethrows if this job was cancelled, so cancellation stays cancellation
                            // instead of being reported as a failure.
                            currentCoroutineContext().ensureActive()
                            lastErrorMessage = "Failed to load file: ${t.message}"
                            t.printStackTrace()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            // Failure: clear previous data to avoid showing stale content
            orgContents.clear()
            tree = Folder(name = "Documents", path = "", children = emptyList())
            scheduledByDate = buildScheduledIndex(orgContents)
            currentFile = null
            selectedFileLabel = "No file selected"
            selectedFileContent = ""
            // Surface a friendly message to the UI
            lastErrorMessage = friendlyErrorMessage(t)
            println("[DEBUG_LOG][FileTreeViewModel] Failed to load from server: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
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
        val dateRegex = Regex("<(\\d{4})-(\\d{2})-(\\d{2})")
        val result = mutableMapOf<LocalDate, MutableList<Pair<String, OrgNode>>>()
        contents.forEach { (fileName, doc) ->
            var currentSection: OrgNode? = null
            doc.children.forEach { node ->
                when (node) {
                    is OrgSection -> currentSection = node
                    is OrgPlanning -> if (node.planType == "SCHEDULED") {
                        val m = dateRegex.find(node.timestamp)
                        if (m != null) {
                            val y = m.groupValues[1].toInt()
                            val mo = m.groupValues[2].toInt()
                            val d = m.groupValues[3].toInt()
                            val date = LocalDate(y, mo, d)
                            if (currentSection != null) {
                                result.getOrPut(date) { mutableListOf() }.add(fileName to currentSection)
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
        return result.mapValues { (_, list) ->
            list.map { it.first to (it.second ) }
        }
    }
}
