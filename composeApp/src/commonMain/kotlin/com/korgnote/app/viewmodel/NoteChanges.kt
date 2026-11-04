package com.korgnote.app.viewmodel

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgPriority
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.setPriority
import com.korgnote.common.org.edit.tickAsOrgDoes
import com.korgnote.common.org.edit.updateClockDurations
import com.korgnote.common.org.edit.updateStatisticsCookies
import com.korgnote.common.org.edit.withTimestampToggledAt
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

/**
 * Changing a note's text: what writes to the replica on the app's behalf, then keeps the library and
 * the screen in step and passes the change on to where the notes are kept.
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
) {
    /**
     * Writes the open note to the replica, then tries to push.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the next sync carries it.
     */
    suspend fun saveCurrentFile() {
        val path = note.path ?: return
        scope.launch { save(path) }.join()
    }

    private suspend fun save(path: String) {
        // A heading's `[2/5]` counts what is under it, which the edit may have changed — and only
        // those over the edit: the rest of the note is saved as it was.
        // And a clock edited by hand sums its stamps again, as `C-c C-c` on its line would.
        val before = store.read(path)
        note.content = updateClockDurations(updateStatisticsCookies(note.content, todoKeywords(), before = before), before)
        store.save(path, note.content)
        library.put(path, note.content.toOrgDocument(library.fallbackKeywords))
        refresh()
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
     * [edit] made to the open note, on screen and in the library at once; written, then pushed,
     * behind that. Null from [edit], or the same text, is nothing to do.
     *
     * Nothing is rebuilt from every note now unless [refreshAtOnce] asks: the scans held a tick, the
     * keyword menu and the note dialog's Save up for a second on a phone. The note shows the change
     * at once, and the calendar follows a moment later.
     */
    private fun changeOpenNote(refreshAtOnce: Boolean = false, edit: (OrgDocument) -> OrgDocument?) {
        val path = note.path ?: return
        val document = note.content.toOrgDocument(library.fallbackKeywords)
        val updated = edit(document) ?: return
        if (updated.text == document.text) return
        note.content = updated.text
        library.put(path, updated)
        if (refreshAtOnce) refresh() else refreshSoon()
        // A failure leaves the file dirty for the next cycle rather than losing the change.
        scope.launch { server().push(writeFirst = { store.save(path, note.content) }) }
    }

    /**
     * [change] made to the note at [path] — the open one as it stands on screen, or another as the
     * replica has it — then saved and pushed as a keyword change is.
     */
    suspend fun changeNote(path: String, change: (OrgDocument) -> OrgDocument) {
        val open = path == note.path
        val before = if (open) note.content.toOrgDocument(library.fallbackKeywords) else library[path] ?: return
        val updated = change(before)
        if (updated.text == before.text) return
        library.put(path, updated)
        if (open) note.content = updated.text
        clocks.refresh()
        refreshSoon()
        server().push(writeFirst = { store.save(path, updated.text) })
    }

    /**
     * This document with the statistics cookies over what changed [since] counting what is now
     * under them, as Org updates them.
     */
    fun OrgDocument.withStatistics(since: OrgDocument): OrgDocument =
        updateStatisticsCookies(text, todoKeywords(), before = since.text).let { if (it == text) this else it.toOrgDocument(todoFallback) }
}
