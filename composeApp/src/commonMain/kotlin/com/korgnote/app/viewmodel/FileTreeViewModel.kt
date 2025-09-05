package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Node as SharedNode
import com.korgnote.common.org.toOrgString
import kotlinx.datetime.LocalDate
import kotlin.collections.iterator

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

    // Keep parsed org contents for structural rendering
    private var parsedOrgContents: Map<String, com.korgnote.common.org.OrgDocument> = emptyMap()

    // For mapping checkbox ordinal -> line index in original text
    private var checkboxLineIndices: List<Int> = emptyList()

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

    // Simple in-memory org-mode contents keyed by file name. Will be replaced by server data.
    private val orgContents: MutableMap<String, String> = mutableMapOf()

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    var selectedFileContent by mutableStateOf("")
        private set

    // Selected node for structural rendering in UI
    var selectedOrgNode: com.korgnote.common.org.OrgNode? by mutableStateOf(null)
        private set

    // Map of scheduled entries by LocalDate, computed from all contents
    var scheduledByDate by mutableStateOf(buildScheduledIndex(orgContents))

    fun toggleCheckboxAt(lineIndex: Int, checked: Boolean) {
        val file = currentFile ?: return
        val content = orgContents[file] ?: return
        val lines = content.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return
        val line = lines[lineIndex]
        val regex = Regex("^(\\s*)- \\[([ xX])](.*)")
        val m = regex.find(line) ?: return
        val leading = m.groupValues[1]
        val rest = m.groupValues[3]
        val newMark = if (checked) "X" else " "
        val newLine = buildString {
            append(leading)
            append("- [")
            append(newMark)
            append("]")
            append(rest)
        }
        lines[lineIndex] = newLine
        val updated = lines.joinToString("\n")
        orgContents[file] = updated
        selectedFileContent = updated
        // After toggling, recompute checkbox mapping and refresh selectedOrgNode fallback
        rebuildCheckboxLineIndices()
        selectedOrgNode = parsedOrgContents[file] ?: com.korgnote.common.org.OrgRaw(updated)
    }

    private fun rebuildCheckboxLineIndices() {
        val text = selectedFileContent
        if (text.isBlank()) {
            checkboxLineIndices = emptyList()
            return
        }
        val re = Regex("^\\s*- \\[([ xX])].*$")
        val list = mutableListOf<Int>()
        text.split('\n').forEachIndexed { idx, line -> if (re.containsMatchIn(line)) list.add(idx) }
        checkboxLineIndices = list
    }

    fun toggleCheckboxByOrdinal(ordinal: Int, checked: Boolean) {
        val idx = checkboxLineIndices.getOrNull(ordinal) ?: return
        toggleCheckboxAt(idx, checked)
    }

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
        selectedFileContent = orgContents[name] ?: "* ${name}\nNo content yet."
        selectedOrgNode = parsedOrgContents[name] ?: com.korgnote.common.org.OrgRaw(
            selectedFileContent
        )
        rebuildCheckboxLineIndices()
        currentFile = name
    }

    // Load tree and contents from server repository and update state
    suspend fun loadFromServer() {
        try {
            val sharedTree = repository.fetchTree()
            val data = repository.fetchAllContents()
            // Save parsed documents for structural UI
            parsedOrgContents = data
            // Convert parsed OrgDocument values to string for current UI and indexing
            val stringData: Map<String, String> = data.mapValues { (_, doc) -> doc.toOrgString() }
            orgContents.clear()
            orgContents.putAll(stringData)
            tree = mapRootToUiFolder(sharedTree)
            scheduledByDate = buildScheduledIndex(orgContents)
            // clear error on success
            clearError()
            if (currentFile == null) {
                val first = data.keys.minOrNull()
                if (first != null) onFileSelected(first) else {
                    selectedOrgNode = null
                    checkboxLineIndices = emptyList()
                }
            } else {
                currentFile?.let { current ->
                    selectedFileContent = orgContents[current] ?: selectedFileContent
                    selectedOrgNode = parsedOrgContents[current] ?: selectedOrgNode
                    rebuildCheckboxLineIndices()
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

    private fun buildScheduledIndex(contents: Map<String, String>): Map<LocalDate, List<ScheduledEntry>> {
        val regex = Regex("SCHEDULED:\\s*<([0-9]{4})-([0-9]{2})-([0-9]{2})")
        val titleRegex = Regex("^(\\*+)\\s+(.*)")
        val result = mutableMapOf<LocalDate, MutableList<ScheduledEntry>>()
        for ((fileName, text) in contents) {
            var currentTitle = ""
            for (line in text.lineSequence()) {
                val heading = titleRegex.find(line.trim())
                if (heading != null) {
                    currentTitle = heading.groupValues[2].trim()
                }
                val m = regex.find(line)
                if (m != null) {
                    val y = m.groupValues[1].toInt()
                    val mo = m.groupValues[2].toInt()
                    val d = m.groupValues[3].toInt()
                    val date = LocalDate(y, mo, d)
                    val entry = ScheduledEntry(
                        title = currentTitle.ifBlank { fileName },
                        date = date,
                        fileName = fileName,
                        line = line.trim()
                    )
                    result.getOrPut(date) { mutableListOf() }.add(entry)
                }
            }
        }
        // sort entries per date by fileName/title for deterministic order
        return result.mapValues { (_, list) ->
            list.sortedWith(
                compareBy(
                    { it.fileName },
                    { it.title })
            )
        }
    }
}
