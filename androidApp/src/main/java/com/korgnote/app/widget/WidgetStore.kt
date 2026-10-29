package com.korgnote.app.widget

import android.content.Context
import androidx.core.content.edit
import com.korgnote.common.org.agenda.AgendaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What the widgets draw from, kept where they can read it with the app closed: the agenda's entries
 * in a file, as the app last handed them over or a widget's sync last read them, and in preferences
 * when the notes last matched where they are kept, and how the last sync from a widget went.
 */
object WidgetStore {
    private const val ENTRIES = "agenda-widget.json"
    private const val PREFS = "korgnote_widgets"
    private const val KEY_SYNCED = "lastSynced"
    private const val KEY_SYNCING = "syncing"
    private const val KEY_PROBLEM = "problem"

    private val serializer = ListSerializer(AgendaEntry.serializer())

    /** Entries an older version wrote may carry what this one no longer reads; the rest still serves. */
    private val json = Json { ignoreUnknownKeys = true }

    /** The app and a widget's sync can both write: one at a time, so neither half-writes over the other. */
    private val writing = Mutex()

    private val _changes = MutableStateFlow(0)

    /**
     * Counts each change to what is kept, and each new day, for a widget to read it all again: while
     * Glance keeps a widget's session open, a redraw recomposes it without reading anything anew.
     */
    val changes: StateFlow<Int> = _changes

    /** Something the widgets show changed: what is kept, or the day. */
    fun changed() {
        _changes.value++
    }

    /**
     * Keeps [entries], and [lastSynced] when it is later than what is kept: the app knows only the
     * syncs of this run, and one it has not made yet must not make the widgets forget an earlier one.
     */
    suspend fun save(context: Context, entries: List<AgendaEntry>, lastSynced: Instant?) = writing.withLock {
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, ENTRIES)
            // Written beside and moved over, so a widget reading meanwhile reads the old or the new.
            val next = File(context.filesDir, "$ENTRIES.new")
            next.writeText(json.encodeToString(serializer, entries))
            next.renameTo(file)
            if (lastSynced != null && lastSynced > (lastSynced(context) ?: Instant.DISTANT_PAST)) {
                // A sync got through since: whatever stopped a widget's last one is over.
                prefs(context).edit {
                    putLong(KEY_SYNCED, lastSynced.toEpochMilliseconds())
                    remove(KEY_PROBLEM)
                }
            }
            changed()
        }
    }

    /** The entries last kept; null when there are none to read — the app has not run since installed. */
    suspend fun entries(context: Context): List<AgendaEntry>? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, ENTRIES)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    fun lastSynced(context: Context): Instant? =
        prefs(context).getLong(KEY_SYNCED, 0L).takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds)

    /**
     * True while a widget's sync runs, for the widgets to say so rather than show the old time. Kept
     * as when it started, so a sync whose process was killed stops being shown after a while
     * instead of for good.
     */
    fun syncing(context: Context): Boolean {
        val started = prefs(context).getLong(KEY_SYNCING, 0L)
        return started > 0L && Clock.System.now() - Instant.fromEpochMilliseconds(started) < 2.minutes
    }

    fun setSyncing(context: Context, syncing: Boolean) {
        prefs(context).edit {
            if (syncing) putLong(KEY_SYNCING, Clock.System.now().toEpochMilliseconds()) else remove(KEY_SYNCING)
        }
        changed()
    }

    /** Why a widget's last sync did not get through, or null when it did. */
    fun problem(context: Context): String? = prefs(context).getString(KEY_PROBLEM, null)

    fun setProblem(context: Context, problem: String?) {
        prefs(context).edit { putString(KEY_PROBLEM, problem) }
        changed()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
