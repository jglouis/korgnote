package com.korgnote.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgKeyword
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgPlanning
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgBlock
import com.korgnote.common.org.OrgComment
import com.korgnote.common.org.OrgDescriptionItem
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgFootnoteDefinition
import com.korgnote.common.org.OrgHorizontalRule
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.isNumeric
import com.korgnote.common.org.parseOrgTable
import com.korgnote.common.org.toOrgString



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
            content.children.forEachIndexed { idx, child ->
                // A Column, not a Box: some nodes (e.g. a heading plus its body) render as several
                // sibling composables that must stack vertically, which a Box would overlay instead.
                Column(
                    modifier = Modifier.bringIntoViewRequester(requesters[idx]),
                    horizontalAlignment = Alignment.Start
                ) {
                    RenderNode(
                        node = child,
                        todoConfig = todoConfig,
                        onToggleCheckbox = onToggleCheckbox,
                        onSetTodo = onSetTodo,
                    )
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
) {
    when (node) {
        is OrgDocument -> {
            Column(horizontalAlignment = Alignment.Start) {
                node.children.forEach { child -> RenderNode(child, todoConfig, onToggleCheckbox, onSetTodo) }
            }
        }

        is OrgSection -> {
            // A section owns its body and its sub-headings as children now, so rendering one renders
            // its whole subtree. The heading itself carries the level, so nothing here indents:
            // the visual result is the same flow of blocks the flat model produced.
            OrgHeading(node, todoConfig, onSetTodo)
            node.children.forEach { child -> RenderNode(child, todoConfig, onToggleCheckbox, onSetTodo) }
        }

        // The grammar models these now, so they render from the node rather than by re-scanning
        // text. The views themselves are the ones OrgBody already used.
        is OrgBlock -> OrgBlockView(node.type, node.parameters, node.body.trimEnd('\n').split('\n'))

        is OrgDrawer -> OrgDrawerView(node.name, node.lines)

        is OrgComment -> OrgLine(
            node.text,
            todoConfig,
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline),
        )

        is OrgHorizontalRule -> HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        is OrgDescriptionItem -> OrgLine("${node.term} :: ${node.description}", todoConfig)

        is OrgFootnoteDefinition -> OrgLine("[fn:${node.label}] ${node.text}", todoConfig)

        is OrgListItem -> {
            OrgLine(node.text, todoConfig)
            node.children.forEach { child -> RenderNode(child, todoConfig, onToggleCheckbox) }
        }

        is OrgList -> {
            Column(
                horizontalAlignment = Alignment.Start,
                // A nested list is indented by the depth its source had.
                modifier = Modifier.padding(start = (node.indent * 4).dp),
            ) {
                node.children.forEachIndexed { index, child ->
                    val marker = if (node.ordered) "${index + 1}${node.bulletType}" else "${node.bulletType}"
                    when (child) {
                        is OrgCheckboxItem -> {
                            RenderCheckboxItem(child, onToggleCheckbox)
                            child.children.forEach { RenderNode(it, todoConfig, onToggleCheckbox) }
                        }

                        is OrgListItem -> {
                            OrgLine("$marker ${child.text}", todoConfig)
                            child.children.forEach { RenderNode(it, todoConfig, onToggleCheckbox) }
                        }

                        is OrgDescriptionItem ->
                            OrgLine("$marker ${child.term} :: ${child.description}", todoConfig)

                        is OrgRaw -> OrgLine("$marker ${child.content}", todoConfig)

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
            if (title != null) {
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

        is OrgTable -> OrgTableView(node)

        is OrgTableRow -> OrgTableView(OrgTable(listOf(node)))
    }
}

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
                    // editing. A capitalised first word the keywords do not know is left alone.
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
                var title = node.title
                OrgSyntax.priority.find(title)?.let { prio ->
                    withStyle(SpanStyle(color = OrgColors.priority)) { append(prio.value) }
                    title = title.substring(prio.value.length)
                }
                appendOrgInline(title, viewMode = true)
            }
            Text(text = heading, style = style, modifier = Modifier.weight(1f, fill = false))
            if (node.tags.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.tags.forEach { tag -> OrgTagChip(tag) }
                }
            }
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
 * The keywords a heading can move to, in the order the file declares them: the not-done ones, then
 * the done ones, then none at all. The current one is checked, and picking it changes nothing.
 */
@Composable
private fun TodoKeywordMenu(
    expanded: Boolean,
    current: String?,
    todoConfig: OrgTodoConfig,
    onPick: (keyword: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    @Composable
    fun Item(keyword: String) = DropdownMenuItem(
        text = { Text(keyword, color = todoConfig.colorFor(keyword) ?: Color.Unspecified, fontWeight = FontWeight.Bold) },
        trailingIcon = if (keyword == current) {
            { Icon(Icons.Filled.Check, contentDescription = "Current keyword") }
        } else null,
        onClick = { onPick(keyword) },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        todoConfig.todo.forEach { Item(it) }
        if (todoConfig.todo.isNotEmpty() && todoConfig.done.isNotEmpty()) HorizontalDivider()
        todoConfig.done.forEach { Item(it) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("No keyword") }, onClick = { onPick(null) })
    }
}

/** The link annotation on a heading's TODO keyword, which offers the other keywords when tapped. */
internal const val TODO_KEYWORD_TAG = "todo-keyword"

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
    Surface(color = OrgColors.tag.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = OrgColors.tag,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
                    val paragraph = lines.subList(i, j).joinToString(" ") { it.trim() }
                    Text(text = buildOrgInlineString(paragraph), style = MaterialTheme.typography.bodyMedium)
                    i = j
                }
            }
        }
    }
}

@Composable
private fun OrgBlockView(type: String, params: String, lines: List<String>) {
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
                    text = body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )

                "quote", "verse" -> Text(
                    text = buildOrgInlineString(if (type == "verse") body else body.replace('\n', ' ')),
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

/** The drawer name as Org writes it, `:NAME:`, whichever way the caller spelled it. */
internal fun orgDrawerLabel(name: String): String = ":" + name.trim().trim(':') + ":"

@Composable
private fun OrgDrawerView(name: String, lines: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Collapse drawer" else "Expand drawer",
                tint = OrgColors.drawer,
                modifier = Modifier.width(16.dp)
            )
            Text(
                text = orgDrawerLabel(name),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = OrgColors.drawer
            )
        }
        if (expanded) {
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
    onToggleCheckbox: ((OrgCheckboxItem) -> Unit)?
) {
    // Keyed by where the item came from: two items with the same text are still different items.
    key(item.range.first) {
        CheckboxLine(checked = item.checked, text = item.text, onCheckedChange = {
            onToggleCheckbox?.invoke(item)
        })
    }
}

@Composable
private fun CheckboxLine(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { newValue: Boolean -> onCheckedChange(newValue) })
        Spacer(Modifier.width(6.dp))
        Text(text = buildOrgItemString(text), style = MaterialTheme.typography.bodyMedium)
    }
}
