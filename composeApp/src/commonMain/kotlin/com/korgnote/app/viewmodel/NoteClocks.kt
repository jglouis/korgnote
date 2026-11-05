package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.index.NoteFacts
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.edit.runningClocks
import kotlinx.datetime.LocalDateTime

/** A clock running in one of the notes: in which, on which heading, and since when. */
data class ClockRunning(val path: String, val headingStart: Int, val title: String, val start: LocalDateTime)

/** A heading clocked before, to clock back into: in which note, where, and when its clock last stopped. */
data class ClockRecent(val path: String, val headingStart: Int, val title: String, val end: LocalDateTime)

/** How many recently clocked headings are offered to clock back into. */
const val RECENT_CLOCKS = 5

/**
 * Where the running clock is shown outside the app: Android's ongoing notification, or nowhere.
 * Told each time the clocks running change, with the newest first; an empty list takes it away.
 */
interface ClockNotifier {
    fun show(clocks: List<ClockRunning>)
}

object NoClockNotifier : ClockNotifier {
    override fun show(clocks: List<ClockRunning>) = Unit
}

/** The clocks running in [documents], newest first. */
fun runningClocksIn(documents: Map<String, OrgDocument>): List<ClockRunning> =
    documents.flatMap { (path, doc) ->
        doc.runningClocks().map { ClockRunning(path, it.headingStart, it.title, it.start) }
    }.sortedWith(compareByDescending<ClockRunning> { it.start }.thenBy { it.path })

/** [runningClocksIn], from what is known about the notes: no text needs reading. */
fun runningClocksFrom(facts: Map<String, NoteFacts>): List<ClockRunning> =
    facts.flatMap { (path, note) ->
        note.running.map { ClockRunning(path, it.headingStart, it.title, it.start) }
    }.sortedWith(compareByDescending<ClockRunning> { it.start }.thenBy { it.path })

/**
 * The clocks running across the notes, read from the notes themselves: a running clock is an open
 * `CLOCK:` line, so one started in Emacs, or on another device, shows here once it has synced.
 *
 * There is normally one. Two run when two devices clocked in while apart, and then every one is
 * listed, so that each can be stopped.
 */
class NoteClocks(
    private val library: NoteLibrary,
    private val notifier: ClockNotifier = NoClockNotifier,
) {
    /** Newest first. */
    var running: List<ClockRunning> by mutableStateOf(emptyList())
        private set

    /**
     * The headings clocked most recently, the latest stop first, as many as [RECENT_CLOCKS]: Emacs's
     * clock history, read from the notes, so a task clocked in Emacs is there too. A heading whose
     * clock runs now is left out.
     */
    var recent: List<ClockRecent> by mutableStateOf(emptyList())
        private set

    /** Reads the clocks again from what is known about the notes as they stand. */
    fun refresh() {
        val facts = library.facts
        val found = runningClocksFrom(facts)
        val runningOn = found.map { it.path to it.headingStart }.toSet()
        recent = facts.flatMap { (path, note) ->
            note.lastClocked.map { ClockRecent(path, it.headingStart, it.title, it.end) }
        }.filter { (it.path to it.headingStart) !in runningOn }
            .sortedWith(compareByDescending<ClockRecent> { it.end }.thenBy { it.path })
            .take(RECENT_CLOCKS)
        // The first time whatever it finds: a notification left from before the app last closed
        // may describe a clock stopped since, in Emacs or on another device.
        if (found != running || !told) {
            running = found
            told = true
            notifier.show(found)
        }
    }

    private var told = false
}
