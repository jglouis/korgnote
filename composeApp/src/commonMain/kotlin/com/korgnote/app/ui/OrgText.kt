package com.korgnote.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.template.parseTypedTags
import com.korgnote.common.org.OrgKeyword
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgPlanning
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgBlock
import com.korgnote.common.org.OrgComment
import com.korgnote.common.org.OrgDescriptionItem
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgFixedWidth
import com.korgnote.common.org.OrgLatexEnvironment
import com.korgnote.common.org.OrgFootnoteDefinition
import com.korgnote.common.org.orderedNumbers
import com.korgnote.common.org.OrgHorizontalRule
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.isNumeric
import com.korgnote.common.org.joinOrgLines
import com.korgnote.common.org.parseOrgTable
import com.korgnote.common.org.todoSequencesOf
import com.korgnote.common.org.srcLanguageOf
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.toOrgString
import com.korgnote.common.org.edit.Progress
import com.korgnote.common.org.edit.STATISTICS_COOKIE
import com.korgnote.common.org.edit.progressOf
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_collapse_drawer
import korgnote.composeapp.generated.resources.editor_current_keyword
import korgnote.composeapp.generated.resources.editor_edit_table
import korgnote.composeapp.generated.resources.editor_expand_drawer
import korgnote.composeapp.generated.resources.editor_keywords
import korgnote.composeapp.generated.resources.editor_keywords_with_done
import korgnote.composeapp.generated.resources.editor_no_keyword
import korgnote.composeapp.generated.resources.editor_progress_done
import korgnote.composeapp.generated.resources.editor_show_on_map
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.compositionLocalOf
import com.korgnote.common.org.OrgPriorities
import korgnote.composeapp.generated.resources.editor_current_priority
import korgnote.composeapp.generated.resources.editor_no_priority
import com.korgnote.common.org.OrgPriority



/**
 * Reading-mode renderer for an Org node: headings are sized by level, tables become grids,
 * source blocks are shown in monospace, drawers are collapsible and inline markup is hidden.
 */
@Composable
fun OrgText(
    content: OrgNode,
    modifier: Modifier = Modifier,
    // The item itself, not its position: a node carries the source range it was parsed from, while
    // an ordinal shifts whenever anything above it changes.
    onToggleCheckbox: ((item: OrgCheckboxItem) -> Unit)? = null,
    // A keyword picked for a heading, or null to clear it. A tap on the heading's TODO keyword offers
    // the choice, and only on a keyword [todoKeywords] knows.
    onSetTodo: ((heading: OrgSection, keyword: String?) -> Unit)? = null,
    // A tap on a table, which is edited as a grid rather than as the element's text.
    onEditTable: ((table: OrgTable) -> Unit)? = null,
    // Index into content.children (when content is an OrgDocument) to scroll into view, e.g. after
    // opening a file from a calendar entry. Consumed once via onScrolledToChildIndex.
    scrollToChildIndex: Int? = null,
    onScrolledToChildIndex: () -> Unit = {},
    /**
     * Keywords for a document that declares none of its own. Reading mode renders one heading at a
     * time, which never holds the file's `#+TODO:` lines, so it passes the file's keywords here.
     */
    todoKeywords: OrgTodoConfig = DefaultOrgTodoConfig,
) {
    val todoConfig = if (content is OrgDocument) orgTodoConfigOf(content, todoKeywords) else todoKeywords
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        if (content is OrgDocument) {
            // One requester per top-level node so a calendar entry (which always refers to a
            // top-level OrgSection) can be scrolled into view within the surrounding scroll container.
            val requesters = remember(content) { content.children.map { BringIntoViewRequester() } }
            // The author and the date, as one line where the first of them is written.
            val byline = remember(content) { noteBylineOf(content) }
            content.children.forEachIndexed { idx, child ->
                // A Column, not a Box: some nodes (e.g. a heading plus its body) render as several
                // sibling composables that must stack vertically, which a Box would overlay instead.
                Column(
                    modifier = Modifier.bringIntoViewRequester(requesters[idx]),
                    horizontalAlignment = Alignment.Start
                ) {
                    when {
                        // The note's own drawer, above its first heading, says what its ID is.
                        child is OrgDrawer -> OrgDrawerView(child.name, child.lines, showId = true)
                        byline == null || idx !in byline.indices -> RenderNode(
                            node = child,
                            todoConfig = todoConfig,
                            onToggleCheckbox = onToggleCheckbox,
                            onSetTodo = onSetTodo,
                            onEditTable = onEditTable,
                        )
                        idx == byline.indices.min() -> NoteBylineView(byline)
                    }
                }
            }
            LaunchedEffect(scrollToChildIndex, requesters) {
                val idx = scrollToChildIndex
                if (idx != null && idx in requesters.indices) {
                    requesters[idx].bringIntoView()
                    onScrolledToChildIndex()
                }
            }
        } else {
            RenderNode(
                node = content,
                todoConfig = todoConfig,
                onToggleCheckbox = onToggleCheckbox,
                onSetTodo = onSetTodo,
                onEditTable = onEditTable,
            )
        }
    }
}

