package com.korgnote.app.files

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
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

    private fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

actual fun folderLabel(folder: String): String = folder
