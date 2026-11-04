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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
        // Read now, before anything waits: by the time the disk answers, the screen may be showing
        // what a reload read, or another note altogether.
        val text = note.content
        val keywords = todoKeywords()
        began(path)
        scope.launch { save(path, text, keywords) }.join()
    }

    private suspend fun save(path: String, text: String, keywords: OrgTodoConfig) {
        val saved = try {
            // A heading's `[2/5]` counts what is under it, which the edit may have changed — and only
            // those over the edit: the rest of the note is saved as it was.
            // And a clock edited by hand sums its stamps again, as `C-c C-c` on its line would.
            val before = store.read(path)
            updateClockDurations(updateStatisticsCookies(text, keywords, before = before), before).also { store.save(path, it) }
        } finally {
            landed(path)
        }
        if (note.path == path && note.content == text) {
            note.content = saved
            note.replicaText = saved
        }
        library.put(path, saved.toOrgDocument(library.fallbackKeywords))
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
        val updated = changeOnScreen(path, edit) ?: return
        if (refreshAtOnce) refresh() else refreshSoon()
        scope.launch { writeAndPush(path, updated.text) }
    }

    /**
     * [change] made to the note at [path] — the open one as it stands on screen, or another as the
     * replica has it — then saved and pushed as a keyword change is.
     */
    suspend fun changeNote(path: String, change: (OrgDocument) -> OrgDocument) {
        val updated = if (path == note.path) {
            changeOnScreen(path, change) ?: return
        } else {
            val before = library[path] ?: return
            change(before).takeIf { it.text != before.text }?.also {
                library.put(path, it)
                began(path)
            } ?: return
        }
        clocks.refresh()
        refreshSoon()
        writeAndPush(path, updated.text)
    }

    /**
     * [edit] made to the open note at [path], on screen and in the library; null when there is
     * nothing to write. Until a note's text has arrived, what the screen holds is the note before it:
     * a change made there has no business in this one.
     */
    private fun changeOnScreen(path: String, edit: (OrgDocument) -> OrgDocument?): OrgDocument? {
        if (note.replicaText == null) return null
        val document = note.content.toOrgDocument(library.fallbackKeywords)
        val updated = edit(document)?.takeIf { it.text != document.text } ?: return null
        note.content = updated.text
        library.put(path, updated)
        began(path)
        return updated
    }

    /** Writes a change begun with [began]. A failure leaves the file dirty for the next cycle rather than losing it. */
    private suspend fun writeAndPush(path: String, text: String) {
        server().push(
            writeFirst = {
                try {
                    store.save(path, text)
                    if (note.path == path && note.content == text) note.replicaText = text
                } finally {
                    landed(path)
                }
            },
        )
    }

    /** The writes on their way to the replica, by note. */
    private val underWay = MutableStateFlow<Map<String, Int>>(emptyMap())

    private fun began(path: String) {
        underWay.update { it + (path to (it[path] ?: 0) + 1) }
    }

    private fun landed(path: String) {
        underWay.update { writes ->
            val left = (writes[path] ?: 0) - 1
            if (left > 0) writes + (path to left) else writes - path
        }
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
}
