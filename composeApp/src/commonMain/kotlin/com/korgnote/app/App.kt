package com.korgnote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.korgnote.app.di.appModule
import org.koin.compose.KoinApplication
import com.korgnote.app.ui.CalendarScreen
import com.korgnote.app.ui.FileTree
import com.korgnote.app.ui.OrgText
import com.korgnote.app.viewmodel.FileTreeViewModel
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.home
import korgnote.composeapp.generated.resources.calendar
import korgnote.composeapp.generated.resources.settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    KoinApplication(application = { modules(appModule) }) {
        MaterialTheme {
            var selectedScreen by remember { mutableStateOf(BottomItem.HOME) }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val vm = koinViewModel<FileTreeViewModel>()
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
                                        drawerState.close()
                                    }
                                }
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
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (selectedScreen) {
                            BottomItem.HOME -> {
                                Text(
                                    text = selectedScreen.getLabel(),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                // Reflect selection from ViewModel in UI
                                Text(text = vm.selectedFileLabel)
                                Spacer(modifier = Modifier.height(16.dp))
                                OrgText(content = vm.selectedFileContent.ifBlank { "Select an .org file to view its content" })
                            }

                            BottomItem.CALENDAR -> {
                                CalendarScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    scheduledByDate = vm.scheduledByDate,
                                    onOpenScheduled = { entry ->
                                        vm.onFileSelected(entry.fileName)
                                        selectedScreen = BottomItem.HOME
                                    }
                                )
                            }

                            BottomItem.SETTINGS -> {
                                Text(
                                    text = selectedScreen.getLabel(),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Settings placeholder")
                            }
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
        HOME -> stringResource(Res.string.home)
        CALENDAR -> stringResource(Res.string.calendar)
        SETTINGS -> stringResource(Res.string.settings)
    }

    fun getIconRes() = when (this) {
        HOME -> Icons.Filled.Home
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}