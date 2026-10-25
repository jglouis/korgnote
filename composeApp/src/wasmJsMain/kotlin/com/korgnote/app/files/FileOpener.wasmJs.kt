package com.korgnote.app.files

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

actual fun folderLabel(folder: String): String = folder
