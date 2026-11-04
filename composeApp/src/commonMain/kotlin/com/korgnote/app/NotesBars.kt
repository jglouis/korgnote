package com.korgnote.app

import kotlinx.datetime.LocalDateTime
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.NotesSource
import com.korgnote.app.ui.RunningClockBar
import com.korgnote.app.ui.*
import korgnote.composeapp.generated.resources.capture_event
import com.korgnote.app.viewmodel.OpenNote
import kotlinx.coroutines.launch
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_add_heading_at_end
import korgnote.composeapp.generated.resources.app_discard_element
import korgnote.composeapp.generated.resources.app_open_drawer
import korgnote.composeapp.generated.resources.app_save_element
import korgnote.composeapp.generated.resources.app_scroll_to_end
import korgnote.composeapp.generated.resources.back
import org.jetbrains.compose.resources.stringResource

/** The sync's state, and any conflict, wherever the top bar's actions go. */
@Composable
internal fun NotesAppState.StatusIcons() {
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

/** The tabs, down the side of a window wide enough to spare it. */
@Composable
internal fun NotesAppState.NotesRail(safeInsets: WindowInsets) {
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

/** Over the note: its name, and the way to the drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesAppState.NotesTopBar(rail: Boolean, safeInsets: WindowInsets, statusIcons: @Composable () -> Unit) {
    TopAppBar(
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
}

@Composable
internal fun NotesAppState.NotesFloatingButtons() {
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
}

@Composable
internal fun NotesAppState.NotesBottomBar(wide: Boolean, safeInsets: WindowInsets, clockTime: LocalDateTime) {
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
