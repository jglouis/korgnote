package com.korgnote.common.org.agenda

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgPriority
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.prioritiesOf
import com.korgnote.common.org.search.fileTagsOf
import com.korgnote.common.org.sliceOf
import com.korgnote.common.org.todoKeywordsOf
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * A heading whose keyword is still to do, dated or not: a line of org-agenda's TODO list (`t`).
 *
 * The agenda's entries are only the dated headings; a task with no date is never on a day, and this is
 * where it is found.
 */
@Serializable
data class TodoEntry(
    val file: String,
    val headingOffset: Int,
    val title: String,
    val todo: String,
    /** Where [todo] comes in its file's `#+TODO:` sequence, so `TODO NEXT` lists TODO first, as declared. */
    val keywordOrder: Int = 0,
    /** The heading's own tags, then those it inherits, as an [AgendaEntry]'s. */
    val tags: List<String> = emptyList(),
    val priority: OrgPriority? = null,
    val priorityOrder: Int = 0,
    /** The day its active `SCHEDULED:` stamp starts, if it has one. */
    val scheduled: LocalDate? = null,
    /** The day its active `DEADLINE:` stamp falls on, if it has one. */
    val deadline: LocalDate? = null,
) {
    /** The date it is due by, or to be started on: its deadline before its scheduled day. */
    val due: LocalDate? get() = deadline ?: scheduled
}

/**
 * Every heading in [text] whose keyword is one still to do, in document order. Skips what the agenda
 * skips: a commented-out or archived subtree.
 */
fun openTodos(path: String, text: String, fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): List<TodoEntry> {
    val todoConfig = todoKeywordsOf(text, fallback)
    // Most notes have no task in them: a word that is not there cannot start a heading.
    if (todoConfig.todo.none { text.contains(it) }) return emptyList()
    val order = todoConfig.todo.withIndex().associate { (i, keyword) -> keyword to i }
    val priorities = prioritiesOf(text)
    val fileTags = fileTagsOf(text)
    data class Ancestor(val level: Int, val tags: List<String>, val commented: Boolean)
    val ancestors = mutableListOf<Ancestor>()
    val todos = mutableListOf<TodoEntry>()

    for (element in text.orgElements()) {
        if (element.isPreamble) continue
        val slice = element.sliceOf(text)
        val heading = orgHeadingOf(slice.substringBefore('\n'), todoConfig)
        while (ancestors.isNotEmpty() && ancestors.last().level >= element.level) ancestors.removeAt(ancestors.size - 1)
        ancestors += Ancestor(element.level, heading.tags, OrgSyntax.isCommentedTitle(heading.title))
        val keyword = heading.todo ?: continue
        if (keyword !in todoConfig.todo) continue
        val tags = (heading.tags + ancestors.dropLast(1).flatMap { it.tags } + fileTags).distinct()
        if (ancestors.any { it.commented } || OrgSyntax.ARCHIVE_TAG in tags) continue

        var scheduled: LocalDate? = null
        var deadline: LocalDate? = null
        forEachStampedLine(slice) { _, line, isHeadingLine ->
            if (isHeadingLine || !OrgSyntax.planning.containsMatchIn(line)) return@forEachStampedLine
            val keywords = OrgSyntax.planningKeyword.findAll(line).toList()
            keywords.forEachIndexed { i, kw ->
                val segmentEnd = keywords.getOrNull(i + 1)?.range?.first ?: line.length
                val stamp = line.substring(kw.range.last + 1, segmentEnd).orgTimestamps(0).firstOrNull()
                    ?.takeIf { it.active } ?: return@forEachIndexed
                when (kw.groupValues[1]) {
                    "SCHEDULED" -> if (scheduled == null) scheduled = stamp.start
                    "DEADLINE" -> if (deadline == null) deadline = stamp.start
                }
            }
        }
        todos += TodoEntry(
            file = path,
            headingOffset = element.start,
            title = heading.title.withoutStamps(),
            todo = keyword,
            keywordOrder = order[keyword] ?: 0,
            tags = tags,
            priority = heading.priority,
            priorityOrder = priorities.orderOf(heading.priority),
            scheduled = scheduled,
            deadline = deadline,
        )
    }
    return todos
}

/** A group of the TODO list: one keyword and its headings. */
data class TodoGroup(val keyword: String, val todos: List<TodoEntry>)

/**
 * [todos] grouped by keyword, the groups in the order the notes declare their keywords and then by
 * name, and each group most important first, then soonest due, then by file and position: Org's
 * `todo-state-up priority-down`, with the due date to break ties.
 */
fun List<TodoEntry>.grouped(): List<TodoGroup> =
    groupBy { it.todo }
        .map { (keyword, todos) ->
            TodoGroup(
                keyword,
                todos.sortedWith(
                    compareBy<TodoEntry> { it.priorityOrder }
                        .thenBy(nullsLast()) { it.due }
                        .thenBy { it.file }
                        .thenBy { it.headingOffset },
                ),
            )
        }
        .sortedWith(compareBy<TodoGroup> { group -> group.todos.minOf { it.keywordOrder } }.thenBy { it.keyword })
