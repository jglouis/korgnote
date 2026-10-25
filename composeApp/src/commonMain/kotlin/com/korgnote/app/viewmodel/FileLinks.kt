package com.korgnote.app.viewmodel

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.FileOpenResult
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.covering
import com.korgnote.common.org.link.fileLinkPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Links to files outside the notes — `[[file:~/Sync/invoice.pdf]]` — opened in the app the device
 * picks for them, at the path the link names or, through a mapping in Settings, at the place the
 * same folder has on this device.
 */
class FileLinks(
    private val scope: CoroutineScope,
    /** Read on each tap, so a change in Settings applies at once. */
    private val mappings: () -> List<FileLinkMapping>,
    private val opener: FileOpener,
    /** Shows a message to the user; an empty one clears it. */
    private val showMessage: (String) -> Unit,
) {
    /** Opens the file a link tapped in reading mode names, or says why it cannot. */
    fun open(target: String) {
        scope.launch { showMessage(openNow(target)) }
    }

    /** [open], waited for: the message to show, empty when the file was opened. */
    suspend fun openNow(target: String): String {
        val path = fileLinkPath(target) ?: return "[[$target]] is not a file this app can open."
        val covered = mappings().covering(path)
        val result = if (covered == null) {
            opener.openAsWritten(path)
        } else {
            opener.open(covered.first.to, covered.second)
        }
        return when (result) {
            FileOpenResult.Opened -> ""
            FileOpenResult.NotFound -> if (covered == null) {
                "$path is not on this device. If it is somewhere else here, say where in Settings, under File links."
            } else {
                "$path is not on this device, in the folder set for ${covered.first.from}."
            }
            FileOpenResult.NoApp -> "No app on this device opens ${path.substringAfterLast('/')}."
            FileOpenResult.NeedsMapping ->
                "No folder on this device is set for $path. Choose one in Settings, under File links."
            FileOpenResult.Unsupported -> "Files cannot be opened from links on this device."
            is FileOpenResult.Failed -> result.reason
        }
    }
}
