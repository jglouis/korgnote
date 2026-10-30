package com.korgnote.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.NoteFolder
import com.korgnote.common.replica.isListedNotePath
import com.korgnote.common.replica.requireSafeNotePath
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.files_cannot_create
import korgnote.composeapp.generated.resources.files_cannot_write
import korgnote.composeapp.generated.resources.files_folder_access_lost
import korgnote.composeapp.generated.resources.files_folder_unreadable
import korgnote.composeapp.generated.resources.files_picking_hint_android
import korgnote.composeapp.generated.resources.files_places_android
import korgnote.composeapp.generated.resources.files_written_not_found
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A folder of notes in shared storage, reached through the document tree the user granted in
 * [rememberNotesFolderPicker]: Android lets an app use a folder there only through what the user
 * hands it, never by its path.
 *
 * Every call is a query to the folder's provider, in another process, so a document's id is kept by
 * its path once a listing has found it, rather than looked up folder by folder for each read.
 */
class SafNoteFolder(private val context: Context, location: String) : NoteFolder {

    private val tree = Uri.parse(location)
    private val resolver get() = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    /** Document ids by path, files and folders alike, as last found. */
    private val ids = ConcurrentHashMap<String, String>()

    /**
     * Lists the folders under the tree several at a time: a listing is a query per folder, a tenth
     * of a second each on a slow phone, and one after another they added up to seconds.
     */
    override suspend fun list(): List<FolderEntry> = withContext(Dispatchers.IO) {
        requireAccess()
        val found = ConcurrentLinkedQueue<FolderEntry>()
        val seen = ConcurrentHashMap<String, String>()
        val queries = Semaphore(PARALLEL_QUERIES)
        suspend fun walk(parentId: String, prefix: String): Unit = coroutineScope {
            // The permit covers the query only: a folder waiting on its subfolders holds none.
            for (child in queries.withPermit { children(parentId) }) {
                if (child.name.startsWith('.')) continue
                val path = prefix + child.name
                if (child.isFolder) {
                    seen[path] = child.id
                    launch { walk(child.id, "$path/") }
                } else if (isListedNotePath(path)) {
                    seen[path] = child.id
                    found += FolderEntry(path, child.stamp)
                }
            }
        }
        walk(rootId, "")
        ids.clear()
        ids.putAll(seen)
        found.sortedBy { it.path }
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        retryingStaleId(path) { id ->
            resolver.openInputStream(documentUri(id))?.use { it.readBytes().decodeToString() }
        }
    }

    override suspend fun write(path: String, content: String): FileStamp = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        requireAccess()
        val id = idOf(path) ?: create(path)
        // "wt", not "w": some versions leave the end of a longer old note behind with "w".
        val out = resolver.openOutputStream(documentUri(id), "wt")
            ?: throw FileNotFoundException(getString(Res.string.files_cannot_write, path))
        out.use { it.write(content.encodeToByteArray()) }
        query(id)?.stamp ?: throw FileNotFoundException(getString(Res.string.files_written_not_found, path))
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            requireSafeNotePath(path)
            val id = idOf(path) ?: return@withContext
            ids.remove(path)
            try {
                DocumentsContract.deleteDocument(resolver, documentUri(id))
            } catch (_: FileNotFoundException) {
                // Gone already: what deleting it was for.
            }
        }
    }

    override suspend fun stamp(path: String): FileStamp? = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        retryingStaleId(path) { id -> query(id)?.stamp }
    }

    /**
     * Runs [block] on [path]'s document, looked up again once when its kept id no longer names it:
     * another app may have replaced the file, and some providers give the new one a new id.
     */
    private suspend fun <T> retryingStaleId(path: String, block: (String) -> T?): T? {
        requireAccess()
        val id = idOf(path) ?: return null
        val first = try {
            block(id)
        } catch (_: FileNotFoundException) {
            null
        }
        if (first != null) return first
        ids.remove(path)
        val again = idOf(path)?.takeIf { it != id } ?: return null
        return try {
            block(again)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /** [path]'s document id, found by name folder by folder when no listing has seen it. */
    private suspend fun idOf(path: String): String? {
        ids[path]?.let { return it }
        var id = rootId
        var walked = ""
        for (name in path.split('/')) {
            walked = if (walked.isEmpty()) name else "$walked/$name"
            id = ids[walked] ?: children(id).firstOrNull { it.name == name }?.id?.also { ids[walked] = it } ?: return null
        }
        return id
    }

    /** Creates [path], and the folders above it that are missing, returning its document id. */
    private suspend fun create(path: String): String {
        val names = path.split('/')
        var parent = rootId
        var walked = ""
        for (name in names.dropLast(1)) {
            walked = if (walked.isEmpty()) name else "$walked/$name"
            parent = idOf(walked) ?: createDocument(parent, Document.MIME_TYPE_DIR, name).also { ids[walked] = it }
        }
        // Not text/plain: a provider adds `.txt` to a name whose extension does not fit its type.
        return createDocument(parent, "application/octet-stream", names.last()).also { ids[path] = it }
    }

    private suspend fun createDocument(parentId: String, type: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, documentUri(parentId), type, name)
            ?: throw FileNotFoundException(getString(Res.string.files_cannot_create, name))
        return DocumentsContract.getDocumentId(created)
    }

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private class Child(val id: String, val name: String, val isFolder: Boolean, val stamp: FileStamp)

    private suspend fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: throw FileNotFoundException(getString(Res.string.files_folder_unreadable))
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    add(Child(it.getString(0), name, it.getString(2) == Document.MIME_TYPE_DIR, FileStamp(it.getLong(3), it.getLong(4))))
                }
            }
        }
    }

    private fun query(id: String): Child? =
        resolver.query(documentUri(id), COLUMNS, null, null, null)?.use {
            if (!it.moveToFirst()) null
            else Child(it.getString(0), it.getString(1).orEmpty(), it.getString(2) == Document.MIME_TYPE_DIR, FileStamp(it.getLong(3), it.getLong(4)))
        }

    /** Throws, saying what to do, once the user has taken KorgNote's access to the folder away. */
    private suspend fun requireAccess() {
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        if (!granted) throw SecurityException(getString(Res.string.files_folder_access_lost))
    }

    private companion object {
        const val PARALLEL_QUERIES = 8

        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}

@Composable
actual fun rememberNotesFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            // Kept across restarts, for writing as well as reading: these are the notes themselves.
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            currentOnPicked(tree.toString())
        }
    }
    return { launcher.launch(null) }
}

@Composable
actual fun notesFolderLabel(location: String): String = folderLabel(location)

internal actual val notesFolderPlaces: String
    @Composable get() = stringResource(Res.string.files_places_android)

internal actual val notesFolderPickingHint: String
    @Composable get() = stringResource(Res.string.files_picking_hint_android)
