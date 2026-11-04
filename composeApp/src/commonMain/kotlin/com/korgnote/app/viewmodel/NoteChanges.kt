package com.korgnote.app.viewmodel

import com.korgnote.app.di.conflictTimestamp
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgPriority
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.restampRange
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.replace
import com.korgnote.common.org.edit.setPriority
import com.korgnote.common.org.edit.tickAsOrgDoes
import com.korgnote.common.org.edit.updateClockDurations
import com.korgnote.common.org.edit.updateStatisticsCookies
import com.korgnote.common.org.edit.withTimestampToggledAt
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.conflictCopyName
import com.korgnote.common.sync.merge
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_edit_conflict
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import korgnote.composeapp.generated.resources.vm_kept_beside
import korgnote.composeapp.generated.resources.vm_note_brought_back
import korgnote.composeapp.generated.resources.vm_reschedule_moved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.getString

/**
 * Changing a note's text: what writes to the replica on the app's behalf, then keeps the library and
 * the screen in step and passes the change on to where the notes are kept.
 *
 * A change is made on the text the person was looking at, since that is what its offsets mean, and
 * the replica may have moved past that text: a sync pulled an edit from Emacs, or a background worker
 * filed a capture, and the notes have not been read again yet. So a change is merged with what the
 * replica holds by the time it lands, never written over it. Written over it, and pushed with the
 * server's newest etag, it would silently take back whatever had come in.
 */
