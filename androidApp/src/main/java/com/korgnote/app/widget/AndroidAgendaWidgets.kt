package com.korgnote.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.korgnote.app.widgets.AgendaWidgets
import com.korgnote.common.org.agenda.AgendaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The app's agenda, handed to the home-screen widgets: kept in [WidgetStore], then each widget
 * redrawn from it.
 *
 * Put off for a moment and only the last of a burst kept: saving a note hands the agenda over, and
 * a widget redrawn for each of several saves in a row would only flicker.
 */
class AndroidAgendaWidgets(context: Context) : AgendaWidgets {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    override fun show(entries: List<AgendaEntry>, lastSynced: Instant?) {
        pending?.cancel()
        pending = scope.launch {
            delay(1.seconds)
            // Once begun, finished: the next one waits for the store rather than cutting this short.
            withContext(NonCancellable) {
                WidgetStore.save(app, entries, lastSynced)
                redrawWidgets(app)
            }
        }
    }
}

/**
 * Redraws every agenda and calendar widget on the home screen from what [WidgetStore] holds now, and
 * as of now: a widget whose session is open reads it again, one whose session is closed opens one.
 */
suspend fun redrawWidgets(context: Context) {
    WidgetStore.changed()
    AgendaWidget().updateAll(context)
    CalendarWidget().updateAll(context)
}
