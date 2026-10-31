package com.korgnote.app.capture

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Where captures wait on iOS: a folder in the App Group the app shares with its share extension,
 * which adds to it while the app may not even be running. The group is named in both Info.plists as
 * `KorgNoteAppGroup`, from the bundle identifier, so a build with another team gets a group of its own.
 *
 * Without the group — a build whose signing could not give it — the folder is the app's own: links
 * and in-app captures still work, and the share extension's are kept until there is one.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosCaptureQueueDirectory(fallback: String): String {
    val group = NSBundle.mainBundle.objectForInfoDictionaryKey("KorgNoteAppGroup") as? String
    val container = group?.let { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it)?.path }
    if (container == null) println("[DEBUG_LOG][Capture] No App Group container for $group; captures wait in the app's own folder")
    val directory = "${container ?: fallback}/capture-queue"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    // Waiting captures are notes too, and like them stay out of backups.
    NSURL.fileURLWithPath(directory).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return directory
}

/** The queue as files in [directory]; see [iosCaptureQueueDirectory]. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCaptureQueueFolder(private val directory: String) : CaptureQueueFolder {
    // The share extension reads the templates and headings the app leaves here.
    override val readByOthers: Boolean get() = true

    override suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, error = null)?.filterIsInstance<String>().orEmpty()
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        NSString.stringWithContentsOfFile("$directory/$name", encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun write(name: String, text: String) {
        withContext(Dispatchers.IO) {
            // Atomically: written beside, then moved over, so a reader never sees half of it.
            val written = NSString.create(string = text)
                .writeToFile("$directory/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
            check(written) { "Could not queue $name" }
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { NSFileManager.defaultManager.removeItemAtPath("$directory/$name", error = null) }
    }
}
