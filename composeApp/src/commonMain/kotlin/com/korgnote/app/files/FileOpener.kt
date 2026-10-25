package com.korgnote.app.files

import androidx.compose.runtime.Composable

/** How an attempt to open a linked file went. */
sealed interface FileOpenResult {
    data object Opened : FileOpenResult

    /** Nothing is at that path. */
    data object NotFound : FileOpenResult

    /** The file is there, but no app on the device opens files of its kind. */
    data object NoApp : FileOpenResult

    /** This device cannot reach a path as written; it needs a mapping to a folder it can read. */
    data object NeedsMapping : FileOpenResult

    /** Files cannot be opened from links here at all. */
    data object Unsupported : FileOpenResult

    data class Failed(val reason: String) : FileOpenResult
}

/** Opens a file in the app the system picks for it, as Org does with `xdg-open` or `open`. */
interface FileOpener {
    /** Opens what [below] names — folders, then a file name — inside [folder], a mapping's [FileLinkMapping.to]. */
    suspend fun open(folder: String, below: List<String>): FileOpenResult

    /** Opens [path] as the link writes it, for when no mapping covers it. */
    suspend fun openAsWritten(path: String): FileOpenResult
}

/** For iOS and the web, which open no local files from links. */
object NoFileOpener : FileOpener {
    override suspend fun open(folder: String, below: List<String>) = FileOpenResult.Unsupported
    override suspend fun openAsWritten(path: String) = FileOpenResult.Unsupported
}

/**
 * The system's folder picker, where a mapping's folder is chosen rather than typed: a function that
 * shows it, after which [onPicked] receives the folder chosen. Null where folders are typed as paths.
 */
@Composable
expect fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)?

/** How to show a folder from [rememberFolderPicker]: a name for it, not the address behind it. */
expect fun folderLabel(folder: String): String
