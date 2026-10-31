package com.korgnote.app.capture

import com.korgnote.common.org.capture.HeadingAdditions
import com.korgnote.common.org.capture.HeadingRef
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.captureInto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Something captured, waiting to be filed into the notes.
 *
 * Captures wait rather than go straight into a note because what takes them is not always the app:
 * on iOS it is the share extension, a process of its own that must not write to the notes while
 * the app syncs them. Everything that captures adds one of these to the [CaptureQueue]; only the
 * app files them, one at a time ([CaptureFiler]).
 *
 * This is also the file format of the queue, written by Swift as well as Kotlin: field names are
 * part of it, and every field but [id] may be missing.
 */
@Serializable
data class CaptureRequest(
    /** Names its file in the queue. */
    val id: String,
    /** When it was captured, in milliseconds since 1970: `%U` and the like say this, not when it was filed. */
    val at: Long = 0,
    /** What was shared, as it was shared. */
    val shared: SharedContent = SharedContent(),
    /** The TODO keyword its heading is marked with, of the destination note's; none when null. */
    val keyword: String? = null,
    /** The entry as the user left it in the capture sheet; made from [shared] when null. */
    val entry: String? = null,
    /** A title typed over what was shared, where the entry itself was not shown: iOS's share sheet. */
    val title: String? = null,
    /** A line or two typed with it there, put under what was shared. */
    val note: String? = null,
    /** The text as edited in the sheet, in place of what was shared; [note] is then part of it. */
    val body: String? = null,
    /** The note a new entry goes into, in place of its template's; made when it does not exist. */
    val file: String? = null,
    /** [file]'s title when it is made for this capture. */
    val fileTitle: String? = null,
    /** A heading the new entry goes under, as a sub-entry: in [file], or in the note it is in. */
    val under: HeadingRef? = null,
    /** An existing heading to add it to, rather than a new entry; see [captureInto]. */
    val into: HeadingRef? = null,
    /** What of it goes to [into]; what fits what was shared when null. */
    val additions: HeadingAdditions? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** The request in [text], or null when it is not one: a file half written, or from a newer app. */
        fun decode(text: String): CaptureRequest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.id.isNotBlank() }
    }
}

/**
 * Where captures wait: a file each, in a folder the platform gives — the app's own on Android, the
 * App Group's on iOS so the share extension can add to it.
 */
interface CaptureQueueFolder {
    /** The names of the files in it, in no particular order. */
    suspend fun names(): List<String>

    suspend fun read(name: String): String?

    /** Writes [name] whole or not at all, so a reader never sees half a capture. */
    suspend fun write(name: String, text: String)

    suspend fun delete(name: String)

    /**
     * Whether something other than the app captures into it — iOS's share extension — and so needs
     * the headings left beside the captures, since it cannot read the notes.
     */
    val readByOthers: Boolean get() = false
}

/** The queue itself: captures in the order they were taken. */
class CaptureQueue(private val folder: CaptureQueueFolder) {

    suspend fun add(request: CaptureRequest) = folder.write(fileName(request.id), request.encode())

    /** Every capture waiting, oldest first. A file that cannot be read is left for a newer app. */
    suspend fun waiting(): List<CaptureRequest> =
        folder.names().filter { it.endsWith(EXTENSION) }
            .mapNotNull { name -> folder.read(name)?.let(CaptureRequest::decode) }
            .sortedWith(compareBy({ it.at }, { it.id }))

    suspend fun remove(id: String) = folder.delete(fileName(id))

    /**
     * Leaves the notes' [headings] beside the captures, for what captures without the app to capture
     * into one: iOS's share extension cannot read the notes. Written only when they changed.
     */
    suspend fun offerHeadings(headings: List<HeadingRef>) {
        if (!folder.readByOthers) return
        val text = headingsJson.encodeToString(ListSerializer(HeadingRef.serializer()), headings)
        if (folder.read(HEADINGS) != text) folder.write(HEADINGS, text)
    }

    private fun fileName(id: String) = id.filter { it.isLetterOrDigit() || it == '-' } + EXTENSION

    private companion object {
        const val EXTENSION = ".json"
        const val HEADINGS = "headings.index"

        val headingsJson = Json { explicitNulls = false }
    }
}

/** A queue that lasts as long as the app: the desktop's and the web's, which nothing else captures into, and the tests'. */
class InMemoryCaptureQueueFolder : CaptureQueueFolder {
    private val files = mutableMapOf<String, String>()

    override suspend fun names(): List<String> = files.keys.toList()
    override suspend fun read(name: String): String? = files[name]
    override suspend fun write(name: String, text: String) { files[name] = text }
    override suspend fun delete(name: String) { files.remove(name) }
}
