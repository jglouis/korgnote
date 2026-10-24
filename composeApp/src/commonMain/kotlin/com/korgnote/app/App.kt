package com.korgnote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.*
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.app.viewmodel.OpenNote
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.korgnote.app.notifications.ReminderTaps
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        var selectedScreen by remember { mutableStateOf(BottomItem.HOME) }
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
        // The day a tapped SCHEDULED or DEADLINE date opens the calendar on; null for the calendar's
        // own starting view, as when it is opened from the bottom bar.
        var calendarDay by remember { mutableStateOf<LocalDate?>(null) }
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
        // calendar or search alike, and a tapped planning date as a `korgnote-calendar:` one;
        // anything else is the platform's to open, as before.
        val platformUriHandler = LocalUriHandler.current
        val uriHandler = remember(platformUriHandler, vm) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val day = calendarDayOf(uri)
                    when {
                        uri.startsWith(NOTE_LINK_URI_PREFIX) -> {
                            vm.links.follow(uri.removePrefix(NOTE_LINK_URI_PREFIX))
                            selectedScreen = BottomItem.HOME
                        }
                        day != null -> {
                            calendarDay = day
                            selectedScreen = BottomItem.CALENDAR
                        }
                        else -> platformUriHandler.openUri(uri)
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
                                if (selectedScreen == BottomItem.HOME && vm.links.canGoBack) {
                                    IconButton(onClick = { vm.links.goBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                                ServerStatusIcon(
                                    unreachable = vm.server.unreachable,
                                    retrying = vm.server.retrying,
                                    onRetry = { scope.launch { vm.server.retry() } },
                                )
                            }
                        )
                    },
                    floatingActionButton = {
                        // While editing, the element's own actions live on the completion strip.
                        if (
                            vm.editor.editingIndex == null &&
                            selectedScreen == BottomItem.HOME &&
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
                            ) {
                                // Saving an element is explicit: edits stay local until this is pressed.
                                IconButton(onClick = { vm.editor.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Discard changes to this element")
                                }
                                // Most headings are added right after editing the one above.
                                IconButton(onClick = { scope.launch { vm.editor.commitAndAddBelow() } }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Save and add a heading below")
                                }
                                FilledIconButton(
                                    onClick = { scope.launch { vm.editor.commit() } },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = "Save this element")
                                }
                            }
                        } else NavigationBar {
                            BottomItem.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = selectedScreen == item,
                                    onClick = {
                                        if (item == BottomItem.CALENDAR) calendarDay = null
                                        selectedScreen = item
                                    },
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
                        val stamp = vm.completion.pickerStamp
                        OrgDatePickerDialog(
                            initialDate = stamp?.start ?: vm.completion.context.today,
                            initialTime = stamp?.startTime,
                            onPick = { date, time -> vm.completion.onDatePicked(date, time) },
                            onDismiss = { vm.completion.dismissDatePicker() },
                        )
                    }
                    // Reading mode scrolls itself, one element at a time, so that opening a large file
                    // does not have to lay out every element first; search does the same with its
                    // results, and the calendar with its agenda list and hour grid. A lazy list cannot
                    // be nested in a vertically scrolling parent — and a screen that fills the space
                    // left gets none inside one — so this Column yields its scroll to those three;
                    // every other screen still scrolls here.
                    val readingDocument = selectedScreen == BottomItem.HOME &&
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
                                // Why something could not be done; being offline shows in the top bar instead.
                                if (vm.lastErrorMessage.isNotBlank()) {
                                    Text(vm.lastErrorMessage, color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                // Reflect selection from ViewModel in UI
                                Text(text = vm.note.label)
                                Spacer(modifier = Modifier.height(16.dp))

                                if (vm.note.content.isBlank()) {
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
                                        onMoveSection = { index, up -> scope.launch { vm.editor.moveSection(index, up) } },
                                        onShiftSection = { index, delta ->
                                            scope.launch {
                                                if (delta < 0) vm.editor.promoteSection(index) else vm.editor.demoteSection(index)
                                            }
                                        },
                                        foldedElements = vm.folds.folded,
                                        onToggleFold = { vm.folds.toggle(it) },
                                        scrollToElementIndex = vm.note.scrollToElementIndex,
                                        onScrolledToElement = { vm.note.onScrolledToElement() },
                                        revealElementIndex = vm.note.revealElementIndex,
                                        onRevealed = { vm.note.onRevealed() },
                                        onToggleCheckbox = { offset -> vm.toggleCheckboxAt(offset, scope) },
                                        onCycleTodo = { offset -> vm.cycleTodoAt(offset, scope) },
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
                                        selectedScreen = BottomItem.HOME
                                    },
                                )
                            }

                            BottomItem.CALENDAR -> key(calendarDay) {
                                CalendarScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    agenda = vm.agenda.index,
                                    initialMode = calendarDay?.let { CalendarMode.Day },
                                    initialAnchorDate = calendarDay,
                                    onOpenEntry = { entry ->
                                        // The heading is resolved by the view model once the file's text
                                        // has loaded: that text arrives asynchronously, so working it out
                                        // here would search the previously open file.
                                        vm.agenda.openEntry(entry)
                                        selectedScreen = BottomItem.HOME
                                    }
                                )
                            }

                            BottomItem.SETTINGS -> {
                                val settingsRepo = koinInject<SettingsRepository>()
                                SettingsScreen(repo = settingsRepo, onSaved = { scope.launch { vm.server.retry() } })
                            }
                        }
                    }
                }
            }
        }
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