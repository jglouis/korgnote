package com.korgnote.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.korgnote.app.viewmodel.CompletionController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.org.edit.DraftInsert
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_date
import korgnote.composeapp.generated.resources.editor_edit_link
import korgnote.composeapp.generated.resources.editor_insert_checkbox
import korgnote.composeapp.generated.resources.editor_insert_link
import korgnote.composeapp.generated.resources.editor_insert_place
import korgnote.composeapp.generated.resources.editor_insert_schedule
import korgnote.composeapp.generated.resources.editor_insert_table
import korgnote.composeapp.generated.resources.editor_insert_tag
import korgnote.composeapp.generated.resources.editor_make_active
import korgnote.composeapp.generated.resources.editor_make_inactive
import org.jetbrains.compose.resources.stringResource

/** What a hardware keyboard can ask of the suggestions. */
enum class CompletionKey { ACCEPT, DISMISS, NEXT, PREVIOUS }

/**
 * The keys a field being completed hands to its suggestions, through [onKey]: Tab to accept, the
 * arrows to move, Esc to dismiss. Only meaningful with a hardware keyboard; on a phone the chips are
 * the interface.
 *
 * Tab, not Enter: Enter is a newline in a multi-line element, and stealing it would make an ordinary
 * edit depend on whether a chip happened to be showing. A key [onKey] does not take — there was
 * nothing to suggest — keeps its ordinary meaning: an arrow still moves the caret.
 */
internal fun Modifier.completionKeys(onKey: (CompletionKey) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val intent = when (event.key) {
        Key.Escape -> CompletionKey.DISMISS
        Key.Tab -> CompletionKey.ACCEPT
        Key.DirectionDown, Key.DirectionRight -> CompletionKey.NEXT
        Key.DirectionUp, Key.DirectionLeft -> CompletionKey.PREVIOUS
        else -> return@onPreviewKeyEvent false
    }
    onKey(intent)
}

/** [key] answered by these suggestions; false when there are none, so that the key does its usual thing. */
internal fun CompletionController.answer(key: CompletionKey): Boolean =
    if (suggestions.isEmpty()) false else when (key) {
        CompletionKey.ACCEPT -> acceptSelected()
        CompletionKey.DISMISS -> { dismiss(); true }
        CompletionKey.NEXT -> { moveSelection(1); true }
        CompletionKey.PREVIOUS -> { moveSelection(-1); true }
    }

/**
 * The suggestions, as a strip of chips **pinned to the bottom of the screen** while an element is
 * being edited.
 *
 * Pinned rather than placed next to the field, because the field is inside a scrolling list: the
 * `imePadding` around the app brings *the field* into view when the keyboard opens, so anything
 * laid out after it lands in the region the keyboard just took. Position it against the
 * screen and that cannot happen, whatever the element's height or where the caret is in it.
 *
 * A strip rather than a floating popup, on every platform: the popup the design sketched suits a
 * desktop with a hardware keyboard, but it fights the IME and the selection handles on a phone,
 * which is where these notes are mostly written. One presentation also means one thing to keep
 * working. The keyboard path (Tab to accept, arrows to move, Esc to dismiss) is handled by the
 * field itself, so a desktop user never has to reach for the chips.
 *
 * With nothing to suggest, the strip holds [inserts] instead: buttons that begin a link, a tag, a
 * date, a place or a checkbox, so that none of their syntax has to be remembered or typed.
 *
 * [actions] — discarding and saving the element — sit at the end of the strip for the same reason.
 * As floating buttons they were drawn over the document, and the last element of a file, having
 * nothing below it to scroll up with, stayed under them while it was being edited.
 */
