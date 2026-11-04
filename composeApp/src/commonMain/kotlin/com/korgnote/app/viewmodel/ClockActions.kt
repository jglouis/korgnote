package com.korgnote.app.viewmodel

import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.edit.cancelClock
import com.korgnote.common.org.edit.clockIn
import com.korgnote.common.org.edit.clockOut
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.runningClocks
import com.korgnote.common.org.edit.setEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

/** Clocking in and out of headings, in the open note or any other, and setting their efforts. */
class ClockActions(
    private val scope: CoroutineScope,
    private val note: OpenNote,
    private val clocks: NoteClocks,
    private val changes: NoteChanges,
    /** The wall-clock time clocks are stamped with. */
    private val now: () -> LocalDateTime,
    /** True, with a word to say so, when an element of the open note is being edited. */
    private val refuseWhileEditing: () -> Boolean,
) {
    /** The time now, as clocks are stamped with it: for elapsed times and the totals beside headings. */
    fun currentTime(): LocalDateTime = now()

    /**
     * Clocks in the heading at [offsetInDocument] of the open note, as `org-clock-in` does: any
     * clock running elsewhere — in this note or another — stops at the same minute first.
     */
    fun clockInAt(offsetInDocument: Int) {
        clockIn(note.path ?: return, offsetInDocument)
    }

    /**
     * [clockInAt] for the heading at [headingOffset] of the note at [path], open or not: from the
     * calendar, say.
     */
    fun clockIn(path: String, headingOffset: Int) {
        if (path == note.path && refuseWhileEditing()) return
        val at = now()
        scope.launch {
            for (other in clocks.running.filter { it.path != path }) changes.changeNote(other.path) { it.withClock(other) { doc, h -> doc.clockOut(h, at) } }
            changes.changeNote(path) { doc ->
                val heading = doc.nodeAt<OrgSection>(headingOffset) ?: return@changeNote doc
                var start = heading.range.first
                var updated = doc.clockIn(heading, at)
                while (true) {
                    val other = updated.runningClocks().firstOrNull { it.headingStart != start } ?: break
                    val running = updated.nodeAt<OrgSection>(other.headingStart) ?: break
                    val next = updated.clockOut(running, at)
                    if (next.text == updated.text) break
                    // Its clock line is in its own entry: before this heading when that heading is above it.
                    if (other.headingStart < start) start += next.text.length - updated.text.length
                    updated = next
                }
                updated
            }
        }
    }

    /** Stops the clock running on the heading at [offsetInDocument] of the open note. */
    fun clockOutAt(offsetInDocument: Int) = changeHeading(offsetInDocument) { doc, h -> doc.clockOut(h, now()) }

    /** Removes the clock running on the heading at [offsetInDocument], as `org-clock-cancel` does. */
    fun cancelClockAt(offsetInDocument: Int) = changeHeading(offsetInDocument) { doc, h -> doc.cancelClock(h) }

    /** Sets the effort of the heading at [offsetInDocument] to [minutes], or removes it for null, as `org-set-effort`. */
    fun setEffortAt(offsetInDocument: Int, minutes: Int?) = changeHeading(offsetInDocument) { doc, h -> doc.setEffort(h, minutes) }

    /** Stops [clock], wherever it runs: now, or at [at] for one left running. */
    fun clockOut(clock: ClockRunning, at: LocalDateTime? = null) {
        if (clock.path == note.path && refuseWhileEditing()) return
        val end = at ?: now()
        scope.launch { changes.changeNote(clock.path) { it.withClock(clock) { doc, h -> doc.clockOut(h, end) } } }
    }

    /** The clock running on the heading at [headingOffset] of the note at [path], if one is. */
    fun clockRunningOn(path: String, headingOffset: Int): ClockRunning? =
        clocks.running.firstOrNull { it.path == path && it.headingStart == headingOffset }

    /** Removes [clock], wherever it runs. */
    fun cancelClock(clock: ClockRunning) {
        if (clock.path == note.path && refuseWhileEditing()) return
        scope.launch { changes.changeNote(clock.path) { it.withClock(clock) { doc, h -> doc.cancelClock(h) } } }
    }

    private fun changeHeading(offsetInDocument: Int, change: (OrgDocument, OrgSection) -> OrgDocument) {
        val path = note.path ?: return
        if (refuseWhileEditing()) return
        scope.launch {
            changes.changeNote(path) { doc -> doc.nodeAt<OrgSection>(offsetInDocument)?.let { change(doc, it) } ?: doc }
        }
    }
}

/**
 * [change] made to the heading [clock] runs on, found again by its title and start: the note may
 * have changed since the clock was read, on another device or in Emacs. This document as it is when
 * no such clock runs in it.
 */
fun OrgDocument.withClock(clock: ClockRunning, change: (OrgDocument, OrgSection) -> OrgDocument): OrgDocument {
    val found = runningClocks().firstOrNull { it.title == clock.title && it.start == clock.start } ?: return this
    val heading = nodeAt<OrgSection>(found.headingStart) ?: return this
    return change(this, heading)
}
