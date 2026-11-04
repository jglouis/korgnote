package com.korgnote.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.korgnote.app.ui.*
import kotlinx.coroutines.launch
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_files
import korgnote.composeapp.generated.resources.app_filter_files
import korgnote.composeapp.generated.resources.app_filter_files_hint
import korgnote.composeapp.generated.resources.app_new_note
import korgnote.composeapp.generated.resources.app_todays_daily
import org.jetbrains.compose.resources.stringResource

/** The drawer, which belongs to the tab whose button opens it: the note's lists the files, the search's its saved searches. */
@Composable
internal fun NotesAppState.NotesDrawerSheet() {
    val focusManager = LocalFocusManager.current
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
            // What the drawer does besides opening notes, each a button that says what it does:
            // three bare icons in a row were not obvious, and more were coming.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                DrawerAction(Icons.AutoMirrored.Filled.NoteAdd, stringResource(Res.string.app_new_note)) { creatingNote = true }
                DrawerAction(Icons.Filled.Today, stringResource(Res.string.app_todays_daily)) { openToday() }
            }
            Text(
                stringResource(Res.string.app_files),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.search.filterQuery,
                onValueChange = { vm.search.onFilterQueryChanged(it) },
                label = { Text(stringResource(Res.string.app_filter_files)) },
                placeholder = { Text(stringResource(Res.string.app_filter_files_hint)) },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
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
                    scope.launch { deletingNote = path to vm.noteFiles.hasUnsyncedChanges(path) }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One of the drawer's actions: an icon and what it does, as a button that looks like one. */
@Composable
private fun DrawerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
