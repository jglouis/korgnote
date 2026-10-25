package com.korgnote.app.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens linked files from folders chosen in the system picker.
 *
 * Android lets an app read shared storage only through what the user hands it, so a mapping's
 * folder is a document tree granted once in [rememberFolderPicker], and a path as written in a link
 * means nothing here. The file is found by name, folder by folder, under that tree, and handed to
 * the viewer as a content URI with read access: a viewer cannot be given a plain file path.
 */
class AndroidFileOpener(private val context: Context) : FileOpener {

    override suspend fun open(folder: String, below: List<String>): FileOpenResult {
        val tree = Uri.parse(folder)
        if (tree.scheme != "content") return FileOpenResult.NeedsMapping
        val found = try {
            withContext(Dispatchers.IO) { find(tree, below) }
        } catch (_: SecurityException) {
            return FileOpenResult.Failed("KorgNote may no longer read the folder for this link. Choose it again in Settings, under File links.")
        } ?: return FileOpenResult.NotFound
        val (uri, providerType) = found
        val type = providerType.takeUnless { it == null || it == GENERIC_TYPE }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(below.lastOrNull()?.substringAfterLast('.', "")?.lowercase())
            ?: providerType
            ?: GENERIC_TYPE
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(view)
            FileOpenResult.Opened
        } catch (_: ActivityNotFoundException) {
            FileOpenResult.NoApp
        }
    }

    override suspend fun openAsWritten(path: String) = FileOpenResult.NeedsMapping

    /** The document [below] names under [tree], with its type; null when a name along the way is missing. */
    private fun find(tree: Uri, below: List<String>): Pair<Uri, String?>? {
        var id = DocumentsContract.getTreeDocumentId(tree)
        var type: String? = DocumentsContract.Document.MIME_TYPE_DIR
        for (name in below) {
            val children = children(tree, id)
            // Shared storage ignores case, so a link typed in another case still finds the file.
            val child = children.firstOrNull { it.name == name }
                ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return null
            id = child.id
            type = child.type
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id) to type
    }

    private class Child(val id: String, val name: String, val type: String?)

    private fun children(tree: Uri, parent: String): List<Child> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    add(Child(cursor.getString(0), name, cursor.getString(2)))
                }
            }
        }.orEmpty()
    }

    private companion object {
        const val GENERIC_TYPE = "application/octet-stream"
    }
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            // Kept across restarts: without it the folder could be read only until the app is closed.
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onPicked(tree.toString())
        }
    }
    return { launcher.launch(null) }
}

/**
 * `Internal storage/Sync` for the tree of the shared folder `Sync`; the volume's id in place of
 * "Internal storage" on an SD card, and the folder's own id for other providers.
 */
actual fun folderLabel(folder: String): String {
    val uri = Uri.parse(folder)
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return folder
    if (uri.authority != "com.android.externalstorage.documents") return id
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val root = if (volume == "primary") "Internal storage" else volume
    return if (path.isEmpty()) root else "$root/$path"
}
