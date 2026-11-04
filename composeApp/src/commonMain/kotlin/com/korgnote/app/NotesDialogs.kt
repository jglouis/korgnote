package com.korgnote.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.korgnote.app.ui.*
import com.korgnote.app.capture.CaptureLinks
import com.korgnote.app.capture.DeviceCalendar
import com.korgnote.common.org.capture.SharedContent
import org.koin.compose.getKoin
import korgnote.composeapp.generated.resources.editor_refile_title
import com.korgnote.app.capture.CaptureQueue
import com.korgnote.app.viewmodel.NoteFileResult
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.viewModelScope
import com.korgnote.common.org.edit.TableGrid
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_new_table
import korgnote.composeapp.generated.resources.calendar
import korgnote.composeapp.generated.resources.notes_conflict_moved
import org.jetbrains.compose.resources.stringResource
import com.korgnote.app.ui.StateNoteDialog

/** What opens over the whole of the notes screens, from the drawer or from anywhere. */
@Composable
internal fun NotesAppState.NotesDialogs() {
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
}

/** What opens from the note being read or edited, within what handles its links. */
@Composable
internal fun NotesAppState.NotesScreenDialogs() {
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
}