@Composable
private fun RenderNode(
    node: OrgNode,
    todoConfig: OrgTodoConfig,
    onToggleCheckbox: ((OrgCheckboxItem) -> Unit)?,
    // Only documents and sections pass this on: nothing else can hold a heading.
    onSetTodo: ((OrgSection, String?) -> Unit)? = null,
    // Likewise: a table sits in a section's body, never in a list.
    onEditTable: ((OrgTable) -> Unit)? = null,
) {
    when (node) {
        is OrgDocument -> {
            Column(horizontalAlignment = Alignment.Start) {
                node.children.forEach { child -> RenderNode(child, todoConfig, onToggleCheckbox, onSetTodo, onEditTable) }
            }
        }

        is OrgSection -> {
            // A section owns its body and its sub-headings as children now, so rendering one renders
            // its whole subtree. The heading itself carries the level, so nothing here indents:
            // the visual result is the same flow of blocks the flat model produced.
            OrgHeading(node, todoConfig, onSetTodo)
            node.children.forEach { child -> RenderNode(child, todoConfig, onToggleCheckbox, onSetTodo, onEditTable) }
        }

        // The grammar models these now, so they render from the node rather than by re-scanning
        // text. The views themselves are the ones OrgBody already used.
        // Without the indentation all its lines share, which a block inside a list item has from the
        // item: Org strips it too (`org-src-preserve-indentation` is off by default).
        is OrgBlock -> OrgBlockView(node.type.lowercase(), node.parameters, node.body.trimEnd('\n').trimIndent().split('\n'), todoConfig)

        is OrgDrawer -> OrgDrawerView(node.name, node.lines)

        is OrgComment -> OrgLine(
            node.text,
            todoConfig,
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline),
        )

        is OrgHorizontalRule -> HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // Verbatim, as Org shows `: lines`: one line each, monospace, scrolling rather than wrapping.
        is OrgFixedWidth -> Text(
            text = node.contents.joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        )

        // Shown as written, as Emacs shows it unless asked to preview it.
        is OrgLatexEnvironment -> Text(
            // Without the indent the whole environment shares, keeping what its body adds.
            text = node.lines.joinToString("\n").trimIndent(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        )

        is OrgDescriptionItem -> OrgLine("${node.term} :: ${joinOrgLines(node.description)}", todoConfig)

        is OrgFootnoteDefinition -> OrgLine("[fn:${node.label}] ${joinOrgLines(node.text)}", todoConfig)

        is OrgListItem -> {
            OrgLine(joinOrgLines(node.text), todoConfig)
            ItemContents(node.children, todoConfig, onToggleCheckbox)
        }

        is OrgList -> {
            val guide = MaterialTheme.colorScheme.outlineVariant
            Column(
                horizontalAlignment = Alignment.Start,
                // A nested list is indented by the depth its source had, with a bar down its left
                // as a sub-heading has.
                modifier = if (node.indent > 0) {
                    Modifier
                        .drawBehind {
                            val x = 6.dp.toPx()
                            drawLine(guide, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                        }
                        .padding(start = (node.indent * 4).coerceAtLeast(14).dp)
                } else {
                    Modifier
                },
            ) {
                // Numbered as Org numbers them, by position and `[@n]` counters, not by what the source
                // wrote: a list typed `1.`, `1.`, `1.` reads 1, 2, 3, as it does in Emacs.
                val numbers = node.orderedNumbers()
                node.children.forEachIndexed { index, child ->
                    val marker = if (node.ordered) "${numbers[index]}${node.bulletType}" else "${node.bulletType}"
                    when (child) {
                        is OrgCheckboxItem -> {
                            // In a numbered list, its number before the box, as Org writes `3. [ ]`;
                            // in a bulleted one the box stands for the bullet.
                            RenderCheckboxItem(child, onToggleCheckbox, number = marker.takeIf { node.ordered })
                            ItemContents(child.children, todoConfig, onToggleCheckbox)
                        }

                        is OrgListItem -> {
                            ListItemLine(marker, joinOrgLines(child.text))
                            ItemContents(child.children, todoConfig, onToggleCheckbox)
                        }

                        is OrgDescriptionItem -> {
                            ListItemLine(marker, "${child.term} :: ${joinOrgLines(child.description)}")
                            ItemContents(child.children, todoConfig, onToggleCheckbox)
                        }

                        is OrgRaw -> ListItemLine(marker, child.content)

                        else -> RenderNode(child, todoConfig, onToggleCheckbox)
                    }
                }
            }
        }

        is OrgCheckboxItem -> {
            RenderCheckboxItem(node, onToggleCheckbox)
        }

        is OrgKeyword -> {
            val title = OrgSyntax.titleKeyword.find(node.content.trim())?.groupValues?.get(1)
            // The note's own tags, as chips in their colours, like a heading's.
            val fileTags = OrgSyntax.filetagsKeyword.find(node.content.trim())?.groupValues?.get(1)
                ?.let(::parseTypedTags)
            val keywords = todoSequencesOf(listOf(node.content)).firstOrNull()
            val caption = OrgSyntax.captionKeyword.find(node.content)?.groupValues?.get(1)
            if (caption != null) {
                // What the table or figure below it is, as export shows it.
                Text(
                    text = buildOrgInlineString(caption),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (!fileTags.isNullOrEmpty()) {
                TagChips(fileTags, Modifier.padding(vertical = 2.dp))
            } else if (keywords != null && (keywords.todo.isNotEmpty() || keywords.done.isNotEmpty())) {
                TodoKeywordsView(keywords)
            } else if (title != null) {
                Text(
                    text = buildOrgInlineString(title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            } else {
                Text(
                    text = node.content,
                    style = MaterialTheme.typography.bodySmall.copy(color = OrgColors.keyword)
                )
            }
        }

        is OrgPlanning -> OrgLine(node.planType + ": " + node.timestamp, todoConfig, MaterialTheme.typography.bodySmall)

        is OrgRaw -> OrgBody(node.content, todoConfig)

        is OrgTable -> OrgTableView(
            node,
            modifier = if (onEditTable == null) Modifier else Modifier.clickable(onClickLabel = stringResource(Res.string.editor_edit_table)) { onEditTable(node) },
        )

        is OrgTableRow -> OrgTableView(OrgTable(listOf(node)))
    }
}

/**
 * Choosing a heading's priority, where it can be: the file's priorities, and what to do with the
 * one picked. Provided around a note's headings rather than handed down through every node.
 */
class PriorityEditing(val priorities: OrgPriorities, val onSet: (heading: OrgSection, priority: OrgPriority?) -> Unit)

val LocalPriorityEditing = compositionLocalOf<PriorityEditing?> { null }

/**
 * What an item holds under its first line — further paragraphs, blocks, tables — indented under
 * its text, as Org keeps them inside the item. A nested list indents itself, by its own depth.
 */
@Composable
private fun ItemContents(contents: List<OrgNode>, todoConfig: OrgTodoConfig, onToggleCheckbox: ((OrgCheckboxItem) -> Unit)?) {
    for (child in contents) {
        if (child is OrgList) {
            RenderNode(child, todoConfig, onToggleCheckbox)
        } else {
            // A little space above, as between paragraphs: in the file a blank line sets them apart.
            Column(Modifier.padding(start = ITEM_CONTENTS_INDENT, top = 4.dp), horizontalAlignment = Alignment.Start) {
                RenderNode(child, todoConfig, onToggleCheckbox)
            }
        }
    }
}

/** How far an item's contents sit in from its bullet: about the width of `1. `. */
private val ITEM_CONTENTS_INDENT = 20.dp

@Composable
private fun OrgHeading(node: OrgSection, todoConfig: OrgTodoConfig, onSetTodo: ((OrgSection, String?) -> Unit)?) {
    val typography = MaterialTheme.typography
    val base = when (node.level) {
        1 -> typography.titleLarge
        2 -> typography.titleMedium
        3 -> typography.titleSmall
        else -> typography.bodyLarge
    }
    val style = base.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    var picking by remember { mutableStateOf(false) }
    var pickingPriority by remember { mutableStateOf(false) }
    val priorityEditing = LocalPriorityEditing.current
    // A filled-in `[2/5]` or `[40%]` is drawn as a bar beside the title rather than as text in it.
    val progress = remember(node.title) {
        STATISTICS_COOKIE.find(node.title)?.let { cookie -> progressOf(cookie)?.let { cookie to it } }
    }
    // A Box so the keyword menu opens from the heading, whose first word the keyword is.
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = if (node.level == 1) 12.dp else 6.dp, bottom = 2.dp)
        ) {
            val heading = buildAnnotatedString {
                val todoColor = node.todo?.let { todoConfig.colorFor(it) }
                if (!node.todo.isNullOrEmpty()) {
                    val keywordStyle = SpanStyle(color = todoColor ?: style.color)
                    // A link rather than a Text of its own, so a long title still wraps under the
                    // keyword. Like any link it takes its own tap, so the element is not opened for
                    // editing.
                    if (onSetTodo != null && todoColor != null) {
                        val pick = LinkAnnotation.Clickable(
                            tag = TODO_KEYWORD_TAG,
                            styles = TextLinkStyles(style = keywordStyle),
                            linkInteractionListener = { picking = true },
                        )
                        withLink(pick) { append(node.todo) }
                    } else {
                        withStyle(keywordStyle) { append(node.todo) }
                    }
                    append(' ')
                }
                val title = progress?.let { (cookie, _) -> node.title.removeRange(cookie.range).replace("  ", " ").trim() }
                    ?: node.title
                // The parser takes the cookie off the title into [OrgSection.priority], so it is
                // drawn from there: looked for in the title, it was never found, and never shown.
                node.priority?.let { priority ->
                    val cookie = "[#${priority.text}]"
                    val priorityStyle = SpanStyle(color = OrgColors.priority)
                    // Like the keyword, a link of its own: a tap picks another priority.
                    if (priorityEditing != null) {
                        val pick = LinkAnnotation.Clickable(
                            tag = PRIORITY_TAG,
                            styles = TextLinkStyles(style = priorityStyle),
                            linkInteractionListener = { pickingPriority = true },
                        )
                        withLink(pick) { append(cookie) }
                    } else {
                        withStyle(priorityStyle) { append(cookie) }
                    }
                    append(' ')
                }
                appendOrgInline(title, viewMode = true)
            }
            Text(text = heading, style = style, modifier = Modifier.weight(1f, fill = false))
            progress?.let { (cookie, value) ->
                Spacer(Modifier.width(8.dp))
                ProgressPill(value, percent = cookie.value.endsWith("%]"))
            }
            if (node.tags.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.tags.forEach { tag -> OrgTagChip(tag) }
                }
            }
            // Where it happens, a tap away, without opening the property drawer to find the link.
            node.location()?.let { location ->
                val uriHandler = LocalUriHandler.current
                IconButton(onClick = { uriHandler.openUri(googleMapsUrl(location)) }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = stringResource(Res.string.editor_show_on_map, location),
                        tint = OrgColors.link,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        if (priorityEditing != null) {
            PriorityMenu(
                expanded = pickingPriority,
                current = node.priority,
                priorities = priorityEditing.priorities,
                onPick = { priority ->
                    pickingPriority = false
                    if (priority != node.priority) priorityEditing.onSet(node, priority)
                },
                onDismiss = { pickingPriority = false },
            )
        }
        if (onSetTodo != null) {
            TodoKeywordMenu(
                expanded = picking,
                current = node.todo,
                todoConfig = todoConfig,
                onPick = { keyword ->
                    picking = false
                    if (keyword != node.todo) onSetTodo(node, keyword)
                },
                onDismiss = { picking = false },
            )
        }
    }
}

/**
 * A heading's statistics cookie as a short bar and its count, `2/5` or `40%` as it was written; green
 * once everything under it is done.
 */
@Composable
private fun ProgressPill(progress: Progress, percent: Boolean) {
    val complete = progress.total > 0 && progress.done >= progress.total
    val color = if (complete) OrgColors.todo else MaterialTheme.colorScheme.primary
    val label = if (percent) "${progress.percent}%" else "${progress.done}/${progress.total}"
    val description = stringResource(Res.string.editor_progress_done, label)
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {},
                modifier = Modifier.width(32.dp).height(4.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** The heading's own `:LOCATION:` property, or null when it has none. */
internal fun OrgSection.location(): String? =
    children.asSequence()
        .filterIsInstance<OrgDrawer>()
        .filter { it.name.equals("PROPERTIES", ignoreCase = true) }
        .flatMap { it.properties }
        .firstOrNull { (key, _) -> key.equals("LOCATION", ignoreCase = true) }
        ?.second?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The keywords a heading can move to, in the order the file declares them: the not-done ones, then
 * the done ones, then none at all. The current one is checked, and picking it changes nothing.
 */
/** The file's priorities, most important first, then none; the heading's own is ticked. */
@Composable
fun PriorityMenu(
    expanded: Boolean,
    current: OrgPriority?,
    priorities: OrgPriorities,
    onPick: (priority: OrgPriority?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (priority in priorities.range) {
            DropdownMenuItem(
                text = { Text("[#${priority.text}]", color = OrgColors.priority, fontWeight = FontWeight.Bold) },
                trailingIcon = if (priority == current) {
                    { Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.editor_current_priority)) }
                } else null,
                onClick = { onPick(priority) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.editor_no_priority)) },
            trailingIcon = if (current == null) {
                { Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.editor_current_priority)) }
            } else null,
            onClick = { onPick(null) },
        )
    }
}

@Composable
private fun TodoKeywordMenu(
    expanded: Boolean,
    current: String?,
    todoConfig: OrgTodoConfig,
    onPick: (keyword: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        TodoKeywordItems(current, todoConfig, onPick)
    }
}

/**
 * A heading's keywords as menu items: the not-done ones, then the done ones, each in its colour and
 * [current] ticked, then *No keyword*. The note's keyword menu, and the calendar's long-press.
 */
@Composable
internal fun TodoKeywordItems(current: String?, todoConfig: OrgTodoConfig, onPick: (keyword: String?) -> Unit) {
    @Composable
    fun Item(keyword: String) = DropdownMenuItem(
        text = { Text(keyword, color = todoConfig.colorFor(keyword) ?: Color.Unspecified, fontWeight = FontWeight.Bold) },
        trailingIcon = if (keyword == current) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.editor_current_keyword)) }
        } else null,
        onClick = { onPick(keyword) },
    )

    todoConfig.todo.forEach { Item(it) }
    if (todoConfig.todo.isNotEmpty() && todoConfig.done.isNotEmpty()) HorizontalDivider()
    todoConfig.done.forEach { Item(it) }
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(Res.string.editor_no_keyword)) }, onClick = { onPick(null) })
}

