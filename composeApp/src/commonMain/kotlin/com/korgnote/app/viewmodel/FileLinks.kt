package com.korgnote.app.viewmodel

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.FileOpenResult
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.covering
import com.korgnote.common.org.link.fileLinkPath
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_file_link_unsupported_target
import korgnote.composeapp.generated.resources.vm_file_links_unsupported
import korgnote.composeapp.generated.resources.vm_file_needs_mapping
import korgnote.composeapp.generated.resources.vm_file_no_app
import korgnote.composeapp.generated.resources.vm_file_not_in_mapped_folder
import korgnote.composeapp.generated.resources.vm_file_not_on_device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * Links to files outside the notes — `[[file:~/Sync/invoice.pdf]]` — opened in the app the device
 * picks for them, at the path the link names or, through a mapping in Settings, at the place the
 * same folder has on this device.
 */
class FileLinks(
    private val scope: CoroutineScope,
    /** Read on each tap, so a change in Settings applies at once; the link form reads them in reverse. */
    val mappings: () -> List<FileLinkMapping>,
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
        val path = fileLinkPath(target) ?: return getString(Res.string.vm_file_link_unsupported_target, target)
        val covered = mappings().covering(path)
        val result = if (covered == null) {
            opener.openAsWritten(path)
        } else {
            opener.open(covered.first.to, covered.second)
        }
        return when (result) {
            FileOpenResult.Opened -> ""
            FileOpenResult.NotFound -> if (covered == null) {
                getString(Res.string.vm_file_not_on_device, path)
            } else {
                getString(Res.string.vm_file_not_in_mapped_folder, path, covered.first.from)
            }
            FileOpenResult.NoApp -> getString(Res.string.vm_file_no_app, path.substringAfterLast('/'))
            FileOpenResult.NeedsMapping -> getString(Res.string.vm_file_needs_mapping, path)
            FileOpenResult.Unsupported -> getString(Res.string.vm_file_links_unsupported)
            is FileOpenResult.Failed -> result.reason
        }
    }
}
