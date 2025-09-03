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
                name = "Projects",
                children = listOf(
                    Folder(
                        name = "KorgNote",
                        children = listOf(
                            File("README.md"),
                            File("Design.md"),
                            Folder(
                                name = "assets",
                                children = listOf(
                                    File("icon.png"),
                                    File("banner.jpg")
                                )
                            )
                        )
                    ),
                    File("todo.txt")
                )
            ),
            Folder(
                name = "Photos",
                children = listOf(
                    Folder(
                        name = "2023",
                        children = listOf(
                            File("vacation-1.jpg"),
                            File("vacation-2.jpg")
                        )
                    ),
                    Folder(
                        name = "2024",
                        children = listOf(
                            File("family.png")
                        )
                    )
                )
            ),
            File("notes.txt")
        )
    )

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    fun onFileSelected(name: String) {
        selectedFileLabel = "Selected: $name"
    }
}
