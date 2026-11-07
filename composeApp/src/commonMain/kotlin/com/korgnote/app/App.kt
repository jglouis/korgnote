package com.korgnote.app

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.LastScreen
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.offersNotesFolder
import com.korgnote.app.ui.CalendarActions
import com.korgnote.app.ui.clockBarIsLive
import com.korgnote.app.ui.*
import com.korgnote.app.data.DevicePairing
import org.koin.compose.getKoin
import com.korgnote.app.capture.CaptureSignals
import com.korgnote.app.data.PairingLinks
import org.koin.mp.KoinPlatform
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
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
import org.koin.compose.viewmodel.koinViewModel
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_select_file
import org.jetbrains.compose.resources.stringResource

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
        // Asked for on the welcome screens' last step, opened once the notes are first loaded.
        var tourPending by rememberSaveable { mutableStateOf(false) }
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
                onTour = { tourPending = true },
            )
            // Everything below starts over for another source: its notes are other notes.
            else -> key(source) {
                NotesApp(source, choice, tour = tourPending, onTourOpened = { tourPending = false }) { changingSource = true }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesApp(
    source: NotesSource,
    choice: NotesSourceChoice,
    tour: Boolean,
    onTourOpened: () -> Unit,
    onChangeSource: () -> Unit,
) {
    val vm = koinViewModel<NotesViewModel>(key = "notes:${source.encode()}") { parametersOf(source) }
    rememberNotesAppState(source, vm, koinInject<SettingsRepository>()).NotesShell(choice, tour, onTourOpened) {
        // Stays alive behind the next source's: it should not stay in the way of those notes.
        vm.library.letGo()
        onChangeSource()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesAppState.NotesShell(
    choice: NotesSourceChoice,
    /** The tour note was asked for on the welcome screens, and is opened once the notes are loaded. */
    tour: Boolean,
    onTourOpened: () -> Unit,
    onChangeSource: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { TagColors.chosen = settingsRepo.getTagColors() }
    LaunchedEffect(vm) {
        choice.active = vm.server
        vm.server.load()
        // After the first sync, so that a tour note another device added is found and only opened.
        if (tour) {
            onTourOpened()
            openTour()
        }
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

    NotesDialogs()
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
            val statusIcons = @Composable { StatusIcons() }
            // Edge to edge, the cutout and the three-button bar sit on a side in landscape; the
            // rail keeps the start one clear and the screen beside it the rest.
            val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            Row {
                if (rail) {
                    NotesRail(safeInsets)
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
                        if (topBar) NotesTopBar(rail, safeInsets, statusIcons)
                    },
                    floatingActionButton = {
                        NotesFloatingButtons()
                    },
                    bottomBar = {
                        NotesBottomBar(wide, safeInsets, clockTime)
                    }
                ) { paddingValues ->
                    NotesScreenDialogs()
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
                                if (vm.note.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                // A note in conflict, or one of its copies, says so until it is settled.
                                conflictShown(vm.note.path, vm.library.conflicts)?.let { conflict ->
                                    ConflictBanner(
                                        conflict,
                                        onCompare = { dialog = NoteDialog.ComparingCopy(conflict.copy) },
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
                                                onRefileSection = { index -> dialog = NoteDialog.Refiling(index, vm.changes.refileTargets(index)) },
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
                                            { dialog = NoteDialog.NoteDetails }
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
                                    searching = vm.search.searching,
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
                                            dialog = NoteDialog.NamingSearch(SavedSearch(name = query, query = query), renaming = false)
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
                                    stillReading = vm.library.stillReading,
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
                                    onTour = { scope.launch { openTour() } },
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
