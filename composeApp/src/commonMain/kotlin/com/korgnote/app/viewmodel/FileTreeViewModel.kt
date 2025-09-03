package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.datetime.LocalDate
import kotlin.collections.iterator

/**
 * Simple ViewModel holding the fake file tree and the selected file name.
 * The fake data itself remains unchanged; it is just moved out of the Composable.
 */

data class ScheduledEntry(
    val title: String,
    val date: LocalDate,
    val fileName: String,
    val line: String
)

class FileTreeViewModel : ViewModel() {
    // Fake tree data (moved from App.kt)
    val tree: Folder = Folder(
        name = "Documents",
        children = listOf(
            Folder(
                name = "Org",
                children = listOf(
                    Folder(
                        name = "Agenda",
                        children = listOf(
                            File("inbox.org"),
                            File("next-actions.org"),
                            File("calendar.org"),
                            File("sep-2025.org")
                        )
                    ),
                    Folder(
                        name = "Projects",
                        children = listOf(
                            File("korgnote.org"),
                            File("website-redesign.org"),
                            File("research-notes.org")
                        )
                    ),
                    Folder(
                        name = "Journal",
                        children = listOf(
                            Folder(
                                name = "2024",
                                children = listOf(
                                    File("2024-12-31.org")
                                )
                            ),
                            Folder(
                                name = "2025",
                                children = listOf(
                                    File("2025-01-01.org"),
                                    File("2025-09-03.org")
                                )
                            )
                        )
                    ),
                    Folder(
                        name = "Reference",
                        children = listOf(
                            File("books.org"),
                            File("quotes.org")
                        )
                    )
                )
            ),
            File("notes.org")
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

    // Simple in-memory org-mode contents keyed by file name
    private val orgContents: Map<String, String> = mapOf(
        "inbox.org" to "* Inbox\n** TODO Capture an idea\nAdded: [2025-09-03 Wed 15:03]",
        "next-actions.org" to "* Next Actions\n** TODO Implement Org preview in app\nSCHEDULED: <2025-09-04 Thu>\n** TODO Review release notes\nSCHEDULED: <2025-09-21 Sun>",
        "calendar.org" to "* Calendar\n** 2025\n*** Team sync\nSCHEDULED: <2025-09-10 Wed>\n*** Release planning\nSCHEDULED: <2025-09-15 Mon>",
        "sep-2025.org" to "* September 2025\n** TODO Kickoff Q4 planning\nSCHEDULED: <2025-09-10 Wed>\n** DONE Submit budget proposal\nSCHEDULED: <2025-09-15 Mon>\n** TODO Write blog post\nSCHEDULED: <2025-09-21 Sun>\n** TODO End of month review\nSCHEDULED: <2025-09-30 Tue>",
        "korgnote.org" to "* Project KorgNote\n** Goals\n- Cross-platform Org viewer\n** Tasks\n- [ ] Show content on select\n** TODO Calendar markers\nSCHEDULED: <2025-09-18 Thu>",
        "website-redesign.org" to "* Website Redesign\n** Milestones\n- [ ] Wireframes\n- [ ] Prototype\n- [ ] Launch",
        "research-notes.org" to "* Research Notes\n- Compose Multiplatform\n- Org-mode parsing",
        "2024-12-31.org" to "* Journal 2024-12-31\n- Reflect on the year",
        "2025-01-01.org" to "* Journal 2025-01-01\n- New beginnings",
        "2025-09-03.org" to "* Journal 2025-09-03\n- Implemented Org content in app",
        "books.org" to "* Books\n** Reading\n- The Pragmatic Programmer\n** Wishlist\n- Structure and Interpretation of Computer Programs",
        "quotes.org" to "* Quotes\n** Alan Kay\n> The best way to predict the future is to invent it.",
        "notes.org" to "* Notes\nMiscellaneous notes"
    )

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    var selectedFileContent by mutableStateOf("")
        private set

    // Map of scheduled entries by LocalDate, computed from all fake org contents
    val scheduledByDate: Map<LocalDate, List<ScheduledEntry>> = buildScheduledIndex(orgContents)

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
        selectedFileContent = orgContents[name] ?: "* ${'$'}name\nNo content yet."
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
