package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.FileOpener
import com.korgnote.app.files.NoFileOpener
import com.korgnote.app.notifications.NoopNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.common.fs.NotePaths
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.fileKeywordsOf
import com.korgnote.common.org.edit.withFileKeywords
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.toggleCheckbox
import com.korgnote.common.org.template.dailyFileName
import com.korgnote.common.org.template.dailyTitle
import com.korgnote.common.org.template.newNoteText
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.sync.DiffHunk
import com.korgnote.common.sync.conflictCopyOriginal
import com.korgnote.common.sync.diffHunks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The app's notes, and everything shown about them.
 *
 * Each concern is its own state holder — [library], [note], [folds], [pins], [editor],
 * [completion], [search], [links], [files], [agenda], [server] — and the UI talks to them directly. What stays here is what
 * crosses them: opening a note, which every one of them follows; rebuilding what is derived from the
 * notes when they change; and the operations that change the notes as files — saving, creating,
 * renaming, deleting, and settling a conflict between a note and its copy.
 */
class NotesViewModel(
    sync: SyncEngine,
    /**
     * The local replica. Reads and writes go here first, which is what lets the app open and edit
     * notes with the server unreachable; the sync engine keeps it and the server in step.
     */
    private val store: DocumentStore = DocumentStore(InMemoryReplicaStorage()),
    /**
     * Today, for the dates completion offers and the agenda.
     *
     * A parameter rather than a call to the clock, so a test can say what day it is instead of
     * asserting against whatever day it runs on.
     */
    private val today: () -> LocalDate = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
    /** Where reminders go: the device's own notifications, or nowhere on desktop and web. */
    notifications: NotificationScheduler = NoopNotificationScheduler,
    /** Read each time reminders are planned, so a change in Settings applies on the next plan. */
    reminderSettings: () -> ReminderSettings = { ReminderSettings() },
    /**
     * The wall-clock time reminders are planned from and state changes are stamped with; a parameter
     * for the same reason as [today].
     */
    private val now: () -> LocalDateTime = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    },
    /** Where [createNote] puts a note; read each time, so a change in Settings applies at once. */
    private val newNotesFolder: () -> String = { "" },
    /** The author, daily folder and extra header new notes are written with; read each time too. */
    private val noteTemplate: () -> NoteTemplateSettings = { NoteTemplateSettings() },
    /** A new note's `:ID:`; a parameter so a test can know what it will be. */
    private val newId: () -> String = { @OptIn(ExperimentalUuidApi::class) Uuid.random().toString() },
    /** The notes pinned on this device, read once; and where a change to them is kept. */
    pinnedNotes: () -> List<String> = { emptyList() },
    savePinnedNotes: (List<String>) -> Unit = {},
    /** Where linked folders are on this device, read on each tap; and what opens a linked file. */
    fileLinkMappings: () -> List<FileLinkMapping> = { emptyList() },
    fileOpener: FileOpener = NoFileOpener,
    /** Where scans of every note run when they need not hold up the screen; see [refreshDerivedViewsSoon]. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** The one message line: why something could not be done, or what was done instead. */
    var lastErrorMessage by mutableStateOf("")
        private set

    fun clearError() {
        lastErrorMessage = ""
    }

    private fun showMessage(message: String) {
        lastErrorMessage = message
    }

    private fun open(path: String, offset: Int?) = onFileSelected(path, scrollToOffset = offset)

    val library: NoteLibrary = NoteLibrary(store)

    val note: OpenNote = OpenNote()

    val folds: Folds = Folds(note)

    val pins: NotePins = NotePins(library, pinnedNotes, savePinnedNotes)

    val editor: ElementEditor = ElementEditor(
        note,
        folds,
        showMessage = ::showMessage,
        save = ::saveCurrentFile,
        draftMoved = { completion.onDraftChanged() },
        editEnded = { completion.reset() },
    )

    val links: LinkNavigator = LinkNavigator(
        library,
        note,
        open = ::open,
        showMessage = ::showMessage,
        beforeGoingBack = { editor.cancel() },
    )

    val files: FileLinks = FileLinks(viewModelScope, fileLinkMappings, fileOpener, showMessage = ::showMessage)

    val completion: CompletionController = CompletionController(
        library,
        note,
        links,
        today,
        draft = { editor.activeDraft },
        replaceDraft = { editor.replaceDraft(it) },
        author = { noteTemplate().author },
    )

    val search: NoteSearch = NoteSearch(viewModelScope, library, open = ::open)

    val agenda: NoteAgenda = NoteAgenda(viewModelScope, library, today, notifications, reminderSettings, now, open = ::open)

    val server: ServerSync = ServerSync(viewModelScope, sync, onReplicaChanged = ::showFromReplica, showMessage = ::showMessage)

    /**
     * The open file's TODO keywords — its own `#+TODO:`, or what the notes declare when it has
     * none — for everything that renders or edits it.
     */
    val todoKeywords: OrgTodoConfig get() = completion.todoKeywords

    // --- Opening a note ---

    /**
     * Opens [path], and optionally brings a place in it into view once its text has arrived. The
     * place is a character offset: an agenda entry's heading, or a search hit's line.
     *
     * The target is resolved here rather than by the caller because the text loads asynchronously:
     * resolving it at the call site reads whichever file was open *before* this one and scrolls to
     * the wrong place, or nowhere at all.
     */
    fun onFileSelected(path: String, scrollToOffset: Int? = null) {
        if (!beginOpening(path)) return
        // From the replica, so opening a note works offline and does not wait on a round trip.
        viewModelScope.launch { finishOpening(path, scrollToOffset) }
    }

    /** [onFileSelected] for callers that go on to edit the note and so must wait for its text. */
    private suspend fun openAndWait(path: String) {
        if (beginOpening(path)) finishOpening(path, scrollToOffset = null)
    }

    /** The part of opening that needs no text. False when there is no text to wait for. */
    private fun beginOpening(path: String): Boolean {
        val hasText = path in library
        note.beginOpening(path, hasText)
        links.onOpened()
        // This file's `#+TODO:` keywords and headings are part of what completion offers.
        completion.refreshContext()
        return hasText
    }

    /**
     * Shows [path]'s text. The raw text is kept rather than re-serialised from the parse tree, so
     * saving an unchanged file round-trips it byte for byte.
     */
    private suspend fun finishOpening(path: String, scrollToOffset: Int?) {
        val raw = store.read(path)
        if (note.path == path && raw != null) {
            // A jump must never land inside a fold: open everything around the target, and
            // the target itself, since what was asked for may be in its body.
            note.show(raw, scrollToOffset)?.let { folds.unfoldAncestorsOf(it, includingItself = true) }
        }
    }

    // --- Keeping everything in step with the notes ---

    /**
     * Rebuilds everything computed from the notes: links, the agenda and its reminders, the
     * filtered tree, the search results and what completion offers.
     *
     * Called wherever the library changes — a sync, a save, a checkbox — because all of these go
     * stale together and forgetting one of them is invisible until someone notices results that
     * describe a document as it used to be.
     */
    private fun refreshDerivedViews() {
        // This one is up to date; one put off would only redo it.
        pendingRefresh?.cancel()
        pendingRefresh = null
        // Links first: completion offers the ids and titles they collect.
        links.refresh()
        agenda.refresh()
        search.refresh()
        completion.refreshContext()
    }

    /** A refresh put off by [refreshDerivedViewsSoon], until it runs or another replaces it. */
    private var pendingRefresh: Job? = null

    /**
     * [refreshDerivedViews] for a change that barely touches what they show, such as a ticked
     * checkbox: once changes stop coming, with the scans of every note away from the main thread.
     *
     * Run at once, the scans hold the tick up long enough to be felt on a phone, and a run of
     * ticks pays for them on each one. Meanwhile the links, agenda and search describe the note as
     * it was a moment ago, which a checkbox cannot make wrong in any way that matters.
     */
    private fun refreshDerivedViewsSoon() {
        pendingRefresh?.cancel()
        pendingRefresh = viewModelScope.launch {
            delay(DERIVED_REFRESH_PAUSE)
            // Read here, on the main thread; what is built from them reads nothing else.
            val texts = library.texts()
            val keywords = library.corpusKeywords
            val linkTables = withContext(background) { LinkNavigator.Tables(texts) }
            val agendaIndex = withContext(background) { agenda.build(texts, keywords) }
            pendingRefresh = null
            links.show(linkTables)
            agenda.show(agendaIndex)
            search.refresh()
            completion.refreshContext()
        }
    }

    /** Rebuilds everything the UI shows out of the replica. */
    private suspend fun showFromReplica() {
        library.reload()
        refreshDerivedViews()

        // The open note may be gone: deleted here, or on another device. With nothing open — at
        // launch, say — the first pinned note opens: it was pinned to be at hand.
        val current = note.path?.takeIf { it in library }
        if (current == null) {
            note.path = null
            val first = pins.shown.firstOrNull() ?: library.documents.keys.minOrNull()
            if (first != null) onFileSelected(first) else note.close()
        } else {
            store.read(current)?.let { fresh ->
                // A heading being inserted is not in the replica until it is saved. When the note
                // is otherwise unchanged — a sync that only pushed it — keep it on screen, or the
                // editor would be left pointing at a heading that is gone.
                val beforeInsertion = editor.textBeforeInsertion
                if (beforeInsertion != null && fresh == beforeInsertion) return@let
                note.content = fresh
            }
        }
        agenda.onNotesShown()
    }

    // --- Changing the notes ---

    /**
     * Writes the open note to the replica, then tries to push.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the next sync carries it.
     */
    suspend fun saveCurrentFile() {
        val path = note.path ?: return
        store.save(path, note.content)
        library.put(path, note.content.toOrgDocument())
        refreshDerivedViews()
        server.push()
    }

    /**
     * Ticks the checkbox whose source contains [offsetInDocument].
     *
     * The offset identifies the node rather than counting boxes: an ordinal shifts whenever anything
     * above it changes. The edit itself is a range replacement, so every other character in the file
     * — blank lines, trailing whitespace, formatting — is left exactly as it was on disk.
     */
    fun toggleCheckboxAt(offsetInDocument: Int, scope: CoroutineScope? = null) {
        val file = note.path ?: return
        val document = note.content.toOrgDocument()
        val item = document.nodeAt<OrgCheckboxItem>(offsetInDocument) ?: return
        val updated = document.toggleCheckbox(item)
        note.content = updated.text
        library.put(file, updated)
        // Not now: the box should flip the moment it is tapped.
        refreshDerivedViewsSoon()

        // Written locally in the coroutine, then pushed; a failure leaves the file dirty for the
        // next cycle rather than losing the toggle.
        scope?.launch { server.push(writeFirst = { store.save(file, note.content) }) }
    }

    /**
     * Sets the keyword of the heading at [offsetInDocument], or clears it when [keyword] is null, as
     * [toggleCheckboxAt] ticks a checkbox: the file is saved and pushed the same way.
     *
     * The change is logged under the heading with the time it was made, as Org logs a state change.
     * Marking a repeating entry done repeats it, as Org does: it goes back to its first keyword and
     * its dates move on to the next occurrence.
     */
    fun setTodoAt(offsetInDocument: Int, keyword: String?, scope: CoroutineScope? = null) {
        val file = note.path ?: return
        val document = note.content.toOrgDocument()
        val heading = document.nodeAt<OrgSection>(offsetInDocument) ?: return
        val updated = document.changeTodoState(heading, keyword, now(), todoKeywords)
        if (updated.text == document.text) return
        note.content = updated.text
        library.put(file, updated)
        refreshDerivedViews()

        scope?.launch { server.push(writeFirst = { store.save(file, note.content) }) }
    }

    /** What the open note says about itself — title, author, date, tags — for the details form. */
    fun openNoteKeywords(): FileKeywords = fileKeywordsOf(note.content)

    /**
     * Rewrites the open note's `#+TITLE:`, `#+AUTHOR:`, `#+DATE:` and `#+filetags:` to say
     * [keywords], then saves. Refused while an element is being edited, as a restructuring is: the
     * draft's offsets would move under it.
     */
    suspend fun setOpenNoteKeywords(keywords: FileKeywords) {
        if (note.path == null) return
        if (editor.editingIndex != null) {
            showMessage("Save or discard the element you are editing first.")
            return
        }
        val updated = withFileKeywords(note.content, keywords)
        if (updated == note.content) return
        note.content = updated
        saveCurrentFile()
    }

    /** Where a note titled [title] would be created, or null when the title makes no file name. */
    fun newNotePathFor(title: String): String? =
        NotePaths.fileNameFor(title)?.let { NotePaths.join(newNotesFolder(), it) }

    /**
     * Creates a note titled [title], tagged with [tags], in the new-notes folder, opens it, and starts
     * its first heading: a capture, so what was worth writing down gets written before anything else
     * is asked. It is headed as org-roam heads a note — its own `:ID:`, title, author and date.
     *
     * Returns the new path, or null — with [lastErrorMessage] saying why — when the title makes no
     * file name or a note already has it. Works offline; the next sync pushes it.
     */
    suspend fun createNote(title: String, tags: List<String> = emptyList()): String? {
        val path = newNotePathFor(title)
        if (path == null) {
            lastErrorMessage = "A note needs a title with at least one letter or digit."
            return null
        }
        if (!store.create(path, headOfNewNote(title, tags))) {
            lastErrorMessage = "There is already a note called $path."
            return null
        }
        openNewNote(path)
        return path
    }

    /** Where today's daily is, in the daily folder Settings names. */
    fun todaysDailyPath(): String = NotePaths.join(noteTemplate().dailyFolder, dailyFileName(today()))

    /**
     * Opens today's daily, creating it first — headed like any new note, titled with the date — when
     * there is none yet. A new one starts its first heading, as a new note does; one that exists is
     * only opened, since what is in it is what was being looked for.
     */
    suspend fun openToday() {
        val path = todaysDailyPath()
        if (path in library) {
            onFileSelected(path)
            return
        }
        // Already in the replica, though not yet read into the library: it is there to open.
        if (!store.create(path, headOfNewNote(dailyTitle(today()), emptyList()))) {
            onFileSelected(path)
            return
        }
        openNewNote(path)
    }

    private fun headOfNewNote(title: String, tags: List<String>): String {
        val template = noteTemplate()
        return newNoteText(
            id = newId(),
            title = title,
            date = today(),
            author = template.author,
            tags = tags,
            extraHeader = template.extraHeader,
        )
    }

    private suspend fun openNewNote(path: String) {
        editor.cancel()
        note.path = path
        showFromReplica()
        openAndWait(path)
        editor.addAtEnd()
        server.pushQuietly()
    }

    /**
     * Renames [from] to [to], a path as typed. Content, unsynced edits and folds go with it.
     * Returns false, with [lastErrorMessage] saying why, when [to] is not a valid path or is taken.
     */
    suspend fun renameNote(from: String, to: String): Boolean {
        val target = NotePaths.normalize(to)
        if (target == null) {
            lastErrorMessage = "\"$to\" is not a note path: use folders and a name, like notes/idea.org."
            return false
        }
        if (target == from) return true
        val wasOpen = note.path == from
        // The element editor holds offsets into the note being moved; finish nothing half-way.
        if (wasOpen) editor.cancel()
        if (!store.rename(from, target)) {
            lastErrorMessage = "There is already a note called $target."
            return false
        }
        folds.move(from, target)
        pins.move(from, target)
        if (wasOpen) note.path = target
        showFromReplica()
        if (wasOpen) openAndWait(target)
        server.pushQuietly()
        return true
    }

    /** True when [path] holds edits the server has not seen, which a delete would take with it. */
    suspend fun hasUnsyncedChanges(path: String): Boolean = store.isDirty(path)

    /**
     * Deletes [path] here and, at the next sync, on the server — only the version deleted here, so
     * an edit made elsewhere in the meantime brings the note back instead of being lost.
     * The server keeps a copy in its trash either way.
     */
    suspend fun deleteNote(path: String) {
        if (note.path == path) {
            editor.cancel()
            note.path = null
        }
        folds.forget(path)
        pins.forget(path)
        store.delete(path)
        showFromReplica()
        server.pushQuietly()
    }

    // --- Conflicts ---

    /**
     * The note [path] is a conflict copy of, or null when it is not one — or its note is gone, which
     * leaves nothing to conflict with.
     */
    fun conflictOriginalOf(path: String): String? = conflictCopyOriginal(path)?.takeIf { it in library }

    /** How [copy] differs from its note, change by change; empty when it does not, or is no copy. */
    fun conflictDiff(copy: String): List<DiffHunk> {
        val original = conflictOriginalOf(copy) ?: return emptyList()
        val ours = library[original]?.text ?: return emptyList()
        val theirs = library[copy]?.text ?: return emptyList()
        return diffHunks(ours, theirs)
    }

    /**
     * Settles a conflict by keeping the note as it is: [copy] is deleted, here and on the server,
     * which keeps it in its trash. A reader looking at the copy is taken to the note.
     */
    suspend fun discardConflictCopy(copy: String) {
        val original = conflictOriginalOf(copy)
        val wasOpen = note.path == copy
        deleteNote(copy)
        if (wasOpen && original != null) openAndWait(original)
    }

    /**
     * Settles a conflict in favour of [copy]: its text becomes the note's, pushed as an edit of the
     * server's version, and the copy is deleted. Returns false, changing nothing, when [copy] is not
     * a conflict copy of a note here.
     */
    suspend fun useConflictCopy(copy: String): Boolean {
        val original = conflictOriginalOf(copy) ?: return false
        val wasOpen = note.path == copy || note.path == original
        // The element editor holds offsets into a text about to be replaced.
        if (wasOpen) editor.cancel()
        if (!store.replaceWithCopy(original, copy)) return false
        folds.forget(copy)
        pins.forget(copy)
        if (wasOpen) note.path = original
        showFromReplica()
        if (wasOpen) openAndWait(original)
        server.pushQuietly()
        return true
    }

    internal companion object {
        /**
         * How long the ticking has to stop before [refreshDerivedViewsSoon] refreshes: long enough
         * to take a run of ticks down a list as one, short enough that the calendar is right by the
         * time anyone switches to it.
         */
        val DERIVED_REFRESH_PAUSE = 600.milliseconds
    }
}
