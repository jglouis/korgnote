package com.korgnote.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.*
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.app.viewmodel.OpenNote
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.korgnote.app.notifications.ReminderTaps
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        var selectedScreen by remember { mutableStateOf(BottomItem.HOME) }
        var isEditing by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val vm = koinViewModel<NotesViewModel>()
        LaunchedEffect(Unit) {
            vm.server.load()
            // Then follow the server's change stream for the rest of the session.
            vm.server.watch()
        }
        // A tapped reminder opens its note at the heading, whatever screen was showing.
        LaunchedEffect(Unit) {
            ReminderTaps.pending.collect { target ->
                if (target != null) {
                    ReminderTaps.pending.value = null
                    vm.agenda.openReminderTarget(target)
                    isEditing = false
                    selectedScreen = BottomItem.HOME
                }
            }
        }
        // Coming back to the app moves the reminder window on and drops what has already fired.
        LifecycleResumeEffect(Unit) {
            vm.agenda.refreshReminders()
            onPauseOrDispose { }
        }
        // At most one of these is open; each names the note it is about.
        var creatingNote by remember { mutableStateOf(false) }
        var renamingNote by remember { mutableStateOf<String?>(null) }
        var deletingNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        var noteDialogError by remember { mutableStateOf<String?>(null) }

        if (creatingNote) {
            NewNoteDialog(
                pathFor = vm::newNotePathFor,
                error = noteDialogError,
                onDismiss = { creatingNote = false; noteDialogError = null },
                onCreate = { title ->
                    scope.launch {
                        // Stays open when refused, with the reason shown behind it.
                        if (vm.createNote(title) == null) {
                            noteDialogError = vm.lastErrorMessage.also { vm.clearError() }
                        } else {
                            creatingNote = false
                            noteDialogError = null
                            isEditing = false
                            selectedScreen = BottomItem.HOME
                            drawerState.close()
                        }
                    }
                },
            )
        }
        renamingNote?.let { path ->
            RenameNoteDialog(
                path = path,
                error = noteDialogError,
                onDismiss = { renamingNote = null; noteDialogError = null },
                onRename = { target ->
                    scope.launch {
                        if (vm.renameNote(path, target)) {
                            renamingNote = null
                            noteDialogError = null
                        } else {
                            noteDialogError = vm.lastErrorMessage.also { vm.clearError() }
                        }
                    }
                },
            )
        }
        deletingNote?.let { (path, unsynced) ->
            DeleteNoteDialog(
                path = path,
                hasUnsyncedChanges = unsynced,
                onDismiss = { deletingNote = null },
                onDelete = {
                    deletingNote = null
                    scope.launch { vm.deleteNote(path) }
                },
            )
        }

        // A tapped link into the notes arrives here as a `korgnote-link:` URI, from a note, the
        // calendar or search alike; anything else is the platform's to open, as before.
        val platformUriHandler = LocalUriHandler.current
        val uriHandler = remember(platformUriHandler, vm) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    if (uri.startsWith(NOTE_LINK_URI_PREFIX)) {
                        vm.links.follow(uri.removePrefix(NOTE_LINK_URI_PREFIX))
                        isEditing = false
                        selectedScreen = BottomItem.HOME
                    } else {
                        platformUriHandler.openUri(uri)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = selectedScreen != BottomItem.CALENDAR,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Files",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { creatingNote = true }) {
                                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "New note")
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = vm.search.filterQuery,
                                onValueChange = { vm.search.onFilterQueryChanged(it) },
                                label = { Text("Filter files") },
                                placeholder = { Text("name, or tag:work") },
                                singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            FileTree(
                                root = vm.search.filteredTree,
                                // While filtering, show what matched even inside folders the reader
                                // had closed: a hidden match looks like a filter that does not work.
                                expandAll = vm.search.filterQuery.isNotBlank(),
                                onFileSelected = { name ->
                                    scope.launch {
                                        vm.onFileSelected(name)
                                        isEditing = false
                                        drawerState.close()
                                    }
                                },
                                onRenameFile = { renamingNote = it },
                                onDeleteFile = { path ->
                                    scope.launch { deletingNote = path to vm.hasUnsyncedChanges(path) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(selectedScreen.getLabel()) },
                            navigationIcon = {
                                if (selectedScreen != BottomItem.CALENDAR) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                                    }
                                }
                            },
                            actions = {
                                // Back from a followed link, to where it was tapped.
                                if (selectedScreen == BottomItem.HOME && vm.links.canGoBack && !isEditing) {
                                    IconButton(onClick = { vm.links.goBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                                if (selectedScreen == BottomItem.HOME && vm.note.label != OpenNote.NO_FILE_LABEL) {
                                    if (isEditing) {
                                        IconButton(onClick = {
                                            scope.launch {
                                                vm.saveCurrentFile()
                                                isEditing = false
                                            }
                                        }) {
                                            Icon(Icons.Filled.Save, contentDescription = "Save file")
                                        }
                                    } else {
                                        IconButton(onClick = { isEditing = true }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Edit file")
                                        }
                                    }
                                }
                            }
                        )
                    },
                    floatingActionButton = {
                        // Saving an element is explicit: edits stay local until this is pressed.
                        if (vm.editor.editingIndex != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallFloatingActionButton(onClick = { vm.editor.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Discard changes to this element")
                                }
                                // Most headings are added right after editing the one above.
                                SmallFloatingActionButton(onClick = { scope.launch { vm.editor.commitAndAddBelow() } }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Save and add a heading below")
                                }
                                FloatingActionButton(onClick = { scope.launch { vm.editor.commit() } }) {
                                    Icon(Icons.Filled.Save, contentDescription = "Save this element")
                                }
                            }
                        } else if (
                            selectedScreen == BottomItem.HOME &&
                            !isEditing &&
                            vm.note.label != OpenNote.NO_FILE_LABEL
                        ) {
                            FloatingActionButton(onClick = { vm.editor.addAtEnd() }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add a heading at the end of the file")
                            }
                        }
                    },
                    bottomBar = {
                        // While an element is being edited the suggestions take the bottom of the
                        // screen, which is where the keyboard pushes everything else against. The
                        // navigation bar goes away with them: switching tabs mid-edit only ever
                        // happened by accident.
                        if (vm.editor.editingIndex != null) {
                            CompletionStrip(
                                items = vm.completion.suggestions,
                                selectedIndex = vm.completion.selectedIndex,
                                onAccept = { vm.completion.accept(it) },
                            )
                        } else NavigationBar {
                            BottomItem.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = selectedScreen == item,
                                    onClick = { selectedScreen = item },
                                    icon = {
                                        Icon(
                                            item.getIconRes(),
                                            contentDescription = item.getLabel()
                                        )
                                    },
                                    label = { Text(item.getLabel()) },
                                    alwaysShowLabel = true
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    vm.completion.pendingDatePicker?.let {
                        OrgDatePickerDialog(
                            initial = vm.completion.context.today,
                            onPick = { date -> vm.completion.onDatePicked(date) },
                            onDismiss = { vm.completion.dismissDatePicker() },
                        )
                    }
                    // Reading mode scrolls itself, one element at a time, so that opening a large file
                    // does not have to lay out every element first; search does the same with its
                    // results, and the calendar with its agenda list and hour grid. A lazy list cannot
                    // be nested in a vertically scrolling parent — and a screen that fills the space
                    // left gets none inside one — so this Column yields its scroll to those three;
                    // every other screen, and the whole-file editor, still scroll here as before.
                    val readingDocument = selectedScreen == BottomItem.HOME &&
                        !isEditing &&
                        vm.note.content.isNotBlank()
                    val ownsItsScroll = readingDocument ||
                        selectedScreen == BottomItem.SEARCH ||
                        selectedScreen == BottomItem.CALENDAR
                    val screenScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                            .then(
                                if (ownsItsScroll) Modifier else Modifier.verticalScroll(screenScrollState)
                            ),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (selectedScreen) {
                            BottomItem.HOME -> {
                                Text(
                                    text = selectedScreen.getLabel(),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                // Error banner if server unreachable
                                if (vm.lastErrorMessage.isNotBlank()) {
                                    Text(vm.lastErrorMessage, color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                // Reflect selection from ViewModel in UI
                                Text(text = vm.note.label)
                                Spacer(modifier = Modifier.height(16.dp))

                                if (isEditing) {
                                    val headingColor = MaterialTheme.colorScheme.primary
                                    val orgHighlighting = remember(headingColor) {
                                        OrgSyntaxHighlightTransformation(headingColor)
                                    }
                                    // The whole file is being edited, so its own #+TODO: lines win;
                                    // this only covers a file that has none.
                                    orgHighlighting.fallbackKeywords = vm.todoKeywords
                                    // Styling is limited to the characters on screen: span count, not
                                    // span production, is what drives layout cost in a large document.
                                    val viewport = remember { EditorViewport() }
                                    // Seeded rather than null: null means "style everything", which
                                    // would make the first frame after entering edit mode pay for the
                                    // whole document. The real range replaces this once the field has
                                    // been laid out.
                                    var styledRange by remember {
                                        mutableStateOf<IntRange?>(0..EditorViewport.INITIAL_STYLED_CHARS)
                                    }
                                    orgHighlighting.visibleChars = styledRange
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val editorTextStyle = LocalTextStyle.current.merge(
                                        TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    )
                                    // No fillMaxSize/weight here: the field grows to its natural height so
                                    // the surrounding screen Column's own scroll (shared with reading mode
                                    // below) is what scrolls it, keeping the scroll position when toggling
                                    // between reading and editing.
                                    // BasicTextField rather than OutlinedTextField: only it exposes
                                    // onTextLayout, which is how the visible character range is found.
                                    // The Material look is preserved by the decoration box below.
                                    BasicTextField(
                                        value = vm.note.content,
                                        onValueChange = { vm.note.onContentChanged(it) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onGloballyPositioned { coordinates ->
                                                viewport.onPositioned(coordinates)
                                                viewport.widenIfNeeded(styledRange)?.let { styledRange = it }
                                            },
                                        textStyle = editorTextStyle,
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        interactionSource = interactionSource,
                                        onTextLayout = { layout ->
                                            viewport.onTextLayout(layout)
                                            viewport.widenIfNeeded(styledRange)?.let { styledRange = it }
                                        },
                                        visualTransformation = orgHighlighting,
                                        decorationBox = { innerTextField ->
                                            OutlinedTextFieldDefaults.DecorationBox(
                                                value = vm.note.content,
                                                innerTextField = innerTextField,
                                                enabled = true,
                                                singleLine = false,
                                                // None on purpose: the decoration box only needs the
                                                // text, and passing the real transformation would run
                                                // the highlighter a second time on every keystroke.
                                                visualTransformation = VisualTransformation.None,
                                                interactionSource = interactionSource,
                                                label = { Text("Content") },
                                            )
                                        }
                                    )
                                } else if (vm.note.content.isBlank()) {
                                    Text("Select an .org file to view its content")
                                } else {
                                    OrgDocumentView(
                                        // weight, not wrap: the lazy list needs a bounded height to
                                        // know what is on screen, and fills what the header leaves.
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        text = vm.note.content,
                                        todoKeywords = vm.todoKeywords,
                                        editingElementIndex = vm.editor.editingIndex,
                                        elementDraftValue = vm.editor.draftValue,
                                        onElementDraftChange = { vm.editor.onDraftChanged(it) },
                                        onCompletionKey = { key ->
                                            // Nothing showing means the key was never ours: an arrow
                                            // has to go on moving the caret.
                                            if (vm.completion.suggestions.isEmpty()) false else when (key) {
                                                CompletionKey.ACCEPT -> vm.completion.acceptSelected()
                                                CompletionKey.DISMISS -> { vm.completion.dismiss(); true }
                                                CompletionKey.NEXT -> { vm.completion.moveSelection(1); true }
                                                CompletionKey.PREVIOUS -> { vm.completion.moveSelection(-1); true }
                                            }
                                        },
                                        onEditElement = { vm.editor.beginEdit(it) },
                                        onAddHeadingBelow = { vm.editor.addAfter(it) },
                                        onAddSubheading = { vm.editor.addSubheading(it) },
                                        onDeleteSection = { scope.launch { vm.editor.deleteSection(it) } },
                                        foldedElements = vm.folds.folded,
                                        onToggleFold = { vm.folds.toggle(it) },
                                        scrollToElementIndex = vm.note.scrollToElementIndex,
                                        onScrolledToElement = { vm.note.onScrolledToElement() },
                                        onToggleCheckbox = { offset -> vm.toggleCheckboxAt(offset, scope) },
                                        backlinks = vm.links.backlinks,
                                        onOpenBacklink = { vm.links.openBacklink(it) },
                                        onFirstVisibleElement = { vm.links.onReadingPositionChanged(it) },
                                    )
                                }
                            }

                            BottomItem.SEARCH -> {
                                SearchScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    query = vm.search.query,
                                    results = vm.search.results,
                                    onQueryChange = { vm.search.onQueryChanged(it) },
                                    onOpen = { hit ->
                                        // Same shape as the calendar's jump: the view model resolves
                                        // the place once the file's text has arrived.
                                        vm.search.openHit(hit)
                                        isEditing = false
                                        selectedScreen = BottomItem.HOME
                                    },
                                )
                            }

                            BottomItem.CALENDAR -> {
                                CalendarScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    agenda = vm.agenda.index,
                                    onOpenEntry = { entry ->
                                        // The heading is resolved by the view model once the file's text
                                        // has loaded: that text arrives asynchronously, so working it out
                                        // here would search the previously open file.
                                        vm.agenda.openEntry(entry)
                                        isEditing = false
                                        selectedScreen = BottomItem.HOME
                                    }
                                )
                            }

                            BottomItem.SETTINGS -> {
                                val settingsRepo = koinInject<SettingsRepository>()
                                SettingsScreen(repo = settingsRepo, onSaved = { scope.launch { vm.server.load() } })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tracks which characters of the editor are on screen, so the highlighter can style only those.
 *
 * The field has no height of its own — it grows to fit the document inside the screen's scroll
 * container — so "visible" is derived from where the field currently sits in the window rather
 * than from a scroll offset, which keeps this independent of who owns the scrolling.
 */
private class EditorViewport {
    private var layout: TextLayoutResult? = null
    private var topInRoot = 0f
    private var rootHeight = 0f

    fun onPositioned(coordinates: LayoutCoordinates) {
        topInRoot = coordinates.positionInRoot().y
        rootHeight = coordinates.findRootCoordinates().size.height.toFloat()
    }

    fun onTextLayout(result: TextLayoutResult) {
        layout = result
    }

    /**
     * Returns a new range to style when [current] no longer covers what is on screen, else null.
     *
     * The returned range is padded by a screenful on each side, so ordinary scrolling stays inside
     * it and does not re-trigger. Widening only when the visible region escapes the current window
     * is also what stops layout → range → re-layout from looping.
     */
    fun widenIfNeeded(current: IntRange?): IntRange? {
        val result = layout ?: return null
        if (rootHeight <= 0f) return null

        val topInField = (-topInRoot).coerceAtLeast(0f)
        val firstLine = result.getLineForVerticalPosition(topInField)
        val lastLine = result.getLineForVerticalPosition(topInField + rootHeight)
        val start = result.getLineStart(firstLine)
        val end = result.getLineEnd(lastLine, visibleEnd = true)
        if (current != null && start >= current.first && end <= current.last) return null

        val padding = (end - start).coerceAtLeast(MIN_PADDING_CHARS)
        return (start - padding).coerceAtLeast(0)..(end + padding)
    }

    companion object {
        private const val MIN_PADDING_CHARS = 1_000

        /** Styled up front, before the first layout tells us what is actually on screen. */
        const val INITIAL_STYLED_CHARS = 4_000
    }
}

private enum class BottomItem {
    HOME, SEARCH, CALENDAR, SETTINGS;

    @Composable
    fun getLabel() = when (this) {
        HOME -> "Home"
        SEARCH -> "Search"
        CALENDAR -> "Calendar"
        SETTINGS -> "Settings"
    }

    fun getIconRes() = when (this) {
        HOME -> Icons.Filled.Home
        SEARCH -> Icons.Filled.Search
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}