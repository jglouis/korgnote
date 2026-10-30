package com.korgnote.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.korgnote.androidapp.R
import com.korgnote.app.ui.TagColors
import com.korgnote.app.widgets.WidgetWords
import com.korgnote.app.widgets.updatedLabel
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.DateFormatSymbols
import kotlin.time.Clock

/** What a widget draws from, as [WidgetStore] held it at [WidgetStore.changes] [version]. */
class WidgetState(
    val version: Int,
    /** The agenda's entries; null when the app has not handed any over yet. */
    val entries: List<AgendaEntry>?,
    val now: LocalDateTime,
    /** How fresh the notes are, or that they are being synced, or why they could not be. */
    val status: String,
    val problem: Boolean,
) {
    companion object {
        suspend fun load(context: Context): WidgetState {
            val version = WidgetStore.changes.value
            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now().toLocalDateTime(zone)
            val problem = WidgetStore.problem(context)?.let { problemText(context, it) }
            val updated = updatedLabel(WidgetStore.lastSynced(context)?.toLocalDateTime(zone), now, AndroidWidgetWords(context))
            return WidgetState(
                version = version,
                entries = WidgetStore.entries(context),
                now = now,
                status = when {
                    WidgetStore.syncing(context) -> context.getString(R.string.widget_syncing)
                    problem != null -> "$problem · $updated"
                    else -> updated
                },
                problem = problem != null && !WidgetStore.syncing(context),
            )
        }

        /**
         * A made-up agenda around today, in the phone's language, for the widget picker to show
         * what a widget looks like before it is placed.
         */
        fun preview(context: Context): WidgetState {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val today = now.date
            fun stamp(daysFromToday: Int, time: String = ""): String {
                val date = today.plus(daysFromToday, DateTimeUnit.DAY)
                return "<$date ${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }}$time>"
            }
            val text = """
                #+TODO: TODO NEXT | DONE
                * TODO [#A] ${context.getString(R.string.widget_preview_entry_1)}
                DEADLINE: ${stamp(0)}
                * ${context.getString(R.string.widget_preview_entry_2)} ${stamp(0, " 10:00-11:00")}
                * NEXT ${context.getString(R.string.widget_preview_entry_4)}
                SCHEDULED: ${stamp(1)}
                * ${context.getString(R.string.widget_preview_entry_3)} ${stamp(2, " 14:30")}
                * TODO ${context.getString(R.string.widget_preview_entry_1)}
                SCHEDULED: ${stamp(9)}
            """.trimIndent()
            return WidgetState(
                version = -1,
                entries = agendaEntries(mapOf("preview.org" to text)),
                now = now,
                status = context.getString(R.string.widget_preview_status),
                problem = false,
            )
        }
    }
}

/** What a widget's last sync ran into, in the phone's language; as kept, if an older version kept words. */
private fun problemText(context: Context, problem: String): String = when (problem) {
    WidgetStore.PROBLEM_SETUP -> context.getString(R.string.widget_problem_setup)
    WidgetStore.PROBLEM_SERVER -> context.getString(R.string.widget_problem_server)
    WidgetStore.PROBLEM_FOLDER -> context.getString(R.string.widget_problem_folder)
    else -> problem
}

/** The widgets' words, from the app's resources, and the dates' from the phone's language. */
class AndroidWidgetWords(private val context: Context) : WidgetWords {
    private val symbols = DateFormatSymbols.getInstance(context.resources.configuration.locales[0])

    override val overdue get() = context.getString(R.string.widget_overdue)
    override val today get() = context.getString(R.string.widget_today)
    override val tomorrow get() = context.getString(R.string.widget_tomorrow)
    override val notSyncedYet get() = context.getString(R.string.widget_not_synced_yet)
    override fun updated(whenSynced: String) = context.getString(R.string.widget_updated, whenSynced)
    override fun updatedYesterday(time: String) = context.getString(R.string.widget_updated_yesterday, time)

