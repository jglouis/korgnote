package com.korgnote.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
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
        // The conflict copy being compared with its note.
        var comparingCopy by remember { mutableStateOf<String?>(null) }
        // The open note's scroll position, held here for the floating buttons to read and move.
        // Keyed by screen so that coming back to Home starts at the top, as it did when the
        // document view owned it.
        val documentListState = rememberSaveable(selectedScreen, saver = LazyListState.Saver) { LazyListState() }

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
        comparingCopy?.let { copy ->
            val original = vm.conflictOriginalOf(copy)
            if (original == null) {
                // Settled meanwhile, here or on another device.
                comparingCopy = null
            } else {
                ConflictCompareDialog(
                    original = original,
                    copy = copy,
                    hunks = remember(copy, vm.library.conflicts) { vm.conflictDiff(copy) },
                    onKeepNote = { comparingCopy = null; scope.launch { vm.discardConflictCopy(copy) } },
                    onUseCopy = { comparingCopy = null; scope.launch { vm.useConflictCopy(copy) } },
                    onDismiss = { comparingCopy = null },
                )
            }
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
        // calendar or search alike, a link to a file outside them as a `korgnote-file:` one, and a
        // tapped planning date as a `korgnote-calendar:` one; anything else is the platform's to
        // open, as before.
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
                        uri.startsWith(FILE_LINK_URI_PREFIX) -> vm.files.open(uri.removePrefix(FILE_LINK_URI_PREFIX))
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
                            // Above the filter, so it never scrolls away or filters out.
                            PinnedNotes(
                                paths = vm.pins.shown,
                                onOpen = { path ->
                                    scope.launch {
                                        vm.onFileSelected(path)
                                        selectedScreen = BottomItem.HOME
                                        drawerState.close()
                                    }
                                },
                                onUnpin = { vm.pins.toggle(it) },
                            )
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
                                conflicts = vm.library.conflicts,
                                pinned = vm.pins.shown.toSet(),
                                onTogglePin = { vm.pins.toggle(it) },
                                onDeleteFile = { path ->
                                    scope.launch { deletingNote = path to vm.hasUnsyncedChanges(path) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            ) {
                // A phone on its side, or a tablet: the screen is short for its width, so the tabs
                // go down the side instead of taking a band off the bottom.
                val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
                // Edge to edge, the cutout and the three-button bar sit on a side in landscape; the
                // rail keeps the start one clear and the screen beside it the rest.
                val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                val selectTab = { item: BottomItem ->
                    if (item == BottomItem.CALENDAR) calendarDay = null
                    selectedScreen = item
                }
                Row {
                    if (wide) {
                        NavigationRail(windowInsets = safeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                            BottomItem.entries.forEach { item ->
                                NavigationRailItem(
                                    selected = selectedScreen == item,
                                    onClick = { selectTab(item) },
                                    icon = { Icon(item.getIconRes(), contentDescription = item.getLabel()) },
                                    label = { Text(item.getLabel()) },
                                    // Kept, unlike the bottom bar, so the note does not shift sideways;
                                    // tabs still do not switch mid-edit.
                                    enabled = vm.editor.editingIndex == null,
                                    alwaysShowLabel = true,
                                )
                            }
                        }
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        contentWindowInsets = if (wide) {
                            safeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                        } else {
                            ScaffoldDefaults.contentWindowInsets
                        },
                        topBar = {
                            TopAppBar(
                                // Reading, the note is what the screen is about: the tab it is on
                                // already shows in the navigation.
                                title = {
                                    val note = vm.note.path?.substringAfterLast('/')
                                    Text(
                                        if (selectedScreen == BottomItem.HOME && note != null) note else selectedScreen.getLabel(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                windowInsets = if (wide) {
                                    safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                                } else {
                                    TopAppBarDefaults.windowInsets
                                },
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
                                    ConflictStatusIcon(vm.library.conflicts) { path ->
                                        vm.onFileSelected(path)
                                        selectedScreen = BottomItem.HOME
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
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    // Only while there is more below: at the end it would do nothing.
                                    AnimatedVisibility(
                                        visible = documentListState.canScrollForward,
                                        enter = fadeIn() + scaleIn(),
                                        exit = fadeOut() + scaleOut(),
                                    ) {
                                        SmallFloatingActionButton(
                                            onClick = {
                                                scope.launch {
                                                    val last = documentListState.layoutInfo.totalItemsCount - 1
                                                    if (last < 0) return@launch
                                                    // The last item at the top, then on past it: a final
                                                    // element taller than the screen still ends at the bottom.
                                                    documentListState.scrollToItem(last)
                                                    val page = documentListState.layoutInfo.viewportSize.height.toFloat()
                                                    while (page > 0 && documentListState.canScrollForward) {
                                                        documentListState.scrollBy(page)
                                                    }
                                                }
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.KeyboardDoubleArrowDown,
                                                contentDescription = "Scroll to the end of the file",
                                            )
                                        }
                                    }
                                    FloatingActionButton(onClick = { vm.editor.addAtEnd() }) {
                                        Icon(Icons.Filled.Add, contentDescription = "Add a heading at the end of the file")
                                    }
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
                            } else if (!wide) NavigationBar {
                                BottomItem.entries.forEach { item ->
                                    NavigationBarItem(
                                        selected = selectedScreen == item,
                                        onClick = { selectTab(item) },
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
                                initialEndTime = stamp?.endTime,
                                onPick = { date, time, end -> vm.completion.onDatePicked(date, time, end) },
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
                                    // Why something could not be done; being offline shows in the top bar instead.
                                    if (vm.lastErrorMessage.isNotBlank()) {
                                        Text(vm.lastErrorMessage, color = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    // A note in conflict, or one of its copies, says so until it is settled.
                                    conflictShown(vm.note.path, vm.library.conflicts)?.let { conflict ->
                                        ConflictBanner(
                                            conflict,
                                            onCompare = { comparingCopy = conflict.copy },
                                            onKeepNote = { scope.launch { vm.discardConflictCopy(conflict.copy) } },
                                            onUseCopy = { scope.launch { vm.useConflictCopy(conflict.copy) } },
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }

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
                                            onSetTodo = { offset, keyword -> vm.setTodoAt(offset, keyword, scope) },
                                            backlinks = vm.links.backlinks,
                                            onOpenBacklink = { vm.links.openBacklink(it) },
                                            onFirstVisibleElement = { vm.links.onReadingPositionChanged(it) },
                                            listState = documentListState,
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
