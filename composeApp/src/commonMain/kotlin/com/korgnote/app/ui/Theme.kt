package com.korgnote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * The app's theme: dark or light as the system is set, for Material's colours and the Org palette
 * ([OrgColors]) alike.
 *
 * Org text is highlighted, and cached, outside composition, so when the setting changes while the
 * app is open the content is composed afresh rather than left in the other theme's colours. Android
 * recreates the activity then anyway.
 */
@Composable
fun KorgNoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    OrgColors.dark = dark
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        key(dark) { content() }
    }
}
