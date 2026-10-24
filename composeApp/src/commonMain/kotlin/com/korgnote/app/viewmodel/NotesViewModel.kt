package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.korgnote.app.notifications.NoopNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.fs.NotePaths
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.toggleCheckbox
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The app's notes, and everything shown about them.
 *
 * Each concern is its own state holder — [library], [note], [folds], [editor], [completion],
 * [search], [links], [agenda], [server] — and the UI talks to them directly. What stays here is what
 * crosses them: opening a note, which every one of them follows; rebuilding what is derived from the
 * notes when they change; and the operations that change the notes as files — saving, creating,
 * renaming, deleting.
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
    today: () -> LocalDate = {
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

    val completion: CompletionController = CompletionController(
        library,
        note,
        links,
        today,
        draft = { editor.activeDraft },
        replaceDraft = { editor.replaceDraft(it) },
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
        // Links first: completion offers the ids and titles they collect.
        links.refresh()
        agenda.refresh()
        search.refresh()
        completion.refreshContext()
    }

    /** Rebuilds everything the UI shows out of the replica. */
    private suspend fun showFromReplica() {
        library.reload()
        refreshDerivedViews()

        // The open note may be gone: deleted here, or on another device.
        val current = note.path?.takeIf { it in library }
        if (current == null) {
            note.path = null
            val first = library.documents.keys.minOrNull()
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
        refreshDerivedViews()

        // Written locally in the coroutine, then pushed; a failure leaves the file dirty for the
        // next cycle rather than losing the toggle.
        scope?.launch { server.push(writeFirst = { store.save(file, note.content) }) }
    }

    /**
     * Sets the keyword of the heading at [offsetInDocument], or clears it when [keyword] is null, as
     * [toggleCheckboxAt] ticks a checkbox: the file is saved and pushed the same way.
     *
     * The change is logged under the heading with the time it was made, as Org logs a state change.
     */
    fun setTodoAt(offsetInDocument: Int, keyword: String?, scope: CoroutineScope? = null) {
        val file = note.path ?: return
        val document = note.content.toOrgDocument()
        val heading = document.nodeAt<OrgSection>(offsetInDocument) ?: return
        val updated = document.changeTodoState(heading, keyword, now())
        if (updated.text == document.text) return
        note.content = updated.text
        library.put(file, updated)
        refreshDerivedViews()

        scope?.launch { server.push(writeFirst = { store.save(file, note.content) }) }
    }

    /** Where a note titled [title] would be created, or null when the title makes no file name. */
    fun newNotePathFor(title: String): String? =
        NotePaths.fileNameFor(title)?.let { NotePaths.join(newNotesFolder(), it) }

    /**
     * Creates a note titled [title] in the new-notes folder, opens it, and starts its first heading:
     * a capture, so what was worth writing down gets written before anything else is asked.
     *
     * Returns the new path, or null — with [lastErrorMessage] saying why — when the title makes no
     * file name or a note already has it. Works offline; the next sync pushes it.
     */
    suspend fun createNote(title: String): String? {
        val path = newNotePathFor(title)
        if (path == null) {
            lastErrorMessage = "A note needs a title with at least one letter or digit."
            return null
        }
        if (!store.create(path, "#+TITLE: ${title.trim()}\n\n")) {
            lastErrorMessage = "There is already a note called $path."
            return null
        }
        editor.cancel()
        note.path = path
        showFromReplica()
        openAndWait(path)
        editor.addAtEnd()
        server.pushQuietly()
        return path
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
        store.delete(path)
        showFromReplica()
        server.pushQuietly()
    }
}