/** The link annotation on a heading's TODO keyword, which offers the other keywords when tapped. */
internal const val TODO_KEYWORD_TAG = "todo-keyword"

/** The tag of the link a heading's priority cookie is drawn as. */
private const val PRIORITY_TAG = "priority"

/**
 * A heading's tags as chips, the way the reading view shows them, for the places that list
 * headings out of their document: the agenda, search results. They wrap onto as many lines as
 * [maxLines] allows, and whatever does not fit is left out.
 */
@Composable
fun TagChips(tags: List<String>, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxLines = maxLines,
    ) {
        tags.forEach { OrgTagChip(it) }
    }
}

@Composable
private fun OrgTagChip(tag: String) {
    val color = TagColors.of(tag)
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** A tag offered for picking: a chip in the tag's colour, larger than [OrgTagChip], to be tapped. */
@Composable
fun TagSuggestionChip(tag: String, colorOverride: Color? = null, onClick: () -> Unit) {
    val color = colorOverride ?: TagColors.of(tag)
    Surface(
        onClick = onClick,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Renders a single Org line (list item, planning, keyword...) with markup hidden. */
@Composable
private fun OrgLine(line: String, todoConfig: OrgTodoConfig, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(
        text = buildOrgHighlightedString(line, MaterialTheme.colorScheme.primary, todoConfig, viewMode = true),
        style = style
    )
}

/** A list item's [marker] and [text], neither of them read as Org a second time. */
@Composable
private fun ListItemLine(marker: String, text: String) {
    Text(text = buildOrgListItemString(marker, text), style = MaterialTheme.typography.bodyMedium)
}

private fun isBlankLine(line: String) = line.isBlank()

private fun isSpecialLine(line: String): Boolean =
    isBlankLine(line) ||
        OrgSyntax.heading.containsMatchIn(line) ||
        OrgSyntax.blockBegin.containsMatchIn(line) ||
        OrgSyntax.blockEnd.containsMatchIn(line) ||
        OrgSyntax.keyword.containsMatchIn(line) ||
        OrgSyntax.comment.containsMatchIn(line) ||
        OrgSyntax.drawerBegin.containsMatchIn(line) ||
        OrgSyntax.planning.containsMatchIn(line) ||
        OrgSyntax.isTableLine(line) ||
        OrgSyntax.horizontalRule.containsMatchIn(line) ||
        OrgSyntax.listItem.containsMatchIn(line)

/**
 * Renders raw Org text (section content or a paragraph) by grouping lines into tables, blocks,
 * drawers, paragraphs and single special lines.
 */
@Composable
fun OrgBody(text: String, todoConfig: OrgTodoConfig = DefaultOrgTodoConfig) {
    val lines = text.split('\n')
    Column(horizontalAlignment = Alignment.Start) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val blockBegin = OrgSyntax.blockBegin.find(line)
            when {
                blockBegin != null -> {
                    var j = i + 1
                    while (j < lines.size && !OrgSyntax.blockEnd.containsMatchIn(lines[j])) j++
                    OrgBlockView(
                        type = blockBegin.groupValues[1].lowercase(),
                        params = blockBegin.groupValues[2].trim(),
                        lines = lines.subList(i + 1, j)
                    )
                    i = j + 1
                }

                OrgSyntax.isTableLine(line) -> {
                    var j = i
                    while (j < lines.size && OrgSyntax.isTableLine(lines[j])) j++
                    OrgTableView(parseOrgTable(lines.subList(i, j)))
                    i = j
                }

                OrgSyntax.drawerBegin.containsMatchIn(line) && !OrgSyntax.drawerEnd.containsMatchIn(line) -> {
                    var j = i + 1
                    while (j < lines.size && !OrgSyntax.drawerEnd.containsMatchIn(lines[j])) j++
                    OrgDrawerView(name = line.trim(), lines = lines.subList(i + 1, j))
                    i = j + 1
                }

                OrgSyntax.horizontalRule.containsMatchIn(line) -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    i++
                }

                isBlankLine(line) -> {
                    Spacer(Modifier.height(6.dp))
                    i++
                }

                isSpecialLine(line) -> {
                    OrgLine(line, todoConfig)
                    i++
                }

                else -> {
                    // Paragraph: consecutive plain lines are joined into one wrapped text
                    var j = i + 1
                    while (j < lines.size && !isSpecialLine(lines[j])) j++
                    val paragraph = joinOrgLines(lines.subList(i, j).joinToString("\n"))
                    Text(text = buildOrgInlineString(paragraph), style = MaterialTheme.typography.bodyMedium)
                    i = j
                }
            }
        }
    }
}

