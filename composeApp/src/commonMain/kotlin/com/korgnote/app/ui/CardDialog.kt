package com.korgnote.app.ui

import androidx.compose.ui.window.DialogProperties

/**
 * A dialog drawn over the whole window, with no inset of its own, for content that places itself
 * in what the system bars and the keyboard leave (`safeDrawingPadding`). Back closes it; a tap
 * beside its content does not.
 *
 * Per platform because Android's dialog window otherwise keeps its own size and slides when the
 * keyboard opens, never telling the content how much the keyboard covers.
 */
internal expect fun cardDialogProperties(): DialogProperties
