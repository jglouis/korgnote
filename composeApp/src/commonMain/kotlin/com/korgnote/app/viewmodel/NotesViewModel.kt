package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
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
import com.korgnote.app.capture.CaptureFiler
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.ReadingPlace
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.replaceOrgTable
import com.korgnote.common.org.edit.tableFormulaLine
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import korgnote.composeapp.generated.resources.vm_table_changed
import kotlinx.coroutines.CoroutineDispatcher
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
 * [completion], [search], [savedSearches], [links], [files], [agenda], [server], [changes], [noteFiles] — and the UI talks to them directly. What stays here is what
 * crosses them: opening a note, which every one of them follows; rebuilding what is derived from the
 * notes when they change.
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
    /** Where the notes read on this device were left, read once; and where a change to them is kept. */
    readingPlaces: () -> List<ReadingPlace> = { emptyList() },
    saveReadingPlaces: (List<ReadingPlace>) -> Unit = {},
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
    /** Files what was captured — shared from another app, or by a link — into these notes; none in the tests. */
    private val captures: CaptureFiler? = null,
    /** Where a running clock shows outside the app: Android's ongoing notification, or nowhere. */
    clockNotifier: ClockNotifier = NoClockNotifier,
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

    /** Where each note was left, so it opens there again; the first is the one the app opens on. */
    val places: ReadingPlaces = ReadingPlaces(readingPlaces, saveReadingPlaces)

    /** The clocks running in the notes, for the bar that shows them on every tab. */
    val clocks: NoteClocks = NoteClocks(library, clockNotifier)

    val editor: ElementEditor = ElementEditor(
        note,
        folds,
        showMessage = ::showMessage,
        save = { changes.saveCurrentFile() },
        draftMoved = { completion.onDraftChanged() },
        editEnded = { completion.reset() },
        editDiscarded = {
            // The note was deleted elsewhere and stayed open only for this edit.
            val path = note.path
            if (path != null && library.loaded && path !in library && note.replicaText != null) {
                viewModelScope.launch { showFromReplica() }
            }
        },
    )

    /** What changes a note's text. */
    val changes: NoteChanges = NoteChanges(
        viewModelScope,
        store,
        library,
        note,
        editor,
        clocks,
        now,
        todoKeywords = { todoKeywords },
        // Read when something is pushed, by which time [server] is there.
        server = { server },
        refresh = ::refreshDerivedViews,
        refreshSoon = ::refreshDerivedViewsSoon,
        showReplica = ::showFromReplica,
        showMessage = ::showMessage,
    )

    /** Notes as files: creating, renaming and deleting them, and settling a conflict with a copy. */
    val noteFiles: NoteFiles = NoteFiles(
        store,
        library,
        note,
        folds,
        pins,
        places,
        editor,
        today,
        newNotesFolder,
        noteTemplate,
        newId,
        scope = viewModelScope,
        reload = { reloadLibrary() },
        showReplica = ::showFromReplica,
        open = { path -> onFileSelected(path) },
        openAndWait = ::openAndWait,
        refreshSoon = ::refreshDerivedViewsSoon,
        // Read when something is pushed, by which time [server] is there.
        server = { server },
        showMessage = ::showMessage,
    )

    /** Starting and stopping clocks, and setting efforts. */
    val clockActions: ClockActions = ClockActions(viewModelScope, note, clocks, changes, now)

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

    /** Keywords, and the notes a keyword's `@` asks for. */
    val stateNotes: StateNotes = StateNotes(
        viewModelScope,
        library,
        note,
        links,
        changes,
        today,
        now,
        author = { noteTemplate().author },
        todoKeywords = { todoKeywords },
        openAndWait = ::openAndWait,
        showMessage = ::showMessage,
    )

    /**
     * Completion for a field of its own, such as a cell of the table editor: what the element
     * editor draws on — this note's headings, every note's paths and tags — over [draft], with an
     * accepted suggestion written back through [replaceDraft]. Its material is gathered now, once,
     * for a field that is open for a few moments.
     */
    fun completionFor(draft: () -> TextFieldValue?, replaceDraft: (TextFieldValue) -> Unit): CompletionController =
        CompletionController(library, note, links, today, draft, replaceDraft, author = { noteTemplate().author })
            .also { it.refreshContext() }

    /** What the link form and the link picker do with the element being edited. */
    val linkForm: LinkForm = LinkForm(library, note, editor, completion, changes, newId, refreshSoon = ::refreshDerivedViewsSoon, server = { server })

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
        if (path != note.path && !leaveEdit()) return
        if (!beginOpening(path)) return
        // From the replica, so opening a note works offline and does not wait on a round trip.
        viewModelScope.launch { finishOpening(path, scrollToOffset) }
    }

    /**
     * Whether another note may be opened over the element being edited, if one is. An edit with
     * nothing typed into it — the heading a new note opens on, say — is dropped. One with changes
     * keeps the note where it is, with a word to save or discard it: kept open across the switch,
     * it showed over the other note's element at the same place, and its save was refused there.
     */
    private fun leaveEdit(): Boolean {
        if (editor.editingIndex == null) return true
        if (editor.isUntouched) {
            editor.cancel()
            return true
        }
        viewModelScope.launch { showMessage(getString(Res.string.vm_finish_editing_first)) }
        return false
    }

    /** [onFileSelected] for callers that go on to edit the note and so must wait for its text. */
    private suspend fun openAndWait(path: String) {
        if (beginOpening(path)) finishOpening(path, scrollToOffset = null)
    }

    /** The part of opening that needs no text. False when there is no text to wait for. */
    private fun beginOpening(path: String): Boolean {
        val hasText = path in library
        // Where the note being left was left, while its text is still the one on screen.
        saveReadingPlace()
        note.beginOpening(path, hasText)
        if (hasText) places.opened(path)
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
        changes.whenWritten()
        val shown = note.content
        val raw = store.read(path)
        if (note.path == path && raw != null) {
            // A change made to it while that was read is newer than what was read.
            val current = note.content != shown || changes.isWriting(path)
            // A jump must never land inside a fold: open everything around the target, and
            // the target itself, since what was asked for may be in its body.
            val index = if (current) {
                note.show(note.content, scrollToOffset, places.placeOf(path), fromReplica = note.replicaText)
            } else {
                note.show(raw, scrollToOffset, places.placeOf(path))
            }
            index?.let { folds.unfoldAncestorsOf(it, includingItself = true) }
        }
    }

    /** Where the reader is in the open note, by element, not yet kept in [places]. */
    private var unsavedPlace: Pair<String, ReadingSpot>? = null
    private var placeSave: Job? = null

    /**
     * The reading view of [path] now has [elementIndex] at its top, scrolled [pixels] into it: to
     * be kept as where the note was left, once the scrolling stops. Ignored while the view still
     * shows where it was before this note was opened, and for a note no longer open.
     *
     * Called on every frame of a scroll, so it only notes the spot: working out where it is in the
     * text, and storing it, wait for [saveReadingPlace].
     */
    fun onReadingPlaceChanged(path: String, elementIndex: Int, pixels: Int) {
        if (path != note.path || !note.settled) return
        unsavedPlace = path to ReadingSpot(elementIndex, pixels)
        placeSave?.cancel()
        placeSave = viewModelScope.launch {
            delay(PLACE_SAVE_PAUSE)
            saveReadingPlace()
        }
    }

    /**
     * Keeps where the reader is in the open note now, rather than once scrolling stops: before
     * another note replaces it, or when the app goes to the background and may not come back.
     */
    fun saveReadingPlace() {
        placeSave?.cancel()
        placeSave = null
        val (path, spot) = unsavedPlace ?: return
        unsavedPlace = null
        // The element is one of the open note's: of any other, its index means nothing.
        if (path != note.path) return
        val elements = note.content.orgElements()
        // Past the last element is the backlinks below it: the last element is near enough.
        val element = elements.getOrNull(spot.elementIndex) ?: elements.lastOrNull() ?: return
        places.record(path, element.start, if (spot.elementIndex in elements.indices) spot.pixels else 0)
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
        clocks.refresh()
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
            val keywords = library.fallbackKeywords
            val linkTables = withContext(background) { LinkNavigator.Tables(texts) }
            val agendaIndex = withContext(background) { agenda.build(texts, keywords) }
            pendingRefresh = null
            links.show(linkTables)
            agenda.show(agendaIndex)
            search.refresh()
            completion.refreshContext()
            clocks.refresh()
        }
    }

    /** Reads the notes again. A change on its way to the replica would otherwise be read as it was before it. */
    private suspend fun reloadLibrary() {
        changes.whenWritten()
        library.reload(parseOn = background)
    }

    /**
     * Rebuilds everything the UI shows out of the replica, the scans of every note away from the
     * main thread: run on each sync, they held the screen still for seconds on a slow phone. Returns
     * once everything is shown, as [refreshDerivedViews] does.
     */
    private suspend fun showFromReplica() {
        reloadLibrary()
        pendingRefresh?.cancel()
        pendingRefresh = null
        val texts = library.texts()
        val keywords = withContext(background) { library.fallbackKeywords }
        val linkTables = withContext(background) { LinkNavigator.Tables(texts) }
        val agendaIndex = withContext(background) { agenda.build(texts, keywords) }
        links.show(linkTables)
        agenda.show(agendaIndex)
        search.refresh()
        completion.refreshContext()
        clocks.refresh()

        // The open note may be gone: deleted here, or on another device. With nothing open — at
        // launch, say — the note last read opens, where it was left; failing that the first pinned
        // note, since it was pinned to be at hand.
        val current = note.path?.takeIf { it in library }
        if (current == null && note.path != null && editor.editingIndex != null && !editor.isUntouched) {
            // Except under an edit with changes: what was typed is not lost to a deletion made
            // elsewhere. It stays open, for its save to bring the note back or its discard to end it.
        } else if (current == null) {
            note.path = null
            editor.cancel()
            val first = places.lastRead { it in library } ?: pins.shown.firstOrNull() ?: library.documents.keys.minOrNull()
            if (first != null) onFileSelected(first) else note.close()
        } else {
            val shown = note.content
            store.read(current)?.let { fresh ->
                // Another note was opened while that was read, and shows its own text.
                if (note.path != current) return@let
                // An element is open: the note stays as it is under it, or the element would move
                // or vanish under the caret — a heading being added is not in the replica until it
                // is saved. The editor makes its edit on what came in, when it saves.
                if (editor.editingIndex != null) {
                    if (fresh != editor.textBeforeInsertion && fresh != note.content) editor.noteChangedElsewhere(fresh)
                    return@let
                }
                // A change made to it while that was read is newer than what was read.
                if (note.content == shown && !changes.isWriting(current)) {
                    note.content = fresh
                    note.replicaText = fresh
                }
            }
        }
        agenda.onNotesShown()
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
        val table = content.toOrgDocument(library.fallbackKeywords).nodeAt<OrgTable>(offsetInDocument) ?: return
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
        changes.saveCurrentFile()
    }

    /** The filer captures go through, with these notes' templates and inbox; null where nothing captures. */
    val captureFiler: CaptureFiler? get() = captures

    /** Every note and heading a capture can go to, from the notes the app has read already. */
    fun captureDestinations(): List<LinkTarget> = captures?.destinationsAmong(linkTargetsOf(library.documents)).orEmpty()

    /** The notes captures are filed into: the ones on screen. */
    val captureStore: DocumentStore get() = store

    /**
     * Files the captures waiting into the notes, then shows and pushes the notes they went into. Run
     * when the notes are first shown and whenever the app comes back, since the share sheet may
     * have queued some meanwhile, and when this process queues one.
     */
    suspend fun fileCaptures() {
        val filer = captures ?: return
        filer.offerHeadings(linkTargetsOf(library.documents))
        if (filer.fileWaiting(store).isEmpty()) return
        showFromReplica()
        server.pushQuietly()
    }

    internal companion object {
        /**
         * How long the ticking has to stop before [refreshDerivedViewsSoon] refreshes: long enough
         * to take a run of ticks down a list as one, short enough that the calendar is right by the
         * time anyone switches to it.
         */
        val DERIVED_REFRESH_PAUSE = 600.milliseconds

        /** How long scrolling has to stop before where the reader stopped is kept. */
        val PLACE_SAVE_PAUSE = 500.milliseconds
    }
}
