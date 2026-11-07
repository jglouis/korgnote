package com.korgnote.app.viewmodel

import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.template.newNoteText
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.tour_app_calendar
import korgnote.composeapp.generated.resources.tour_app_capture
import korgnote.composeapp.generated.resources.tour_app_drawer
import korgnote.composeapp.generated.resources.tour_app_heading
import korgnote.composeapp.generated.resources.tour_app_search
import korgnote.composeapp.generated.resources.tour_app_settings
import korgnote.composeapp.generated.resources.tour_box_1
import korgnote.composeapp.generated.resources.tour_box_2
import korgnote.composeapp.generated.resources.tour_box_3
import korgnote.composeapp.generated.resources.tour_boxes_heading
import korgnote.composeapp.generated.resources.tour_calendar_body
import korgnote.composeapp.generated.resources.tour_calendar_heading
import korgnote.composeapp.generated.resources.tour_edit_body
import korgnote.composeapp.generated.resources.tour_edit_heading
import korgnote.composeapp.generated.resources.tour_fold_body
import korgnote.composeapp.generated.resources.tour_fold_child
import korgnote.composeapp.generated.resources.tour_fold_heading
import korgnote.composeapp.generated.resources.tour_hold_body
import korgnote.composeapp.generated.resources.tour_hold_heading
import korgnote.composeapp.generated.resources.tour_intro
import korgnote.composeapp.generated.resources.tour_links_body
import korgnote.composeapp.generated.resources.tour_links_heading
import korgnote.composeapp.generated.resources.tour_links_table
import korgnote.composeapp.generated.resources.tour_links_web
import korgnote.composeapp.generated.resources.tour_table_apples
import korgnote.composeapp.generated.resources.tour_table_body
import korgnote.composeapp.generated.resources.tour_table_count
import korgnote.composeapp.generated.resources.tour_table_fruit
import korgnote.composeapp.generated.resources.tour_table_heading
import korgnote.composeapp.generated.resources.tour_table_pears
import korgnote.composeapp.generated.resources.tour_table_total
import korgnote.composeapp.generated.resources.tour_title
import korgnote.composeapp.generated.resources.tour_todo_body
import korgnote.composeapp.generated.resources.tour_todo_heading
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.getString

/**
 * The words of the tour note, in the app's language. [tourNoteText] puts the Org around them, so
 * that a translation cannot break a table, a link or a date.
 */
data class TourWords(
    val title: String,
    val intro: String,
    val todoHeading: String,
    val todoBody: String,
    val editHeading: String,
    val editBody: String,
    val holdHeading: String,
    val holdBody: String,
    val boxesHeading: String,
    val boxes: List<String>,
    val calendarHeading: String,
    val calendarBody: String,
    val tableHeading: String,
    val tableBody: String,
    /** Header, the two rows, and the total's label. */
    val tableFruit: String,
    val tableCount: String,
    val tableApples: String,
    val tablePears: String,
    val tableTotal: String,
    val foldHeading: String,
    val foldBody: String,
    val foldChild: String,
    val linksHeading: String,
    /** With `%1$s` for the link to the table, `%2$s` for the one to orgmode.org. */
    val linksBody: String,
    val linksTable: String,
    val linksWeb: String,
    val appHeading: String,
    val appItems: List<String>,
)

