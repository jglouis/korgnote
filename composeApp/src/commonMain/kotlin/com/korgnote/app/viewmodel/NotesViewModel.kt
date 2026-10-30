package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
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
import com.korgnote.app.widgets.AgendaWidgets
import com.korgnote.app.widgets.NoAgendaWidgets
import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.data.SavedSearch
import com.korgnote.common.fs.NotePaths
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.DraftLink
import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.replaceOrgTable
import com.korgnote.common.org.edit.tableFormulaLine
import com.korgnote.common.org.edit.linkAt
import com.korgnote.common.org.edit.orgLinkOf
import com.korgnote.common.org.edit.withLink
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.fileKeywordsOf
import com.korgnote.common.org.edit.withFileKeywords
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.tickAsOrgDoes
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.link.withFileId
import com.korgnote.common.org.link.withHeadingId
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.edit.updateStatisticsCookies
import com.korgnote.common.org.template.dailyFileName
import com.korgnote.common.org.template.dailyTitle
import com.korgnote.common.org.template.newNoteText
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.sync.DiffHunk
import com.korgnote.common.sync.conflictCopyOriginal
import com.korgnote.common.sync.diffHunks
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import korgnote.composeapp.generated.resources.vm_not_a_note_path
import korgnote.composeapp.generated.resources.vm_note_exists
import korgnote.composeapp.generated.resources.vm_note_title_needed
import korgnote.composeapp.generated.resources.vm_table_changed
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
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The app's notes, and everything shown about them.
 *
 * Each concern is its own state holder — [library], [note], [folds], [pins], [editor],
 * [completion], [search], [savedSearches], [links], [files], [agenda], [server] — and the UI talks to them directly. What stays here is what
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
    /** Sends an edit made offline once the network is back, with the app closed if need be. */
    private val backgroundSync: BackgroundSync = NoBackgroundSync,
    /** Read each time reminders are planned, so a change in Settings applies on the next plan. */
    reminderSettings: () -> ReminderSettings = { ReminderSettings() },
    /** The home-screen widgets showing the agenda: Android's, or none. */
    widgets: AgendaWidgets = NoAgendaWidgets,
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
    /** The searches saved on this device, read once; and where a change to them is kept. */
    savedSearches: () -> List<SavedSearch> = { emptyList() },
    saveSavedSearches: (List<SavedSearch>) -> Unit = {},
    /** Where linked folders are on this device, read on each tap; and what opens a linked file. */
    fileLinkMappings: () -> List<FileLinkMapping> = { emptyList() },
    fileOpener: FileOpener = NoFileOpener,
    /** Where scans of every note run when they need not hold up the screen; see [refreshDerivedViewsSoon]. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The folder [store] keeps the notes in, when they are a folder of the user's rather than the
     * replica: there is then no server to sync with, only the folder to keep looking at.
     */
    folder: FolderNoteStorage? = null,
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
        viewModelScope,
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

    val savedSearches: SavedSearches = SavedSearches(savedSearches, saveSavedSearches)

    val agenda: NoteAgenda = NoteAgenda(
        viewModelScope, library, today, notifications, reminderSettings, now, open = ::open,
        widgets = widgets,
        // Read when the agenda is shown, by which time [server] is there.
        lastSynced = { server.lastSynced },
    )

    /** Where the notes are kept, kept in step with the screen: the server, or the folder. */
    val server: NotesSync = if (folder != null) {
        FolderSync(viewModelScope, folder, onReplicaChanged = ::showFromReplica, showMessage = ::showMessage)
    } else {
        ServerSync(viewModelScope, sync, onReplicaChanged = ::showFromReplica, showMessage = ::showMessage, background = backgroundSync)
    }

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

    /**
     * Rebuilds everything the UI shows out of the replica, the scans of every note away from the
     * main thread: run on each sync, they held the screen still for seconds on a slow phone. Returns
     * once everything is shown, as [refreshDerivedViews] does.
     */
    private suspend fun showFromReplica() {
        library.reload(parseOn = background)
        pendingRefresh?.cancel()
        pendingRefresh = null
        val texts = library.texts()
        val keywords = withContext(background) { library.corpusKeywords }
        val linkTables = withContext(background) { LinkNavigator.Tables(texts) }
        val agendaIndex = withContext(background) { agenda.build(texts, keywords) }
        links.show(linkTables)
        agenda.show(agendaIndex)
        search.refresh()
        completion.refreshContext()

        // The open note may be gone: deleted here, or on another device. With nothing open — at
        // launch, say — the first pinned note opens: it was pinned to be at hand.
        val current = note.path?.takeIf { it in library }
        if (current == null) {
            note.path = null
            val first = pins.shown.firstOrNull() ?: library.documents.keys.minOrNull()
            if (first != null) onFileSelected(first) else note.close()
        } else {
            store.read(current)?.let { fresh ->
                // An element is open: the note stays as it is under it, or the element would move
                // or vanish under the caret — a heading being added is not in the replica until it
                // is saved. The editor makes its edit on what came in, when it saves.
                if (editor.editingIndex != null) {
                    if (fresh != editor.textBeforeInsertion && fresh != note.content) editor.noteChangedElsewhere(fresh)
                    return@let
                }
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
        // A heading's `[2/5]` counts what is under it, which the edit may have changed — and only
        // those over the edit: the rest of the note is saved as it was.
        note.content = updateStatisticsCookies(note.content, todoKeywords, before = store.read(path))
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
        val updated = document.tickAsOrgDoes(item).withStatistics(since = document)
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
        val updated = document.changeTodoState(heading, keyword, now(), todoKeywords).withStatistics(since = document)
        if (updated.text == document.text) return
        note.content = updated.text
        library.put(file, updated)
        refreshDerivedViews()

        scope?.launch { server.push(writeFirst = { store.save(file, note.content) }) }
    }

    /**
     * This document with the statistics cookies over what changed [since] counting what is now
     * under them, as Org updates them.
     */
    private fun OrgDocument.withStatistics(since: OrgDocument): OrgDocument =
        updateStatisticsCookies(text, todoKeywords, before = since.text).let { if (it == text) this else it.toOrgDocument() }

    /**
     * Every note and heading a link can go to, for the heading-link picker: the open note as it
     * stands on screen, so that its offsets are the ones the editor holds. The open note itself is
     * left out as a whole, and so is a heading with no title yet, such as the one just added.
     */
    fun linkTargets(): List<LinkTarget> {
        val documents = library.documents.toMutableMap()
        note.path?.let { documents[it] = note.content.toOrgDocument() }
        return linkTargetsOf(documents).filter { target ->
            !(target.isNote && target.path == note.path) && target.title.isNotBlank()
        }
    }

    /**
     * Writes a link to [target] at the caret of the element being edited, as org-roam's
     * `org-roam-node-insert` does: by its `:ID:`, which a heading without one is given first.
     *
     * The id goes where the heading is. In another note it is saved and pushed at once, as Emacs
     * would; harmless if the link is then discarded. In the open note it waits for the element's
     * save, which is the save that writes the link — and the heading being edited gets it in its
     * draft.
     */
    suspend fun insertLinkTo(target: LinkTarget) = writeLinkTo(target, description = null, replacing = null)

    /**
     * [insertLinkTo], showing [description] where that is not null, and in place of [replacing] —
     * a link being changed — rather than of the selection.
     */
    private suspend fun writeLinkTo(target: LinkTarget, description: String?, replacing: TextRange?) {
        val index = editor.editingIndex ?: return
        val draft = editor.activeDraft ?: return
        val id = target.id ?: newId()
        val link = if (description == null) target.linkWith(id) else orgLinkOf("id:$id", description)
        val range = replacing ?: draft.selection
        val from = range.min
        var text = draft.text.replaceRange(from, range.max.coerceAtMost(draft.text.length), link)
        var caret = from + link.length
        val path = target.path
        if (target.id != null) {
            // Already has one: nothing to write but the link.
        } else if (path == note.path) {
            val element = note.content.orgElements().getOrNull(index)
            if (element != null && target.offset == element.start) {
                val withId = withHeadingId(text, 0, id)
                if (text.firstDifferenceWith(withId) < caret) caret += withId.length - text.length
                text = withId
            } else {
                note.content = withHeadingId(note.content, target.offset ?: return, id)
            }
        } else {
            val before = library[path]?.text ?: return
            val updated = target.offset?.let { withHeadingId(before, it, id) } ?: withFileId(before, id)
            if (updated != before) {
                store.save(path, updated)
                library.put(path, updated.toOrgDocument())
                refreshDerivedViewsSoon()
                server.pushQuietly()
            }
        }
        // The edit may have ended while the other note was written; the link then has nowhere to go.
        if (editor.editingIndex != index) return
        editor.replaceDraft(TextFieldValue(text, TextRange(caret)))
        completion.onDraftChanged()
    }

    /**
     * The link the link form opens on: the one the caret is in, or a new one in place of the
     * selection, showing the words selected.
     */
    fun linkToEdit(): LinkEdit? {
        val draft = editor.activeDraft ?: return null
        if (draft.selection.collapsed) {
            linkAt(draft.text, draft.selection.start)?.let { return LinkEdit(it, isNew = false) }
        }
        val selected = draft.text.substring(draft.selection.min, draft.selection.max)
        return LinkEdit(
            DraftLink(draft.selection.min, draft.selection.max, "", if ('\n' in selected) "" else selected),
            isNew = true,
        )
    }

    /** Writes what the link form says in place of [edit]'s link; to a note by its id, given one first. */
    suspend fun saveLink(edit: LinkEdit, target: String, description: String, note: LinkTarget? = null) {
        val range = TextRange(edit.link.start, edit.link.end)
        if (note != null) return writeLinkTo(note, description, range)
        val draft = editor.activeDraft ?: return
        if (edit.link.end > draft.text.length) return
        val result = withLink(draft.text, edit.link.start, edit.link.end, target, description)
        editor.replaceDraft(TextFieldValue(result.text, TextRange(result.caret)))
        completion.onDraftChanged()
    }

    /** [edit]'s link turned back into the words it shows, or its target when it shows nothing else. */
    fun removeLink(edit: LinkEdit) {
        val draft = editor.activeDraft ?: return
        if (edit.link.end > draft.text.length) return
        val words = edit.link.description.ifBlank { edit.link.target }
        val text = draft.text.replaceRange(edit.link.start, edit.link.end, words)
        editor.replaceDraft(TextFieldValue(text, TextRange(edit.link.start + words.length)))
        completion.onDraftChanged()
    }

    /** What the link form says a note link leads to: the note's or heading's title, or the link as written. */
    fun noteLinkTitle(target: String): String {
        val id = target.trim().takeIf { it.startsWith("id:", ignoreCase = true) }?.substring(3)?.trim()
        val found = id?.let { wanted -> linkTargets().firstOrNull { it.id == wanted } }
        return found?.title ?: target
    }

    /** Where this text and [other] first differ. */
    private fun String.firstDifferenceWith(other: String): Int =
        indices.firstOrNull { it >= other.length || this[it] != other[it] } ?: length

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
            showMessage(getString(Res.string.vm_finish_editing_first))
            return
        }
        val updated = withFileKeywords(note.content, keywords)
        if (updated == note.content) return
        note.content = updated
        saveCurrentFile()
    }

    /**
     * A table of the open note being edited as a grid, as [beginTableEdit] opened it: [original] is
     * its text as it was then, its `#+TBLFM:` line included.
     */
    data class TableEdit(val path: String, val range: IntRange, val original: String, val grid: TableGrid)

    /** The table being edited in its own editor, or null when none is. */
    var tableEdit by mutableStateOf<TableEdit?>(null)
        private set

    /**
     * Opens the table whose source contains [offsetInDocument] as a grid, cell by cell: a table is
     * all but impossible to keep aligned as text on a phone. Refused while an element is being
     * edited, as the note's details are.
     */
    fun beginTableEdit(offsetInDocument: Int) {
        val path = note.path ?: return
        if (editor.editingIndex != null) {
            viewModelScope.launch { showMessage(getString(Res.string.vm_finish_editing_first)) }
            return
        }
        val content = note.content
        val table = content.toOrgDocument().nodeAt<OrgTable>(offsetInDocument) ?: return
        val formulas = tableFormulaLine(content, table.range.last + 1)
        val end = formulas?.range?.let { it.last + 1 } ?: (table.range.last + 1)
        tableEdit = TableEdit(path, table.range, content.substring(table.range.first, end), TableGrid.of(table, formulas?.formulas.orEmpty()))
    }

    fun cancelTableEdit() {
        tableEdit = null
    }

    /**
     * Writes [grid] over the table being edited, aligned, and saves. Nothing is written when nothing
     * changed — not even the alignment — or when the table's text moved while the editor was open,
     * say by a sync, since the range would then cover something else.
     */
    suspend fun commitTableEdit(grid: TableGrid) {
        val edit = tableEdit ?: return
        tableEdit = null
        if (grid == edit.grid) return
        val content = note.content
        val end = edit.range.first + edit.original.length
        val unchanged = note.path == edit.path && end <= content.length &&
            content.substring(edit.range.first, end) == edit.original
        if (!unchanged) {
            showMessage(getString(Res.string.vm_table_changed))
            return
        }
        note.content = replaceOrgTable(content, edit.range, grid)
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
            lastErrorMessage = getString(Res.string.vm_note_title_needed)
            return null
        }
        if (!store.create(path, headOfNewNote(title, tags))) {
            lastErrorMessage = getString(Res.string.vm_note_exists, path)
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
            lastErrorMessage = getString(Res.string.vm_not_a_note_path, to)
            return false
        }
        if (target == from) return true
        val wasOpen = note.path == from
        // The element editor holds offsets into the note being moved; finish nothing half-way.
        if (wasOpen) editor.cancel()
        if (!store.rename(from, target)) {
            lastErrorMessage = getString(Res.string.vm_note_exists, target)
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
