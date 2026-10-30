package com.korgnote.app.files

import androidx.compose.runtime.Composable

// The notes are the server's, on this computer: there is no folder to choose here.

@Composable
actual fun rememberNotesFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun notesFolderLabel(location: String): String = location

internal actual val notesFolderPlaces: String
    @Composable get() = ""

internal actual val notesFolderPickingHint: String
    @Composable get() = ""