    // Sunday first in java.util.Calendar's numbering, Monday first in ours.
    override fun weekdayShort(day: DayOfWeek) = symbols.shortWeekdays[day.isoDayNumber % 7 + 1]
    override fun month(month: Month) = symbols.months[month.ordinal].replaceFirstChar { it.titlecase() }
    override fun monthShort(month: Month): String = symbols.shortMonths[month.ordinal]
    override val deadline get() = context.getString(R.string.widget_deadline)
    override fun dueIn(days: Int) = context.getString(R.string.widget_due_in, days)
    override fun overdueBy(days: Int) = context.getString(R.string.widget_overdue_by, days)
    override fun scheduledAgo(days: Int) = context.getString(R.string.widget_scheduled_ago, days)
    override val closed get() = context.getString(R.string.widget_closed)
    override val starts get() = context.getString(R.string.widget_starts)
    override val continues get() = context.getString(R.string.widget_continues)
    override val ends get() = context.getString(R.string.widget_ends)
}

/**
 * [first], read again each time [WidgetStore] changes for as long as Glance keeps the widget's
 * session open. Within it, an update only recomposes — `provideGlance`, where [first] was read, is
 * not asked again — and the widget would go on showing what it read then.
 */
@Composable
fun currentWidgetState(context: Context, first: WidgetState): WidgetState {
    val version by WidgetStore.changes.collectAsState()
    val state by produceState(first, version) {
        if (version != first.version) value = WidgetState.load(context)
    }
    return state
}

/** The card a widget is drawn on, in the wallpaper's colours where Android has them (12 and on). */
@Composable
fun WidgetFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        content = content,
    )
}

/** A widget's first row: its title and buttons, centred on one line. */
@Composable
fun WidgetHeader(content: @Composable RowScope.() -> Unit) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** The widget's title, which opens the app's calendar at the same place. */
@Composable
fun WidgetTitle(text: String, onClick: Action, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text,
        modifier.clickable(onClick),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

/** How fresh the notes are: the second line of every widget. */
@Composable
fun StatusLine(state: WidgetState) {
    Text(
        state.status,
        GlanceModifier.padding(bottom = 4.dp),
        style = TextStyle(
            color = if (state.problem) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
        maxLines = 1,
    )
}

/** An icon that does [onClick], big enough to tap. */
@Composable
fun WidgetIcon(icon: Int, description: String, onClick: Action) {
    Image(
        ImageProvider(icon),
        contentDescription = description,
        modifier = GlanceModifier.size(40.dp).padding(8.dp).cornerRadius(20.dp).clickable(onClick),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
    )
}

/** Syncs the notes and redraws the widgets, the app closed or not. */
@Composable
fun SyncButton() =
    WidgetIcon(R.drawable.ic_widget_sync, LocalContext.current.getString(R.string.widget_sync), actionRunCallback<SyncFromWidget>())

/** Said, and tappable, until the app has handed the widgets an agenda. */
@Composable
fun NotLoadedYet(context: Context) {
    Text(
        context.getString(R.string.widget_open_once),
        GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(actionStartActivity(WidgetIntents.openApp(context))),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

/**
 * A TODO keyword as a label of its own, in its colour as the app's agenda has it: a widget's text
 * takes one colour, so the keyword cannot be coloured within the title.
 */
@Composable
fun KeywordLabel(keyword: String, swatch: TagColors.Swatch) {
    val light = Color(0xFF000000.toInt() or swatch.light)
    val dark = Color(0xFF000000.toInt() or swatch.dark)
    Text(
        keyword,
        GlanceModifier
            .background(ColorProvider(day = light.copy(alpha = 0.16f), night = dark.copy(alpha = 0.24f)))
            .cornerRadius(4.dp)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = TextStyle(color = ColorProvider(day = light, night = dark), fontSize = 10.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

/** The colour each kind of date is marked in, as the app's calendar marks it. */
@Composable
fun AgendaKind.accent(): ColorProvider = when (this) {
    AgendaKind.Deadline -> GlanceTheme.colors.error
    AgendaKind.Scheduled -> GlanceTheme.colors.primary
    AgendaKind.Timestamp -> GlanceTheme.colors.tertiary
    AgendaKind.Closed -> GlanceTheme.colors.outline
}
