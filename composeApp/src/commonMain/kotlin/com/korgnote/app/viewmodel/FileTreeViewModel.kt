package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.korgnote.app.data.OrgRepository
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
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
    private var _currentFile: String? = null
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
            val filtered = filterNode(tree, q.lowercase())
            return when (filtered) {
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

    // Map of scheduled entries by LocalDate, computed from all contents
    var scheduledByDate by mutableStateOf<Map<LocalDate, List<ScheduledEntry>>>(buildScheduledIndex(orgContents))

    fun toggleCheckboxAt(lineIndex: Int, checked: Boolean) {
        val file = _currentFile ?: return
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
    }

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
        selectedFileContent = orgContents[name] ?: "* ${name}\nNo content yet."
        _currentFile = name
    }

    // Load tree paths and contents from server repository and update state
    suspend fun loadFromServer() {
        try {
            val paths = repository.fetchTreePaths()
            val data = repository.fetchAllContents()
            orgContents.clear()
            orgContents.putAll(data)
            tree = buildTreeFromPaths(paths)
            scheduledByDate = buildScheduledIndex(orgContents)
            if (_currentFile == null) {
                val first = data.keys.sorted().firstOrNull()
                if (first != null) onFileSelected(first)
            } else {
                _currentFile?.let { current ->
                    selectedFileContent = orgContents[current] ?: selectedFileContent
                }
            }
        } catch (t: Throwable) {
            // Keep local fallback content on error; could set an error state if needed
        }
    }

    private fun buildTreeFromPaths(paths: List<String>): Folder {
        class FolderBuilder(val name: String) {
            val folders = mutableMapOf<String, FolderBuilder>()
            val files = mutableSetOf<String>()
        }
        val root = FolderBuilder("Documents")
        for (raw in paths) {
            val parts = raw.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var cursor = root
            for (i in parts.indices) {
                val segment = parts[i]
                if (i == parts.lastIndex) {
                    // last segment -> a file
                    cursor.files.add(segment)
                } else {
                    cursor = cursor.folders.getOrPut(segment) { FolderBuilder(segment) }
                }
            }
        }
        fun toNode(b: FolderBuilder): Folder {
            val childFolders = b.folders.values.sortedBy { it.name }.map { toNode(it) }
            val childFiles = b.files.sorted().map { File(it) }
            return Folder(b.name, childFolders + childFiles)
        }
        return toNode(root)
    }

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
