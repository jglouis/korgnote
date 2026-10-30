package com.korgnote.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgElement
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.link.OrgBacklink
import com.korgnote.common.org.nextSibling
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.previousSibling
import com.korgnote.common.org.sliceOf
import com.korgnote.common.org.subtreeEnd
import com.korgnote.common.org.toOrgDocument
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.delete
import korgnote.composeapp.generated.resources.editor_add_heading
import korgnote.composeapp.generated.resources.editor_add_heading_below
import korgnote.composeapp.generated.resources.editor_add_subheading
import korgnote.composeapp.generated.resources.editor_collapse_heading
import korgnote.composeapp.generated.resources.editor_delete_heading
import korgnote.composeapp.generated.resources.editor_delete_heading_text
import korgnote.composeapp.generated.resources.editor_delete_heading_text_subheadings
import korgnote.composeapp.generated.resources.editor_delete_heading_title
import korgnote.composeapp.generated.resources.editor_demote
import korgnote.composeapp.generated.resources.editor_edit
import korgnote.composeapp.generated.resources.editor_editing_element
import korgnote.composeapp.generated.resources.editor_expand_heading
import korgnote.composeapp.generated.resources.editor_linked_from
import korgnote.composeapp.generated.resources.editor_more_actions
import korgnote.composeapp.generated.resources.editor_move_down
import korgnote.composeapp.generated.resources.editor_move_up
import korgnote.composeapp.generated.resources.editor_note_details
import korgnote.composeapp.generated.resources.editor_open
import korgnote.composeapp.generated.resources.editor_promote
import korgnote.composeapp.generated.resources.editor_this_heading
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reading view of a whole document, rendered one editable element at a time.
 *
 * Each element is parsed and rendered from its own slice of [text] rather than from a single parse
 * of the document. That costs a little more parsing and buys the property this screen depends on:
 * every rendered element knows the exact character range it came from, so editing one is a text
 * replacement over that range and can never disturb its neighbours.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrgDocumentView(
    text: String,
    modifier: Modifier = Modifier,
    // The offset of the tapped checkbox in [text]; the view model finds the node there.
    onToggleCheckbox: ((offsetInDocument: Int) -> Unit)? = null,
    /** A keyword picked from a heading's TODO keyword, or null to clear it, with that heading's offset in [text]. */
    onSetTodo: ((offsetInDocument: Int, keyword: String?) -> Unit)? = null,
    /** A tap on a table, with its offset in [text]: tables are edited as a grid, not as text. */
    onEditTable: ((offsetInDocument: Int) -> Unit)? = null,
    editingElementIndex: Int? = null,
    elementDraftValue: TextFieldValue = TextFieldValue(""),
    onElementDraftChange: (TextFieldValue) -> Unit = {},
    /**
     * A keyboard key aimed at the suggestions. Returns true when it was used, so an unhandled Tab
     * or Escape still does whatever it would normally do.
     */
    onCompletionKey: (CompletionKey) -> Boolean = { false },
    /** A plain tap on an element, anywhere an inner target (checkbox, link, drawer) does not claim. */
    onEditElement: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: a sibling after the element's whole subtree. */
    onAddHeadingBelow: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: a first child of the element. */
    onAddSubheading: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu, once confirmed: the heading with its body and sub-headings. */
    onDeleteSection: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: past the previous sibling (up) or the next one, subtree and all. */
    onMoveSection: ((elementIndex: Int, up: Boolean) -> Unit)? = null,
    /** From the long-press menu: the heading and its subtree one level shallower (-1) or deeper (1). */
    onShiftSection: ((elementIndex: Int, delta: Int) -> Unit)? = null,
    /** Folded headings: each shows only its heading line, and its descendants render nothing. */
    foldedElements: Set<Int> = emptySet(),
    onToggleFold: ((elementIndex: Int) -> Unit)? = null,
    scrollToElementIndex: Int? = null,
    onScrolledToElement: () -> Unit = {},
    /** An element that moved within the note: scrolled to only if it went off screen. */
    revealElementIndex: Int? = null,
    onRevealed: () -> Unit = {},
    /**
     * The file's TODO keywords: its own `#+TODO:`, or what the notes declare when it has none.
     * Each element is rendered from its own slice, which does not contain the declaration, so
     * this is the only way a `FINISHED` heading far below it can know it is finished.
     */
    todoKeywords: OrgTodoConfig = DefaultOrgTodoConfig,
    /** Links elsewhere in the notes that lead here, listed after the last element. */
    backlinks: List<OrgBacklink> = emptyList(),
    onOpenBacklink: (OrgBacklink) -> Unit = {},
    /** The element at the top of the view, as the reader scrolls; where Back returns to. */
    onFirstVisibleElement: (Int) -> Unit = {},
    /** Hoisted so the screen's floating buttons can see, and move, where the reader is. */
    listState: LazyListState = rememberLazyListState(),
    /**
     * The note's title, author, date and tags as a form: a button beside the lines that say them,
     * above the first heading — or on its own over that heading, in a note that has none yet.
     */
    onEditDetails: (() -> Unit)? = null,
) {
    val elements = remember(text) { text.orgElements() }
    // An element is hidden when it lies inside a folded heading's subtree. The folded heading itself
    // stays, reduced to its heading line.
    val hidden = remember(elements, foldedElements) {
        BooleanArray(elements.size).also { hidden ->
            for (folded in foldedElements) {
                for (i in folded + 1 until elements.subtreeEnd(folded)) hidden[i] = true
            }
        }
    }
    // How many headings each element sits under, which is how far it is indented.
    val depths = remember(elements) { elements.nestingDepths() }
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    // The heading a delete is waiting to be confirmed for.
    var confirmingDelete by remember { mutableStateOf<Int?>(null) }
    // The element a jump just landed on — a link, a search hit, an agenda entry, Back — lit for a
    // moment so the eye finds it among its neighbours. A new [Landing] each time, so landing on
    // the same element twice lights it twice.
    var landing by remember { mutableStateOf<Landing?>(null) }
    val landingGlow = remember { Animatable(0f) }
    val landingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        landingGlow.snapTo(1f)
        delay(LANDING_HOLD)
        landingGlow.animateTo(0f, tween(durationMillis = LANDING_FADE_MILLIS))
        landing = null
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        // Room for the end of the file to scroll clear of the "add heading" button floating over it.
        contentPadding = PaddingValues(bottom = 72.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // Every element gets an item, including a blank preamble or a folded-away heading that
        // renders nothing, so that an item's index is its element's index and a scroll target
        // needs no translation.
        itemsIndexed(elements, key = { index, _ -> index }) { index, element ->
            if (hidden[index]) return@itemsIndexed
            val slice = remember(text, element) { element.sliceOf(text) }
            // Not while an element is being edited: the form rewrites the top of the note.
            val editDetails = onEditDetails?.takeIf { index == 0 && editingElementIndex == null }
            if (element.isPreamble && slice.isBlank()) {
                if (editDetails != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { NoteDetailsButton(editDetails) }
                }
                return@itemsIndexed
            }

            Column(
                modifier = Modifier.fillMaxWidth().then(
                    if (landing?.index == index) {
                        // Read while drawing, so the fade redraws the element without recomposing it.
                        Modifier.drawBehind {
                            drawRoundRect(
                                landingColor.copy(alpha = landingColor.alpha * landingGlow.value),
                                cornerRadius = CornerRadius(8.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    }
                ).nestingGuides(depths[index], guideColor),
                horizontalAlignment = Alignment.Start,
            ) {
                if (editDetails != null && !element.isPreamble) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { NoteDetailsButton(editDetails) }
                }
                if (index == editingElementIndex) {
                    OrgElementEditor(
                        elementKey = index,
                        value = elementDraftValue,
                        onValueChange = onElementDraftChange,
                        onCompletionKey = onCompletionKey,
                        todoKeywords = todoKeywords,
                    )
                } else {
                    val folded = index in foldedElements
                    val foldable = !element.isPreamble && (
                        elements.subtreeEnd(index) > index + 1 ||
                            slice.substringAfter('\n', "").isNotBlank()
                        )
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                // Inner targets — a checkbox, a link, a drawer's toggle — are
                                // clickables of their own and take their tap first; anything
                                // else in the element edits it. A scroll never becomes a tap.
                                onClickLabel = stringResource(Res.string.editor_edit),
                                onClick = { onEditElement?.invoke(index) },
                                onLongClickLabel = stringResource(Res.string.editor_more_actions),
                                onLongClick = { menuOpen = true },
                            ),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            // Folded, only the heading line shows, as `org-cycle` leaves it.
                            val shown = if (folded) slice.substringBefore('\n') else slice
                            val document = remember(shown) { shown.toOrgDocument() }
                            OrgText(
                                content = document,
                                todoKeywords = todoKeywords,
                                // The element was parsed from its own slice, so its nodes carry
                                // slice-relative ranges; shifting by the element's start makes them
                                // offsets into the whole document.
                                onToggleCheckbox = onToggleCheckbox?.let { toggle ->
                                    { item -> toggle(element.start + item.range.first) }
                                },
                                onSetTodo = onSetTodo?.let { set ->
                                    { heading, keyword -> set(element.start + heading.range.first, keyword) }
                                },
                                onEditTable = onEditTable?.let { edit ->
                                    { table -> edit(element.start + table.range.first) }
                                },
                            )
                            ElementMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                isPreamble = element.isPreamble,
                                onAddHeadingBelow = onAddHeadingBelow?.let { add -> { add(index) } },
                                onAddSubheading = onAddSubheading?.let { add -> { add(index) } },
                                onDelete = onDeleteSection?.let { { confirmingDelete = index } },
                                onMoveUp = onMoveSection?.takeIf { elements.previousSibling(index) != null }
                                    ?.let { move -> { move(index, true) } },
                                onMoveDown = onMoveSection?.takeIf { elements.nextSibling(index) != null }
                                    ?.let { move -> { move(index, false) } },
                                onPromote = onShiftSection?.takeIf { element.level > 1 }
                                    ?.let { shift -> { shift(index, -1) } },
                                onDemote = onShiftSection?.let { shift -> { shift(index, 1) } },
                            )
                        }
                        if (editDetails != null && element.isPreamble) NoteDetailsButton(editDetails)
                        if (foldable && onToggleFold != null) {
                            val foldDescription = stringResource(if (folded) Res.string.editor_expand_heading else Res.string.editor_collapse_heading, element.title)
                            IconButton(
                                onClick = { onToggleFold(index) },
                                modifier = Modifier.size(32.dp)
                                    .semantics { contentDescription = foldDescription },
                            ) {
                                Icon(
                                    if (folded) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }

        // After every element, so an element's item index is still its element index.
        if (backlinks.isNotEmpty()) {
            item(key = "backlinks") { BacklinksSection(backlinks, onOpenBacklink) }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onFirstVisibleElement(it) }
    }

    confirmingDelete?.let { index ->
        val element = elements.getOrNull(index)
        if (element == null || onDeleteSection == null) {
            confirmingDelete = null
        } else {
            DeleteSectionDialog(
                title = element.title,
                subheadings = elements.subtreeEnd(index) - index - 1,
                onDelete = { confirmingDelete = null; onDeleteSection(index) },
                onDismiss = { confirmingDelete = null },
            )
        }
    }

    LaunchedEffect(scrollToElementIndex, elements) {
        val index = scrollToElementIndex
        if (index != null && index in elements.indices) {
            // scrollToItem, not bringIntoView: the target is usually far off screen and has not been
            // composed yet, so there is no node to bring anywhere — the list has to go to it.
            listState.scrollToItem(index)
            landing = Landing(index)
            onScrolledToElement()
        }
    }

    LaunchedEffect(revealElementIndex, elements) {
        val index = revealElementIndex ?: return@LaunchedEffect
        if (index in elements.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == index }
        ) {
            listState.scrollToItem(index)
        }
        onRevealed()
    }

    // A lazy list only composes what is on screen, so an element added at the far end (the FAB)
    // has no editor to focus and nothing would bring it into view: go to it. An element already
    // visible — tapped, or added from its neighbour's menu — is left where the user sees it.
    LaunchedEffect(editingElementIndex) {
        val index = editingElementIndex ?: return@LaunchedEffect
        if (index in elements.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == index }
        ) {
            listState.scrollToItem(index)
        }
    }
}

/**
 * For each element, the number of headings it sits under: a `**` under a `*` is 1, and so is a
 * `***` right under a `*`, since a skipped level is no deeper an outline.
 */
internal fun List<OrgElement>.nestingDepths(): IntArray {
    val open = ArrayList<Int>() // the levels of the headings the walk is inside, outermost first
    return IntArray(size) { index ->
        val level = this[index].level
        if (level == 0) return@IntArray 0
        while (open.isNotEmpty() && open.last() >= level) open.removeAt(open.lastIndex)
        open.size.also { open += level }
    }
}

/**
 * Indents a sub-heading under its parent, with a thin bar down the left for each heading it sits
 * under. Neighbouring items draw their bars end to end, so a subtree reads as one strand.
 *
 * Deep outlines stop indenting at [MAX_GUIDES], so that a phone's width is not all margin.
 */
private fun Modifier.nestingGuides(depth: Int, color: Color): Modifier {
    if (depth == 0) return this
    val shown = depth.coerceAtMost(MAX_GUIDES)
    return drawBehind {
        val step = GUIDE_STEP.toPx()
        val width = GUIDE_WIDTH.toPx()
        for (d in 0 until shown) {
            val x = d * step + GUIDE_INSET.toPx()
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = width)
        }
    }.padding(start = GUIDE_STEP * shown)
}

private val GUIDE_STEP = 14.dp
private val GUIDE_INSET = 4.dp
private val GUIDE_WIDTH = 2.dp
private const val MAX_GUIDES = 5

/** A jump's arrival at the element at [index]; compared by identity, so each jump is its own. */
private class Landing(val index: Int)

/** How long a landed-on element stays lit before it fades, and how long the fade takes. */
private val LANDING_HOLD = 700.milliseconds
private const val LANDING_FADE_MILLIS = 1300

@Composable
private fun NoteDetailsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Filled.EditNote, contentDescription = stringResource(Res.string.editor_note_details), tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * "What links here": each link elsewhere that leads to this note, by note and the heading it sits
 * under. Tapping one opens that note at the link.
 */
@Composable
private fun BacklinksSection(backlinks: List<OrgBacklink>, onOpen: (OrgBacklink) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        HorizontalDivider()
        Text(
            stringResource(Res.string.editor_linked_from, backlinks.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        for (backlink in backlinks) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(Res.string.editor_open)) { onOpen(backlink) }
                    .padding(vertical = 6.dp),
            ) {
                Text(backlink.context, style = MaterialTheme.typography.bodyMedium, color = OrgColors.link)
                if (backlink.context != backlink.path) {
                    Text(backlink.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/**
 * What a long-press offers. Adding a heading used to be a **+** on every element; it is rare next
 * to reading, so it lives here, out of sight, along with restructuring a heading's subtree.
 *
 * A null action is one that does not apply here. Moving and shifting stay listed, disabled, so the
 * menu keeps its shape from one heading to the next; the preamble, which is not a heading, gets
 * only what applies to it.
 */
@Composable
private fun ElementMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isPreamble: Boolean,
    onAddHeadingBelow: (() -> Unit)?,
    onAddSubheading: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onPromote: (() -> Unit)?,
    onDemote: (() -> Unit)?,
) {
    @Composable
    fun Item(label: String, action: (() -> Unit)?) = DropdownMenuItem(
        text = { Text(label) },
        enabled = action != null,
        onClick = { onDismiss(); action?.invoke() },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onAddHeadingBelow != null) {
            DropdownMenuItem(
                text = { Text(stringResource(if (isPreamble) Res.string.editor_add_heading else Res.string.editor_add_heading_below)) },
                onClick = { onDismiss(); onAddHeadingBelow() },
            )
        }
        // The preamble is not a heading, so there is nothing to put a child under.
        if (onAddSubheading != null && !isPreamble) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_add_subheading)) },
                onClick = { onDismiss(); onAddSubheading() },
            )
        }
        if (!isPreamble) {
            HorizontalDivider()
            Item(stringResource(Res.string.editor_move_up), onMoveUp)
            Item(stringResource(Res.string.editor_move_down), onMoveDown)
            Item(stringResource(Res.string.editor_promote), onPromote)
            Item(stringResource(Res.string.editor_demote), onDemote)
        }
        if (onDelete != null && !isPreamble) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_delete_heading), color = MaterialTheme.colorScheme.error) },
                onClick = { onDismiss(); onDelete() },
            )
        }
    }
}

