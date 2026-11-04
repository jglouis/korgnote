package com.korgnote.app.ui

import com.korgnote.app.viewmodel.ReadingSpot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgElement
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.OrgClock
import com.korgnote.common.org.edit.clocksInText
import com.korgnote.common.org.edit.effortIn
import com.korgnote.common.org.edit.effortMinutes
import com.korgnote.common.org.edit.toggleableTimestamps
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
import korgnote.composeapp.generated.resources.editor_cancel_clock
import korgnote.composeapp.generated.resources.editor_clock_in
import korgnote.composeapp.generated.resources.editor_clock_out
import korgnote.composeapp.generated.resources.editor_clocked
import korgnote.composeapp.generated.resources.editor_clocked_over_effort
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
import korgnote.composeapp.generated.resources.editor_make_stamp_active
import korgnote.composeapp.generated.resources.editor_make_stamp_inactive
import korgnote.composeapp.generated.resources.editor_more_actions
import korgnote.composeapp.generated.resources.editor_move_down
import korgnote.composeapp.generated.resources.editor_move_up
import korgnote.composeapp.generated.resources.editor_note_details
import korgnote.composeapp.generated.resources.editor_open
import korgnote.composeapp.generated.resources.editor_promote
import korgnote.composeapp.generated.resources.editor_effort_current
import korgnote.composeapp.generated.resources.editor_effort_other
import korgnote.composeapp.generated.resources.editor_effort_title
import korgnote.composeapp.generated.resources.editor_refile
import korgnote.composeapp.generated.resources.editor_remove_effort
import korgnote.composeapp.generated.resources.editor_set_effort
import korgnote.composeapp.generated.resources.ok
import korgnote.composeapp.generated.resources.editor_this_heading
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.runtime.CompositionLocalProvider
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.prioritiesOf
import korgnote.composeapp.generated.resources.editor_priority
import com.korgnote.common.org.OrgPriority

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
    /** Read only by the element being edited, so that a keystroke composes that element alone. */
    elementDraft: () -> TextFieldValue = { TextFieldValue("") },
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
    /** From the long-press menu: the heading and its subtree to another heading or note, chosen next. */
    onRefileSection: ((elementIndex: Int) -> Unit)? = null,
    /** A priority picked for the heading at an offset in [text], or null to remove it. */
    onSetPriority: ((offsetInDocument: Int, priority: OrgPriority?) -> Unit)? = null,
    /**
     * From the long-press menu: the stamp beginning at this offset in [text] made inactive when it
     * is active, and active when it is not.
     */
    onToggleTimestamp: ((offsetInDocument: Int) -> Unit)? = null,
    /** Folded headings: each shows only its heading line, and its descendants render nothing. */
    foldedElements: Set<Int> = emptySet(),
    onToggleFold: ((elementIndex: Int) -> Unit)? = null,
    scrollToElementIndex: Int? = null,
    onScrolledToElement: () -> Unit = {},
    /** Where a note opened with no place asked for goes back to: where it was left, or its top. */
    restoreTo: ReadingSpot? = null,
    onRestored: () -> Unit = {},
    /** An element that moved within the note: scrolled to only if it went off screen. */
    revealElementIndex: Int? = null,
    onRevealed: () -> Unit = {},
    /**
     * The file's TODO keywords: its own `#+TODO:`, or Org's own when it has none.
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
    /** From the long-press menu: the heading at this offset in [text] clocked in, out, or its clock cancelled. */
    onClockIn: ((offsetInDocument: Int) -> Unit)? = null,
    onClockOut: ((offsetInDocument: Int) -> Unit)? = null,
    onCancelClock: ((offsetInDocument: Int) -> Unit)? = null,
    /** From the long-press menu: the heading at this offset in [text] given an effort, in minutes, or none. */
    onSetEffort: ((offsetInDocument: Int, minutes: Int?) -> Unit)? = null,
    /** The time a running clock counts to, in the totals beside headings; ticks while one runs. */
    clockTime: LocalDateTime? = null,
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
    // The heading a delete is waiting to be confirmed for, by index and title: when another heading
    // has moved into its place, the delete is not what was asked for any more.
    var confirmingDelete by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val priorities = remember(text) { prioritiesOf(text) }
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
            // A fold opens and closes as the drawer's folders do, the subtree sliding under its
            // heading. Started afresh when the note itself changes — another note opened, an edit
            // saved — so that only a fold, never a load, is animated.
            val visibility = remember(elements) { MutableTransitionState(!hidden[index]) }
            visibility.targetState = !hidden[index]
            AnimatedVisibility(
                visibleState = visibility,
                enter = DisclosureEnter,
                exit = DisclosureExit,
            ) {
            val slice = remember(text, element) { element.sliceOf(text) }
            // Not while an element is being edited: the form rewrites the top of the note.
            val editDetails = onEditDetails?.takeIf { index == 0 && editingElementIndex == null }
            if (element.isPreamble && slice.isBlank()) {
                if (editDetails != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { NoteDetailsButton(editDetails) }
                }
                return@AnimatedVisibility
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
                        value = elementDraft(),
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
                    // The heading this item holds now, whatever its keyword, priority or body. An item is
                    // its index, so a heading added above hands the item to a neighbour, and what was open
                    // on the last one closes rather than landing on this one. The title is all there is to
                    // tell them by: a neighbour of the same name is taken for the same heading.
                    val heading = remember(slice, todoKeywords) {
                        if (element.isPreamble) null else orgHeadingOf(slice.substringBefore('\n'), todoKeywords).title
                    }
                    var menuOpen by remember(heading) { mutableStateOf(false) }
                    var choosingPriority by remember(heading) { mutableStateOf(false) }
                    var choosingEffort by remember(heading) { mutableStateOf(false) }
                    val clocks = remember(text, elements, index) { elementClocks(text, elements, index) }
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
                            )
                            // After the clickable, so it sees the press once the inner targets
                            // have had it but before the element's own long-press does.
                            .longPressThroughInnerTargets { menuOpen = true },
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Sized as it changes, so a heading's own body unfolds with its subtree.
                        Box(modifier = Modifier.weight(1f).animateContentSize(tween(DISCLOSURE_MILLIS))) {
                            // Folded, only the heading line shows, as `org-cycle` leaves it.
                            val shown = if (folded) slice.substringBefore('\n') else slice
                            val document = remember(shown, todoKeywords) { shown.toOrgDocument(todoKeywords) }
                            val priorityEditing = remember(onSetPriority, priorities, element.start) {
                                onSetPriority?.let { set ->
                                    PriorityEditing(priorities) { heading, priority -> set(element.start + heading.range.first, priority) }
                                }
                            }
                            CompositionLocalProvider(LocalPriorityEditing provides priorityEditing) {
                            key(heading) {
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
                            }
                            }
                            // Only what is shown: a folded heading's body is not there to be changed.
                            val stamps = remember(shown) { shown.toggleableTimestamps().take(MAX_MENU_STAMPS) }
                            ElementMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                isPreamble = element.isPreamble,
                                stamps = onToggleTimestamp?.let { toggle ->
                                    stamps.map { stamp ->
                                        MenuStamp(shown.substring(stamp.range), stamp.active) { toggle(element.start + stamp.range.first) }
                                    }
                                }.orEmpty(),
                                onAddHeadingBelow = onAddHeadingBelow?.let { add -> { add(index) } },
                                onAddSubheading = onAddSubheading?.let { add -> { add(index) } },
                                onDelete = onDeleteSection?.let { { confirmingDelete = index to element.title } },
                                onMoveUp = onMoveSection?.takeIf { elements.previousSibling(index) != null }
                                    ?.let { move -> { move(index, true) } },
                                onMoveDown = onMoveSection?.takeIf { elements.nextSibling(index) != null }
                                    ?.let { move -> { move(index, false) } },
                                onPromote = onShiftSection?.takeIf { element.level > 1 }
                                    ?.let { shift -> { shift(index, -1) } },
                                onDemote = onShiftSection?.let { shift -> { shift(index, 1) } },
                                onRefile = onRefileSection?.let { refile -> { refile(index) } },
                                onPriority = onSetPriority?.let { { choosingPriority = true } },
                                onClockIn = onClockIn?.takeIf { clocks?.running != true }?.let { clock -> { clock(element.start) } },
                                onClockOut = onClockOut?.takeIf { clocks?.running == true }?.let { clock -> { clock(element.start) } },
                                onCancelClock = onCancelClock?.takeIf { clocks?.running == true }?.let { cancel -> { cancel(element.start) } },
                                onEffort = onSetEffort?.let { { choosingEffort = true } },
                            )
                            if (choosingEffort && onSetEffort != null) {
                                EffortDialog(
                                    current = remember(slice) { effortIn(slice) },
                                    onPick = { minutes -> choosingEffort = false; onSetEffort(element.start, minutes) },
                                    onDismiss = { choosingEffort = false },
                                )
                            }
                            // From the long-press menu: the same choices as a tap on the cookie, for
                            // a heading that has none yet to tap.
                            if (choosingPriority && onSetPriority != null) {
                                PriorityMenu(
                                    expanded = true,
                                    current = orgHeadingOf(slice.substringBefore('\n'), todoKeywords).priority,
                                    priorities = priorities,
                                    onPick = { priority -> choosingPriority = false; onSetPriority(element.start, priority) },
                                    onDismiss = { choosingPriority = false },
                                )
                            }
                        }
                        if (editDetails != null && element.isPreamble) NoteDetailsButton(editDetails)
                        if (clocks != null && clocks.subtree.isNotEmpty()) ClockedTotal(clocks, clockTime)
                        if (foldable && onToggleFold != null) {
                            val foldDescription = stringResource(if (folded) Res.string.editor_expand_heading else Res.string.editor_collapse_heading, element.title)
                            IconButton(
                                onClick = { onToggleFold(index) },
                                modifier = Modifier.size(32.dp)
                                    .semantics { contentDescription = foldDescription },
                            ) {
                                DisclosureChevron(
                                    expanded = !folded,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
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

    confirmingDelete?.let { (index, title) ->
        val element = elements.getOrNull(index)?.takeIf { it.title == title }
        if (element == null || onDeleteSection == null) {
            LaunchedEffect(Unit) { confirmingDelete = null }
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

    // Back where the note was left: nothing was jumped to, so nothing is lit.
    LaunchedEffect(restoreTo, elements) {
        val spot = restoreTo ?: return@LaunchedEffect
        if (spot.elementIndex in elements.indices) {
            listState.scrollToItem(spot.elementIndex, spot.pixels)
        } else {
            listState.scrollToItem(0)
        }
        onRestored()
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

/**
 * A long-press on an inner target — the TODO keyword, a link, a checkbox — as a long-press on the
 * element. Each of those is a clickable of its own that takes the press, so the element's
 * long-press never starts, and the target's tap waits for the finger to lift however long it is
 * held. Here a press a child has taken that is held past the long-press timeout without moving
 * fires [onLongPress] then, and the rest of the gesture is taken from the child so its tap does
 * not follow. A press no child takes is left to the element's own long-press.
 *
 * A press an ancestor took before any child saw it is not a child's: a list that is still flinging
 * takes the finger that stops it on the way down, and drags from there at once with the content
 * under the finger, so that a drag would look to this element like a press held still. That press
 * stops the list, as it did before, and opens nothing.
 */
@Composable
private fun Modifier.longPressThroughInnerTargets(onLongPress: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return pointerInput(haptics) {
        awaitEachGesture {
            // On the way down first: consumed already, it is an ancestor's, a flinging list's.
            val arriving = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (arriving.isConsumed) return@awaitEachGesture
            // The same press on its way back up, once the inner targets have had it.
            val down = awaitPointerEvent().changes.firstOrNull { it.id == arriving.id } ?: return@awaitEachGesture
            if (!down.isConsumed) return@awaitEachGesture
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val moved = event.changes.fastAny { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                    if (moved || event.changes.fastAll { !it.pressed }) break
                }
            }
            if (ended != null) return@awaitEachGesture
            currentOnLongPress()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Ahead of the child, which then sees its press cancelled rather than released.
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.fastForEach { it.consume() }
            } while (event.changes.fastAny { it.pressed })
        }
    }
}

private val GUIDE_STEP = 14.dp
private val GUIDE_INSET = 4.dp
private val GUIDE_WIDTH = 2.dp
private const val MAX_GUIDES = 5

/**
 * A heading's clocks: every one in its subtree, for its total, whether its own clock runs, for
 * the menu, and its `:Effort:`, which the total is shown against.
 */
private class ElementClocks(val subtree: List<OrgClock>, val running: Boolean, val effort: String?)

/** The clocks of the heading at [index], read from the text: null for the preamble. */
private fun elementClocks(text: String, elements: List<OrgElement>, index: Int): ElementClocks? {
    val element = elements.getOrNull(index)?.takeUnless { it.isPreamble } ?: return null
    val end = elements.getOrNull(elements.subtreeEnd(index))?.start ?: text.length
    val subtree = text.substring(element.start, end)
    if (!subtree.contains("CLOCK:")) return ElementClocks(emptyList(), running = false, effort = null)
    val own = element.sliceOf(text)
    return ElementClocks(clocksInText(subtree), clocksInText(own).any { it.isRunning }, effortIn(own))
}

/**
 * The time clocked on a heading and under it, as Emacs's `C-c C-x C-d` sums it: `1 h 15`, or
 * `1 h 15 / 2 h` against its effort. Its own running clock lights it and counts up to [now].
 */
@Composable
private fun ClockedTotal(clocks: ElementClocks, now: LocalDateTime?) {
    val minutes = clocks.subtree.sumOf { it.minutes(now ?: it.end ?: it.start) }
    // An effort Org can read is shown the same way; any other is shown as written.
    val effortMinutes = clocks.effort?.let(::effortMinutes)
    val effort = clocks.effort?.let { written -> effortMinutes?.let { clockDuration(it) } ?: written }
    val total = clockDuration(minutes) + (effort?.let { " / $it" } ?: "")
    // Past what it should have taken: a warning beside the total, which says so to a screen reader too.
    val over = effortMinutes != null && minutes > effortMinutes
    val color = if (clocks.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val description = stringResource(if (over) Res.string.editor_clocked_over_effort else Res.string.editor_clocked, total)
    Row(
        modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (over) Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp).padding(end = 2.dp))
        if (clocks.running) Icon(Icons.Filled.Timer, contentDescription = null, tint = color, modifier = Modifier.size(14.dp).padding(end = 2.dp))
        Text(total, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** The efforts offered with a tap, in minutes: a quarter of an hour to half a day. */
private val EFFORT_CHOICES = listOf(15, 30, 60, 120, 240)

/**
 * What a heading should take, as `org-set-effort` asks it: a few common lengths a tap away, or
 * another typed as Org reads one (`1:30`, `45`, `2h`, `1h 30min`); and, when it has one, removing it.
 */
@Composable
private fun EffortDialog(current: String?, onPick: (minutes: Int?) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val typedMinutes = effortMinutes(typed)?.takeIf { it > 0 }
    val currentMinutes = current?.let(::effortMinutes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_effort_title)) },
        text = {
            Column {
                for (minutes in EFFORT_CHOICES) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(clockDuration(minutes), modifier = Modifier.weight(1f))
                        if (minutes == currentMinutes) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.editor_effort_current))
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(Res.string.editor_effort_other)) },
                    singleLine = true,
                    isError = typed.isNotBlank() && typedMinutes == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (current != null) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(Res.string.editor_remove_effort), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { typedMinutes?.let(onPick) }, enabled = typedMinutes != null) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

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

/** A stamp of the element the long-press menu can make active or inactive, as written. */
private class MenuStamp(val text: String, val active: Boolean, val toggle: () -> Unit)

/**
 * How many of an element's stamps its menu offers to toggle. A journal entry can hold dozens, and a
 * menu of dozens is no menu; past these, the stamp is toggled from the editor's suggestions.
 */
private const val MAX_MENU_STAMPS = 5

/**
 * What a long-press offers. Adding a heading used to be a **+** on every element; it is rare next
 * to reading, so it lives here, out of sight, along with restructuring a heading's subtree, and
 * making the element's dates active — in the calendar — or inactive.
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
    stamps: List<MenuStamp>,
    onAddHeadingBelow: (() -> Unit)?,
    onAddSubheading: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onPromote: (() -> Unit)?,
    onDemote: (() -> Unit)?,
    onRefile: (() -> Unit)?,
    onPriority: (() -> Unit)?,
    onClockIn: (() -> Unit)? = null,
    onClockOut: (() -> Unit)? = null,
    onCancelClock: (() -> Unit)? = null,
    onEffort: (() -> Unit)? = null,
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
        // The heading's clock: started, or stopped and cancelled when it runs.
        if (!isPreamble && (onClockIn != null || onClockOut != null || onCancelClock != null)) {
            HorizontalDivider()
            onClockIn?.let { Item(stringResource(Res.string.editor_clock_in), it) }
            onClockOut?.let { Item(stringResource(Res.string.editor_clock_out), it) }
            onCancelClock?.let { Item(stringResource(Res.string.editor_cancel_clock), it) }
        }
        // What it should take, which its clocked time is shown against.
        if (!isPreamble && onEffort != null) Item(stringResource(Res.string.editor_set_effort), onEffort)
        if (stamps.isNotEmpty()) {
            HorizontalDivider()
            for (stamp in stamps) {
                val label = if (stamp.active) Res.string.editor_make_stamp_inactive else Res.string.editor_make_stamp_active
                Item(stringResource(label, stamp.text.replace(' ', ' ')), stamp.toggle)
            }
        }
        if (!isPreamble) {
            HorizontalDivider()
            Item(stringResource(Res.string.editor_move_up), onMoveUp)
            Item(stringResource(Res.string.editor_move_down), onMoveDown)
            Item(stringResource(Res.string.editor_promote), onPromote)
            Item(stringResource(Res.string.editor_demote), onDemote)
            // Out of this place entirely, `C-c C-w`: under a heading here or in another note.
            if (onRefile != null) Item(stringResource(Res.string.editor_refile), onRefile)
            if (onPriority != null) Item(stringResource(Res.string.editor_priority), onPriority)
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
        dismissButton = { CancelButton(onDismiss) },
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
internal fun OrgElementEditor(
    elementKey: Int,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCompletionKey: (CompletionKey) -> Boolean,
    todoKeywords: OrgTodoConfig,
    /** What the field says it is for; that it edits an element when null. */
    label: String? = null,
) {
    val headingColor = MaterialTheme.colorScheme.primary
    val highlighting = remember(headingColor) { OrgSyntaxHighlightTransformation(headingColor) }
    highlighting.fallbackKeywords = todoKeywords
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    )

    val focusRequester = rememberAutoFocus(elementKey)

    // A fully controlled field: the text *and the caret* come from above. The caret has to live
    // there because the suggestions computed from it are shown on a strip pinned to the screen,
    // which is nowhere near this composable — and because accepting one moves the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focusRequester)
            .completionKeys(onCompletionKey),
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
                label = { Text(label ?: stringResource(Res.string.editor_editing_element)) },
            )
        },
    )
}
