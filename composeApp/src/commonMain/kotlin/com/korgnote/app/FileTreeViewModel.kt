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

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
    }
}