/**
 * Confirms deleting a heading. It says how much goes with it, because the sub-headings may be
 * folded away or scrolled off screen, and a subtree is easy to underestimate.
 */
@Composable
private fun DeleteSectionDialog(
    title: String,
    subheadings: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_delete_heading_title)) },
        text = {
            val heading = title.ifBlank { stringResource(Res.string.editor_this_heading) }
            Text(
                if (subheadings == 0) {
                    stringResource(Res.string.editor_delete_heading_text, heading)
                } else {
                    pluralStringResource(Res.plurals.editor_delete_heading_text_subheadings, subheadings, heading, subheadings)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Editor for a single element — the only editing surface there is.
 *
 * An element holds a heading and its own body, so highlighting is never scoped to a viewport: the
 * per-line cache already makes a keystroke cost one line, and the text is small enough that laying
 * it out is cheap.
 */
@Composable
private fun OrgElementEditor(
    elementKey: Int,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCompletionKey: (CompletionKey) -> Boolean,
    todoKeywords: OrgTodoConfig,
) {
    val headingColor = MaterialTheme.colorScheme.primary
    val highlighting = remember(headingColor) { OrgSyntaxHighlightTransformation(headingColor) }
    highlighting.fallbackKeywords = todoKeywords
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    )

    val focusRequester = remember(elementKey) { FocusRequester() }
    LaunchedEffect(elementKey) { focusRequester.requestFocus() }

    // A fully controlled field: the text *and the caret* come from above. The caret has to live
    // there because the suggestions computed from it are shown on a strip pinned to the screen,
    // which is nowhere near this composable — and because accepting one moves the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // Only meaningful with a hardware keyboard; on a phone the chips are the interface.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val intent = when (event.key) {
                    Key.Escape -> CompletionKey.DISMISS
                    // Tab, not Enter: Enter is a newline in a multi-line element and stealing it
                    // would make an ordinary edit depend on whether a chip happened to be showing.
                    Key.Tab -> CompletionKey.ACCEPT
                    Key.DirectionDown, Key.DirectionRight -> CompletionKey.NEXT
                    Key.DirectionUp, Key.DirectionLeft -> CompletionKey.PREVIOUS
                    else -> return@onPreviewKeyEvent false
                }
                // Unhandled means there were no suggestions, so the key keeps its ordinary meaning
                // — an arrow still moves the caret.
                onCompletionKey(intent)
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        visualTransformation = highlighting,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                // None on purpose: the decoration box would otherwise run the highlighter a second
                // time on every keystroke, just to decide whether to show a placeholder.
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                label = { Text(stringResource(Res.string.editor_editing_element)) },
            )
        },
    )
}
