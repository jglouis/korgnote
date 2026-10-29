package com.korgnote.app

import com.korgnote.common.org.link.LinkTarget
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.offersNotesFolder
import com.korgnote.app.ui.*
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.viewmodel.LinkEdit
import org.koin.mp.KoinPlatform
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import org.koin.core.parameter.parametersOf
import com.korgnote.app.viewmodel.OpenNote
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.korgnote.app.notifications.ReminderTaps
import com.korgnote.app.widgets.CalendarTaps
import com.korgnote.app.widgets.CalendarTarget
import com.korgnote.app.widgets.WidgetSyncs
import com.korgnote.common.org.edit.TableGrid
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app: the welcome screens until it is known where the notes are, then the notes. Settings can
 * open the welcome screens again, to keep the notes somewhere else.
 */
@Composable
fun App() {
    KorgNoteTheme {
        val choice = koinInject<NotesSourceChoice>()
        LaunchedEffect(choice) { choice.settle() }
        var changingSource by rememberSaveable { mutableStateOf(false) }
        val source = choice.current
        // A pairing link, from the camera, while the notes are a folder: pairing is for the server,
        // which the welcome screens move the notes to. They pair with it on their server step.
        val incomingLink by PairingLinks.pending.collectAsState()
        LaunchedEffect(incomingLink, source) {
            if (incomingLink != null && source is NotesSource.Folder) changingSource = true
        }
        when {
            !choice.settled -> Surface(Modifier.fillMaxSize()) {}
            source == null || changingSource -> Onboarding(
                current = source,
                onChosen = { chosen ->
                    choice.choose(chosen)
                    changingSource = false
                },
                onCancel = if (source != null) ({ changingSource = false }) else null,
            )
            // Everything below starts over for another source: its notes are other notes.
            else -> key(source) { NotesApp(source, choice, onChangeSource = { changingSource = true }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesApp(source: NotesSource, choice: NotesSourceChoice, onChangeSource: () -> Unit) {
    var selectedScreen by rememberSaveable { mutableStateOf(BottomItem.FILE) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val vm = koinViewModel<NotesViewModel>(key = "notes:${source.encode()}") { parametersOf(source) }
    val focusManager = LocalFocusManager.current
    val settingsRepo = koinInject<SettingsRepository>()
    LaunchedEffect(Unit) { TagColors.chosen = settingsRepo.getTagColors() }
    LaunchedEffect(vm) {
        choice.active = vm.server
        vm.server.load()
        // Then follow changes made elsewhere for the rest of the session.
        vm.server.watch()
    }
    // A tapped reminder opens its note at the heading, whatever screen was showing.
    LaunchedEffect(Unit) {
        ReminderTaps.pending.collect { target ->
            if (target != null) {
                ReminderTaps.pending.value = null
                vm.agenda.openReminderTarget(target)
                selectedScreen = BottomItem.FILE
            }
        }
    }
    // A pairing link opened from another app — the camera, reading the QR code — goes to
    // Settings, which pairs with it.
    LaunchedEffect(Unit) {
        PairingLinks.pending.collect { link -> if (link != null) selectedScreen = BottomItem.SETTINGS }
    }
    // Coming back to the app moves the reminder window on and drops what has already fired.
    // A folder of notes is looked at again too: another app may have changed it meanwhile.
    LifecycleResumeEffect(vm) {
        vm.agenda.refreshReminders()
        vm.server.onResume()
        onPauseOrDispose { vm.server.onPause() }
    }
    // At most one of these is open; each names the note it is about.
    var creatingNote by remember { mutableStateOf(false) }
    var editingDetails by remember { mutableStateOf(false) }
    // The notes and headings a link can go to, while the link picker is open.
    var pickingLink by remember { mutableStateOf<List<LinkTarget>?>(null) }
    var editingLink by remember { mutableStateOf<LinkEdit?>(null) }
    // A table being made from the editor's Table button, before it goes into the element.
    var insertingTable by remember { mutableStateOf(false) }
    var renamingNote by remember { mutableStateOf<String?>(null) }
    var deletingNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // A search being named: one about to be saved, or (true) a saved one being renamed.
    var namingSearch by remember { mutableStateOf<Pair<SavedSearch, Boolean>?>(null) }
    // The view and day the calendar opens on when asked from elsewhere — a tapped SCHEDULED or
    // DEADLINE date opens its day, a widget the view it shows — and null for the calendar's own
    // starting view, as when it is opened from the bottom bar. Each such request is counted, so
    // asking for the same day again starts there again, wherever the calendar was moved since.
    var calendarTarget by rememberSaveable(stateSaver = OptionalCalendarTargetSaver) { mutableStateOf<CalendarTarget?>(null) }
    var calendarRequests by rememberSaveable { mutableIntStateOf(0) }
    val openCalendar = { target: CalendarTarget ->
        calendarTarget = target
        calendarRequests++
        selectedScreen = BottomItem.CALENDAR
    }
    // A widget's calendar, tapped, opens the app's at the same place.
    LaunchedEffect(Unit) {
        CalendarTaps.pending.collect { target ->
            if (target != null) {
                CalendarTaps.pending.value = null
                openCalendar(target)
            }
        }
    }
    // A widget synced the replica under the notes on screen: read them again, as when the server
    // says they changed, so nothing showing what they held before is saved back over them.
    LaunchedEffect(vm) {
        WidgetSyncs.finished.collect { vm.server.load() }
    }
    var noteDialogError by remember { mutableStateOf<String?>(null) }
    // The conflict copy being compared with its note.
    var comparingCopy by remember { mutableStateOf<String?>(null) }
    // Whose version a note in conflict holds, for the banner and the comparison to say.
    val keptSide = if (source is NotesSource.Folder) "folder" else "server"
    // Opens or starts today's daily, from the drawer.
    val openToday = {
        scope.launch {
            vm.openToday()
            selectedScreen = BottomItem.FILE
            drawerState.close()
        }
        Unit
    }
    // Why something could not be done, shown for a moment rather than over the note, where it
    // stayed until the next thing went wrong. Being offline shows in the top bar instead.
    val snackbarHostState = remember { SnackbarHostState() }
    // Not keyed on the message: clearing it would restart the effect and cancel the snackbar
    // the moment it appeared. A newer message replaces the one on screen.
    LaunchedEffect(vm) {
        snapshotFlow { vm.lastErrorMessage }
            .filter { it.isNotBlank() }
            .collectLatest { message ->
                vm.clearError()
                snackbarHostState.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            }
    }
    // The open note's scroll position, held here for the floating buttons to read and move.
    // Keyed by screen so that coming back to File starts at the top, as it did when the
    // document view owned it.
    val documentListState = rememberSaveable(selectedScreen, saver = LazyListState.Saver) { LazyListState() }

    // The system's back goes where the top bar's does: back from a followed link, to where it
    // was tapped. From another tab it goes to the note first, as Android apps go to their first
    // tab before they close. Of these handlers the later one is asked first, so an open drawer
    // closes before anything else.
    OnBack(enabled = selectedScreen != BottomItem.FILE) { selectedScreen = BottomItem.FILE }
    OnBack(enabled = selectedScreen == BottomItem.FILE && vm.links.canGoBack) { vm.links.goBack() }
    OnBack(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    if (creatingNote) {
        NewNoteDialog(
            pathFor = vm::newNotePathFor,
            knownTags = vm.completion.context.knownTags,
            error = noteDialogError,
            onDismiss = { creatingNote = false; noteDialogError = null },
            onCreate = { title, tags ->
                scope.launch {
                    // Stays open when refused, with the reason shown behind it.
                    if (vm.createNote(title, tags) == null) {
                        noteDialogError = vm.lastErrorMessage.also { vm.clearError() }
                    } else {
                        creatingNote = false
                        noteDialogError = null
                        selectedScreen = BottomItem.FILE
                        drawerState.close()
                    }
                }
            },
        )
    }
    if (editingDetails) {
        NoteDetailsDialog(
            initial = remember { vm.openNoteKeywords() },
            knownTags = vm.completion.context.knownTags,
            defaultAuthor = remember { settingsRepo.getNoteTemplate().author },
            today = vm.completion.context.today,
            onSave = { keywords ->
                editingDetails = false
                scope.launch { vm.setOpenNoteKeywords(keywords) }
            },
            onDismiss = { editingDetails = false },
        )
    }
    if (insertingTable) {
        TableEditorDialog(
            initial = TableGrid.starter(),
            title = "New table",
            onSave = { grid ->
                insertingTable = false
                vm.completion.insertTable(grid)
            },
            onDismiss = { insertingTable = false },
        )
    }
    vm.tableEdit?.let { edit ->
        TableEditorDialog(
            initial = edit.grid,
            onSave = { grid -> scope.launch { vm.commitTableEdit(grid) } },
            onDismiss = { vm.cancelTableEdit() },
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
                keptSide = keptSide,
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
    namingSearch?.let { (search, renaming) ->
        SaveSearchDialog(
            query = search.query,
            name = search.name,
            renaming = renaming,
            onDismiss = { namingSearch = null },
            onSave = { name ->
                namingSearch = null
                vm.savedSearches.save(search.query, name)
            },
        )
    }

    // A tapped link into the notes arrives here as a `korgnote-link:` URI, from a note, the
    // calendar or search alike, a link to a file outside them as a `korgnote-file:` one, and a
    // tapped planning date as a `korgnote-calendar:` one; anything else is the platform's to
    // open, as before. A link that leads nowhere says why, and leaves the screen as it was.
    val platformUriHandler = LocalUriHandler.current
    val uriHandler = remember(platformUriHandler, vm) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val day = calendarDayOf(uri)
                when {
                    uri.startsWith(NOTE_LINK_URI_PREFIX) -> {
                        if (vm.links.follow(uri.removePrefix(NOTE_LINK_URI_PREFIX))) selectedScreen = BottomItem.FILE
                    }
                    uri.startsWith(FILE_LINK_URI_PREFIX) -> vm.files.open(uri.removePrefix(FILE_LINK_URI_PREFIX))
                    day != null -> openCalendar(CalendarTarget(CalendarMode.Day, day))
                    // Android throws when no app takes the link, and the tap would do nothing.
                    else -> try {
                        platformUriHandler.openUri(uri)
                    } catch (e: Exception) {
                        vm.links.onExternalLinkFailed(uri)
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // The drawer belongs to the tab whose button opens it: the note's opens the files, the
            // search's its saved searches. The other tabs have none.
            gesturesEnabled = selectedScreen == BottomItem.FILE || selectedScreen == BottomItem.SEARCH,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    if (selectedScreen == BottomItem.SEARCH) {
                        SavedSearchList(
                            searches = vm.savedSearches.all,
                            onRun = { search ->
                                // The results are to be read, not typed over.
                                focusManager.clearFocus()
                                vm.search.run(search.query)
                                scope.launch { drawerState.close() }
                            },
                            onRename = { namingSearch = it to true },
                            onRemove = { vm.savedSearches.remove(it.query) },
                            modifier = Modifier.padding(8.dp),
                        )
                    } else Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Files",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { creatingNote = true }) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "New note")
                            }
                            IconButton(onClick = { openToday() }) {
                                Icon(Icons.Filled.Today, contentDescription = "Today's daily")
                            }
                        }
                        // Above the filter, so it never scrolls away or filters out.
                        PinnedNotes(
                            paths = vm.pins.shown,
                            onOpen = { path ->
                                scope.launch {
                                    vm.onFileSelected(path)
                                    selectedScreen = BottomItem.FILE
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
                            placeholder = { Text("Part of a file name") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        FileTree(
                            root = vm.search.filteredTree,
                            // While filtering, open the folders holding a match, closed or not:
                            // a hidden match looks like a filter that does not work.
                            expandAll = vm.search.filterQuery.isNotBlank(),
                            onFileSelected = { name ->
                                // The filter had the keyboard open; the note is to be read.
                                focusManager.clearFocus()
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
            val window = LocalWindowInfo.current.containerSize
            val wide = with(LocalDensity.current) { window.width.toDp() } >= 600.dp
            val short = with(LocalDensity.current) { window.height.toDp() } < 480.dp
            // Editing on a screen that short, the keyboard takes half its height: the element gets
            // what is left, with no bar above it and no rail squeezed beside it.
            val editingShort = short && vm.editor.editingIndex != null
            // Only the note has a bar above it, naming it and opening the drawer. The other tabs
            // are named in the navigation, and their own headers carry what the bar would: the
            // calendar's period, the search field, the settings' first heading.
            val topBar = selectedScreen == BottomItem.FILE && !editingShort
            // Even the status bar: its height is another line of the element.
            HideStatusBar(editingShort)
            val rail = wide && !editingShort
            // The sync's state, and any conflict, go wherever the top bar's actions go.
            val statusIcons = @Composable {
                ConflictStatusIcon(vm.library.conflicts) { path ->
                    vm.onFileSelected(path)
                    selectedScreen = BottomItem.FILE
                }
                if (source is NotesSource.Folder) {
                    FolderStatusIcon(
                        unreachable = vm.server.unreachable,
                        onRetry = { scope.launch { vm.server.retry() } },
                    )
                } else {
                    ServerStatusIcon(
                        unreachable = vm.server.unreachable,
                        syncing = vm.server.syncing,
                        lastSynced = vm.server.lastSynced,
                        onRetry = { scope.launch { vm.server.retry() } },
                    )
                }
            }
            // Edge to edge, the cutout and the three-button bar sit on a side in landscape; the
            // rail keeps the start one clear and the screen beside it the rest.
            val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            val selectTab = { item: BottomItem ->
                if (item == BottomItem.CALENDAR) calendarTarget = null
                selectedScreen = item
            }
            Row {
                if (rail) {
                    NavigationRail(windowInsets = safeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                        BottomItem.entries.forEach { item ->
                            NavigationRailItem(
                                selected = selectedScreen == item,
                                onClick = { selectTab(item) },
                                icon = { Icon(item.getIconRes(), contentDescription = item.getLabel()) },
                                label = { Text(item.getLabel()) },
                                // Kept while editing where there is the height for it, unlike the
                                // bottom bar, so the note does not shift sideways; tabs still do
                                // not switch mid-edit.
                                enabled = vm.editor.editingIndex == null,
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = if (rail) {
                        safeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (topBar) TopAppBar(
                            // The note is what the screen is about: the tab it is on already
                            // shows in the navigation.
                            title = {
                                Text(
                                    vm.note.path?.substringAfterLast('/') ?: BottomItem.FILE.getLabel(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            windowInsets = if (rail) {
                                safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                            } else {
                                TopAppBarDefaults.windowInsets
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                                }
                            },
                            actions = {
                                // Back from a followed link, to where it was tapped.
                                if (vm.links.canGoBack) {
                                    IconButton(onClick = { vm.links.goBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                                statusIcons()
                            }
                        )
                    },
                    floatingActionButton = {
                        // While editing, the element's own actions live on the completion strip.
                        if (
                            vm.editor.editingIndex == null &&
                            selectedScreen == BottomItem.FILE &&
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
                        // happened by accident. It goes too while typing anywhere else, as in
                        // search, where the keyboard pushed it up over the results.
                        if (vm.editor.editingIndex != null) {
                            CompletionStrip(
                                items = vm.completion.suggestions,
                                selectedIndex = vm.completion.selectedIndex,
                                onAccept = { vm.completion.accept(it) },
                                inserts = vm.completion.inserts,
                                onInsert = { vm.completion.insert(it) },
                                onPickDate = { vm.completion.pickDate() },
                                onPickLink = { pickingLink = vm.linkTargets() },
                                onEditLink = vm.linkToEdit()?.takeUnless { it.isNew }?.let { edit -> { editingLink = edit } },
                                onInsertTable = { insertingTable = true },
                            ) {
                                // Saving an element is explicit: edits stay local until this is pressed.
                                IconButton(onClick = { vm.editor.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Discard changes to this element")
                                }
                                FilledIconButton(
                                    onClick = { scope.launch { vm.editor.commit() } },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = "Save this element")
                                }
                            }
                        } else if (!wide && WindowInsets.ime.getBottom(LocalDensity.current) == 0) NavigationBar {
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
                    pickingLink?.let { targets ->
                        LinkPickerDialog(
                            targets = targets,
                            currentPath = vm.note.path.orEmpty(),
                            onPick = { target ->
                                pickingLink = null
                                scope.launch { vm.insertLinkTo(target) }
                            },
                            onTypeLink = {
                                pickingLink = null
                                editingLink = vm.linkToEdit()
                            },
                            onDismiss = { pickingLink = null },
                        )
                    }
                    editingLink?.let { edit ->
                        LinkEditorDialog(
                            edit = edit,
                            noteTitle = vm::noteLinkTitle,
                            linkTargets = vm::linkTargets,
                            currentPath = vm.note.path.orEmpty(),
                            fileLinkMappings = vm.files.mappings(),
                            onSave = { target, description, note ->
                                editingLink = null
                                scope.launch { vm.saveLink(edit, target, description, note) }
                            },
                            onRemove = {
                                editingLink = null
                                vm.removeLink(edit)
                            },
                            onDismiss = { editingLink = null },
                        )
                    }
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
                    // left gets none inside one — so this Column yields its scroll to those three, and
                    // to settings, which keeps its save button in place below what scrolls.
                    val readingDocument = selectedScreen == BottomItem.FILE &&
                        vm.note.content.isNotBlank()
                    val ownsItsScroll = readingDocument ||
                        selectedScreen == BottomItem.SEARCH ||
                        selectedScreen == BottomItem.CALENDAR ||
                        selectedScreen == BottomItem.SETTINGS
                    val screenScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            // The calendar pads itself; above and below, as around an element being edited on a
                            // short screen, height is what is short.
                            .padding(
                                when {
                                    selectedScreen == BottomItem.CALENDAR -> PaddingValues(horizontal = 8.dp)
                                    editingShort -> PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                    else -> PaddingValues(16.dp)
                                }
                            )
                            .then(
                                if (ownsItsScroll) Modifier else Modifier.verticalScroll(screenScrollState)
                            ),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (selectedScreen) {
                            BottomItem.FILE -> {
                                // A note in conflict, or one of its copies, says so until it is settled.
                                conflictShown(vm.note.path, vm.library.conflicts)?.let { conflict ->
                                    ConflictBanner(
                                        conflict,
                                        onCompare = { comparingCopy = conflict.copy },
                                        onKeepNote = { scope.launch { vm.discardConflictCopy(conflict.copy) } },
                                        onUseCopy = { scope.launch { vm.useConflictCopy(conflict.copy) } },
                                        keptSide = keptSide,
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
                                        onEditTable = { vm.beginTableEdit(it) },
                                        backlinks = vm.links.backlinks,
                                        onOpenBacklink = { vm.links.openBacklink(it) },
                                        onFirstVisibleElement = { vm.links.onReadingPositionChanged(it) },
                                        listState = documentListState,
                                        onEditDetails = if (vm.note.path != null) {
                                            { editingDetails = true }
                                        } else {
                                            null
                                        },
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
                                        selectedScreen = BottomItem.FILE
                                    },
                                    saved = vm.savedSearches.find(vm.search.query) != null,
                                    onToggleSaved = {
                                        val query = vm.search.query.trim()
                                        if (vm.savedSearches.find(query) != null) {
                                            vm.savedSearches.remove(query)
                                        } else {
                                            namingSearch = SavedSearch(name = query, query = query) to false
                                        }
                                    },
                                    onShowSaved = {
                                        focusManager.clearFocus()
                                        scope.launch { drawerState.open() }
                                    },
                                    actions = statusIcons,
                                )
                            }

                            BottomItem.CALENDAR -> key(calendarTarget, calendarRequests) {
                                CalendarScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    agenda = vm.agenda.index,
                                    initialMode = calendarTarget?.mode,
                                    initialAnchorDate = calendarTarget?.date,
                                    onOpenEntry = { entry ->
                                        // The heading is resolved by the view model once the file's text
                                        // has loaded: that text arrives asynchronously, so working it out
                                        // here would search the previously open file.
                                        vm.agenda.openEntry(entry)
                                        selectedScreen = BottomItem.FILE
                                    },
                                    headerActions = statusIcons,
                                )
                            }

                            BottomItem.SETTINGS -> {
                                SettingsScreen(
                                    repo = settingsRepo,
                                    knownTags = vm.completion.context.knownTags,
                                    modifier = Modifier.fillMaxSize(),
                                    serverStatus = statusIcons,
                                    onSaved = { scope.launch { vm.server.retry() } },
                                    pairing = remember { KoinPlatform.getKoin().getOrNull<DevicePairing>() },
                                    notesSource = source,
                                    onChangeNotesSource = onChangeSource.takeIf { offersNotesFolder },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the system's back does while [enabled]. Of two such handlers the later one is asked first. */
@Composable
private fun OnBack(enabled: Boolean, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}

private enum class BottomItem {
    FILE, SEARCH, CALENDAR, SETTINGS;

    @Composable
    fun getLabel() = when (this) {
        FILE -> "File"
        SEARCH -> "Search"
        CALENDAR -> "Calendar"
        SETTINGS -> "Settings"
    }

    fun getIconRes() = when (this) {
        FILE -> Icons.Filled.Description
        SEARCH -> Icons.Filled.Search
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}
