package com.korgnote.common.index

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A [NoteIndexFile] in files named after [base], which a failure to read or write only ever costs a scan of the notes. */
class FileNoteIndexFile(private val base: File) : NoteIndexFile {
    private fun fileOf(part: IndexPart) = File(base.parentFile, "${base.name}.${part.name.lowercase()}.jsonl")

    override suspend fun read(part: IndexPart, line: (String) -> Unit) = withContext(Dispatchers.IO) {
        val file = fileOf(part)
        try {
            if (file.isFile) file.forEachLine(action = line)
        } catch (e: IOException) {
            println("[DEBUG_LOG][NoteIndex] Reading the index failed: $e")
        }
    }

    override suspend fun write(part: IndexPart, lines: Sequence<String>) = writing.withLock { withContext(Dispatchers.IO) {
        val file = fileOf(part)
        val partial = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            partial.bufferedWriter().use { out ->
                for (line in lines) {
                    out.write(line)
                    out.newLine()
                }
            }
            if (!partial.renameTo(file)) throw IOException("cannot replace $file")
        } catch (e: IOException) {
            println("[DEBUG_LOG][NoteIndex] Writing the index failed: $e")
            partial.delete()
        }
        Unit
    } }

    private companion object {
        /** The app and a worker running beside it in one process each have one of these for the same file. */
        val writing = Mutex()
    }
}
