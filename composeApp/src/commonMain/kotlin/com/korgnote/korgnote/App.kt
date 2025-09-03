package com.korgnote.korgnote

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                NavigationBar {
                    NavigationBarItem(
                        selected = selected == BottomItem.HOME,
                        onClick = { selected = BottomItem.HOME },
                        icon = { Icon(Icons.Filled.Home, contentDescription = homeLabel) },
                        label = { Text(homeLabel) },
                        alwaysShowLabel = true
                    )
                    NavigationBarItem(
                        selected = selected == BottomItem.INFO,
                        onClick = { selected = BottomItem.INFO },
                        icon = { Icon(Icons.Filled.Info, contentDescription = infoLabel) },
                        label = { Text(infoLabel) },
                        alwaysShowLabel = true
                    )
                    NavigationBarItem(
                        selected = selected == BottomItem.SETTINGS,
                        onClick = { selected = BottomItem.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = settingsLabel) },
                        label = { Text(settingsLabel) },
                        alwaysShowLabel = true
                    )
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