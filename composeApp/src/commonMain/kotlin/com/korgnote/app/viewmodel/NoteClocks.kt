package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.edit.runningClocks
import kotlinx.datetime.LocalDateTime

/** A clock running in one of the notes: in which, on which heading, and since when. */
data class ClockRunning(val path: String, val headingStart: Int, val title: String, val start: LocalDateTime)

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

    /** Reads the clocks again from the notes as they stand; cheap, since few notes hold any. */
    fun refresh() {
        val found = library.documents.flatMap { (path, doc) ->
            if (!doc.text.contains("CLOCK:")) emptyList()
            else doc.runningClocks().map { ClockRunning(path, it.headingStart, it.title, it.start) }
        }.sortedWith(compareByDescending<ClockRunning> { it.start }.thenBy { it.path })
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
