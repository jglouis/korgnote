package com.korgnote.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cal_kind_closed
import korgnote.composeapp.generated.resources.cal_kind_deadline
import korgnote.composeapp.generated.resources.cal_kind_scheduled
import korgnote.composeapp.generated.resources.cal_kind_timestamp
import korgnote.composeapp.generated.resources.cal_status_continues
import korgnote.composeapp.generated.resources.cal_status_due_in
import korgnote.composeapp.generated.resources.cal_status_ends
import korgnote.composeapp.generated.resources.cal_status_overdue
import korgnote.composeapp.generated.resources.cal_status_scheduled_ago
import korgnote.composeapp.generated.resources.cal_status_starts
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// --- How each kind looks -----------------------------------------------------------------------

/**
 * One colour per kind of date, used for the bar, the chip and the label alike, so that a deadline
 * reads as a deadline in every view. The icon says the same thing to anyone who cannot tell the
 * colours apart.
 */
@Composable
fun AgendaKind.accent(): Color = when (this) {
    AgendaKind.Deadline -> MaterialTheme.colorScheme.error
    AgendaKind.Scheduled -> MaterialTheme.colorScheme.primary
    AgendaKind.Timestamp -> MaterialTheme.colorScheme.tertiary
    AgendaKind.Closed -> MaterialTheme.colorScheme.outline
}

fun AgendaKind.icon(): ImageVector = when (this) {
    AgendaKind.Deadline -> Icons.Filled.Flag
    AgendaKind.Scheduled -> Icons.Filled.Schedule
    AgendaKind.Timestamp -> Icons.Filled.Event
    AgendaKind.Closed -> Icons.Filled.CheckCircle
}

/** The kind's name, for a screen reader where the icon stands for it. */
@Composable
fun AgendaKind.label(): String = stringResource(
    when (this) {
        AgendaKind.Deadline -> Res.string.cal_kind_deadline
        AgendaKind.Scheduled -> Res.string.cal_kind_scheduled
        AgendaKind.Timestamp -> Res.string.cal_kind_timestamp
        AgendaKind.Closed -> Res.string.cal_kind_closed
    },
)

/** The words for a status, and the number of days that goes in them. */
private val AgendaStatus.words: Pair<StringResource, Int?>
    get() = when (this) {
        AgendaStatus.Deadline -> Res.string.cal_kind_deadline to null
        is AgendaStatus.DueIn -> Res.string.cal_status_due_in to days
        is AgendaStatus.Overdue -> Res.string.cal_status_overdue to days
        is AgendaStatus.ScheduledAgo -> Res.string.cal_status_scheduled_ago to days
        AgendaStatus.Closed -> Res.string.cal_kind_closed to null
        AgendaStatus.Starts -> Res.string.cal_status_starts to null
        AgendaStatus.Continues -> Res.string.cal_status_continues to null
        AgendaStatus.Ends -> Res.string.cal_status_ends to null
    }

/** "Due in 3 d.", "Dans 3 j": the status in the language the app is shown in. */
@Composable
fun AgendaStatus.label(): String = words.let { (text, days) ->
    if (days == null) stringResource(text) else stringResource(text, days)
}

/** [label], away from composition: for a widget, say. */
suspend fun AgendaStatus.text(): String = words.let { (text, days) ->
    if (days == null) getString(text) else getString(text, days)
}

/**
 * The order entries are stacked in a cell or the all-day strip: multi-day ranges first, in the same
 * order on every day so each keeps its row and reads as one bar; then what matters most when there
 * is room for only a few — deadlines, then scheduled work, then appointments — and then by time,
 * then by priority.
 */
private val KIND_ORDER = listOf(AgendaKind.Deadline, AgendaKind.Scheduled, AgendaKind.Timestamp, AgendaKind.Closed)

internal val chipOrder = compareBy<AgendaOccurrence>(
    { it.span == DaySpan.Single },
    { if (it.span != DaySpan.Single) it.entry.stamp.start.toEpochDays() else 0L },
    { KIND_ORDER.indexOf(it.entry.kind) },
    { it.time?.let { t -> t.hour * 60 + t.minute } ?: -1 },
    { it.entry.priorityOrder },
    { it.entry.file },
    { it.entry.stamp.range.first },
)