class NoteChanges(
    /**
     * Where writes run: the view model's, which outlives the screen. Turning the phone cancels what
     * the screen launched, and a change it has shown by then must still reach the replica.
     */
    private val scope: CoroutineScope,
    private val store: DocumentStore,
    private val library: NoteLibrary,
    private val note: OpenNote,
    private val editor: ElementEditor,
    private val clocks: NoteClocks,
    /** The wall-clock time state changes are stamped with. */
    private val now: () -> LocalDateTime,
    /** The open note's keywords, as completion knows them. */
    private val todoKeywords: () -> OrgTodoConfig,
    /** Where the change goes next. Read when it is needed, by which time it is there. */
    private val server: () -> NotesSync,
    /** Rebuilds what is derived from the notes, at once. */
    private val refresh: () -> Unit,
    /** Rebuilds what is derived from the notes once changes stop coming. */
    private val refreshSoon: () -> Unit,
    /** Reads the replica again and shows what it holds. */
    private val showReplica: suspend () -> Unit,
    /** Shows a message to the user. */
    private val showMessage: (String) -> Unit,
) {
    /**
     * Writes the open note to the replica, then tries to push.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the next sync carries it.
     */
    suspend fun saveCurrentFile() {
        val path = note.path ?: return
        // Read now, before anything waits: by the time the disk answers, the screen may be showing
        // what a reload read, or another note altogether.
        val text = note.content
        val keywords = todoKeywords()
        val base = began(path, note.replicaText, text)
        scope.launch { save(path, base, text, keywords) }.join()
    }

    private suspend fun save(path: String, base: String?, text: String, keywords: OrgTodoConfig) {
        var saved = text
        var broughtBack = false
        val written = try {
            // A heading's `[2/5]` counts what is under it, which the edit may have changed — and only
            // those over the edit: the rest of the note is saved as it was.
            // And a clock edited by hand sums its stamps again, as `C-c C-c` on its line would.
            val before = base ?: store.read(path)
            saved = updateClockDurations(updateStatisticsCookies(text, keywords, before = before), before)
            // A note that was read and is not there now was deleted, here or elsewhere, under the edit.
            broughtBack = base != null && store.read(path) == null
            land(path, base, saved, bringBack = true)
        } finally {
            landed(path)
        }
        if (written == null) return keepBeside(path, saved)
        showWritten(path, text, written)
        library.put(path, written.toOrgDocument(library.fallbackKeywords))
        if (broughtBack) {
            // Back in the drawer too, which a single note's change does not touch.
            showReplica()
            showMessage(getString(Res.string.vm_note_brought_back, path))
        } else {
            refresh()
        }
        server().push()
    }

    /**
     * What was typed, when it cannot be merged with what the replica now holds: kept beside the note
     * as a conflict copy, as a sync does with a conflict, for the person to settle. A tick can simply
     * be made again; words cannot.
     */
    private suspend fun keepBeside(path: String, text: String) {
        val stamp = conflictTimestamp()
        var copy = conflictCopyName(path, stamp)
        var attempt = 2
        while (!store.create(copy, text)) copy = conflictCopyName(path, "$stamp-${attempt++}")
        showReplica()
        showMessage(getString(Res.string.vm_kept_beside, path))
        server().push()
    }

    /**
     * Ticks the checkbox whose source contains [offsetInDocument].
     *
     * The offset identifies the node rather than counting boxes: an ordinal shifts whenever anything
     * above it changes. The edit itself is a range replacement, so every other character in the file
     * — blank lines, trailing whitespace, formatting — is left exactly as it was on disk.
     */
    fun toggleCheckboxAt(offsetInDocument: Int) = changeOpenNote { document ->
        document.nodeAt<OrgCheckboxItem>(offsetInDocument)?.let { document.tickAsOrgDoes(it).withStatistics(since = document) }
    }

    /**
     * Makes the keyword of the heading at [offsetInDocument] [keyword], or none when that is null,
     * with what Org logs of it, here [noteText]: saved and pushed as [toggleCheckboxAt] saves a tick.
     */
    fun changeTodoStateAt(offsetInDocument: Int, keyword: String?, noteText: String? = null) = changeOpenNote { document ->
        document.nodeAt<OrgSection>(offsetInDocument)?.let { heading ->
            document.changeTodoState(heading, keyword, now(), todoKeywords(), noteText).withStatistics(since = document)
        }
    }

    /**
     * Makes the stamp that begins at [offsetInDocument] inactive when it is active and active when it
     * is not, saved and pushed as [toggleCheckboxAt] saves a tick. The calendar follows at once: an
     * active date is in it and an inactive one is not.
     */
    fun toggleTimestampAt(offsetInDocument: Int) = changeOpenNote(refreshAtOnce = true) { document ->
        document.text.withTimestampToggledAt(offsetInDocument)?.toOrgDocument(library.fallbackKeywords)
    }

    /**
     * Sets the priority of the heading at [offsetInDocument] — `[#A]` — or removes it when
     * [priority] is null, as Org's `C-c ,` does; saved and pushed as a keyword is.
     */
    fun setPriorityAt(offsetInDocument: Int, priority: OrgPriority?) = changeOpenNote { document ->
        document.nodeAt<OrgSection>(offsetInDocument)?.let { document.setPriority(it, priority) }
    }

    /**
     * Moves the date of the calendar's [entry] — its `SCHEDULED:`, its `DEADLINE:`, or the stamp in
     * its body — to [date] at [time], or with no time when that is null, as Org's `C-c C-s` and
     * `C-c C-d` do from the agenda: in its note, open or not, saved and pushed. What the picker does
     * not ask about stays: the brackets, a repeater, a warning, the length of a range over days.
     *
     * The stamp is replaced where the agenda found it, once it is checked to be still there and
     * still the same: the note may have changed since, here, on another device or in Emacs.
     */
    fun reschedule(entry: AgendaEntry, date: LocalDate, time: LocalTime?, endTime: LocalTime?) {
        val path = entry.file
        if (path == note.path && refuseWhileEditing()) return
        val range = entry.stamp.range
        scope.launch {
            var moved = false
            changeNote(path) { doc ->
                val written = doc.text.takeIf { !range.isEmpty() && range.last < it.length }?.substring(range)
                val stamp = written?.let(::parseOrgTimestamp)
                if (stamp == null || stamp.copy(range = IntRange.EMPTY) != entry.stamp.copy(range = IntRange.EMPTY)) {
                    moved = true
                    return@changeNote doc
                }
                doc.replace(range, restampRange(written, date, time, endTime))
            }
            if (moved) showMessage(getString(Res.string.vm_reschedule_moved))
        }
    }

    /** An element of the open note being edited would have the note change under it: says so, and true. */
    fun refuseWhileEditing(): Boolean {
        if (editor.editingIndex == null) return false
        scope.launch { showMessage(getString(Res.string.vm_finish_editing_first)) }
        return true
    }

    /**
     * [edit] made to the open note, on screen and in the library at once; written, then pushed,
     * behind that. Null from [edit], or the same text, is nothing to do.
     *
     * Nothing is rebuilt from every note now unless [refreshAtOnce] asks: the scans held a tick, the
     * keyword menu and the note dialog's Save up for a second on a phone. The note shows the change
     * at once, and the calendar follows a moment later.
     */
    private fun changeOpenNote(refreshAtOnce: Boolean = false, edit: (OrgDocument) -> OrgDocument?) {
        val path = note.path ?: return
        val change = changeOnScreen(path, edit) ?: return
        if (refreshAtOnce) refresh() else refreshSoon()
        scope.launch { writeAndPush(change) }
    }

    /**
     * [change] made to the note at [path], on the text the person was looking at — the open one as it
     * stands on screen, or another as the library has it — then saved and pushed as a keyword change is.
     */
    suspend fun changeNote(path: String, change: (OrgDocument) -> OrgDocument) {
        val made = if (path == note.path) {
            changeOnScreen(path, change) ?: return
        } else {
            val before = library[path] ?: return
            val updated = change(before)
            if (updated.text == before.text) return
            library.put(path, updated)
            Change(path, began(path, before.text, updated.text), updated.text)
        }
        clocks.refresh()
        refreshSoon()
        writeAndPush(made)
    }

    /**
     * [edit] made to a note other than the open one, as the library has it, and written. False when
     * it declines by returning null, or what it was made on has moved in a way it cannot be merged
     * with. Pushing is left to the caller, whose own change may go with it.
     */
    suspend fun writeOtherNote(path: String, edit: (String) -> String?): Boolean {
        val before = library[path]?.text ?: return false
        val updated = edit(before) ?: return false
        if (updated == before) return true
        library.put(path, updated.toOrgDocument(library.fallbackKeywords))
        val base = began(path, before, updated)
        val written = try {
            land(path, base, updated)
        } finally {
            landed(path)
        }
        if (written == null) {
            showReplica()
            return false
        }
        showWritten(path, updated, written)
        return true
    }

    /** A change shown and on its way to the replica: its [text], made on [base]. */
    private class Change(val path: String, val base: String?, val text: String)

    /**
     * [edit] made to the open note at [path], on screen and in the library; null when there is
     * nothing to write. Until a note's text has arrived, what the screen holds is the note before it:
     * a change made there has no business in this one.
     */
    private fun changeOnScreen(path: String, edit: (OrgDocument) -> OrgDocument?): Change? {
        if (note.replicaText == null) return null
        val document = note.content.toOrgDocument(library.fallbackKeywords)
        val updated = edit(document)?.takeIf { it.text != document.text } ?: return null
        note.content = updated.text
        library.put(path, updated)
        return Change(path, began(path, note.replicaText, updated.text), updated.text)
    }

    /**
     * Writes [change]. A failure leaves the file dirty for the next cycle rather than losing it; one
     * that cannot be merged is refused, with a word, and what the replica holds is shown.
     */
    private suspend fun writeAndPush(change: Change) {
        server().push(
            writeFirst = {
                val written = try {
                    land(change.path, change.base, change.text)
                } finally {
                    landed(change.path)
                }
                if (written == null) {
                    showReplica()
                    showMessage(getString(Res.string.vm_edit_conflict))
                } else {
                    showWritten(change.path, change.text, written)
                }
            },
        )
    }

    /** One write at a time, in the order they were made: each is made on the one before it. */
    private val landing = Mutex()

    /**
     * Writes [text], made on [base], merged with whatever the replica has come to hold since. The
     * text now in the replica; null, with nothing written, when the two changed the same lines or the
     * note is gone. Only [bringBack] writes a note that is gone.
     */
    private suspend fun land(path: String, base: String?, text: String, bringBack: Boolean = false): String? =
        // Once begun, finished: the change is on screen, and leaving the app must not cut off its write.
        withContext(NonCancellable) {
            landing.withLock {
                repeat(WRITE_ATTEMPTS) {
                    val current = store.read(path)
                    if (current == null) {
                        if (!bringBack) return@withLock null
                        store.save(path, text)
                        return@withLock text
                    }
                    val merged = if (base == null || current == base) text else (merge(base, text, current) as? MergeResult.Merged)?.text
                    if (merged == null) return@withLock null
                    if (store.saveIfUnchanged(path, current, merged)) return@withLock merged
                }
                null
            }
        }

    /** [written] landed for a change that made [text]: when something came in, it is now to be seen. */
    private fun showWritten(path: String, text: String, written: String) {
        if (written != text) {
            library.put(path, written.toOrgDocument(library.fallbackKeywords))
            refreshSoon()
        }
        if (note.path != path) return
        if (editor.editingIndex != null) {
            // The screen stays under the element being edited, whose save makes its edit on this.
            note.replicaText = text
            if (written != text) editor.noteChangedElsewhere(written)
        } else if (note.content == text) {
            note.content = written
            note.replicaText = written
        }
        // Otherwise another change is on its way, made on this one, and will do the same.
    }

    /** The writes on their way to the replica, by note. */
    private val underWay = MutableStateFlow<Map<String, Queued>>(emptyMap())

    /** How many writes are on their way to a note, and what the last of them makes it. */
    private class Queued(val writes: Int, val text: String)

    /**
     * A change that makes [path] [text] is on its way. Returns what it is made on: what the last one
     * under way made the note, or else [base].
     */
    private fun began(path: String, base: String?, text: String): String? {
        val queued = underWay.value[path]
        underWay.value += path to Queued((queued?.writes ?: 0) + 1, text)
        return if (queued != null) queued.text else base
    }

    private fun landed(path: String) {
        val queued = underWay.value[path] ?: return
        underWay.value = if (queued.writes > 1) underWay.value + (path to Queued(queued.writes - 1, queued.text)) else underWay.value - path
    }

    /**
     * True while a change to [path] is on its way to the replica: the note is ahead of what a read
     * of it would return, which must not be put back on screen.
     */
    fun isWriting(path: String): Boolean = path in underWay.value

    /** Waits until the replica holds every change made so far, before reading it for what is new. */
    suspend fun whenWritten() {
        underWay.first { it.isEmpty() }
    }

    /**
     * This document with the statistics cookies over what changed [since] counting what is now
     * under them, as Org updates them.
     */
    fun OrgDocument.withStatistics(since: OrgDocument): OrgDocument =
        updateStatisticsCookies(text, todoKeywords(), before = since.text).let { if (it == text) this else it.toOrgDocument(todoFallback) }

    private companion object {
        /** Saves in a row lost to another write before giving up. */
        const val WRITE_ATTEMPTS = 5
    }
}