@Composable
private fun OrgBlockView(
    type: String,
    params: String,
    lines: List<String>,
    todoConfig: OrgTodoConfig = DefaultOrgTodoConfig,
) {
    val label = listOf(type, params).filter { it.isNotBlank() }.joinToString(" ")
    val body = lines.joinToString("\n")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (type) {
                in OrgSyntax.monospaceBlocks -> Text(
                    text = buildSrcBlockString(body, if (type == "src") srcLanguageOf(params) else null),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )

                // Commented out: shown dimmed, as a `# comment` line is, and as Emacs shows both.
                "comment" -> Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = FontStyle.Italic,
                    ),
                )

                // A quote holds Org like any other text, lists and tables included. Its own
                // checkboxes are not ticked from here: the block's ranges are not the note's.
                "quote" -> {
                    val document = remember(body, todoConfig) { body.toOrgDocument(todoConfig) }
                    Column(horizontalAlignment = Alignment.Start) {
                        document.children.forEach { RenderNode(it, todoConfig, onToggleCheckbox = null) }
                    }
                }

                "center" -> Text(
                    text = buildOrgInlineString(body),
                    style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )

                "verse" -> Text(
                    text = buildOrgInlineString(body),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                )

                else -> Text(text = buildOrgInlineString(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The Org source a drawer is highlighted from, rebuilt from its name and body lines.
 *
 * The two call sites spell the name differently — the grammar strips the colons off [OrgDrawer.name]
 * while the raw-text path passes the `:PROPERTIES:` line as it stands — and the highlighter only
 * enters drawer mode on a line that still has them, so the colons are put back here. Without that,
 * a `:LOCATION:` property inside a parsed drawer was highlighted as a plain paragraph and lost its
 * Google Maps link.
 */
internal fun orgDrawerSource(name: String, lines: List<String>): String =
    (listOf(orgDrawerLabel(name)) + lines + ":END:").joinToString("\n")

/** The `:ID:` a `:PROPERTIES:` drawer holds, or null when it is another drawer or has none. */
internal fun drawerIdOf(name: String, lines: List<String>): String? {
    if (!name.trim().trim(':').equals("PROPERTIES", ignoreCase = true)) return null
    return lines.firstNotNullOfOrNull { OrgSyntax.idProperty.matchEntire(it)?.groupValues?.get(1) }
}

/**
 * A note's `#+TODO:` line as what it declares: its keywords, each in the colour headings show it in,
 * the open ones leading to the done ones as a heading moves through them.
 */
@Composable
private fun TodoKeywordsView(keywords: OrgTodoConfig) {
    @Composable
    fun Keyword(word: String) {
        val color = keywords.colorFor(word) ?: MaterialTheme.colorScheme.onSurfaceVariant
        Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
            Text(
                text = word,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = color,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    val description = if (keywords.done.isEmpty()) {
        stringResource(Res.string.editor_keywords, keywords.todo.joinToString(", "))
    } else {
        stringResource(Res.string.editor_keywords_with_done, keywords.todo.joinToString(", "), keywords.done.joinToString(", "))
    }
    FlowRow(
        modifier = Modifier.padding(vertical = 2.dp).semantics {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Checklist,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        keywords.todo.forEach { Keyword(it) }
        if (keywords.done.isNotEmpty()) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            keywords.done.forEach { Keyword(it) }
        }
    }
}

/** The drawer name as Org writes it, `:NAME:`, whichever way the caller spelled it. */
internal fun orgDrawerLabel(name: String): String = ":" + name.trim().trim(':') + ":"

/**
 * A drawer, closed until tapped. With [showId], a `:PROPERTIES:` drawer holding an `:ID:` is labelled
 * with that ID instead of its name — for the note's own drawer, where the ID is what there is to
 * know and the name says nothing.
 */
@Composable
private fun OrgDrawerView(name: String, lines: List<String>, showId: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val id = if (showId) drawerIdOf(name, lines) else null
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            DisclosureChevron(
                expanded = expanded,
                contentDescription = stringResource(if (expanded) Res.string.editor_collapse_drawer else Res.string.editor_expand_drawer),
                tint = OrgColors.drawer,
                modifier = Modifier.width(16.dp)
            )
            if (id != null) {
                Text(
                    text = "ID",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = OrgColors.drawer,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = orgDrawerLabel(name),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = OrgColors.drawer
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(DISCLOSURE_MILLIS)) + fadeIn(tween(DISCLOSURE_MILLIS)),
            exit = shrinkVertically(tween(DISCLOSURE_MILLIS)) + fadeOut(tween(DISCLOSURE_MILLIS)),
        ) {
            Text(
                text = buildOrgHighlightedString(
                    orgDrawerSource(name, lines),
                    MaterialTheme.colorScheme.primary,
                    viewMode = true
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

/**
 * Renders an Org table as a grid: columns are as wide as their widest cell, numeric cells are
 * right-aligned, rows before the first horizontal rule are treated as header and horizontal rules
 * become separator lines. Wide tables scroll horizontally.
 */
@Composable
fun OrgTableView(table: OrgTable, modifier: Modifier = Modifier) {
    val cols = table.columnCount
    if (cols == 0) return
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val firstDataRow = table.rows.indexOfFirst { !it.isRule }
    val ruleAfterFirstData = table.rows.withIndex().indexOfFirst { (idx, row) -> idx > firstDataRow && row.isRule }
    val headerRange = if (firstDataRow >= 0 && ruleAfterFirstData > firstDataRow) firstDataRow until ruleAfterFirstData else IntRange.EMPTY

    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
            .border(1.dp, lineColor)
    ) {
        Layout(
            content = {
                table.rows.forEachIndexed { r, row ->
                    if (row.isRule) {
                        Box(Modifier.height(1.dp).background(lineColor))
                    } else {
                        for (c in 0 until cols) {
                            val cell = row.cells.getOrNull(c)
                            val text = (cell as? OrgRaw)?.content ?: cell?.toOrgString().orEmpty()
                            TableCell(
                                text = text,
                                header = r in headerRange,
                                drawRightBorder = c < cols - 1,
                                lineColor = lineColor
                            )
                        }
                    }
                }
            }
        ) { measurables, _ ->
            var idx = 0
            val rowMeasurables = table.rows.map { row ->
                if (row.isRule) listOf(measurables[idx++]) else List(cols) { measurables[idx++] }
            }
            val colWidths = IntArray(cols)
            table.rows.forEachIndexed { r, row ->
                if (!row.isRule) rowMeasurables[r].forEachIndexed { c, m ->
                    colWidths[c] = maxOf(colWidths[c], m.maxIntrinsicWidth(Constraints.Infinity))
                }
            }
            val totalWidth = colWidths.sum()
            val rowHeights = IntArray(table.rows.size)
            val placeables = table.rows.mapIndexed { r, row ->
                if (row.isRule) {
                    val p = rowMeasurables[r][0].measure(Constraints.fixedWidth(totalWidth))
                    rowHeights[r] = p.height
                    listOf(p)
                } else {
                    val h = rowMeasurables[r].withIndex().maxOf { (c, m) -> m.minIntrinsicHeight(colWidths[c]) }
                    rowHeights[r] = h
                    rowMeasurables[r].mapIndexed { c, m -> m.measure(Constraints.fixed(colWidths[c], h)) }
                }
            }
            layout(totalWidth, rowHeights.sum()) {
                var y = 0
                placeables.forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        p.placeRelative(x, y)
                        if (!table.rows[r].isRule) x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean, drawRightBorder: Boolean, lineColor: Color) {
    Box(
        modifier = Modifier
            .drawBehind {
                if (drawRightBorder) {
                    drawLine(lineColor, Offset(size.width, 0f), Offset(size.width, size.height))
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = if (text.isNumeric()) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = buildOrgInlineString(text),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal
            ),
            softWrap = false
        )
    }
}

@Composable
private fun RenderCheckboxItem(
    item: OrgCheckboxItem,
    onToggleCheckbox: ((OrgCheckboxItem) -> Unit)?,
    /** `3.`, for an item of a numbered list; null in a bulleted one. */
    number: String? = null,
) {
    // Keyed by where the item came from: two items with the same text are still different items.
    key(item.range.first) {
        CheckboxLine(checked = item.checked, partial = item.partial, text = joinOrgLines(item.text), number = number, onCheckedChange = {
            onToggleCheckbox?.invoke(item)
        })
    }
}

@Composable
private fun CheckboxLine(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
    /** `[-]`, drawn as a box part-way ticked. */
    partial: Boolean = false,
    /** The item's number before the box, styled as a numbered item's: `3.`. */
    number: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (number != null) {
            Text(
                text = number,
                style = MaterialTheme.typography.bodyMedium.copy(color = OrgColors.bullet, fontWeight = FontWeight.Bold),
            )
        }
        TriStateCheckbox(
            state = when {
                checked -> ToggleableState.On
                partial -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            },
            onClick = { onCheckedChange(!checked) },
        )
        Spacer(Modifier.width(6.dp))
        Text(text = buildOrgItemString(text), style = MaterialTheme.typography.bodyMedium)
    }
}
