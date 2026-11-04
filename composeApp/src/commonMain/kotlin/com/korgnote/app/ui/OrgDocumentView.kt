package com.korgnote.app.ui

import com.korgnote.app.viewmodel.ReadingSpot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.TextFieldValue
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
import korgnote.composeapp.generated.resources.editor_clocked
import korgnote.composeapp.generated.resources.editor_clocked_over_effort
import korgnote.composeapp.generated.resources.editor_collapse_heading
import korgnote.composeapp.generated.resources.editor_edit
import korgnote.composeapp.generated.resources.editor_expand_heading
import korgnote.composeapp.generated.resources.editor_linked_from
import korgnote.composeapp.generated.resources.editor_more_actions
import korgnote.composeapp.generated.resources.editor_note_details
import korgnote.composeapp.generated.resources.editor_open
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.runtime.CompositionLocalProvider
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.prioritiesOf
import com.korgnote.common.org.OrgPriorities
import com.korgnote.common.org.OrgPriority

/**
 * What can be done to a note from its reading view, given by the app. An action left out is one not
 * offered. Places are element indexes, or offsets into the whole text of the note, whichever the
 * view model finds its way from.
 */
class ElementActions(
    // The offset of the tapped checkbox; the view model finds the node there.
    val onToggleCheckbox: ((offsetInDocument: Int) -> Unit)? = null,
    /** A keyword picked from a heading's TODO keyword, or null to clear it, with that heading's offset. */
    val onSetTodo: ((offsetInDocument: Int, keyword: String?) -> Unit)? = null,
    /** A tap on a table, with its offset: tables are edited as a grid, not as text. */
    val onEditTable: ((offsetInDocument: Int) -> Unit)? = null,
    /** A plain tap on an element, anywhere an inner target (checkbox, link, drawer) does not claim. */
    val onEditElement: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: a sibling after the element's whole subtree. */
    val onAddHeadingBelow: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: a first child of the element. */
    val onAddSubheading: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu, once confirmed: the heading with its body and sub-headings. */
    val onDeleteSection: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: past the previous sibling (up) or the next one, subtree and all. */
    val onMoveSection: ((elementIndex: Int, up: Boolean) -> Unit)? = null,
    /** From the long-press menu: the heading and its subtree one level shallower (-1) or deeper (1). */
    val onShiftSection: ((elementIndex: Int, delta: Int) -> Unit)? = null,
    /** From the long-press menu: the heading and its subtree to another heading or note, chosen next. */
    val onRefileSection: ((elementIndex: Int) -> Unit)? = null,
    /** A priority picked for the heading at an offset, or null to remove it. */
    val onSetPriority: ((offsetInDocument: Int, priority: OrgPriority?) -> Unit)? = null,
    /**
     * From the long-press menu: the stamp beginning at this offset made inactive when it is active,
     * and active when it is not.
     */
    val onToggleTimestamp: ((offsetInDocument: Int) -> Unit)? = null,
    val onToggleFold: ((elementIndex: Int) -> Unit)? = null,
    /** From the long-press menu: the heading at this offset clocked in, out, or its clock cancelled. */
    val onClockIn: ((offsetInDocument: Int) -> Unit)? = null,
    val onClockOut: ((offsetInDocument: Int) -> Unit)? = null,
    val onCancelClock: ((offsetInDocument: Int) -> Unit)? = null,
    /** From the long-press menu: the heading at this offset given an effort, in minutes, or none. */
    val onSetEffort: ((offsetInDocument: Int, minutes: Int?) -> Unit)? = null,
)

/**
 * Reading view of a whole document, rendered one editable element at a time.
 *
 * Each element is parsed and rendered from its own slice of [text] rather than from a single parse
 * of the document. That costs a little more parsing and buys the property this screen depends on:
 * every rendered element knows the exact character range it came from, so editing one is a text
 * replacement over that range and can never disturb its neighbours.
 */