suspend fun tourWords(): TourWords = TourWords(
    title = getString(Res.string.tour_title),
    intro = getString(Res.string.tour_intro),
    todoHeading = getString(Res.string.tour_todo_heading),
    todoBody = getString(Res.string.tour_todo_body),
    editHeading = getString(Res.string.tour_edit_heading),
    editBody = getString(Res.string.tour_edit_body),
    holdHeading = getString(Res.string.tour_hold_heading),
    holdBody = getString(Res.string.tour_hold_body),
    boxesHeading = getString(Res.string.tour_boxes_heading),
    boxes = listOf(getString(Res.string.tour_box_1), getString(Res.string.tour_box_2), getString(Res.string.tour_box_3)),
    calendarHeading = getString(Res.string.tour_calendar_heading),
    calendarBody = getString(Res.string.tour_calendar_body),
    tableHeading = getString(Res.string.tour_table_heading),
    tableBody = getString(Res.string.tour_table_body),
    tableFruit = getString(Res.string.tour_table_fruit),
    tableCount = getString(Res.string.tour_table_count),
    tableApples = getString(Res.string.tour_table_apples),
    tablePears = getString(Res.string.tour_table_pears),
    tableTotal = getString(Res.string.tour_table_total),
    foldHeading = getString(Res.string.tour_fold_heading),
    foldBody = getString(Res.string.tour_fold_body),
    foldChild = getString(Res.string.tour_fold_child),
    linksHeading = getString(Res.string.tour_links_heading),
    linksBody = getString(Res.string.tour_links_body),
    linksTable = getString(Res.string.tour_links_table),
    linksWeb = getString(Res.string.tour_links_web),
    appHeading = getString(Res.string.tour_app_heading),
    appItems = listOf(
        getString(Res.string.tour_app_drawer),
        getString(Res.string.tour_app_search),
        getString(Res.string.tour_app_calendar),
        getString(Res.string.tour_app_capture),
        getString(Res.string.tour_app_settings),
    ),
)

/**
 * The tour note: a note headed as any new one is, whose headings each ask for one tap or hold and
 * say what it does. Learning by doing, on a note that is the user's own to tick through and delete.
 *
 * Its date is [today]'s, so that the calendar's heading is in today's agenda when the date is tapped.
 * The date has no time: a reminder from a tour would be a surprise.
 */
fun tourNoteText(words: TourWords, id: String, today: LocalDate): String = buildString {
    append(newNoteText(id = id, title = words.title, date = today))
    append(words.intro).append("\n\n")

    heading("TODO ${words.todoHeading}", words.todoBody)
    heading(words.editHeading, words.editBody)
    heading(words.holdHeading, words.holdBody)

    append("* ").append(words.boxesHeading).append(" [0/").append(words.boxes.size).append("]\n")
    words.boxes.forEach { append("- [ ] ").append(it).append('\n') }
    append('\n')

    append("* ").append(words.calendarHeading).append('\n')
    append("SCHEDULED: ").append(today.orgStamp(active = true)).append('\n')
    append(words.calendarBody).append("\n\n")

    append("* ").append(words.tableHeading).append('\n')
    append(words.tableBody).append("\n\n")
    append(
        alignedTable(
            listOf(words.tableFruit, words.tableCount),
            null,
            listOf(words.tableApples, "3"),
            listOf(words.tablePears, "2"),
            null,
            listOf(words.tableTotal, "5"),
        ),
    )
    append("#+TBLFM: @>$2=vsum(@I..@II)\n\n")

    heading(words.foldHeading, words.foldBody)
    append("** ").append(words.foldChild).append("\n\n")

    val toTable = "[[*${words.tableHeading}][${words.linksTable}]]"
    val toWeb = "[[https://orgmode.org][${words.linksWeb}]]"
    heading(words.linksHeading, words.linksBody.replace("%1\$s", toTable).replace("%2\$s", toWeb))

    append("* ").append(words.appHeading).append('\n')
    words.appItems.forEach { append("- ").append(it).append('\n') }
}

private fun StringBuilder.heading(title: String, body: String) {
    append("* ").append(title).append('\n').append(body).append("\n\n")
}

/** [rows] as an Org table aligned as Emacs aligns one, a null being a rule; numbers to the right. */
private fun alignedTable(vararg rows: List<String>?): String {
    val widths = rows.filterNotNull().fold(IntArray(2)) { acc, row ->
        row.forEachIndexed { i, cell -> acc[i] = maxOf(acc[i], cell.length) }
        acc
    }
    return rows.joinToString("") { row ->
        if (row == null) {
            widths.joinToString("+", prefix = "|", postfix = "|\n") { "-".repeat(it + 2) }
        } else {
            row.mapIndexed { i, cell ->
                if (cell.all { it.isDigit() }) cell.padStart(widths[i]) else cell.padEnd(widths[i])
            }.joinToString(" | ", prefix = "| ", postfix = " |\n")
        }
    }
}
