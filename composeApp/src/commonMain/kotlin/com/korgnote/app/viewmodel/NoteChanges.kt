package com.korgnote.app.viewmodel

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
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
import com.korgnote.common.org.cutOrgSubtree
import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.org.edit.fileKeywordsOf
import com.korgnote.common.org.edit.updateClockDurations
import com.korgnote.common.org.edit.updateStatisticsCookies
import com.korgnote.common.org.edit.withFileKeywords
import com.korgnote.common.org.edit.withTimestampToggledAt
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.index.pickerFactsOf
import com.korgnote.common.org.link.linkTargetsFrom
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.org.withOrgSubtreeFiled
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.conflictCopyName
import com.korgnote.common.sync.merge
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_edit_conflict
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import korgnote.composeapp.generated.resources.vm_kept_beside
import korgnote.composeapp.generated.resources.vm_note_brought_back
import korgnote.composeapp.generated.resources.vm_refile_target_gone
import korgnote.composeapp.generated.resources.vm_refiled
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
    /** Rebuilds what is derived from the notes, now, back when it is shown. */
    private val refresh: suspend () -> Unit,
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
     * push fails the file simply stays dirty and the next sync carries it. False when it could not
     * be merged with what the replica holds, and is kept beside the note instead.
     */
    suspend fun saveCurrentFile(): Boolean {
        val path = note.path ?: return false
        // Read now, before anything waits: by the time the disk answers, the screen may be showing
        // what a reload read, or another note altogether.
        val text = note.content
        val keywords = todoKeywords()
        val base = began(path, note.replicaText, text)
        var landed = false
        scope.launch { landed = save(path, base, text, keywords) }.join()
        return landed
    }

    private suspend fun save(path: String, base: String?, text: String, keywords: OrgTodoConfig): Boolean {
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
        if (written == null) {
            keepBeside(path, saved)
            return false
        }
        showWritten(path, text, written)
        library.put(path, written.toOrgDocument())
        if (broughtBack) {
            // Back in the drawer too, which a single note's change does not touch.
            showReplica()
            showMessage(getString(Res.string.vm_note_brought_back, path))
        } else {
            refresh()
        }
        server().push()
        return true
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
        document.nodeAt<OrgCheckboxItem>(offsetInDocument)?.let { document.tickAsOrgDoes(it).withStatistics(todoKeywords(), since = document) }
    }

    /**
     * Makes the keyword of the heading at [offsetInDocument] [keyword], or none when that is null,
     * with what Org logs of it, here [noteText]: saved and pushed as [toggleCheckboxAt] saves a tick.
     */
    fun changeTodoStateAt(offsetInDocument: Int, keyword: String?, noteText: String? = null) = changeOpenNote { document ->
        document.nodeAt<OrgSection>(offsetInDocument)?.let { heading ->
            document.changeTodoState(heading, keyword, now(), todoKeywords(), noteText).withStatistics(todoKeywords(), since = document)
        }
    }

    /**
     * Makes the stamp that begins at [offsetInDocument] inactive when it is active and active when it
     * is not, saved and pushed as [toggleCheckboxAt] saves a tick. The calendar follows at once: an
     * active date is in it and an inactive one is not.
     */
    fun toggleTimestampAt(offsetInDocument: Int) = changeOpenNote(refreshAtOnce = true) { document ->
        document.text.withTimestampToggledAt(offsetInDocument)?.toOrgDocument()
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
            changeNote(path, byOffset = false) { doc ->
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
     * Nothing is rebuilt from every note now unless [refreshAtOnce] asks: a run of ticks would pay
     * for the scans on each one. The note shows the change at once, and the calendar follows a
     * moment later.
     */
    private fun changeOpenNote(refreshAtOnce: Boolean = false, edit: (OrgDocument) -> OrgDocument?) {
        val path = note.path ?: return
        val change = changeOnScreen(path, edit) ?: return
        if (refreshAtOnce) scope.launch { refresh() } else refreshSoon()
        scope.launch { writeAndPush(change) }
    }

    /**
     * [change] made to the note at [path], on the text the person was looking at — the open one as it
     * stands on screen, or another as the library has it — then saved and pushed as a keyword change is.
     *
     * @param byOffset whether [change] goes by an offset from what the calendar shows, which is refused if
     * the note has moved on. One that makes sure of its place some other way, by a title or a stamp, is not.
     */
    /**
     * The note at [path] to make a change on, when what the change is about still holds in it. Its text
     * may have had to be read and have moved on since it was shown, leaving the place to change
     * somewhere else, or another heading in its place: it is refused then, with nothing lost, and what
     * is shown brought up to date.
     */
    suspend fun documentToChange(path: String): OrgDocument? {
        val text = textToChange(path) ?: return null
        return library.document(path).takeIf { it?.text == text }
    }

    private suspend fun textToChange(path: String): String? {
        val text = library.text(path) ?: return null
        return text.takeUnless { hasMoved(path, it) }
    }

    /**
     * Whether [text], what [path] now holds, is no longer what is on screen, as far as an offset from there
     * goes. If so it says that the change was not made, with what is shown brought up to date.
     */
    suspend fun hasMoved(path: String, text: String): Boolean {
        if (library.isAsShown(path, text)) return false
        library.catchUpWith(path, text)
        // Before anything else can be tapped, or the next tap would go by the same offset.
        refresh()
        showMessage(getString(Res.string.vm_edit_conflict))
        return true
    }

    suspend fun changeNote(path: String, byOffset: Boolean = true, change: (OrgDocument) -> OrgDocument) {
        val made = if (path == note.path) {
            if (byOffset && note.replicaText != null && hasMoved(path, note.content)) return
            changeOnScreen(path, change) ?: return
        } else {
            val before = (if (byOffset) documentToChange(path) else library.document(path)) ?: return
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
        val before = textToChange(path) ?: return false
        val updated = edit(before) ?: return false
        if (updated == before) return true
        library.put(path, updated.toOrgDocument())
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

    /**
     * Where the heading at element [index] of the open note can be refiled to: every note, as a
     * place for a top-level heading, and every heading with a title — except the heading itself and
     * those under it. The open note as it stands on screen, so that its offsets are the editor's.
     */
    fun refileTargets(index: Int): List<LinkTarget> {
        val path = note.path
        val facts = library.facts.toMutableMap()
        path?.let { facts[it] = pickerFactsOf(note.content) }
        val cut = note.content.cutOrgSubtree(index)
        return linkTargetsFrom(facts).filter { target ->
            val offset = target.offset
            val inside = cut != null && target.path == path && offset != null && offset in cut.start until cut.end
            !inside && (target.isNote || target.title.isNotBlank())
        }
    }

    /**
     * Refiles the heading at element [index] of the open note, with its subtree, under [target] —
     * after what is already there, one level below it — or to the end of [target] when it is a note,
     * as `org-refile` (`C-c C-w`) does. Into another note, that note is written first.
     */
    suspend fun refile(index: Int, target: LinkTarget) {
        val source = note.path ?: return
        if (editor.editingIndex != null) {
            showMessage(getString(Res.string.vm_finish_editing_first))
            return
        }
        val where = target.title.ifBlank { target.noteTitle }
        var filedElsewhere = false
        val moved = if (target.path == source) {
            editor.refileSection(index, target.offset)
        } else {
            editor.moveSectionOut(index) { subtree ->
                writeOtherNote(target.path) { before ->
                    before.withOrgSubtreeFiled(subtree, target.offset)?.let { filed ->
                        // The heading's `[2/5]` counts what is under it, which now includes this.
                        updateStatisticsCookies(filed, todoKeywordsOf(filed, DEFAULT_TODO_KEYWORDS), before = before)
                    }
                }.also { filedElsewhere = it }
            }
        }
        if (moved) {
            showMessage(getString(Res.string.vm_refiled, where))
        } else if (!filedElsewhere && editor.editingIndex == null) {
            // Filed there and stuck here, what stopped it has said so already.
            showMessage(getString(Res.string.vm_refile_target_gone, where))
        }
    }

    /** What the open note says about itself — title, author, date, tags — for the details form. */
    fun openNoteKeywords(): FileKeywords = fileKeywordsOf(note.content)

    /**
     * Rewrites the open note's `#+TITLE:`, `#+AUTHOR:`, `#+DATE:` and `#+filetags:` to say
     * [keywords], then saves. Refused while an element is being edited, as a restructuring is: the
     * draft's offsets would move under it.
     */
    suspend fun setOpenNoteKeywords(keywords: FileKeywords) {
        if (note.path == null) return
        if (editor.editingIndex != null) {
            showMessage(getString(Res.string.vm_finish_editing_first))
            return
        }
        val updated = withFileKeywords(note.content, keywords)
        if (updated == note.content) return
        note.content = updated
        saveCurrentFile()
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
        // A heading being added is on screen and nowhere else: written now, the empty heading would
        // reach the replica, and discarding it would then take this change back off the screen.
        if (editor.textBeforeInsertion != null) {
            scope.launch { showMessage(getString(Res.string.vm_finish_editing_first)) }
            return null
        }
        val document = note.content.toOrgDocument()
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
                        // Listed but not read, and made on nothing that was: it says nothing about this
                        // note, which may only be out of reach. Kept beside it, by whoever asked. A note
                        // that was never written is different: saving it is how it comes to be.
                        if (!bringBack || (base == null && path in library.paths)) return@withLock null
                        store.save(path, text)
                        return@withLock text
                    }
                    val merged = when {
                        current == base || current == text -> text
                        // Made on nothing that was read, so not on this: a placeholder, or the note
                        // on screen before this one's text arrived. It must not take this one's place.
                        base == null -> null
                        else -> (merge(base, text, current) as? MergeResult.Merged)?.text
                    }
                    if (merged == null) return@withLock null
                    if (store.saveIfUnchanged(path, current, merged)) return@withLock merged
                }
                null
            }
        }

    /** [written] landed for a change that made [text]: when something came in, it is now to be seen. */
    private fun showWritten(path: String, text: String, written: String) {
        if (written != text) {
            library.put(path, written.toOrgDocument())
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

    private companion object {
        /** Saves in a row lost to another write before giving up. */
        const val WRITE_ATTEMPTS = 5
    }
}

/**
 * This document with the statistics cookies over what changed [since] counting what is now under them
 * by [keywords], as Org updates them.
 */
internal fun OrgDocument.withStatistics(keywords: OrgTodoConfig, since: OrgDocument): OrgDocument =
    updateStatisticsCookies(text, keywords, before = since.text).let { if (it == text) this else it.toOrgDocument(todoFallback) }
