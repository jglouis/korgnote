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
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Where scans of every note run when they need not hold up the screen; see [rebuildDerived]. */
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
    val places: ReadingPlaces = ReadingPlaces(readingPlaces, saveReadingPlaces, note, viewModelScope)

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
        refresh = ::rebuildDerived,
        refreshSoon = ::refreshDerivedViewsSoon,
        showReplica = { showFromReplica() },
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
        showReplica = { showFromReplica() },
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

    /** The table being edited as a grid. */
    val tables: TableEditing = TableEditing(viewModelScope, library, note, editor, changes, showMessage = ::showMessage)

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
        FolderSync(viewModelScope, folder, onReplicaChanged = { showFromReplica() }, showMessage = ::showMessage)
    } else {
        ServerSync(
            viewModelScope,
            sync,
            onReplicaChanged = { showFromReplica() },
            onNotesChanged = { showFromReplica(it) },
            showMessage = ::showMessage,
            background = backgroundSync,
        )
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
        places.saveReadingPlace()
        note.beginOpening(path, hasText)
        if (hasText) places.opened(path)
        links.onOpened()
        // This file's `#+TODO:` keywords and headings are part of what completion offers.
        completion.onNoteOpened()
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

    // --- Keeping everything in step with the notes ---

    /**
     * Rebuilds everything computed from the notes: links, the agenda and its reminders, the
     * filtered tree, the search results and what completion offers.
     *
     * Called wherever the library changes — a sync, a save, a checkbox — because all of these go
     * stale together and forgetting one of them is invisible until someone notices results that
     * describe a document as it used to be.
     *
     * The scans of every note run away from the main thread: there, they held the screen still for
     * seconds on a slow phone, and a tick long enough to be felt. One at a time, each from the notes
     * as they are when its turn comes, so that an older rebuild cannot land after a newer one.
     */
    private suspend fun rebuildDerived() = rebuilding.withLock {
        // This one is up to date; one put off would only redo it.
        pendingRefresh?.cancel()
        pendingRefresh = null
        // Read here, on the main thread; what is built from them reads nothing else.
        val texts = library.texts()
        val keywords = library.fallbackKeywords
        val linkTables = withContext(background) { LinkNavigator.Tables(texts) }
        val agendaIndex = withContext(background) { agenda.build(texts, keywords) }
        // Links first: completion offers the ids and titles they collect.
        links.show(linkTables)
        agenda.show(agendaIndex)
        search.refresh()
        completion.refreshContext()
        clocks.refresh()
    }

    private val rebuilding = Mutex()

    /** A refresh put off by [refreshDerivedViewsSoon], until it runs or another replaces it. */
    private var pendingRefresh: Job? = null

    /**
     * [rebuildDerived] for a change that barely touches what is derived, such as a ticked checkbox:
     * once changes stop coming, since a run of ticks would pay for the scans on each one. Meanwhile
     * the links, agenda and search describe the note as it was a moment ago, which a checkbox cannot
     * make wrong in any way that matters.
     */
    private fun refreshDerivedViewsSoon() {
        pendingRefresh?.cancel()
        pendingRefresh = viewModelScope.launch {
            delay(DERIVED_REFRESH_PAUSE)
            pendingRefresh = null
            rebuildDerived()
        }
    }

    /** Reads the notes again. A change on its way to the replica would otherwise be read as it was before it. */
    private suspend fun reloadLibrary(only: Set<String>? = null) {
        changes.whenWritten()
        library.reload(parseOn = background, only = only)
    }

    /** Rebuilds everything the UI shows out of the replica. Returns once everything is shown. */
    private suspend fun showFromReplica() = showFromReplica(changed = null)

    /** [showFromReplica], when it is known which notes [changed]: only those are read again. */
    private suspend fun showFromReplica(changed: Set<String>?) {
        reloadLibrary(changed)
        rebuildDerived()

        // The open note may be gone: deleted here, or on another device. With nothing open — at
        // launch, say — the note last read opens, where it was left; failing that the first pinned
        // note, since it was pinned to be at hand.
        val current = note.path?.takeIf { it in library }
        if (current == null && note.path != null && editor.editingIndex != null && !editor.isUntouched) {
            // Except under an edit with changes: what was typed is not lost to a deletion made
            // elsewhere. It stays open, for its save to bring the note back or its discard to end it.
        } else if (current == null) {
            note.path = null
            if (editor.editingIndex != null) editor.cancel()
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
        // Only for what reads them, and not before the notes are read: it would be told there are none.
        if (library.loaded && filer.offersHeadings) filer.offerHeadings(linkTargetsOf(library.documents))
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
    }
}
