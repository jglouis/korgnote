package com.korgnote.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.korgnote.app.ui.*
import com.korgnote.app.capture.CaptureLinks
import com.korgnote.app.data.NotesSource
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
    vm.tables.edit?.let { edit ->
        TableEditorDialog(
            initial = edit.grid,
            onSave = { grid -> scope.launch { vm.tables.commit(grid) } },
            onDismiss = { vm.tables.cancel() },
            cellCompletion = vm::completionFor,
        )
    }
    when (val open = dialog) {
        NoteDialog.NewNote -> NewNoteDialog(
            pathFor = vm.noteFiles::newNotePathFor,
            knownTags = vm.completion.context.knownTags,
            error = noteDialogError,
            onDismiss = { close(open); noteDialogError = null },
            onCreate = { title, tags ->
                scope.launch {
                    when (val result = vm.noteFiles.createNote(title, tags)) {
                        // Stays open when refused, saying why.
                        is NoteFileResult.Refused -> noteDialogError = result.reason
                        is NoteFileResult.Done -> {
                            close(open)
                            noteDialogError = null
                            selectedScreen = BottomItem.FILE
                            drawerState.close()
                        }
                    }
                }
            },
        )
        NoteDialog.NoteDetails -> NoteDetailsDialog(
            initial = remember { vm.changes.openNoteKeywords() },
            knownTags = vm.completion.context.knownTags,
            defaultAuthor = remember { settingsRepo.getNoteTemplate().author },
            today = vm.completion.context.today,
            onSave = { keywords ->
                close(open)
                scope.launch { vm.changes.setOpenNoteKeywords(keywords) }
            },
            onDismiss = { close(open) },
        )
        NoteDialog.NewTable -> TableEditorDialog(
            initial = TableGrid.starter(),
            title = stringResource(Res.string.app_new_table),
            onSave = { grid ->
                close(open)
                vm.completion.insertTable(grid)
            },
            onDismiss = { close(open) },
            cellCompletion = vm::completionFor,
        )
        is NoteDialog.RenamingNote -> RenameNoteDialog(
            path = open.path,
            error = noteDialogError,
            onDismiss = { close(open); noteDialogError = null },
            onRename = { target ->
                scope.launch {
                    when (val result = vm.noteFiles.renameNote(open.path, target)) {
                        is NoteFileResult.Refused -> noteDialogError = result.reason
                        is NoteFileResult.Done -> {
                            close(open)
                            noteDialogError = null
                        }
                    }
                }
            },
        )
        is NoteDialog.ComparingCopy -> {
            // Worked out again whenever a note's text changes, so the chunks are always the current ones.
            val comparison = remember(open.copy, vm.library.revision) { vm.noteFiles.conflictComparison(open.copy) }
            var refused by remember(open.copy) { mutableStateOf(false) }
            if (comparison == null) {
                // Settled meanwhile, here or on another device.
                LaunchedEffect(Unit) { close(open) }
            } else {
                val moved = stringResource(Res.string.notes_conflict_moved)
                ConflictCompareDialog(
                    comparison = comparison,
                    onApply = { choices ->
                        scope.launch {
                            if (vm.noteFiles.resolveConflictCopy(comparison, choices)) close(open) else refused = true
                        }
                    },
                    onDismiss = { close(open) },
                    keptSide = keptSide,
                    error = moved.takeIf { refused },
                )
            }
        }
        is NoteDialog.DeletingNote -> DeleteNoteDialog(
            path = open.path,
            hasUnsyncedChanges = open.unsynced,
            keepsACopy = source == NotesSource.Server,
            onDismiss = { close(open) },
            onDelete = {
                close(open)
                scope.launch { vm.noteFiles.deleteNote(open.path) }
            },
        )
        is NoteDialog.NamingSearch -> SaveSearchDialog(
            query = open.search.query,
            name = open.search.name,
            renaming = open.renaming,
            onDismiss = { close(open) },
            onSave = { name ->
                close(open)
                vm.savedSearches.save(open.search.query, name)
            },
        )
        // Within the screen, in what follows.
        is NoteDialog.PickingLink, is NoteDialog.Refiling, is NoteDialog.EditingLink, null -> Unit
    }
}

/** What opens from the note being read or edited, within what handles its links. */
@Composable
internal fun NotesAppState.NotesScreenDialogs() {
    when (val open = dialog) {
        is NoteDialog.PickingLink -> LinkPickerDialog(
            targets = open.targets,
            currentPath = vm.note.path.orEmpty(),
            onPick = { target ->
                close(open)
                scope.launch { vm.linkForm.insertLinkTo(target) }
            },
            onTypeLink = {
                dialog = vm.linkForm.linkToEdit()?.let { NoteDialog.EditingLink(it) }
            },
            onDismiss = { close(open) },
        )
        is NoteDialog.Refiling -> LinkPickerDialog(
            targets = open.targets,
            currentPath = vm.note.path.orEmpty(),
            onPick = { target ->
                close(open)
                scope.launch { vm.changes.refile(open.index, target) }
            },
            onTypeLink = null,
            onDismiss = { close(open) },
            title = stringResource(Res.string.editor_refile_title),
        )
        is NoteDialog.EditingLink -> LinkEditorDialog(
            edit = open.edit,
            noteTitle = vm.linkForm::noteLinkTitle,
            linkTargets = vm.linkForm::linkTargets,
            currentPath = vm.note.path.orEmpty(),
            fileLinkMappings = vm.files.mappings(),
            onSave = { target, description, note ->
                close(open)
                scope.launch { vm.linkForm.saveLink(open.edit, target, description, note) }
            },
            onRemove = {
                close(open)
                vm.linkForm.removeLink(open.edit)
            },
            onDismiss = { close(open) },
        )
        else -> Unit
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
