package com.korgnote.app.ui

import androidx.compose.runtime.Composable

/**
 * Hides the system status bar while [hidden], for as long as this is in the composition; a swipe
 * from the edge shows it for a moment. Nothing happens where the app has no status bar of its own.
 */
@Composable
expect fun HideStatusBar(hidden: Boolean)