/** Done entries stay visible but step back. */
internal fun Modifier.dimmedIfDone(occurrence: AgendaOccurrence) = if (occurrence.entry.done) alpha(0.45f) else this

/**
 * A colour for each TODO keyword the calendar shows. In the week and month grids a day is too
 * narrow for `TODO` or `EVENT` to leave any room for the title, so there the keyword is only an
 * edge in its colour; the agenda and the day view, which spell it out, write it in the same
 * colour, which is how the colours are learnt.
 *
 * The colours are [KeywordSwatches]', which the home-screen widget's labels share.
 */
class KeywordColors(keywords: Collection<String>) {
    private val swatches = KeywordSwatches(keywords)

    /** [entry]'s keyword colour, or null when it has no keyword. */
    fun of(entry: AgendaEntry): Color? {
        val keyword = entry.todo ?: return null
        if (entry.done) return OrgColors.done
        return swatches.of(keyword, done = false)?.color()
    }

    /** [keyword]'s colour as a keyword still to do, or null when the agenda has none open. */
    fun of(keyword: String): Color? = swatches.of(keyword, done = false)?.color()
}

@Composable
internal fun rememberKeywordColors(agenda: AgendaIndex) = remember(agenda.whole) {
    // From the whole agenda, so a filter does not move a keyword to another colour.
    KeywordColors(agenda.whole.entries.filter { !it.done }.mapNotNullTo(mutableSetOf()) { it.todo })
}

/** A bar down the left edge in [color], under what follows; nothing for no colour. */
internal fun Modifier.keywordEdge(color: Color?) =
    if (color == null) this else drawBehind { drawRect(color, size = Size(KEYWORD_EDGE.toPx(), size.height)) }

internal val KEYWORD_EDGE = 3.dp

/**
 * `TODO Title`, as the heading reads, with the priority cookie when there is one and the keyword
 * in [keywordColor]; or, [titleOnly], just the title, for where the keyword is an edge instead.
 *
 * The title goes through the same inline rendering as reading mode, so a link shows as its
 * description and emphasis as emphasis rather than as markup. The links themselves are left out:
 * tapping an entry opens it, and a link inside it must not take the tap.
 */
internal fun AgendaOccurrence.headline(
    prefix: String? = null,
    keywordColor: Color? = null,
    titleOnly: Boolean = false,
    /** What a tap on the keyword does, when it does something of its own: narrowing the agenda to it. */
    onKeyword: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    if (!titleOnly) {
        prefix?.let { append("$it ") }
        entry.todo?.let { todo ->
            if (onKeyword != null) {
                // Styled as the keyword, not as a link: the agenda would otherwise be underlined throughout.
                val plain = TextLinkStyles(SpanStyle(color = keywordColor ?: Color.Unspecified, textDecoration = TextDecoration.None))
                withLink(LinkAnnotation.Clickable(KEYWORD_LINK, styles = plain) { onKeyword(todo) }) { append(todo) }
            } else {
                withStyle(SpanStyle(color = keywordColor ?: Color.Unspecified)) { append(todo) }
            }
            append(" ")
        }
        entry.priority?.let { append("[#${it.text}] ") }
    }
    val title = buildOrgInlineString(entry.title, viewMode = true)
    append(AnnotatedString(title.text, title.spanStyles))
}

/** The tag of the link a keyword is drawn as where a tap on it filters by it. */
internal const val KEYWORD_LINK = "agenda-keyword"

/** The colour of clocked time, set apart from the kinds of date: work done, not work planned. */
@Composable
internal fun clockedAccent(): Color = MaterialTheme.colorScheme.secondary

/** The keyword a title-only entry leaves out, still read out by a screen reader. */
internal fun Modifier.keywordDescription(occurrence: AgendaOccurrence, titleOnly: Boolean): Modifier {
    val keyword = occurrence.entry.todo?.takeIf { titleOnly } ?: return this
    val title = buildOrgInlineString(occurrence.entry.title, viewMode = true).text
    return semantics { contentDescription = "$keyword $title" }
}
