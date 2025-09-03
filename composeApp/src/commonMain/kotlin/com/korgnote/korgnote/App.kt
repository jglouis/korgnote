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
        Scaffold(
            bottomBar = {
                NavigationBar {
                    BottomItem.entries.forEach { item ->
                        NavigationBarItem(
                            selected = selected == item,
                            onClick = { selected = item },
                            icon = { Icon(item.getIconRes(), contentDescription = item.getLabel()) },
                            label = { Text(item.getLabel()) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { _ ->
            Text(selected.getLabel(), modifier = Modifier)
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