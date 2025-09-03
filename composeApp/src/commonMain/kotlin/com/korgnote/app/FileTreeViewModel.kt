package com.korgnote.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Simple ViewModel holding the fake file tree and the selected file name.
 * The fake data itself remains unchanged; it is just moved out of the Composable.
 */
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
                            File("calendar.org")
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
                if (selfMatches || childMatches.isNotEmpty()) Folder(node.name, childMatches) else null
            }
            else -> null
        }
    }

    // Simple in-memory org-mode contents keyed by file name
    private val orgContents: Map<String, String> = mapOf(
        "inbox.org" to "* Inbox\n** TODO Capture an idea\nAdded: [2025-09-03 Wed 15:03]",
        "next-actions.org" to "* Next Actions\n** TODO Implement Org preview in app\nSCHEDULED: <2025-09-04 Thu>",
        "calendar.org" to "* Calendar\n** 2025\n*** 2025-09-10 Team sync\n*** 2025-09-15 Release planning",
        "korgnote.org" to "* Project KorgNote\n** Goals\n- Cross-platform Org viewer\n** Tasks\n- [ ] Show content on select",
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

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
        selectedFileContent = orgContents[name] ?: "* ${'$'}name\nNo content yet."
    }
}
