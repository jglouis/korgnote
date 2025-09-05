package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.org.*
import kotlinx.datetime.LocalDate
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.Node as SharedNode

/**
 * Simple ViewModel that holds a file tree and uses a repository to fetch org contents.
 */

data class ScheduledEntry(
    val title: String,
    val date: LocalDate,
    val fileName: String,
    val line: String
)

class FileTreeViewModel(private val repository: OrgRepository) : ViewModel() {
    // Exposed error message when network/server cannot be reached
    var lastErrorMessage by mutableStateOf("")
        private set
    private var currentFile: String? = null



    // File tree populated from server; wrapped in state so UI recomposes
    var tree by mutableStateOf(
        Folder(
            name = "Documents",
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
                is File -> Folder(name = tree.name, children = listOf(filtered))
                null -> Folder(name = tree.name, children = emptyList())
                else -> Folder(name = tree.name, children = emptyList())
            }
        }

    private fun filterNode(node: Node, queryLower: String): Node? {
        return when (node) {
            is File -> if (node.name.lowercase().contains(queryLower)) node else null
            is Folder -> {
                val childMatches = node.children.mapNotNull { filterNode(it, queryLower) }
                val selfMatches = node.name.lowercase().contains(queryLower)
                if (selfMatches || childMatches.isNotEmpty()) Folder(
                    node.name,
                    childMatches
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

    // Selected node for structural rendering in UI
    var selectedOrgNode: OrgNode? by mutableStateOf(null)
        private set

    // Map of scheduled entries by LocalDate, computed from all contents
    var scheduledByDate by mutableStateOf(buildScheduledIndex(orgContents))

    private fun toggleNthCheckbox(doc: OrgDocument, ordinal: Int, checked: Boolean): OrgDocument? {
        val count = 0
        fun visit(node: OrgNode): Pair<OrgNode, Boolean> {
            return when (node) {
                is OrgCheckboxItem -> {
                    if (count == ordinal) {
                        node.copy(checked = checked) to true
                    } else {
                        node to false
                    }
                }
                is OrgList -> {
                    val newChildren = mutableListOf<OrgNode>()
                    var done = false
                    for (child in node.children) {
                        if (!done) {
                            val (nc, d) = visit(child)
                            newChildren.add(nc)
                            done = d
                        } else {
                            newChildren.add(child)
                        }
                    }
                    if (done) OrgList(node.bulletType, newChildren) to true else node to false
                }
                is OrgDocument -> {
                    val newChildren = mutableListOf<OrgNode>()
                    var done = false
                    for (child in node.children) {
                        if (!done) {
                            val (nc, d) = visit(child)
                            newChildren.add(nc)
                            done = d
                        } else {
                            newChildren.add(child)
                        }
                    }
                    if (done) OrgDocument(newChildren, node.metadata) to true else node to false
                }
                else -> node to false
            }
        }
        val (newDocNode, done) = visit(doc)
        return if (done) newDocNode as OrgDocument else null
    }

    fun toggleCheckboxByOrdinal(ordinal: Int, checked: Boolean) {
        val file = currentFile ?: return
        val doc = orgContents[file] ?: return
        val updated = toggleNthCheckbox(doc, ordinal, checked) ?: return
        orgContents[file] = updated
        selectedOrgNode = updated
        selectedFileContent = updated.toOrgString()
    }



    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
        val doc = orgContents[name]
        selectedOrgNode = doc
        selectedFileContent = doc?.toOrgString() ?: "* ${name}\nNo content yet."
        currentFile = name
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
                        selectedFileContent = doc.toOrgString()
                    }
                }
            }
        } catch (t: Throwable) {
            // Failure: clear previous data to avoid showing stale content
            orgContents.clear()
            tree = Folder(name = "Documents", children = emptyList())
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

    fun clearError() { lastErrorMessage = "" }

    private fun friendlyErrorMessage(t: Throwable): String {
        val msg = t.message ?: ""
        val simple = t::class.simpleName ?: "Error"
        // Generic, user-friendly phrasing
        return "Cannot reach the server. Please check Settings and your connection. ($simple: $msg)"
    }

    private fun mapSharedToUi(node: SharedNode): Node {
        return when (node) {
            is SharedFile -> File(node.name)
            is SharedFolder -> Folder(
                name = node.name,
                children = node.children.map { mapSharedToUi(it) }
            )
        }
    }

    private fun mapRootToUiFolder(shared: SharedFolder): Folder = mapSharedToUi(shared) as Folder

    private fun buildScheduledIndex(contents: Map<String, OrgDocument>): Map<LocalDate, List<ScheduledEntry>> {
        val dateRegex = Regex("<(\\d{4})-(\\d{2})-(\\d{2})")
        val result = mutableMapOf<LocalDate, MutableList<ScheduledEntry>>()
        contents.forEach { (fileName, doc) ->
            var currentTitle = ""
            doc.children.forEach { node ->
                when (node) {
                    is OrgSection -> currentTitle = node.title.trim()
                    is OrgPlanning -> if (node.planType == "SCHEDULED") {
                        val m = dateRegex.find(node.timestamp)
                        if (m != null) {
                            val y = m.groupValues[1].toInt()
                            val mo = m.groupValues[2].toInt()
                            val d = m.groupValues[3].toInt()
                            val date = LocalDate(y, mo, d)
                            val entry = ScheduledEntry(
                                title = currentTitle.ifBlank { fileName },
                                date = date,
                                fileName = fileName,
                                line = node.toOrgString()
                            )
                            result.getOrPut(date) { mutableListOf() }.add(entry)
                        }
                    }
                    else -> {}
                }
            }
        }
        return result.mapValues { (_, list) ->
            list.sortedWith(compareBy({ it.fileName }, { it.title }))
        }
    }
}
