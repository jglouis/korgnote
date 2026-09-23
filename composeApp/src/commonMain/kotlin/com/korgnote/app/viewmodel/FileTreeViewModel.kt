package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncEngine
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.korgnote.common.org.*
import com.korgnote.common.org.complete.CompletionContext
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.org.complete.CompletionResult
import com.korgnote.common.org.complete.applyTo
import com.korgnote.common.org.complete.complete
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.complete.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.app.notifications.NoopNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.Reminder
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.app.notifications.ReminderTarget
import com.korgnote.app.notifications.planReminders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import com.korgnote.common.org.search.SearchHit
import com.korgnote.common.org.search.SearchQuery
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.search.searchOrgFile
import com.korgnote.common.org.edit.toggleCheckbox
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.korgnote.common.fs.File as SharedFile
import com.korgnote.common.fs.Folder as SharedFolder
import com.korgnote.common.fs.Node as SharedNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Simple ViewModel that holds a file tree and uses a repository to fetch org contents.
 */


class FileTreeViewModel(
    private val sync: SyncEngine,
    /**
     * The local replica. Reads and writes go here first, which is what lets the app open and edit
     * notes with the server unreachable. The network only populates it (T8); the sync engine that
     * pushes from it arrives in T10.
     */
    private val store: DocumentStore = DocumentStore(InMemoryReplicaStorage()),
    /**
     * Today, for the dates completion offers.
     *
     * A parameter rather than a call to the clock, so a test can say what day it is instead of
     * asserting against whatever day it runs on.
     */
    private val today: () -> LocalDate = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
    /** Where reminders go: the device's own notifications, or nowhere on desktop and web. */
    private val notifications: NotificationScheduler = NoopNotificationScheduler,
    /** Read each time reminders are planned, so a change in Settings applies on the next plan. */
    private val reminderSettings: () -> ReminderSettings = { ReminderSettings() },
    /** The wall-clock time reminders are planned from; a parameter for the same reason as [today]. */
    private val now: () -> LocalDateTime = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    },
) : ViewModel() {
    // Exposed error message when network/server cannot be reached
    var lastErrorMessage by mutableStateOf("")
        private set
    private var currentFile: String? = null


    // File tree populated from server; wrapped in state so UI recomposes
    var tree by mutableStateOf(
        Folder(
            name = "Documents",
            path = "",
            children = emptyList()
        )
    )

    // Filter query for the file tree, in the same language as search: words match the file name,
    // and a `tag:` term keeps the files that have a heading carrying it.
    var filterQuery by mutableStateOf("")
        private set

    /** [tree] narrowed to what [filterQuery] keeps; the whole tree when nothing is typed. */
    var filteredTree by mutableStateOf(tree)
        private set

    fun onFilterQueryChanged(query: String) {
        filterQuery = query
        filterJob?.cancel()
        // Clearing the box is not a search, so it takes effect at once rather than after a pause
        // that would leave a filtered tree on screen for no reason.
        if (query.isBlank()) {
            filteredTree = tree
            return
        }
        filterJob = viewModelScope.launch {
            delay(TYPING_PAUSE)
            refreshFilteredTree()
        }
    }

    /**
     * Recomputed when the query or the replica changes, rather than on every recomposition: a
     * `tag:` term has to look inside the files, and the drawer is redrawn far more often than
     * either of those two things moves.
     */
    private fun refreshFilteredTree() {
        val query = SearchQuery.parse(filterQuery)
        if (query.isEmpty) {
            filteredTree = tree
            return
        }
        // Only "does this file have anything to show", so the scan stops at each file's first hit.
        // The path goes in empty on purpose: whether the *name* matches is decided by [filterNode]
        // against the node's own name, and passing the real path here would match the file twice.
        val matching = orgContents
            .filterValues { searchOrgFile("", it.text, query, limit = 1).isNotEmpty() }
            .keys
        filteredTree = when (val filtered = filterNode(tree, query, matching)) {
            is Folder -> filtered
            is File -> Folder(name = tree.name, path = tree.path, children = listOf(filtered))
            else -> Folder(name = tree.name, path = tree.path, children = emptyList())
        }
    }

    private fun filterNode(node: Node, query: SearchQuery, matchingPaths: Set<String>): Node? {
        return when (node) {
            is File -> {
                val nameMatches = query.tags.isEmpty() &&
                    query.text.all { node.name.contains(it, ignoreCase = true) }
                if (nameMatches || node.path in matchingPaths) node else null
            }

            is Folder -> {
                val childMatches = node.children.mapNotNull { filterNode(it, query, matchingPaths) }
                // A folder name says nothing about tags, so only words can keep one on its own.
                val selfMatches = query.tags.isEmpty() &&
                    query.text.all { node.name.contains(it, ignoreCase = true) }
                if (selfMatches || childMatches.isNotEmpty()) Folder(
                    name = node.name,
                    path = node.path,
                    children = childMatches
                ) else null
            }

            else -> null
        }
    }

    // --- Search over the whole replica (T14) ---

    var searchQuery by mutableStateOf("")
        private set

    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /**
     * Records what was typed and searches once the typing stops.
     *
     * The query itself updates on the keystroke, so the field never lags; only the scan waits. It
     * waits because scanning the whole corpus costs tens of milliseconds on a laptop and several
     * times that on a phone, and doing it per character spends nearly all of it on prefixes nobody
     * asked about — "g", "gr", "gro" on the way to "groceries".
     *
     * Results already on screen stay while the next scan is pending: they are what was asked for a
     * moment ago, which is a better thing to show than a blank list that flickers on every letter.
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(TYPING_PAUSE)
            refreshSearch()
        }
    }

    /** In flight or pending; a new keystroke cancels it rather than queueing another. */
    private var searchJob: Job? = null
    private var filterJob: Job? = null

    /**
     * Runs the search over text already in memory.
     *
     * Synchronous on purpose: this is a linear scan of a few megabytes, which is milliseconds, and
     * a coroutine would only make the results arrive a frame after the keystroke that asked for
     * them. If a corpus ever makes this visible, the fix is an index, not a thread.
     */
    private fun refreshSearch() {
        searchResults = searchOrg(orgContents.mapValues { (_, doc) -> doc.text }, searchQuery)
    }

    /** Opens the file a result is in, at the element containing it. */
    fun openSearchHit(hit: SearchHit) = onFileSelected(hit.path, scrollToOffset = hit.offset)

    // --- Completion (T13) ---

    /**
     * What the editor's completion draws on.
     *
     * Rebuilt when the replica or the open file changes, never per keystroke: the tags and paths
     * are corpus-wide, and walking every document for them on each character typed is exactly the
     * cost the engine was kept pure to avoid.
     */
    var completionContext by mutableStateOf(CompletionContext(today = today()))
        private set

    /**
     * What the strip is showing, in order, or empty when there is nothing to offer.
     *
     * Recomputed on every keystroke, which is affordable because it is a rule check over one line
     * of one element; the corpus-wide material it draws on is in [completionContext] and is not.
     */
    var completionSuggestions by mutableStateOf<List<CompletionItem>>(emptyList())
        private set

    /** Which suggestion a hardware keyboard has moved to; the strip highlights it. */
    var selectedSuggestion by mutableStateOf(0)
        private set

    /** The item waiting on a date picker, or null when none is open. */
    var pendingDatePicker: CompletionItem? by mutableStateOf(null)
        private set

    /** Esc silences the suggestions for the current text; typing brings them back. */
    private var completionDismissed = false

    private fun refreshSuggestions() {
        val editing = editingElementIndex != null
        completionSuggestions = when {
            !editing || completionDismissed -> emptyList()
            // A selection is not a caret: completing over one would silently delete it.
            !elementDraftValue.selection.collapsed -> emptyList()
            else -> complete(elementDraft.toOrgDocument(), elementDraftCaret, completionContext)
        }
        // The list is rebuilt under the selection on every keystroke, so it starts from the top.
        selectedSuggestion = 0
    }

    /** Accepts [item]: a range replacement in the draft, with the caret where the item asks. */
    fun acceptCompletion(item: CompletionItem) {
        // The picker is the one suggestion that cannot answer for itself; it asks first.
        if (item.kind == CompletionItem.Kind.DATE_PICKER) {
            pendingDatePicker = item
            return
        }
        applyCompletion(item.applyTo(elementDraft))
    }

    /** Accepts whatever the keyboard has moved to. True when there was something to accept. */
    fun acceptSelectedCompletion(): Boolean {
        val item = completionSuggestions.getOrNull(selectedSuggestion) ?: return false
        acceptCompletion(item)
        return true
    }

    fun moveCompletionSelection(delta: Int) {
        val count = completionSuggestions.size
        if (count == 0) return
        selectedSuggestion = ((selectedSuggestion + delta) % count + count) % count
    }

    fun dismissCompletion() {
        completionDismissed = true
        completionSuggestions = emptyList()
    }

    /** Puts the date the picker came back with where its suggestion would have gone. */
    fun onDatePicked(date: LocalDate) {
        val item = pendingDatePicker ?: return
        pendingDatePicker = null
        applyCompletion(item.applyTo(elementDraft, date.orgStamp(active = true)))
    }

    fun dismissDatePicker() {
        pendingDatePicker = null
    }

    private fun applyCompletion(result: CompletionResult) {
        elementDraftValue = TextFieldValue(result.text, TextRange(result.caret))
        // Not dismissed: one completion often leads to the next — a planning line wants a date.
        completionDismissed = false
        refreshSuggestions()
    }

    /**
     * The open file's TODO keywords — its own `#+TODO:`, or what the notes declare when it has
     * none — for everything that renders or edits it, as well as for completion.
     */
    val todoKeywords: OrgTodoConfig get() = completionContext.todoKeywords

    private fun refreshCompletionContext() {
        val document = currentFile?.let { orgContents[it] }
        completionContext = CompletionContext(
            todoKeywords = document?.let { todoKeywordsOf(it.text, corpusKeywords) } ?: corpusKeywords,
            knownTags = tagsByUse(),
            filePaths = orgContents.keys.sorted(),
            headings = document?.sectionTitles().orEmpty(),
            today = today(),
        )
    }

    /** Every tag in the corpus, most-used first — which is the order they are worth offering in. */
    private fun tagsByUse(): List<String> {
        val counts = mutableMapOf<String, Int>()
        for (document in orgContents.values) {
            for (section in document.allNodes().filterIsInstance<OrgSection>()) {
                for (tag in section.tags) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    private fun OrgDocument.sectionTitles(): List<String> =
        allNodes().filterIsInstance<OrgSection>().map { it.title }

    /**
     * Rebuilds everything computed from the notes: the agenda index, the filtered tree and the
     * search results.
     *
     * Called wherever [orgContents] changes — a sync, a save, a checkbox — because all three go
     * stale together and forgetting one of them is invisible until someone notices results that
     * describe a document as it used to be.
     */
    private fun refreshDerivedViews() {
        corpusKeywords = corpusTodoKeywords(orgContents.values.map { it.text })
        agenda = buildAgenda()
        refreshReminders()
        refreshFilteredTree()
        refreshSearch()
        refreshCompletionContext()
    }

    /**
     * The keywords a note that declares none is read with: every `#+TODO:` in the notes, standing
     * in for the editor-wide setting the app cannot see. See [corpusTodoKeywords].
     */
    private var corpusKeywords: OrgTodoConfig = DEFAULT_TODO_KEYWORDS

    // In-memory parsed org documents keyed by file name, provided by the server
    private val orgContents: MutableMap<String, OrgDocument> = mutableMapOf()

    var selectedFileLabel by mutableStateOf("No file selected")
        private set

    var selectedFileContent by mutableStateOf("")
        private set

    fun onContentChanged(newContent: String) {
        selectedFileContent = newContent
    }

    // --- Editing a single element (a heading and its own body) ---

    /**
     * Element to bring into view, set when a file is opened at a particular heading — currently from
     * a calendar entry. Consumed once, via [onScrolledToElement].
     */
    /** Guards against starting a second event stream on recomposition. */
    private var watching = false

    var scrollToElementIndex: Int? by mutableStateOf(null)
        private set

    fun onScrolledToElement() {
        scrollToElementIndex = null
    }

    /**
     * The element containing [offset], or null when the text no longer reaches that far.
     *
     * This is how a search result or an agenda entry finds its place: each carries a character
     * offset, and every element already knows the range of text it renders.
     */
    private fun String.elementIndexOfOffset(offset: Int): Int? =
        orgElements().indexOfFirst { offset >= it.start && offset < it.endExclusive }.takeIf { it >= 0 }

    /** Index into [selectedFileContent]'s elements, or null when not editing one. */
    var editingElementIndex: Int? by mutableStateOf(null)
        private set

    /**
     * The element's text and caret as it is being edited.
     *
     * The caret lives here, not in the editor composable, because completion is a function of where
     * it is: the suggestions are computed here and the strip that shows them is pinned to the
     * screen rather than drawn next to the field, so neither of them can reach into the field for
     * it. It also means accepting a suggestion is an ordinary state change rather than a message
     * passed down into a composable.
     */
    var elementDraftValue by mutableStateOf(TextFieldValue(""))
        private set

    /** The element's text as it is being edited. */
    val elementDraft: String get() = elementDraftValue.text

    /** Where the caret is; after the stars of a heading that was just added. */
    val elementDraftCaret: Int get() = elementDraftValue.selection.start

    /** The element's text when editing began, used to refuse a splice if the document moved. */
    private var elementOriginal: String? = null

    /** Set when editing a heading that was just inserted, so cancelling removes it again. */
    private var textBeforeInsertion: String? = null

    fun beginElementEdit(index: Int) {
        val element = selectedFileContent.orgElements().getOrNull(index) ?: return
        val original = element.sliceOf(selectedFileContent)
        textBeforeInsertion = null
        elementOriginal = original
        editingElementIndex = index
        setDraft(original, caret = original.length)
    }

    /** Adds a heading at the end of the document and opens it for editing. */
    fun addElementAtEnd() = insertElementAndEdit(selectedFileContent.length, level = 1)

    /** Adds a sibling heading directly after [index] and opens it for editing. */
    fun addElementAfter(index: Int) {
        val element = selectedFileContent.orgElements().getOrNull(index) ?: return
        insertElementAndEdit(element.endExclusive, level = element.level)
    }

    private fun insertElementAndEdit(at: Int, level: Int) {
        val previousText = selectedFileContent
        val insertion = previousText.withNewOrgElement(at, level)
        val elements = insertion.text.orgElements()
        val index = elements.indexOfFirst { it.start == insertion.elementStart }
        if (index < 0) return
        val element = elements[index]

        selectedFileContent = insertion.text
        // Nothing is written to disk yet, and cancelling must not leave an empty heading behind.
        textBeforeInsertion = previousText
        elementOriginal = element.sliceOf(insertion.text)
        editingElementIndex = index
        setDraft(elementOriginal.orEmpty(), caret = insertion.caret - element.start)
    }

    fun onElementDraftChanged(value: TextFieldValue) {
        elementDraftValue = value
        // Typing brings dismissed suggestions back: Esc silences this completion, not completion.
        completionDismissed = false
        refreshSuggestions()
    }

    /** Convenience for callers that have no caret of their own to report; it goes to the end. */
    fun onElementDraftChanged(newDraft: String) =
        onElementDraftChanged(TextFieldValue(newDraft, TextRange(newDraft.length)))

    private fun setDraft(text: String, caret: Int) {
        elementDraftValue = TextFieldValue(text, TextRange(caret.coerceIn(0, text.length)))
        completionDismissed = false
        refreshSuggestions()
    }

    fun cancelElementEdit() {
        // A heading that was inserted for this edit never made it to disk, so discarding the edit
        // discards the heading too.
        textBeforeInsertion?.let { selectedFileContent = it }
        clearElementEdit()
    }

    /**
     * Forgets the edit without touching the document.
     *
     * Kept separate from [cancelElementEdit] deliberately: committing must not run that method's
     * undo, or saving a newly added heading would restore the text from before it was inserted.
     */
    private fun clearElementEdit() {
        textBeforeInsertion = null
        editingElementIndex = null
        elementOriginal = null
        elementDraftValue = TextFieldValue("")
        pendingDatePicker = null
        completionDismissed = false
        refreshSuggestions()
    }

    /** Splices the draft back into the document and saves. Leaves the editor open if it cannot. */
    suspend fun commitElementEdit() {
        val index = editingElementIndex ?: return
        val original = elementOriginal ?: return
        val element = selectedFileContent.orgElements().getOrNull(index)
        val updated = element?.let { selectedFileContent.replaceOrgElement(it, original, elementDraft) }
        if (updated == null) {
            lastErrorMessage = "The document changed while you were editing; nothing was written."
            return
        }
        selectedFileContent = updated
        clearElementEdit()
        saveCurrentFile()
    }

    /**
     * Writes to the replica, then tries to push.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the sync engine (T10) will carry it.
     */
    suspend fun saveCurrentFile() {
        val path = currentFile ?: return
        store.save(path, selectedFileContent)
        val updatedDoc = selectedFileContent.toOrgDocument()
        orgContents[path] = updatedDoc
        selectedOrgNode = updatedDoc
        refreshDerivedViews()
        try {
            sync.sync()
            clearError()
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            lastErrorMessage = "Saved locally; could not reach the server: ${t.message}"
            t.printStackTrace()
        }
    }

    // Selected node for structural rendering in UI
    var selectedOrgNode: OrgNode? by mutableStateOf(null)
        private set

    /** Every dated entry in the notes, which the calendar asks for a day at a time. */
    var agenda by mutableStateOf(buildAgenda())
        private set

    /** Set once the replica has been read; see [refreshReminders]. */
    private var notesLoaded = false

    /** The reminders last handed to [notifications]; null until the notes have been read once. */
    private var plannedReminders: List<Reminder>? = null

    /** One replacement at a time, so an older plan can never land after a newer one. */
    private val reminderLock = Mutex()

    /**
     * Plans reminders from the agenda and hands them to the device — only when the plan changed,
     * so a sync that changes nothing leaves the device's notifications alone.
     *
     * Called whenever the notes change, and by the app when it comes to the foreground, which is
     * what moves the planning window on and drops reminders that have already fired.
     */
    fun refreshReminders() {
        // Before the replica has been read the agenda is empty, and replacing with that would
        // cancel every reminder the device holds until the notes arrive.
        if (!notesLoaded) return
        val plan = planReminders(agenda.entries, now(), reminderSettings())
        if (plan == plannedReminders) return
        plannedReminders = plan
        viewModelScope.launch {
            reminderLock.withLock {
                runCatching { notifications.replaceAll(plan) }
                    .onFailure { println("[DEBUG_LOG][FileTreeViewModel] Scheduling reminders failed: ${it.message}") }
            }
        }
    }

    /** A tapped reminder that arrived before the notes were read, opened once they are. */
    private var pendingReminderOpen: ReminderTarget? = null

    /**
     * Opens the note a tapped reminder points at. A tap can start the app, in which case it arrives
     * before the replica has been read; it is then held until [showFromReplica] has run.
     */
    fun openReminderTarget(target: ReminderTarget) {
        when {
            target.file in orgContents -> onFileSelected(target.file, scrollToOffset = target.offset)
            // Not read yet: hold it. Read, and still not there: the note is gone, so is the tap.
            !notesLoaded -> pendingReminderOpen = target
        }
    }

    /** Opens the file an agenda entry comes from, at its heading. */
    fun openAgendaEntry(entry: AgendaEntry) = onFileSelected(entry.file, scrollToOffset = entry.headingOffset)

    /**
     * Ticks the checkbox whose source contains [offsetInDocument].
     *
     * The offset identifies the node rather than counting boxes: an ordinal shifts whenever anything
     * above it changes. The edit itself is a range replacement, so every other character in the file
     * — blank lines, trailing whitespace, formatting — is left exactly as it was on disk.
     */
    fun toggleCheckboxAt(offsetInDocument: Int, scope: CoroutineScope? = null) {
        val file = currentFile ?: return
        val document = selectedFileContent.toOrgDocument()
        val item = document.nodeAt<OrgCheckboxItem>(offsetInDocument) ?: return
        val updatedDoc = document.toggleCheckbox(item)
        val updatedText = updatedDoc.text
        selectedFileContent = updatedText
        orgContents[file] = updatedDoc
        selectedOrgNode = updatedDoc
        refreshDerivedViews()

        // Write locally, then push. The local write already happened above, so a failure here
        // leaves the file dirty for the next cycle rather than losing the toggle.
        scope?.launch {
            try {
                store.save(file, selectedFileContent)
                sync.sync()
                clearError()
            } catch (t: Throwable) {
                // Rethrows if this job was cancelled, so cancellation stays cancellation
                // instead of being reported as a failure.
                currentCoroutineContext().ensureActive()
                lastErrorMessage = "Saved locally; could not reach the server: ${t.message}"
                t.printStackTrace()
            }
        }
    }


    /**
     * Opens [path], and optionally brings a place in it into view once its text has arrived. The
     * place is a character offset: an agenda entry's heading, or a search hit's line.
     *
     * The target is resolved here rather than by the caller because the text loads asynchronously:
     * resolving it at the call site reads whichever file was open *before* this one and scrolls to
     * the wrong place, or nowhere at all.
     */
    fun onFileSelected(path: String, scrollToOffset: Int? = null) {
        selectedFileLabel = "Selected: $path"
        val doc = orgContents[path]
        selectedOrgNode = doc
        currentFile = path
        scrollToElementIndex = null
        // This file's `#+TODO:` keywords and headings are part of what completion offers.
        refreshCompletionContext()
        if (doc == null) {
            selectedFileContent = "* ${path}\nNo content yet."
            return
        }
        // From the replica, so opening a note works offline and does not wait on a round trip.
        // The raw text is kept rather than re-serialised from the parse tree, so saving an
        // unchanged file round-trips it byte for byte.
        viewModelScope.launch {
            val raw = store.read(path)
            if (currentFile == path && raw != null) {
                selectedFileContent = raw
                scrollToElementIndex = scrollToOffset?.let { raw.elementIndexOfOffset(it) }
            }
        }
    }

    // Load tree and contents from server repository and update state
    /**
     * Refreshes from the server when it can be reached, then shows whatever the replica holds.
     *
     * A network failure is no longer fatal: it used to clear the tree so as not to show stale
     * content, which meant an unreachable server left the app empty. The replica *is* the content
     * now, so a failure costs freshness, not access.
     */
    /**
     * Runs a sync cycle when the server can be reached, then shows whatever the replica holds.
     *
     * A network failure is not fatal: the replica *is* the content, so being offline costs
     * freshness, not access.
     */
    suspend fun loadFromServer() {
        try {
            val report = sync.sync()
            if (report.conflicted.isNotEmpty()) {
                val names = report.conflicted.joinToString(", ")
                lastErrorMessage = "Kept your version of $names. The other version is saved beside " +
                    "it as a .sync-conflict- copy."
            } else {
                clearError()
            }
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation instead of
            // being reported as a failure.
            currentCoroutineContext().ensureActive()
            lastErrorMessage = friendlyErrorMessage(t)
        }
        showFromReplica()
    }

    /**
     * Follows the server's change stream for as long as the app runs, so an edit made in Emacs shows
     * up without a refresh.
     *
     * Reconnects with a backoff, and re-syncs after each reconnection: the stream is a hint, so a
     * gap in it costs latency, never an edit.
     */
    fun watchServer() {
        if (watching) return
        watching = true
        viewModelScope.launch {
            var backoff = 1.seconds
            while (isActive) {
                try {
                    sync.watch { showFromReplica() }
                    // A clean end means the server closed the stream; reconnect promptly.
                    backoff = 1.seconds
                } catch (t: Throwable) {
                    // Cancellation is the app shutting down, not a dropped connection.
                    currentCoroutineContext().ensureActive()
                    println("[DEBUG_LOG][FileTreeViewModel] Event stream dropped: ${t.message}")
                    // Back off so an unreachable server is not hammered, but keep trying: without
                    // this the app silently stops seeing edits made elsewhere.
                    backoff = (backoff * 2).coerceAtMost(30.seconds)
                }
                delay(backoff)
                // Catch up on whatever was missed while disconnected; the stream is only a hint.
                // Not runCatching: it would swallow cancellation and keep the loop alive after the
                // view model is gone.
                try {
                    sync.sync()
                    showFromReplica()
                } catch (catchUpFailed: Throwable) {
                    // Cancellation must still cancel; anything else just means still offline, and
                    // the next reconnection will try again.
                    currentCoroutineContext().ensureActive()
                    println("[DEBUG_LOG][FileTreeViewModel] Catch-up sync failed: ${catchUpFailed.message}")
                }
            }
        }
    }

    /** Rebuilds everything the UI shows out of the replica. */
    private suspend fun showFromReplica() {
        val paths = store.paths()
        orgContents.clear()
        for (path in paths) {
            store.read(path)?.let { orgContents[path] = it.toOrgDocument() }
        }
        tree = mapRootToUiFolder(TreeUtils.buildFromPaths(paths, rootName = "Documents"))
        notesLoaded = true
        refreshDerivedViews()

        val current = currentFile
        if (current == null) {
            val first = paths.minOrNull()
            if (first != null) {
                onFileSelected(first)
            } else {
                selectedOrgNode = null
                selectedFileContent = ""
                selectedFileLabel = "No file selected"
            }
        } else {
            selectedOrgNode = orgContents[current]
            store.read(current)?.let { selectedFileContent = it }
        }
        pendingReminderOpen?.let { target ->
            pendingReminderOpen = null
            if (target.file in orgContents) onFileSelected(target.file, scrollToOffset = target.offset)
        }
    }

    fun clearError() {
        lastErrorMessage = ""
    }

    private fun friendlyErrorMessage(t: Throwable): String {
        val msg = t.message ?: ""
        val simple = t::class.simpleName ?: "Error"
        // Generic, user-friendly phrasing
        return "Cannot reach the server. Please check Settings and your connection. ($simple: $msg)"
    }

    private companion object {
        /**
         * How long the typing has to stop before the corpus is scanned.
         *
         * Long enough that a run of keystrokes costs one scan rather than one each, short enough
         * that it reads as immediate: a pause this length is about where typing stops being a
         * stream of characters and becomes a word someone meant.
         */
        val TYPING_PAUSE = 200.milliseconds
    }

    private fun mapSharedToUi(node: SharedNode): Node {
        return when (node) {
            is SharedFile -> File(node.name, node.path)
            is SharedFolder -> Folder(
                name = node.name,
                path = node.path,
                children = node.children.map { mapSharedToUi(it) }
            )
        }
    }

    private fun mapRootToUiFolder(shared: SharedFolder): Folder = mapSharedToUi(shared) as Folder

    /**
     * Rebuilt with everything else derived from the notes. Today is read here, so the index a day
     * old still carries over to the right day once anything changes.
     */
    private fun buildAgenda(): AgendaIndex =
        AgendaIndex(
            agendaEntries(orgContents.mapValues { it.value.text }, corpusKeywords),
            AgendaOptions(today = today()),
        )
}
