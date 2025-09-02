package com.korgnote.korgnote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.home
import korgnote.composeapp.generated.resources.info
import korgnote.composeapp.generated.resources.settings
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        var selected by remember { mutableStateOf(BottomItem.HOME) }

        val homeLabel = stringResource(Res.string.home)
        val infoLabel = stringResource(Res.string.info)
        val settingsLabel = stringResource(Res.string.settings)
        Scaffold(
            bottomBar = {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { selected = BottomItem.HOME }, modifier = Modifier.semantics {
                            contentDescription = homeLabel
                            this.selected = (selected == BottomItem.HOME)
                        }) {
                            val isSelected = selected == BottomItem.HOME
                            val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Home, contentDescription = null, tint = tint)
                                Text(homeLabel, color = tint, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { selected = BottomItem.INFO }, modifier = Modifier.semantics {
                            contentDescription = infoLabel
                            this.selected = (selected == BottomItem.INFO)
                        }) {
                            val isSelected = selected == BottomItem.INFO
                            val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = tint)
                                Text(infoLabel, color = tint, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { selected = BottomItem.SETTINGS }, modifier = Modifier.semantics {
                            contentDescription = settingsLabel
                            this.selected = (selected == BottomItem.SETTINGS)
                        }) {
                            val isSelected = selected == BottomItem.SETTINGS
                            val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Settings, contentDescription = null, tint = tint)
                                Text(settingsLabel, color = tint, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        ) { _ ->
            when (selected) {
                BottomItem.HOME -> Text(homeLabel, modifier = Modifier)
                BottomItem.INFO -> Text(infoLabel, modifier = Modifier)
                BottomItem.SETTINGS -> Text(settingsLabel, modifier = Modifier)
            }
        }
    }
}

private enum class BottomItem { HOME, INFO, SETTINGS }