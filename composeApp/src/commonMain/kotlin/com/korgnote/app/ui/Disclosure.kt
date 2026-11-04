package com.korgnote.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color

/** How long a chevron takes to turn, and what it opens to unfold: the two move together. */
internal const val DISCLOSURE_MILLIS = 200

/** How what a chevron folds comes and goes: unfolding from where it hangs, in time with the turn. */
internal val DisclosureEnter: EnterTransition = expandVertically(tween(DISCLOSURE_MILLIS)) + fadeIn(tween(DISCLOSURE_MILLIS))
internal val DisclosureExit: ExitTransition = shrinkVertically(tween(DISCLOSURE_MILLIS)) + fadeOut(tween(DISCLOSURE_MILLIS))

/**
 * The one chevron for whatever folds — a folder in the drawer, a heading, an Org drawer: pointing
 * at what it stands for while closed, turned down to what it shows while open, and turning between
 * the two rather than swapping. The drawer's folders pointed down while closed and up while open,
 * and the note's headings right and down, so the same arrow meant opposite things a swipe apart.
 */
@Composable
fun DisclosureChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(DISCLOSURE_MILLIS),
        label = "disclosure",
    )
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.rotate(angle),
    )
}
