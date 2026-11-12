package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaFilter
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.TodoEntry
import com.korgnote.common.org.agenda.grouped
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.back
import korgnote.composeapp.generated.resources.cal_filter_by
import korgnote.composeapp.generated.resources.cal_filter_clear
import korgnote.composeapp.generated.resources.cal_filter_keywords
import korgnote.composeapp.generated.resources.cal_filter_remove
import korgnote.composeapp.generated.resources.cal_filter_tags
import korgnote.composeapp.generated.resources.cal_filter
import korgnote.composeapp.generated.resources.cal_kind_deadline
import korgnote.composeapp.generated.resources.cal_kind_scheduled
import korgnote.composeapp.generated.resources.cal_show_done
import korgnote.composeapp.generated.resources.cal_status_overdue
import korgnote.composeapp.generated.resources.cal_status_scheduled_ago
import korgnote.composeapp.generated.resources.cal_today
import korgnote.composeapp.generated.resources.cal_todos
import korgnote.composeapp.generated.resources.cal_todos_none
import korgnote.composeapp.generated.resources.ok
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.jetbrains.compose.resources.stringResource

// --- Filtering ---------------------------------------------------------------------------------

/** A filter kept across the activity being recreated: its keywords, then its tags, each a line. */
internal val AgendaFilterSaver = Saver<AgendaFilter, List<String>>(
    save = { filter -> filter.keywords.map { "k$it" } + filter.tags.map { "t$it" } },
    restore = { saved ->
        AgendaFilter(
            keywords = saved.filter { it.startsWith("k") }.mapTo(linkedSetOf()) { it.drop(1) },
            tags = saved.filter { it.startsWith("t") }.mapTo(linkedSetOf()) { it.drop(1) },
        )
    },
)

/**
 * What the calendar is narrowed to, a chip for each keyword and tag, whose × takes it away. Nothing
 * at all while nothing is filtered: this is the only thing a filter adds to the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveFilter(filter: AgendaFilter, onChange: (AgendaFilter) -> Unit, modifier: Modifier = Modifier) {
    if (filter.isEmpty) return
    FlowRow(
        modifier = modifier.fillMaxWidth().testTag("activeFilter"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (keyword in filter.keywords) {
            RemovableChip(keyword) { onChange(filter.toggleKeyword(keyword)) }
        }
        for (tag in filter.tags) {
            RemovableChip(tag) { onChange(filter.toggleTag(tag)) }
        }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    val description = stringResource(Res.string.cal_filter_remove, label)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/**
 * What the header's filter icon opens: whether done entries show, then every keyword and tag in the
 * agenda and the TODO list, to pick which to show. A pick applies at once, behind the dialog; *Clear*
 * puts everything back as it starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgendaFilterDialog(
    agenda: AgendaIndex,
    filter: AgendaFilter,
    onChange: (AgendaFilter) -> Unit,
    showDone: Boolean,
    onShowDone: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val whole = agenda.whole
    val keywords = remember(whole, filter) { keywordsIn(whole) + filter.keywords.filter { it !in keywordsIn(whole) } }
    val tags = remember(whole, filter) {
        val used = tagsIn(whole)
        used + filter.tags.filter { tag -> used.none { it.equals(tag, ignoreCase = true) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cal_filter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("agendaFilterDialog")) {
                // Finished entries are history, and a year of them buries what is still to do.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = showDone, role = Role.Switch, onValueChange = onShowDone)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.cal_show_done), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = showDone, onCheckedChange = null)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (keywords.isNotEmpty()) {
                    Text(stringResource(Res.string.cal_filter_keywords), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (keyword in keywords) {
                            FilterChip(
                                selected = keyword in filter.keywords,
                                onClick = { onChange(filter.toggleKeyword(keyword)) },
                                label = { Text(keyword) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (tags.isNotEmpty()) {
                    Text(stringResource(Res.string.cal_filter_tags), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (tag in tags) {
                            FilterChip(
                                selected = filter.tags.any { it.equals(tag, ignoreCase = true) },
                                onClick = { onChange(filter.toggleTag(tag)) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ok)) } },
        dismissButton = {
            TextButton(onClick = { onChange(AgendaFilter.NONE); onShowDone(false) }, enabled = showDone || !filter.isEmpty) {
                Text(stringResource(Res.string.cal_filter_clear))
            }
        },
    )
}

/** The keywords in [agenda]: those still to do in the order the notes declare them, then the rest by name. */
private fun keywordsIn(agenda: AgendaIndex): List<String> {
    val open = agenda.todos.grouped().map { it.keyword }
    val others = agenda.entries.mapNotNull { it.todo }.filter { it !in open }.distinct().sorted()
    return open + others
}