@Composable
fun CompletionStrip(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onAccept: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
    inserts: List<DraftInsert> = emptyList(),
    onInsert: (DraftInsert) -> Unit = {},
    /** The date button, which asks a picker before it has anything to insert. */
    onPickDate: (() -> Unit)? = null,
    /** The link button, when it finds a note or heading to link to instead of starting a `[[`. */
    onPickLink: (() -> Unit)? = null,
    /**
     * Opens the link form on the link the caret is in; null when it is in none. Its button stays in
     * front of the suggestions, which the caret in a link's target brings up in place of the inserts.
     */
    onEditLink: (() -> Unit)? = null,
    /** The table button, which opens the grid editor on a new table rather than typing pipes. */
    onInsertTable: (() -> Unit)? = null,
    /**
     * Keeps the strip clear of the system's navigation bar, as it must be at the foot of the screen;
     * off inside a dialog, which the system already keeps clear of it.
     */
    clearOfNavigationBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Keyboard selection can walk past the edge of the strip; keep the chosen chip in sight.
    val leading = if (onEditLink != null) 1 else 0
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex + leading)
    }

    // The bar keeps its place for the whole edit, empty or not. Suggestions come and go with almost
    // every keystroke, and a bar that came and went with them would reflow the document under the
    // caret each time — trading one distraction for a worse one. Empty, it offers the inserts.
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        // Clear of the system's navigation bar, as the navigation bar it replaces is: edge to edge,
        // three-button navigation drew over the save button whenever the keyboard was down. With
        // the keyboard up, its padding has taken that space already, and this adds nothing.
        Row(if (clearOfNavigationBar) Modifier.navigationBarsPadding() else Modifier, verticalAlignment = Alignment.CenterVertically) {
            // The chips scroll in what the actions leave, so the actions never scroll away.
            BoxWithConstraints(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (items.isEmpty()) {
                    // A date anywhere comes next to the link, before the heading's own things.
                    val buttons = buildList {
                        for (what in inserts) {
                            when {
                                // On a link, the link button is for that link.
                                what == DraftInsert.LINK && onEditLink != null -> add(Triple(stringResource(Res.string.editor_edit_link), what.icon(), onEditLink))
                                what == DraftInsert.LINK && onPickLink != null -> add(Triple(what.label(), what.icon(), onPickLink))
                                what == DraftInsert.TABLE && onInsertTable != null -> add(Triple(what.label(), what.icon(), onInsertTable))
                                else -> add(Triple(what.label(), what.icon()) { onInsert(what) })
                            }
                            if (what == DraftInsert.LINK && onPickDate != null) add(Triple(stringResource(Res.string.editor_date), Icons.Filled.Event, onPickDate))
                        }
                    }
                    // Labelled where there is room for all of them; on a phone, icons, so that none
                    // is scrolled out of sight where nobody would know to look for it.
                    if (maxWidth >= LABELLED_INSERT_WIDTH * buttons.size) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            buttons.forEach { (label, icon, onClick) -> item { InsertChip(label, icon, onClick) } }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            buttons.forEach { (label, icon, onClick) ->
                                IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (onEditLink != null) item { InsertChip(stringResource(Res.string.editor_edit_link), Icons.Filled.Link, onEditLink) }
                        itemsIndexed(items) { index, item ->
                            val selected = index == selectedIndex
                            AssistChip(
                                onClick = { onAccept(item) },
                                // A long address must not take the whole strip and hide the others.
                                label = {
                                    Text(
                                        item.shownLabel(),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp),
                                    )
                                },
                                colors = if (selected) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                },
                            )
                        }
                    }
                }
            }
            // Set apart, so that discarding is never mistaken for one of the inserts beside it.
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
            actions()
        }
    }
}

/** The strip showing what [completion] offers, and telling it what was tapped. */
@Composable
internal fun CompletionStrip(
    completion: CompletionController,
    /** Which of its inserts have a place in this field. */
    inserts: List<DraftInsert> = completion.inserts,
    onPickLink: (() -> Unit)? = null,
    onEditLink: (() -> Unit)? = null,
    onInsertTable: (() -> Unit)? = null,
    clearOfNavigationBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) = CompletionStrip(
    items = completion.suggestions,
    selectedIndex = completion.selectedIndex,
    onAccept = { completion.accept(it) },
    inserts = inserts,
    onInsert = { completion.insert(it) },
    onPickDate = { completion.pickDate() },
    onPickLink = onPickLink,
    onEditLink = onEditLink,
    onInsertTable = onInsertTable,
    clearOfNavigationBar = clearOfNavigationBar,
    actions = actions,
)

/**
 * What a suggestion's chip says. Most say what they write, which is Org and needs no translating;
 * the toggle says what it does, in the reader's language.
 */
@Composable
private fun CompletionItem.shownLabel(): String = when (kind) {
    CompletionItem.Kind.STAMP_TOGGLE ->
        stringResource(if (insert.startsWith('<')) Res.string.editor_make_active else Res.string.editor_make_inactive)
    else -> label
}

/** Room for one labelled insert chip; below that per button, the strip shows icons. */
private val LABELLED_INSERT_WIDTH = 104.dp

@Composable
private fun InsertChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun DraftInsert.label(): String = stringResource(
    when (this) {
        DraftInsert.LINK -> Res.string.editor_insert_link
        DraftInsert.TAG -> Res.string.editor_insert_tag
        DraftInsert.SCHEDULE -> Res.string.editor_insert_schedule
        DraftInsert.LOCATION -> Res.string.editor_insert_place
        DraftInsert.CHECKBOX -> Res.string.editor_insert_checkbox
        DraftInsert.TABLE -> Res.string.editor_insert_table
    },
)

private fun DraftInsert.icon(): ImageVector = when (this) {
    DraftInsert.LINK -> Icons.Filled.Link
    DraftInsert.TAG -> Icons.Filled.Sell
    DraftInsert.SCHEDULE -> Icons.Filled.EditCalendar
    DraftInsert.LOCATION -> Icons.Filled.Place
    DraftInsert.CHECKBOX -> Icons.Filled.CheckBox
    DraftInsert.TABLE -> Icons.Filled.TableChart
}
