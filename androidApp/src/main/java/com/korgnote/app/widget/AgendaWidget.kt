package com.korgnote.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.korgnote.androidapp.R
import com.korgnote.app.ui.CalendarMode
import com.korgnote.app.ui.KeywordSwatches
import com.korgnote.app.ui.timeLabel
import com.korgnote.app.widgets.of
import com.korgnote.app.widgets.plainTitle
import com.korgnote.app.widgets.widgetAgenda
import com.korgnote.app.widgets.widgetKeywordSwatches
import com.korgnote.app.widgets.widgetSectionTitle
import com.korgnote.app.widgets.widgetSections
import com.korgnote.app.widgets.widgetStatus
import com.korgnote.common.org.agenda.AgendaOccurrence
import kotlinx.datetime.LocalDate

/**
 * The agenda on the home screen: what is overdue, then the week from today, as the app's agenda
 * list has it. An entry opens its note at its heading; the title opens the app's agenda.
 */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = WidgetState.load(context)
        WidgetClockReceiver.scheduleMidnight(context)
        provideContent {
            GlanceTheme { AgendaContent(context, currentWidgetState(context, first)) }
        }
    }

    /** The widget picker's picture of it, on a few made-up entries. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetState.preview(context)
        provideContent { GlanceTheme { AgendaContent(context, state) } }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

@Composable
private fun AgendaContent(context: Context, state: WidgetState) {
    val today = state.now.date
    val words = AndroidWidgetWords(context)
    WidgetFrame {
        WidgetHeader {
            WidgetTitle(
                context.getString(R.string.widget_agenda_title),
                actionStartActivity(WidgetIntents.openCalendar(context, CalendarMode.Agenda, today)),
                GlanceModifier.defaultWeight(),
            )
            SyncButton()
        }
        StatusLine(state)
        val entries = state.entries
        if (entries == null) {
            NotLoadedYet(context)
        } else {
            val agenda = widgetAgenda(entries, today)
            val sections = agenda.widgetSections(today)
            val swatches = agenda.widgetKeywordSwatches()
            LazyColumn(GlanceModifier.fillMaxWidth()) {
                for (section in sections) {
                    item { SectionTitle(widgetSectionTitle(section.date, today, words), section.date, today) }
                    if (section.occurrences.isEmpty()) {
                        item {
                            Text(
                                context.getString(R.string.widget_nothing_planned),
                                GlanceModifier.padding(bottom = 2.dp),
                                style = TextStyle(color = GlanceTheme.colors.outline, fontSize = 12.sp),
                            )
                        }
                    }
                    // Room for a time only in a section that has one, as in the app.
                    val timeWidth = if (section.occurrences.any { it.timeLabel() != null }) 76.dp else 0.dp
                    items(section.occurrences) { AgendaRow(context, it, today, timeWidth, swatches, words) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, date: LocalDate?, today: LocalDate) {
    Text(
        title,
        GlanceModifier.padding(top = 6.dp, bottom = 2.dp),
        style = TextStyle(
            color = when (date) {
                null -> GlanceTheme.colors.error
                today -> GlanceTheme.colors.primary
                else -> GlanceTheme.colors.onSurface
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun AgendaRow(
    context: Context,
    occurrence: AgendaOccurrence,
    today: LocalDate,
    timeWidth: Dp,
    swatches: KeywordSwatches,
    words: AndroidWidgetWords,
) {
    val accent = occurrence.entry.kind.accent()
    Row(
        GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(actionStartActivity(WidgetIntents.openNote(context, occurrence.entry.file, occurrence.entry.headingOffset))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.width(3.dp).height(30.dp).background(accent))
        Spacer(GlanceModifier.width(8.dp))
        if (timeWidth > 0.dp) {
            Text(
                occurrence.timeLabel().orEmpty(),
                GlanceModifier.width(timeWidth),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                maxLines = 1,
            )
        }
        Column(GlanceModifier.defaultWeight()) {
            // The keyword's label goes on the status line, leaving the title the row's whole width.
            val keyword = occurrence.entry.todo
            val swatch = swatches.of(occurrence)
            val status = occurrence.widgetStatus(today, words)
            if ((keyword != null && swatch != null) || status != null) {
                Row(GlanceModifier.padding(bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (keyword != null && swatch != null) {
                        KeywordLabel(keyword, swatch)
                        if (status != null) Spacer(GlanceModifier.width(6.dp))
                    }
                    status?.let {
                        Text(it, style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
            }
            Text(
                // A keyword the agenda does not know as one stays in the title, as the app shows it.
                if (keyword != null && swatch == null) "$keyword ${occurrence.plainTitle()}" else occurrence.plainTitle(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                maxLines = 2,
            )
        }
    }
}
