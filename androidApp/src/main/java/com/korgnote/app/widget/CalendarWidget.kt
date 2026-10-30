package com.korgnote.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.korgnote.androidapp.R
import com.korgnote.app.ui.CalendarMode
import com.korgnote.app.ui.monthWeeks
import com.korgnote.app.widgets.monthTitle
import com.korgnote.app.widgets.widgetAgenda
import com.korgnote.app.widgets.widgetCount
import com.korgnote.common.org.agenda.AgendaIndex
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The month on the home screen: each day with a dot for each of its entries, up to three, and
 * today marked. A day opens the app's calendar on it; the title, the app's month. The arrows
 * move this widget's month alone, and Today brings it back.
 */
class CalendarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = WidgetState.load(context)
        WidgetClockReceiver.scheduleMidnight(context)
        provideContent {
            val state = currentWidgetState(context, first)
            // Kept per widget, as the first of the month; none means this month, whichever it is.
            val shown = currentState(SHOWN_MONTH)?.let(LocalDate::parse) ?: state.now.date
            GlanceTheme { CalendarContent(context, state, shown) }
        }
    }

    /** The widget picker's picture of it, on a few made-up entries. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetState.preview(context)
        provideContent { GlanceTheme { CalendarContent(context, state, state.now.date) } }
    }

    companion object {
        internal val SHOWN_MONTH = stringPreferencesKey("shownMonth")
    }
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}

/**
 * The arrows and Today: shows another month in the one widget they were tapped on.
 *
 * Drawn here and now, and handed to the launcher straight away. Glance redraws a widget from a
 * WorkManager job, which a phone may put off for a long while when the app has not been opened
 * lately — and a tap that seems to do nothing is a broken button. The job is asked for all the
 * same, so that a session still open goes on from the new month rather than the old one.
 */
class ShowMonth : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val month = parameters[MONTH]
        updateAppWidgetState(context, glanceId) { prefs ->
            if (month.isNullOrEmpty()) prefs.remove(CalendarWidget.SHOWN_MONTH) else prefs[CalendarWidget.SHOWN_MONTH] = month
        }
        val widget = CalendarWidget()
        runCatching { widget.drawNow(context, glanceId) }
            .onFailure { println("[DEBUG_LOG][CalendarWidget] Drawing the month at once failed: ${it.message}") }
        widget.update(context, glanceId)
    }

    companion object {
        /** The first of the month to show; empty for this month. */
        val MONTH = ActionParameters.Key<String>("month")

        fun to(month: LocalDate?) =
            actionRunCallback<ShowMonth>(actionParametersOf(MONTH to (month?.let { LocalDate(it.year, it.month, 1).toString() } ?: "")))
    }
}

/**
 * Draws [glanceId]'s widget as it now stands and hands it to the launcher, without the session a
 * Glance update waits for. Only for a widget with no list in it: a list's rows need the session.
 */
private suspend fun GlanceAppWidget.drawNow(context: Context, glanceId: GlanceId) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val launcher = AppWidgetManager.getInstance(context)
    val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
    val views = compose(context, glanceId, options = launcher.getAppWidgetOptions(appWidgetId), state = state)
    launcher.updateAppWidget(appWidgetId, views)
}

@Composable
private fun CalendarContent(context: Context, state: WidgetState, shown: LocalDate) {
    val today = state.now.date
    val thisMonth = shown.year == today.year && shown.month == today.month
    val agenda = state.entries?.let { widgetAgenda(it, today) }
    val words = AndroidWidgetWords(context)
    WidgetFrame {
        WidgetHeader {
            WidgetIcon(
                R.drawable.ic_widget_previous,
                context.getString(R.string.widget_previous_month),
                ShowMonth.to(shown.minus(1, DateTimeUnit.MONTH)),
            )
            WidgetTitle(
                monthTitle(shown, words),
                actionStartActivity(WidgetIntents.openCalendar(context, CalendarMode.Month, if (thisMonth) today else shown)),
                GlanceModifier.defaultWeight().padding(horizontal = 4.dp),
            )
            // An icon rather than the word, which left the month's name no room.
            if (!thisMonth) WidgetIcon(R.drawable.ic_widget_today, context.getString(R.string.widget_this_month), ShowMonth.to(null))
            WidgetIcon(R.drawable.ic_widget_next, context.getString(R.string.widget_next_month), ShowMonth.to(shown.plus(1, DateTimeUnit.MONTH)))
            SyncButton()
        }
        StatusLine(state)
        if (agenda == null) {
            NotLoadedYet(context)
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                for (day in DayOfWeek.entries.map { words.weekdayShort(it).take(1).uppercase() }) {
                    Text(
                        day,
                        GlanceModifier.defaultWeight(),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center),
                    )
                }
            }
            for (week in monthWeeks(shown)) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    for (date in week) DayCell(context, agenda, date, today, inMonth = date.month == shown.month)
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(context: Context, agenda: AgendaIndex, date: LocalDate, today: LocalDate, inMonth: Boolean) {
    val isToday = date == today
    val count = agenda.widgetCount(date)
    Column(
        GlanceModifier
            .defaultWeight()
            .fillMaxHeight()
            .clickable(actionStartActivity(WidgetIntents.openCalendar(context, CalendarMode.Day, date))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            if (isToday) GlanceModifier.size(24.dp).background(GlanceTheme.colors.primary).cornerRadius(12.dp)
            else GlanceModifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.day.toString(),
                style = TextStyle(
                    color = when {
                        isToday -> GlanceTheme.colors.onPrimary
                        inMonth -> GlanceTheme.colors.onSurface
                        else -> GlanceTheme.colors.outline
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        if (count > 0) {
            Text(
                "•".repeat(count.coerceAtMost(3)),
                style = TextStyle(
                    color = if (inMonth) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}
