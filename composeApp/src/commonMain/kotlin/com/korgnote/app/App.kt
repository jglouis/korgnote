package com.korgnote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.CalendarScreen
import com.korgnote.app.ui.FileTree
import com.korgnote.app.ui.OrgText
import com.korgnote.app.ui.SettingsScreen
import com.korgnote.app.viewmodel.FileTreeViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    MaterialTheme {
        var selectedScreen by remember { mutableStateOf(BottomItem.HOME) }
        var isEditing by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val vm = koinViewModel<FileTreeViewModel>()
        LaunchedEffect(Unit) {
            vm.loadFromServer()
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = selectedScreen != BottomItem.CALENDAR,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.filterQuery,
                            onValueChange = { vm.filterQuery = it },
                            label = { Text("Filter files") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        FileTree(
                            root = vm.filteredTree,
                            onFileSelected = { name ->
                                scope.launch {
                                    vm.onFileSelected(name)
                                    isEditing = false
                                    drawerState.close()
                                }
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
                            if (selectedScreen == BottomItem.HOME && vm.selectedFileLabel != "No file selected") {
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
                bottomBar = {
                    NavigationBar {
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
                // Main content area
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
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
                            Text(text = vm.selectedFileLabel)
                            Spacer(modifier = Modifier.height(16.dp))

                            if (isEditing) {
                                OutlinedTextField(
                                    value = vm.selectedFileContent,
                                    onValueChange = { vm.onContentChanged(it) },
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    label = { Text("Content") }
                                )
                            } else {
                                OrgText(
                                    content = vm.selectedOrgNode ?: com.korgnote.common.org.OrgRaw(
                                        vm.selectedFileContent.ifBlank { "Select an .org file to view its content" }
                                    ),
                                    onToggleCheckbox = { ordinalIndex, checked ->
                                        vm.toggleCheckboxByOrdinal(
                                            ordinalIndex,
                                            checked
                                        )
                                    }
                                )
                            }
                        }

                        BottomItem.CALENDAR -> {
                            CalendarScreen(
                                modifier = Modifier.fillMaxSize(),
                                scheduledByDate = vm.scheduledByDate,
                                onOpenScheduled = { pair ->
                                    val fileName = pair.first
                                    vm.onFileSelected(fileName)
                                    selectedScreen = BottomItem.HOME
                                }
                            )
                        }

                        BottomItem.SETTINGS -> {
                            val settingsRepo = koinInject<SettingsRepository>()
                            SettingsScreen(repo = settingsRepo, onSaved = { scope.launch { vm.loadFromServer() } })
                        }
                    }
                }
            }
        }
    }
}

private enum class BottomItem {
    HOME, CALENDAR, SETTINGS;

    @Composable
    fun getLabel() = when (this) {
        HOME -> "Home"
        CALENDAR -> "Calendar"
        SETTINGS -> "Settings"
    }

    fun getIconRes() = when (this) {
        HOME -> Icons.Filled.Home
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}