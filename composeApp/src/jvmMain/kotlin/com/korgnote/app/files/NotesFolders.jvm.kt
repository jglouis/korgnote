package com.korgnote.app.files

import androidx.compose.runtime.Composable

// The notes are the server's, on this computer: there is no folder to choose here.

@Composable
actual fun rememberNotesFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

actual fun notesFolderLabel(location: String): String = location

internal actual val notesFolderPlaces: String = ""

internal actual val notesFolderPickingHint: String = ""
