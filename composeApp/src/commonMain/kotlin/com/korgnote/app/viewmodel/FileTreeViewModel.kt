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
        if (scope != null) {
            scope.launch {
                try {
                    repository.saveContent(file, selectedFileContent)
                    scheduledByDate = buildScheduledIndex(orgContents)
                    clearError()
                } catch (t: Throwable) {
                    lastErrorMessage = "Failed to save: ${t.message}"
                    t.printStackTrace()
                }
            }
        }
    }


    fun onFileSelected(path: String) {
        selectedFileLabel = "Selected: $path"
        val doc = orgContents[path]
        selectedOrgNode = doc
        currentFile = path
        if (doc == null) {
            selectedFileContent = "* ${path}\nNo content yet."
            return
        }
        // Fetch the file's exact raw text to edit, rather than a re-serialized version of the
        // parsed document, so saving without changes round-trips the file byte-for-byte.
        viewModelScope.launch {
            try {
                val raw = repository.fetchRawContent(path)
                if (currentFile == path) selectedFileContent = raw
            } catch (t: Throwable) {
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
            orgContents.putAll(data)
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
                            lastErrorMessage = "Failed to load file: ${t.message}"
                            t.printStackTrace()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
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
