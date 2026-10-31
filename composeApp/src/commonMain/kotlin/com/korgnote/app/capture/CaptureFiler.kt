package com.korgnote.app.capture

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.common.fs.NotePaths
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.org.capture.Captured
import com.korgnote.common.org.capture.EntryParts
import com.korgnote.common.org.capture.HeadingAdditions
import com.korgnote.common.org.capture.HeadingRef
import com.korgnote.common.org.capture.captureInto
import com.korgnote.common.org.capture.fileUnder
import com.korgnote.common.org.capture.INBOX_FILE_NAME
import com.korgnote.common.org.capture.RenderedCapture
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.capturedFrom
import com.korgnote.common.org.capture.captureEntry
import com.korgnote.common.org.capture.fileCapture
import com.korgnote.common.org.template.newNoteText
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.org.corpusTodoKeywords
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.toOrgDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Files what waits in the [queue] into the notes: each capture made into an entry and added where
 * it was sent — the inbox unless the sheet said otherwise — or added to a heading, then taken off
 * the queue.
 *
 * Only ever adds to a note, after what is there, so a capture filed here while another device filed
 * one into the same inbox merges with it rather than making a conflict copy.
 */
class CaptureFiler(
    private val queue: CaptureQueue,
    /** The folder new notes go to, where the inbox is. */
    private val newNotesFolder: () -> String,
    /** How a new note is headed, for an inbox that does not exist yet. */
    private val noteTemplate: () -> NoteTemplateSettings,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = { @OptIn(ExperimentalUuidApi::class) Uuid.random().toString() },
) {
    /**
     * Every heading in [store]'s notes, to choose one to capture into: what the sheet offers when the
     * app's own notes are not at hand. A corpus' worth of parsing, so off the main thread.
     */
    suspend fun destinations(store: DocumentStore): List<LinkTarget> = withContext(Dispatchers.Default) {
        val texts = store.paths().filter { it.endsWith(".org") }.mapNotNull { path -> store.read(path)?.let { path to it } }.toMap()
        val corpus = corpusTodoKeywords(texts.values)
        destinationsAmong(linkTargetsOf(texts.mapValues { (_, text) -> text.toOrgDocument(corpus) }))
    }

    /** Where a capture can go among [targets]: every note and heading with a name. */
    fun destinationsAmong(targets: List<LinkTarget>): List<LinkTarget> = targets.filter { it.title.isNotBlank() }

    /** Where a capture goes unless another note is chosen: the inbox, in the folder new notes go to. */
    fun inbox(): String = NotePaths.join(newNotesFolder(), INBOX_FILE_NAME)

    /**
     * The TODO keywords of [path], in the order the note declares them: its own `#+TODO:`, or the
     * ones a new note is given, for a note not made yet; Org's `TODO` and `DONE` when it declares
     * none. A new entry can be marked with one not yet done.
     */
    suspend fun keywordsFor(store: DocumentStore, path: String): OrgTodoConfig =
        todoKeywordsOf(store.read(path) ?: noteTemplate().extraHeader, DEFAULT_TODO_KEYWORDS)

    /** Where a new note titled [title] would be made: in the new-notes folder; null when the title makes no file name. */
    fun newNotePath(title: String): String? = NotePaths.fileNameFor(title)?.let { NotePaths.join(newNotesFolder(), it) }

    /** Tells what captures without the app which headings there are to capture into. */
    suspend fun offerHeadings(targets: List<LinkTarget>) = queue.offerHeadings(destinationsAmong(targets).mapNotNull(HeadingRef::of))

    /** What [request] shared, read for what it is, with the title and note typed over it. */
    fun captured(request: CaptureRequest): Captured {
        val read = capturedFrom(request.shared, zone())
        return read.copy(
            title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: read.title,
            text = request.body?.trim('\n')
                ?: listOf(request.note?.trim().orEmpty(), read.text).filter { it.isNotEmpty() }.joinToString("\n"),
        )
    }

    /**
     * The entry [request] makes of what was shared, as of when it was captured, marked with its
     * keyword, with a place's [location] and the [link] as asked, or as fits what was shared.
     */
    fun render(request: CaptureRequest, location: Boolean? = null, link: Boolean? = null): RenderedCapture {
        val at = if (request.at > 0) Instant.fromEpochMilliseconds(request.at) else now()
        val captured = captured(request)
        val parts = EntryParts.defaultsFor(captured)
        return captureEntry(captured, at.toLocalDateTime(zone()), request.keyword, location ?: parts.location, link ?: parts.link)
    }

    /** A new capture of [shared] content, stamped now, for the queue. */
    fun request(shared: SharedContent): CaptureRequest =
        CaptureRequest(id = newId(), at = now().toEpochMilliseconds(), shared = shared)

    /**
     * Files every capture waiting into [store], oldest first; the notes it changed, which the caller
     * reads again and pushes. A capture that cannot be filed stays in the queue for next time.
     *
     * One filing at a time in the whole process: the app coming back and a share arriving could
     * otherwise both file the same capture.
     */
    suspend fun fileWaiting(store: DocumentStore): Set<String> = lock.withLock {
        val waiting = queue.waiting()
        if (waiting.isEmpty()) return@withLock emptySet()
        val changed = mutableSetOf<String>()
        for (request in waiting) {
            val into = request.into
            if (into != null) {
                when (addInto(store, into, captured(request), request.additions ?: HeadingAdditions.defaultsFor(captured(request)))) {
                    true -> {
                        changed += into.path
                        queue.remove(request.id)
                        continue
                    }
                    false -> continue
                    // The heading went while the capture waited: it becomes an entry in the inbox instead.
                    null -> Unit
                }
            }
            val entry = request.entry ?: render(request).text
            val under = request.under
            val path = under?.path ?: request.file ?: inbox()
            if (fileInto(store, path, entry, under, request.fileTitle)) {
                changed += path
                queue.remove(request.id)
            }
        }
        changed
    }

    /**
     * Adds [entry] at the end of [path], or as a sub-entry of [under], making the note — titled
     * [title], or after its file — when there is none; false when it kept moving. An [under] heading
     * gone meanwhile leaves the entry at the end of its note.
     */
    private suspend fun fileInto(
        store: DocumentStore,
        path: String,
        entry: String,
        under: HeadingRef? = null,
        title: String? = null,
    ): Boolean {
        fun filed(text: String) = under?.let { fileUnder(text, it, entry) } ?: fileCapture(text, entry)
        repeat(ATTEMPTS) {
            val current = store.read(path)
            if (current == null) {
                if (store.create(path, filed(newNoteHead(path, title)))) return true
            } else if (store.saveIfUnchanged(path, current, filed(current))) {
                return true
            }
        }
        return false
    }

    /**
     * Adds what [captured] says, as [additions] says, to the heading [into] names; false when the
     * note kept moving under the save, null when it no longer has that heading, or no longer exists.
     */
    private suspend fun addInto(store: DocumentStore, into: HeadingRef, captured: Captured, additions: HeadingAdditions): Boolean? {
        repeat(ATTEMPTS) {
            val current = store.read(into.path) ?: return null
            val changed = captureInto(current, into, captured, additions) ?: return null
            if (changed == current || store.saveIfUnchanged(into.path, current, changed)) return true
        }
        return false
    }

    /** The head of a note made to file into: titled [title], or after its file, `inbox.org` as Inbox. */
    private fun newNoteHead(path: String, title: String? = null): String {
        val template = noteTemplate()
        val name = path.substringAfterLast('/').removeSuffix(".org").replace('_', ' ')
        return newNoteText(
            id = newId(),
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: name.replaceFirstChar { it.uppercase() },
            date = now().toLocalDateTime(zone()).date,
            author = template.author,
            extraHeader = template.extraHeader,
        )
    }

    private companion object {
        /** Saves in a row lost to another write before this one gives up until next time. */
        const val ATTEMPTS = 5

        val lock = Mutex()
    }
}