/** The tags in [agenda], the most used first. */
private fun tagsIn(agenda: AgendaIndex): List<String> {
    val counts = mutableMapOf<String, Int>()
    for (tags in agenda.entries.map { it.tags } + agenda.todos.map { it.tags }) {
        for (tag in tags) counts[tag] = (counts[tag] ?: 0) + 1
    }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }
}

// --- The TODO list -----------------------------------------------------------------------------

/** The agenda's first line: how many TODOs there are, opening the list of them. Nothing when there are none. */
@Composable
internal fun TodosRow(count: Int, onClick: () -> Unit) {
    if (count == 0) return
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp)
                .testTag("todosRow"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Checklist, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "${stringResource(Res.string.cal_todos)} · $count",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()
    }
}

/**
 * org-agenda's TODO list (`t`): every heading still to do, dated or not, grouped by keyword in the
 * order the notes declare them, the most important first in each. A tap on a group's keyword or on a
 * tag narrows to it, as in the agenda.
 */
@Composable
internal fun TodoListPage(
    agenda: AgendaIndex,
    today: LocalDate,
    filter: AgendaFilter,
    onFilterChange: (AgendaFilter) -> Unit,
    onBack: () -> Unit,
    onOpen: (TodoEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(agenda) { agenda.todos.grouped() }
    val keywordColors = rememberKeywordColors(agenda)
    Column(modifier.fillMaxSize().testTag("todoList")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
            Text(
                "${stringResource(Res.string.cal_todos)} · ${agenda.todos.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        ActiveFilter(filter, onFilterChange, Modifier.padding(horizontal = 4.dp))
        if (groups.isEmpty()) {
            Text(
                stringResource(Res.string.cal_todos_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for (group in groups) {
                item(key = "group-${group.keyword}") {
                    val color = keywordColors.of(group.keyword) ?: MaterialTheme.colorScheme.primary
                    val label = stringResource(Res.string.cal_filter_by, group.keyword)
                    Text(
                        "${group.keyword} · ${group.todos.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 4.dp)
                            .clickable(onClickLabel = label) { onFilterChange(filter.toggleKeyword(group.keyword)) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(group.todos, key = { "${it.file}-${it.headingOffset}" }) { todo ->
                    TodoRow(todo, today, keywordColors.of(todo.todo), filter, onFilterChange) { onOpen(todo) }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(
    todo: TodoEntry,
    today: LocalDate,
    keywordColor: Color?,
    filter: AgendaFilter,
    onFilterChange: (AgendaFilter) -> Unit,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .openOrMenu(onOpen) { menu = true }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryMenu(todo.file, todo.headingOffset, todo.todo, menu, onDismiss = { menu = false })
        Box(Modifier.width(3.dp).height(36.dp).background(keywordColor ?: MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            dueLabel(todo, today)?.let { (text, late) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                (todo.priority?.let { "[#${it.text}] " } ?: "") + buildOrgInlineString(todo.title, viewMode = true).text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (todo.tags.isNotEmpty()) {
                TagChips(
                    todo.tags,
                    Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    onTag = { onFilterChange(filter.toggleTag(it)) },
                    tagClickLabel = { stringResource(Res.string.cal_filter_by, it) },
                )
            }
        }
    }
}

/** When [todo] is due, and whether that has passed: its deadline before its scheduled day. */
@Composable
private fun dueLabel(todo: TodoEntry, today: LocalDate): Pair<String, Boolean>? {
    val deadline = todo.deadline
    val scheduled = todo.scheduled
    return when {
        deadline != null && deadline < today -> stringResource(Res.string.cal_status_overdue, deadline.daysUntil(today)) to true
        deadline != null -> "${stringResource(Res.string.cal_kind_deadline)} · ${dayLabel(deadline, today)}" to false
        scheduled != null && scheduled < today -> stringResource(Res.string.cal_status_scheduled_ago, scheduled.daysUntil(today)) to false
        scheduled != null -> "${stringResource(Res.string.cal_kind_scheduled)} · ${dayLabel(scheduled, today)}" to false
        else -> null
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String =
    if (date == today) stringResource(Res.string.cal_today) else shortDay(date)
