package com.korgnote.app.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.NoteFolder
import com.korgnote.common.replica.isListedNotePath
import com.korgnote.common.replica.requireSafeNotePath
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

/**
 * A folder of notes the user picked in Files: on this iPhone, in iCloud Drive, or in another app's
 * storage such as Dropbox's. [location] is a bookmark of it, which is what lets the app reach it
 * again after a restart; the URL itself means nothing once the app has quit.
 *
 * Each read and write is coordinated, as Files expects of an app sharing a folder with others:
 * that is what has iCloud or Dropbox download a note that is only a placeholder on the phone, and
 * what keeps a write from landing half-way through theirs.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosNoteFolder(private val location: String) : NoteFolder {

    private val manager = NSFileManager.defaultManager

    /** The folder, resolved from the bookmark and opened for use the first time it is needed. */
    private val root: NSURL by lazy {
        val url = resolveBookmark(location) ?: throw IllegalStateException(
            "The folder cannot be found any more. Choose it again in Settings, under Notes.",
        )
        // Kept open for as long as the app runs: the notes are used for all of it.
        url.startAccessingSecurityScopedResource()
        url
    }

    private val rootPath: String get() = root.path ?: throw IllegalStateException("The folder has no path.")

    override suspend fun list(): List<FolderEntry> = withContext(Dispatchers.IO) {
        val enumerator = manager.enumeratorAtPath(rootPath)
            ?: throw IllegalStateException("KorgNote cannot read the folder. Choose it again in Settings, under Notes.")
        val found = mutableListOf<FolderEntry>()
        while (true) {
            val next = enumerator.nextObject() as? String ?: break
            val name = next.substringAfterLast('/')
            // iCloud keeps a note not yet downloaded as `.name.org.icloud` beside where it will be.
            val path = if (name.startsWith('.') && name.endsWith(".org.icloud")) {
                next.dropLast(name.length) + name.removePrefix(".").removeSuffix(".icloud")
            } else {
                next
            }
            if (name.startsWith('.') && path == next) {
                // Not just a file to leave out: Syncthing's `.stversions` holds every note again.
                enumerator.skipDescendents()
                continue
            }
            if (!isListedNotePath(path)) continue
            stampAt("$rootPath/$next")?.let { found += FolderEntry(path, it) }
        }
        found.sortedBy { it.path }
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        coordinated { coordinator, error ->
            var text: String? = null
            coordinator.coordinateReadingItemAtURL(urlOf(path), options = 0u, error = error) { url ->
                text = url?.let { NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) }
            }
            text
        }
    }

    override suspend fun write(path: String, content: String): FileStamp = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        val target = urlOf(path)
        target.URLByDeletingLastPathComponent?.let {
            manager.createDirectoryAtURL(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val written = coordinated { coordinator, error ->
            var ok = false
            coordinator.coordinateWritingItemAtURL(target, options = NSFileCoordinatorWritingForReplacing, error = error) { url ->
                // NSString.create, not a cast: a Kotlin String is not an NSString in Kotlin/Native.
                ok = url != null && NSString.create(string = content)
                    .writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            }
            ok
        }
        if (written != true) throw IllegalStateException("$path cannot be written.")
        stampAt("$rootPath/$path") ?: throw IllegalStateException("$path was written but cannot be found.")
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            requireSafeNotePath(path)
            coordinated { coordinator, error ->
                coordinator.coordinateWritingItemAtURL(urlOf(path), options = NSFileCoordinatorWritingForDeleting, error = error) { url ->
                    url?.let { manager.removeItemAtURL(it, error = null) }
                }
            }
        }
    }

    override suspend fun stamp(path: String): FileStamp? = withContext(Dispatchers.IO) {
        requireSafeNotePath(path)
        stampAt("$rootPath/$path")
    }

    private fun urlOf(path: String): NSURL = NSURL.fileURLWithPath("$rootPath/$path")

    private fun stampAt(absolute: String): FileStamp? {
        val attributes = manager.attributesOfItemAtPath(absolute, error = null) ?: return null
        val modified = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
        val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
        return FileStamp((modified * 1000).toLong(), size)
    }

    /** Runs [block] with a coordinator, throwing what the coordination reports as having failed. */
    private fun <T> coordinated(block: (NSFileCoordinator, kotlinx.cinterop.CPointer<ObjCObjectVar<NSError?>>) -> T): T = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val result = block(NSFileCoordinator(filePresenter = null), error.ptr)
        error.value?.let { throw IllegalStateException(it.localizedDescription) }
        result
    }
}

/** The folder a bookmark names, or null when it cannot be found. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun resolveBookmark(location: String): NSURL? {
    val data = NSData.create(base64EncodedString = location, options = 0u) ?: return null
    return memScoped {
        val stale = alloc<BooleanVar>()
        NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = null)
    }
}

/** A bookmark of [url], as [IosNoteFolder] takes it; null when one cannot be made. */
@OptIn(ExperimentalForeignApi::class)
private fun bookmarkOf(url: NSURL): String? {
    // Opened first: a folder from another app's storage may not be bookmarked otherwise.
    url.startAccessingSecurityScopedResource()
    val data = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
    return data?.base64EncodedStringWithOptions(0u)
}

/** Hands the folder picked in Files to the app; held by the picker only weakly, so kept here. */
private class FolderPickerDelegate(var onPicked: (String) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        bookmarkOf(url)?.let(onPicked)
    }
}

@Composable
actual fun rememberNotesFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val delegate = remember { FolderPickerDelegate { currentOnPicked(it) } }
    return {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** What is on screen, to present the picker over: the app's own view, or what it presented. */
private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

private val labels = mutableMapOf<String, String>()

actual fun notesFolderLabel(location: String): String =
    labels.getOrPut(location) { resolveBookmark(location)?.lastPathComponent ?: "The chosen folder" }

internal actual val notesFolderPlaces: String =
    "On this iPhone, in iCloud Drive, or in Dropbox and other apps that show in Files."

internal actual val notesFolderPickingHint: String =
    "Choose the folder your .org files are in, or an empty one to start. KorgNote then uses what " +
        "is in that folder, and nothing else."
