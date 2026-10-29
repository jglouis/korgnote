package com.korgnote.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.glance.unit.ColorProvider
import com.korgnote.androidapp.R
import com.korgnote.app.widgets.updatedLabel
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
            val problem = WidgetStore.problem(context)
            val updated = updatedLabel(WidgetStore.lastSynced(context)?.toLocalDateTime(zone), now)
            return WidgetState(
                version = version,
                entries = WidgetStore.entries(context),
                now = now,
                status = when {
                    WidgetStore.syncing(context) -> "Syncing…"
                    problem != null -> "$problem · $updated"
                    else -> updated
                },
                problem = problem != null && !WidgetStore.syncing(context),
            )
        }
    }
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
        modifier = GlanceModifier.size(36.dp).padding(6.dp).cornerRadius(18.dp).clickable(onClick),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
    )
}

/** Syncs the notes and redraws the widgets, the app closed or not. */
@Composable
fun SyncButton() = WidgetIcon(R.drawable.ic_widget_sync, "Sync", actionRunCallback<SyncFromWidget>())

/** Said, and tappable, until the app has handed the widgets an agenda. */
@Composable
fun NotLoadedYet(context: Context) {
    Text(
        "Open KorgNote once to show your agenda here.",
        GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(actionStartActivity(WidgetIntents.openApp(context))),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
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
