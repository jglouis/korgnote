package com.korgnote.app.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.files_link_picker_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

/**
 * Opens linked files with the desktop's own handler. A path as written works when the notes were
 * written on this machine: `~` is the user's home here too. A mapping's folder is a typed path.
 */
object DesktopFileOpener : FileOpener {
    override suspend fun open(folder: String, below: List<String>): FileOpenResult =
        openFile(below.fold(File(expandHome(folder.trim()))) { dir, name -> File(dir, name) })

    override suspend fun openAsWritten(path: String): FileOpenResult = openFile(File(expandHome(path)))

    private suspend fun openFile(file: File): FileOpenResult {
        if (!file.exists()) return FileOpenResult.NotFound
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return FileOpenResult.Unsupported
        }
        return try {
            // It can block until the handler has started.
            withContext(Dispatchers.IO) { Desktop.getDesktop().open(file) }
            FileOpenResult.Opened
        } catch (_: IOException) {
            FileOpenResult.NoApp
        }
    }

    internal fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
}

/**
 * The desktop's own file dialog. A file in a mapped folder is written as the mapping names that
 * folder; any other as its path, with the home folder as `~`, as Emacs abbreviates it.
 */
@Composable
actual fun rememberFilePicker(mappings: List<FileLinkMapping>, onPicked: (PickedFile) -> Unit): (() -> Unit)? {
    val currentMappings by rememberUpdatedState(mappings)
    val currentOnPicked by rememberUpdatedState(onPicked)
    val title by rememberUpdatedState(stringResource(Res.string.files_link_picker_title))
    return remember {
        {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            currentMappings.firstOrNull { it.to.isNotBlank() }?.let { dialog.directory = DesktopFileOpener.expandHome(it.to.trim()) }
            // Modal: it returns once a file is chosen or the dialog is closed.
            dialog.isVisible = true
            val file = dialog.files.firstOrNull()
            if (file != null) currentOnPicked(PickedFile.Linkable(desktopLinkPath(file.absolutePath, currentMappings)))
        }
    }
}

internal fun desktopLinkPath(path: String, mappings: List<FileLinkMapping>): String {
    mappings.linkPathFor { pathBelow(DesktopFileOpener.expandHome(it.to.trim()), path) }?.let { return it }
    val home = System.getProperty("user.home")
    return pathBelow(home, path)?.let { joinPath(listOf("~") + it) } ?: path
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun folderLabel(folder: String): String = folder
