package com.korgnote.common.index

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** A [NoteIndexFile] in files named after [base], which a failure to read or write only ever costs a scan of the notes. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileNoteIndexFile(private val base: String) : NoteIndexFile {
    private fun pathOf(part: IndexPart) = "$base.${part.name.lowercase()}.jsonl"

    override suspend fun read(part: IndexPart, line: (String) -> Unit) = withContext(Dispatchers.IO) {
        val text: String? = NSString.stringWithContentsOfFile(pathOf(part), encoding = NSUTF8StringEncoding, error = null)
        text?.lineSequence()?.filter { it.isNotEmpty() }?.forEach(line)
        Unit
    }

    override suspend fun write(part: IndexPart, lines: Sequence<String>) = withContext(Dispatchers.IO) {
        val path = pathOf(part)
        NSFileManager.defaultManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        // Atomically: a killed app leaves the old index or the new one, never half of either.
        NSString.create(string = lines.joinToString("\n"))
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        Unit
    }
}
