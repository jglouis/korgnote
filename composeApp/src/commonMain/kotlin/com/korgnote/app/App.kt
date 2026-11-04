package com.korgnote.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.LastScreen
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.offersNotesFolder
import com.korgnote.app.ui.CalendarActions
import com.korgnote.app.ui.RunningClockBar
import com.korgnote.app.ui.clockBarIsLive
import com.korgnote.app.ui.*
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.capture.CaptureLinks
import com.korgnote.app.capture.DeviceCalendar
import com.korgnote.common.org.capture.SharedContent
import org.koin.compose.getKoin
import korgnote.composeapp.generated.resources.editor_refile_title
import korgnote.composeapp.generated.resources.capture_event
import com.korgnote.app.capture.CaptureQueue
import com.korgnote.app.capture.CaptureSignals
import com.korgnote.app.data.PairingLinks
import org.koin.mp.KoinPlatform
import com.korgnote.app.viewmodel.NoteFileResult
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import org.koin.core.parameter.parametersOf
import com.korgnote.app.viewmodel.OpenNote
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.viewModelScope
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
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_add_heading_at_end
import korgnote.composeapp.generated.resources.app_discard_element
import korgnote.composeapp.generated.resources.app_new_table
import korgnote.composeapp.generated.resources.app_open_drawer
import korgnote.composeapp.generated.resources.app_save_element
import korgnote.composeapp.generated.resources.app_scroll_to_end
import korgnote.composeapp.generated.resources.app_select_file
import korgnote.composeapp.generated.resources.back
import korgnote.composeapp.generated.resources.calendar
import korgnote.composeapp.generated.resources.notes_conflict_moved
import org.jetbrains.compose.resources.stringResource
import com.korgnote.app.ui.StateNoteDialog

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
        // A pairing link, from the camera, while the notes are a folder or on a WebDAV server:
        // pairing is for the KorgNote server, which the welcome screens move the notes to. They
        // pair with it on their server step.
        val incomingLink by PairingLinks.pending.collectAsState()
        LaunchedEffect(incomingLink, source) {
            if (incomingLink != null && source != null && source != NotesSource.Server) changingSource = true
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
    val vm = koinViewModel<NotesViewModel>(key = "notes:${source.encode()}") { parametersOf(source) }
    rememberNotesAppState(source, vm, koinInject<SettingsRepository>()).NotesShell(choice, onChangeSource)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesAppState.NotesShell(choice: NotesSourceChoice, onChangeSource: () -> Unit) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { TagColors.chosen = settingsRepo.getTagColors() }
    LaunchedEffect(vm) {
        choice.active = vm.server
        vm.server.load()
        // What was captured while the app was closed — on iOS, from the share sheet — goes in now.
        vm.fileCaptures()
        // Then follow changes made elsewhere for the rest of the session.
        vm.server.watch()
    }
    // A capture queued by this process, from the share sheet on Android or a link here, goes in at once.
    LaunchedEffect(vm) {
        CaptureSignals.queued.collect { vm.fileCaptures() }
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
        // The share sheet may have queued captures while the app was in the background.
        scope.launch { vm.fileCaptures() }
        onPauseOrDispose {
            vm.server.onPause()
            // The app may not come back from the background: where the note was left, kept now.
            vm.places.saveReadingPlace()
        }
    }
    // Kept as it changes, so that the app opens on it again however it is closed.
    LaunchedEffect(Unit) {
        snapshotFlow { selectedScreen to calendarView }.collect { (tab, view) ->
            settingsRepo.setLastScreen(
                LastScreen(
                    tab = tab.name,
                    calendarMode = view?.mode?.name,
                    calendarDate = view?.date?.toString(),
                    savedOn = currentDate().toString(),
                ),
            )
        }
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
        WidgetSyncs.finished.collect { vm.server.onChangedElsewhere() }
    }
    // Why something could not be done, shown for a moment rather than over the note, where it
    // stayed until the next thing went wrong. Being offline shows in the top bar instead.
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
    // Where the reader is in the open note, to open it there again; the view model keeps it once
    // the scrolling stops. Not while the note is still being brought to where it was opened at.
    LaunchedEffect(vm, documentListState) {
        snapshotFlow {
            val path = vm.note.path
            if (path == null || !vm.note.settled) null
            else Triple(path, documentListState.firstVisibleItemIndex, documentListState.firstVisibleItemScrollOffset)
        }.collect { at -> at?.let { (path, index, pixels) -> vm.places.onReadingPlaceChanged(path, index, pixels) } }
    }

    // The system's back goes where the top bar's does: back from a followed link, to where it
    // was tapped. From another tab it goes to the note first, as Android apps go to their first
    // tab before they close. Of these handlers the later one is asked first, so an open drawer
    // closes before anything else.
    OnBack(enabled = selectedScreen != BottomItem.FILE) { selectedScreen = BottomItem.FILE }
    OnBack(enabled = selectedScreen == BottomItem.FILE && vm.links.canGoBack) { vm.links.goBack() }
    OnBack(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // A `korgnote://capture` link, or the calendar's event: what is captured, shown before it is saved.
    val captureLink by CaptureLinks.pending.collectAsState()
    val filer = vm.captureFiler
    if (filer != null && (captureLink != null || capturingEvent)) {
        val queue = koinInject<CaptureQueue>()
        val done = {
            CaptureLinks.pending.value = null
            capturingEvent = false
        }
        CaptureSheet(
            shared = captureLink?.shared ?: SharedContent(),
            store = vm.captureStore,
            filer = filer,
            // The notes the app has parsed already, rather than every note read again.
            destinations = { vm.captureDestinations() },
            calendar = getKoin().getOrNull<DeviceCalendar>(),
            startWithCalendar = capturingEvent,
            onDismiss = done,
            onSave = { request ->
                done()
                // The sheet is closed by now, and what was typed in it is only here: turning the
                // phone, which ends what the screen launched, must not take it.
                vm.viewModelScope.launch {
                    queue.add(request)
                    vm.fileCaptures()
                }
            },
        )
    }
    if (creatingNote) {
        NewNoteDialog(
            pathFor = vm.noteFiles::newNotePathFor,
            knownTags = vm.completion.context.knownTags,
            error = noteDialogError,
            onDismiss = { creatingNote = false; noteDialogError = null },
            onCreate = { title, tags ->
                scope.launch {
                    when (val result = vm.noteFiles.createNote(title, tags)) {
                        // Stays open when refused, saying why.
                        is NoteFileResult.Refused -> noteDialogError = result.reason
                        is NoteFileResult.Done -> {
                            creatingNote = false
                            noteDialogError = null
                            selectedScreen = BottomItem.FILE
                            drawerState.close()
                        }
                    }
                }
            },
        )
    }
    if (editingDetails) {
        NoteDetailsDialog(
            initial = remember { vm.changes.openNoteKeywords() },
            knownTags = vm.completion.context.knownTags,
            defaultAuthor = remember { settingsRepo.getNoteTemplate().author },
            today = vm.completion.context.today,
            onSave = { keywords ->
                editingDetails = false
                scope.launch { vm.changes.setOpenNoteKeywords(keywords) }
            },
            onDismiss = { editingDetails = false },
        )
    }
    if (insertingTable) {
        TableEditorDialog(
            initial = TableGrid.starter(),
            title = stringResource(Res.string.app_new_table),
            onSave = { grid ->
                insertingTable = false
                vm.completion.insertTable(grid)
            },
            onDismiss = { insertingTable = false },
            cellCompletion = vm::completionFor,
        )
    }
    vm.tables.edit?.let { edit ->
        TableEditorDialog(
            initial = edit.grid,
            onSave = { grid -> scope.launch { vm.tables.commit(grid) } },
            onDismiss = { vm.tables.cancel() },
            cellCompletion = vm::completionFor,
        )
    }
    renamingNote?.let { path ->
        RenameNoteDialog(
            path = path,
            error = noteDialogError,
            onDismiss = { renamingNote = null; noteDialogError = null },
            onRename = { target ->
                scope.launch {
                    when (val result = vm.noteFiles.renameNote(path, target)) {
                        is NoteFileResult.Refused -> noteDialogError = result.reason
                        is NoteFileResult.Done -> {
                            renamingNote = null
                            noteDialogError = null
                        }
                    }
                }
            },
        )
    }
    comparingCopy?.let { copy ->
        // Worked out again whenever a note's text changes, so the chunks are always the current ones.
        val comparison = remember(copy, vm.library.revision) { vm.noteFiles.conflictComparison(copy) }
        var refused by remember(copy) { mutableStateOf(false) }
        if (comparison == null) {
            // Settled meanwhile, here or on another device.
            LaunchedEffect(Unit) { comparingCopy = null }
        } else {
            val moved = stringResource(Res.string.notes_conflict_moved)
            ConflictCompareDialog(
                comparison = comparison,
                onApply = { choices ->
                    scope.launch {
                        if (vm.noteFiles.resolveConflictCopy(comparison, choices)) comparingCopy = null else refused = true
                    }
                },
                onDismiss = { comparingCopy = null },
                keptSide = keptSide,
                error = moved.takeIf { refused },
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
                scope.launch { vm.noteFiles.deleteNote(path) }
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

    CompositionLocalProvider(LocalUriHandler provides rememberNoteLinkHandler()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // The drawer belongs to the tab whose button opens it: the note's opens the files, the
            // search's its saved searches. The other tabs have none.
            gesturesEnabled = selectedScreen == BottomItem.FILE || selectedScreen == BottomItem.SEARCH,
            drawerContent = {
                NotesDrawerSheet()
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
            // What a running clock counts to: the bar's elapsed time and the totals beside headings,
            // moved on each minute while a clock runs, and still otherwise.
            val clockTime by produceState(vm.clockActions.currentTime(), vm.clocks.running, vm.clocks.recent) {
                value = vm.clockActions.currentTime()
                // Also while the bar offers to clock back in, so that it goes when the hour is up.
                while (clockBarIsLive(vm.clocks.running, vm.clocks.recent, value)) {
                    delay((60 - value.second) * 1000L)
                    value = vm.clockActions.currentTime()
                }
            }
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
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(Res.string.app_open_drawer))
                                }
                            },
                            actions = {
                                // Back from a followed link, to where it was tapped.
                                if (vm.links.canGoBack) {
                                    IconButton(onClick = { vm.links.goBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                                    }
                                }
                                statusIcons()
                            }
                        )
                    },
                    floatingActionButton = {
                        // In the app, what there is to capture is what is not in the notes yet: an
                        // event from the phone's calendar, put into the notes' own. It was in the
                        // files' drawer, which is about notes, not dates.
                        if (selectedScreen == BottomItem.CALENDAR) {
                            FloatingActionButton(onClick = { capturingEvent = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.capture_event))
                            }
                        }
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
                                            contentDescription = stringResource(Res.string.app_scroll_to_end),
                                        )
                                    }
                                }
                                FloatingActionButton(onClick = { vm.editor.addAtEnd() }) {
                                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.app_add_heading_at_end))
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
                                vm.completion,
                                onPickLink = { pickingLink = vm.linkForm.linkTargets() },
                                onEditLink = vm.linkForm.linkToEdit()?.takeUnless { it.isNew }?.let { edit -> { editingLink = edit } },
                                onInsertTable = { insertingTable = true },
                            ) {
                                // Saving an element is explicit: edits stay local until this is pressed.
                                IconButton(onClick = { vm.editor.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.app_discard_element))
                                }
                                FilledIconButton(
                                    onClick = { scope.launch { vm.editor.commit() } },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = stringResource(Res.string.app_save_element))
                                }
                            }
                        } else if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) Column {
                            // A running clock, on every tab, just above the tabs; on a wide window,
                            // where the tabs are down the side, at the foot of the screen.
                            RunningClockBar(
                                clocks = vm.clocks.running,
                                now = clockTime,
                                onOpen = { clock ->
                                    vm.onFileSelected(clock.path, scrollToOffset = clock.headingStart)
                                    selectedScreen = BottomItem.FILE
                                },
                                onClockOut = { clock, at -> vm.clockActions.clockOut(clock, at) },
                                onCancel = { vm.clockActions.cancelClock(it) },
                                recent = vm.clocks.recent,
                                onClockIn = { vm.clockActions.clockIn(it.path, it.headingStart) },
                                onOpenRecent = { task ->
                                    vm.onFileSelected(task.path, scrollToOffset = task.headingStart)
                                    selectedScreen = BottomItem.FILE
                                },
                                modifier = if (wide) Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)) else Modifier,
                            )
                            if (!wide) NavigationBar {
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
                    }
                ) { paddingValues ->
                    pickingLink?.let { targets ->
                        LinkPickerDialog(
                            targets = targets,
                            currentPath = vm.note.path.orEmpty(),
                            onPick = { target ->
                                pickingLink = null
                                scope.launch { vm.linkForm.insertLinkTo(target) }
                            },
                            onTypeLink = {
                                pickingLink = null
                                editingLink = vm.linkForm.linkToEdit()
                            },
                            onDismiss = { pickingLink = null },
                        )
                    }
                    refiling?.let { (index, targets) ->
                        LinkPickerDialog(
                            targets = targets,
                            currentPath = vm.note.path.orEmpty(),
                            onPick = { target ->
                                refiling = null
                                scope.launch { vm.changes.refile(index, target) }
                            },
                            onTypeLink = null,
                            onDismiss = { refiling = null },
                            title = stringResource(Res.string.editor_refile_title),
                        )
                    }
                    editingLink?.let { edit ->
                        LinkEditorDialog(
                            edit = edit,
                            noteTitle = vm.linkForm::noteLinkTitle,
                            linkTargets = vm.linkForm::linkTargets,
                            currentPath = vm.note.path.orEmpty(),
                            fileLinkMappings = vm.files.mappings(),
                            onSave = { target, description, note ->
                                editingLink = null
                                scope.launch { vm.linkForm.saveLink(edit, target, description, note) }
                            },
                            onRemove = {
                                editingLink = null
                                vm.linkForm.removeLink(edit)
                            },
                            onDismiss = { editingLink = null },
                        )
                    }
                    vm.stateNotes.pending?.let { pending ->
                        StateNoteDialog(
                            keyword = pending.keyword.takeUnless { pending.closing },
                            headingTitle = pending.title,
                            value = vm.stateNotes.draft,
                            onValueChange = { vm.stateNotes.onDraftChanged(it) },
                            completion = vm.stateNotes.completion,
                            todoKeywords = vm.todoKeywords,
                            onSave = { vm.stateNotes.save() },
                            onCancel = { vm.stateNotes.cancel() },
                        )
                        CompletionDatePicker(vm.stateNotes.completion)
                    }
                    CompletionDatePicker(vm.completion)
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
                                        onKeepNote = { scope.launch { vm.noteFiles.discardConflictCopy(conflict.copy) } },
                                        onUseCopy = { scope.launch { vm.noteFiles.useConflictCopy(conflict.copy) } },
                                        keptSide = keptSide,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                if (vm.note.content.isBlank()) {
                                    Text(stringResource(Res.string.app_select_file))
                                } else {
                                    OrgDocumentView(
                                        // weight, not wrap: the lazy list needs a bounded height to
                                        // know what is on screen, and fills what the header leaves.
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        text = vm.note.content,
                                        todoKeywords = vm.todoKeywords,
                                        editingElementIndex = vm.editor.editingIndex,
                                        elementDraft = { vm.editor.draftValue },
                                        onElementDraftChange = { vm.editor.onDraftChanged(it) },
                                        onCompletionKey = vm.completion::answer,
                                        actions = remember(vm) {
                                            ElementActions(
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
                                                onRefileSection = { index -> refiling = index to vm.changes.refileTargets(index) },
                                                onSetPriority = { offset, priority -> vm.changes.setPriorityAt(offset, priority) },
                                                onClockIn = { vm.clockActions.clockInAt(it) },
                                                onClockOut = { vm.clockActions.clockOutAt(it) },
                                                onCancelClock = { vm.clockActions.cancelClockAt(it) },
                                                onSetEffort = { offset, minutes -> vm.clockActions.setEffortAt(offset, minutes) },
                                                onToggleFold = { vm.folds.toggle(it) },
                                                onToggleCheckbox = { offset -> vm.changes.toggleCheckboxAt(offset) },
                                                onToggleTimestamp = { offset -> vm.changes.toggleTimestampAt(offset) },
                                                onSetTodo = { offset, keyword -> vm.stateNotes.setTodoAt(offset, keyword) },
                                                onEditTable = { vm.tables.begin(it) },
                                            )
                                        },
                                        clockTime = clockTime,
                                        foldedElements = vm.folds.folded,
                                        scrollToElementIndex = vm.note.scrollToElementIndex,
                                        onScrolledToElement = { vm.note.onScrolledToElement() },
                                        restoreTo = vm.note.restoreTo,
                                        onRestored = { vm.note.onRestored() },
                                        revealElementIndex = vm.note.revealElementIndex,
                                        onRevealed = { vm.note.onRevealed() },
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
                                    onViewChanged = { mode, date -> calendarView = CalendarTarget(mode, date) },
                                    onOpenEntry = { entry ->
                                        // The heading is resolved by the view model once the file's text
                                        // has loaded: that text arrives asynchronously, so working it out
                                        // here would search the previously open file.
                                        vm.agenda.openEntry(entry)
                                        selectedScreen = BottomItem.FILE
                                    },
                                    headerActions = statusIcons,
                                    actions = remember(vm) {
                                        CalendarActions(
                                            isRunning = { file, offset -> vm.clockActions.clockRunningOn(file, offset) != null },
                                            clockIn = { file, offset -> vm.clockActions.clockIn(file, offset) },
                                            clockOut = { file, offset -> vm.clockActions.clockRunningOn(file, offset)?.let { vm.clockActions.clockOut(it) } },
                                            open = { file, offset ->
                                                vm.onFileSelected(file, scrollToOffset = offset)
                                                selectedScreen = BottomItem.FILE
                                            },
                                            keywords = { file -> vm.stateNotes.keywordsOf(file) },
                                            setTodo = { file, offset, keyword -> vm.stateNotes.setTodo(file, offset, keyword) },
                                            reschedule = { entry, date, time, end -> vm.changes.reschedule(entry, date, time, end) },
                                        )
                                    },
                                )
                            }

                            BottomItem.SETTINGS -> {
                                SettingsScreen(
                                    repo = settingsRepo,
                                    knownTags = vm.completion.context.knownTags,
                                    modifier = Modifier.fillMaxSize(),
                                    serverStatus = statusIcons,
                                    syncStatus = {
                                        SyncStatusText(
                                            unreachable = vm.server.unreachable,
                                            syncing = vm.server.syncing,
                                            lastSynced = vm.server.lastSynced,
                                        )
                                    },
                                    onSaved = { scope.launch { vm.server.retry() } },
                                    pairing = remember { KoinPlatform.getKoin().getOrNull<DevicePairing>() },
                                    notesSource = source,
                                    onChangeNotesSource = onChangeSource.takeIf { offersNotesFolder },
                                    onNotificationsAllowed = vm.agenda::onNotificationsAllowed,
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
