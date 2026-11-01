package com.korgnote.app.ui

import androidx.compose.ui.window.DialogProperties

internal actual fun cardDialogProperties(): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    useSoftwareKeyboardInset = false,
)
