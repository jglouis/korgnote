package com.korgnote.app.files

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun folderLabel(folder: String): String = folder

@Composable
actual fun rememberFilePicker(mappings: List<FileLinkMapping>, onPicked: (PickedFile) -> Unit): (() -> Unit)? = null
