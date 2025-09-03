package com.korgnote.korgnote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.home
import korgnote.composeapp.generated.resources.info
import korgnote.composeapp.generated.resources.settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    MaterialTheme {
        var selected by remember { mutableStateOf(BottomItem.HOME) }
        var selectedFile by remember { mutableStateOf("No file selected") }
        val drawerState =
            androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                androidx.compose.material3.ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        val tree = Folder(
                            name = "Documents",
                            children = listOf(
                                Folder(
                                    name = "Projects",
                                    children = listOf(
                                        Folder(
                                            name = "KorgNote",
                                            children = listOf(
                                                File("README.md"),
                                                File("Design.md"),
                                                Folder(
                                                    name = "assets",
                                                    children = listOf(
                                                        File("icon.png"),
                                                        File("banner.jpg")
                                                    )
                                                )
                                            )
                                        ),
                                        File("todo.txt")
                                    )
                                ),
                                Folder(
                                    name = "Photos",
                                    children = listOf(
                                        Folder(
                                            name = "2023",
                                            children = listOf(
                                                File("vacation-1.jpg"),
                                                File("vacation-2.jpg")
                                            )
                                        ),
                                        Folder(
                                            name = "2024",
                                            children = listOf(
                                                File("family.png")
                                            )
                                        )
                                    )
                                ),
                                File("notes.txt")
                            )
                        )
                        FileTree(
                            root = tree,
                            onFileSelected = { name -> selectedFile = "Selected: $name" }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(selected.getLabel()) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        BottomItem.entries.forEach { item ->
                            NavigationBarItem(
                                selected = selected == item,
                                onClick = { selected = item },
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
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(text = selected.getLabel(), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = selectedFile)
                }
            }
        }
    }
}

private enum class BottomItem {
    HOME, INFO, SETTINGS;

    @Composable
    fun getLabel() = when (this) {
        HOME -> stringResource(Res.string.home)
        INFO -> stringResource(Res.string.info)
        SETTINGS -> stringResource(Res.string.settings)
    }

    fun getIconRes() = when (this) {
        HOME -> Icons.Filled.Home
        INFO -> Icons.Filled.Info
        SETTINGS -> Icons.Filled.Settings
    }
}