@Composable
fun OrgDocumentView(
    text: String,
    modifier: Modifier = Modifier,
    actions: ElementActions = ElementActions(),
    editingElementIndex: Int? = null,
    /** Read only by the element being edited, so that a keystroke composes that element alone. */
    elementDraft: () -> TextFieldValue = { TextFieldValue("") },
    onElementDraftChange: (TextFieldValue) -> Unit = {},
    /**
     * A keyboard key aimed at the suggestions. Returns true when it was used, so an unhandled Tab
     * or Escape still does whatever it would normally do.
     */
    onCompletionKey: (CompletionKey) -> Boolean = { false },
    /** Folded headings: each shows only its heading line, and its descendants render nothing. */
    foldedElements: Set<Int> = emptySet(),
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
                ElementRow(
                    index = index,
                    element = element,
                    text = text,
                    elements = elements,
                    folded = index in foldedElements,
                    depth = depths[index],
                    guideColor = guideColor,
                    lit = landing?.index == index,
                    glow = landingGlow,
                    glowColor = landingColor,
                    editingElementIndex = editingElementIndex,
                    elementDraft = elementDraft,
                    onElementDraftChange = onElementDraftChange,
                    onCompletionKey = onCompletionKey,
                    todoKeywords = todoKeywords,
                    priorities = priorities,
                    clockTime = clockTime,
                    actions = actions,
                    onEditDetails = onEditDetails,
                    onConfirmDelete = { confirmingDelete = index to element.title },
                )
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
        if (element == null || actions.onDeleteSection == null) {
            LaunchedEffect(Unit) { confirmingDelete = null }
        } else {
            DeleteSectionDialog(
                title = element.title,
                subheadings = elements.subtreeEnd(index) - index - 1,
                onDelete = { confirmingDelete = null; actions.onDeleteSection(index) },
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
 * One element of the note: as it reads, with its long-press menu and its fold, or the editor when it
 * is the one being edited, lit while a jump has just landed on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ElementRow(
    index: Int,
    element: OrgElement,
    text: String,
    elements: List<OrgElement>,
    folded: Boolean,
    depth: Int,
    guideColor: Color,
    lit: Boolean,
    glow: Animatable<Float, AnimationVector1D>,
    glowColor: Color,
    editingElementIndex: Int?,
    elementDraft: () -> TextFieldValue,
    onElementDraftChange: (TextFieldValue) -> Unit,
    onCompletionKey: (CompletionKey) -> Boolean,
    todoKeywords: OrgTodoConfig,
    priorities: OrgPriorities,
    clockTime: LocalDateTime?,
    actions: ElementActions,
    onEditDetails: (() -> Unit)?,
    onConfirmDelete: () -> Unit,
) {
    val slice = remember(text, element) { element.sliceOf(text) }
    // Not while an element is being edited: the form rewrites the top of the note.
    val editDetails = onEditDetails?.takeIf { index == 0 && editingElementIndex == null }
    if (element.isPreamble && slice.isBlank()) {
        if (editDetails != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { NoteDetailsButton(editDetails) }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().then(
            if (lit) {
                // Read while drawing, so the fade redraws the element without recomposing it.
                Modifier.drawBehind {
                    drawRoundRect(
                        glowColor.copy(alpha = glowColor.alpha * glow.value),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                }
            } else {
                Modifier
            }
        ).nestingGuides(depth, guideColor),
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
                        onClick = { actions.onEditElement?.invoke(index) },
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
                    val priorityEditing = remember(actions.onSetPriority, priorities, element.start) {
                        actions.onSetPriority?.let { set ->
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
                        onToggleCheckbox = actions.onToggleCheckbox?.let { toggle ->
                            { item -> toggle(element.start + item.range.first) }
                        },
                        onSetTodo = actions.onSetTodo?.let { set ->
                            { heading, keyword -> set(element.start + heading.range.first, keyword) }
                        },
                        onEditTable = actions.onEditTable?.let { edit ->
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
                        stamps = actions.onToggleTimestamp?.let { toggle ->
                            stamps.map { stamp ->
                                MenuStamp(shown.substring(stamp.range), stamp.active) { toggle(element.start + stamp.range.first) }
                            }
                        }.orEmpty(),
                        onAddHeadingBelow = actions.onAddHeadingBelow?.let { add -> { add(index) } },
                        onAddSubheading = actions.onAddSubheading?.let { add -> { add(index) } },
                        onDelete = actions.onDeleteSection?.let { onConfirmDelete },
                        onMoveUp = actions.onMoveSection?.takeIf { elements.previousSibling(index) != null }
                            ?.let { move -> { move(index, true) } },
                        onMoveDown = actions.onMoveSection?.takeIf { elements.nextSibling(index) != null }
                            ?.let { move -> { move(index, false) } },
                        onPromote = actions.onShiftSection?.takeIf { element.level > 1 }
                            ?.let { shift -> { shift(index, -1) } },
                        onDemote = actions.onShiftSection?.let { shift -> { shift(index, 1) } },
                        onRefile = actions.onRefileSection?.let { refile -> { refile(index) } },
                        onPriority = actions.onSetPriority?.let { { choosingPriority = true } },
                        onClockIn = actions.onClockIn?.takeIf { clocks?.running != true }?.let { clock -> { clock(element.start) } },
                        onClockOut = actions.onClockOut?.takeIf { clocks?.running == true }?.let { clock -> { clock(element.start) } },
                        onCancelClock = actions.onCancelClock?.takeIf { clocks?.running == true }?.let { cancel -> { cancel(element.start) } },
                        onEffort = actions.onSetEffort?.let { { choosingEffort = true } },
                    )
                    if (choosingEffort && actions.onSetEffort != null) {
                        EffortDialog(
                            current = remember(slice) { effortIn(slice) },
                            onPick = { minutes -> choosingEffort = false; actions.onSetEffort(element.start, minutes) },
                            onDismiss = { choosingEffort = false },
                        )
                    }
                    // From the long-press menu: the same choices as a tap on the cookie, for
                    // a heading that has none yet to tap.
                    if (choosingPriority && actions.onSetPriority != null) {
                        PriorityMenu(
                            expanded = true,
                            current = orgHeadingOf(slice.substringBefore('\n'), todoKeywords).priority,
                            priorities = priorities,
                            onPick = { priority -> choosingPriority = false; actions.onSetPriority(element.start, priority) },
                            onDismiss = { choosingPriority = false },
                        )
                    }
                }
                if (editDetails != null && element.isPreamble) NoteDetailsButton(editDetails)
                if (clocks != null && clocks.subtree.isNotEmpty()) ClockedTotal(clocks, clockTime)
                if (foldable && actions.onToggleFold != null) {
                    val foldDescription = stringResource(if (folded) Res.string.editor_expand_heading else Res.string.editor_collapse_heading, element.title)
                    IconButton(
                        onClick = { actions.onToggleFold(index) },
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
