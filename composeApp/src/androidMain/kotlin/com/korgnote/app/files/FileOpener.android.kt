package com.korgnote.app.files

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.data_or
import korgnote.composeapp.generated.resources.files_internal_storage
import korgnote.composeapp.generated.resources.files_link_access_lost
import korgnote.composeapp.generated.resources.files_pick_needs_mapping
import korgnote.composeapp.generated.resources.files_pick_outside_mapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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
            return FileOpenResult.Failed(getString(Res.string.files_link_access_lost))
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
@Composable
actual fun folderLabel(folder: String): String = folderLabel(folder, stringResource(Res.string.files_internal_storage))

/** [folderLabel], away from composition: [internalStorage] is what the device's own storage is called. */
private fun folderLabel(folder: String, internalStorage: String): String {
    val uri = Uri.parse(folder)
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return folder
    if (uri.authority != "com.android.externalstorage.documents") return id
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val root = if (volume == "primary") internalStorage else volume
    return if (path.isEmpty()) root else "$root/$path"
}

/**
 * The system's file picker, opened in the first linked folder. A file chosen inside a folder set
 * under File links is written as that folder's links name it; any other has no path Emacs would
 * know, so it is refused with what to do instead.
 */
@Composable
actual fun rememberFilePicker(mappings: List<FileLinkMapping>, onPicked: (PickedFile) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val currentMappings by rememberUpdatedState(mappings)
    val currentOnPicked by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(OpenDocumentIn()) { document ->
        if (document != null) scope.launch { currentOnPicked(pickedFile(context, document, currentMappings)) }
    }
    return {
        val tree = currentMappings.map { Uri.parse(it.to) }.firstOrNull { it.scheme == "content" }
        val start = tree?.let {
            runCatching { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }.getOrNull()
        }
        launcher.launch(start)
    }
}

private suspend fun pickedFile(context: Context, document: Uri, mappings: List<FileLinkMapping>): PickedFile {
    val trees = mappings.filter { Uri.parse(it.to).scheme == "content" }
    if (trees.isEmpty()) {
        return PickedFile.NotLinkable(getString(Res.string.files_pick_needs_mapping))
    }
    val path = trees.linkPathFor { mapping -> runCatching { below(context, Uri.parse(mapping.to), document) }.getOrNull() }
    return if (path != null) {
        PickedFile.Linkable(path)
    } else {
        val internalStorage = getString(Res.string.files_internal_storage)
        val folders = trees.joinToString(" ${getString(Res.string.data_or)} ") { folderLabel(it.to, internalStorage) }
        PickedFile.NotLinkable(getString(Res.string.files_pick_outside_mapping, folders))
    }
}

/** The folders and file name of [document] inside [tree], or null when it is not in it. */
private fun below(context: Context, tree: Uri, document: Uri): List<String>? {
    if (tree.authority != document.authority) return null
    val treeId = DocumentsContract.getTreeDocumentId(tree)
    val documentId = DocumentsContract.getDocumentId(document)
    // Shared storage names a document by its path: `primary:Sync/photos/a.jpg` in `primary:Sync`,
    // and `primary:Sync/a.jpg` in the whole of `primary:`.
    val prefix = if (treeId.endsWith(':')) treeId else "$treeId/"
    if (documentId.startsWith(prefix)) return documentId.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
    // Other providers' ids mean nothing to read; they can say how to get to it from the tree's top.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val path = DocumentsContract.findDocumentPath(
        context.contentResolver,
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
    )?.path ?: return null
    val top = path.indexOf(treeId)
    if (top < 0) return null
    return path.drop(top + 1).map { id ->
        displayName(context, DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: return null
    }
}

private fun displayName(context: Context, document: Uri): String? =
    context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

/** Picks one file of any type, opening in the folder it is given when there is one. */
private class OpenDocumentIn : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .apply {
                if (input != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
                }
            }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}
