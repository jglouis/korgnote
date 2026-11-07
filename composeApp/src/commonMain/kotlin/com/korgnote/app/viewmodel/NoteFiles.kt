package com.korgnote.app.viewmodel

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.common.fs.NotePaths
import com.korgnote.common.org.template.dailyFileName
import com.korgnote.common.org.template.dailyTitle
import com.korgnote.common.org.template.newNoteText
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.sync.ChunkChoice
import com.korgnote.common.sync.ConflictChunk
import com.korgnote.common.sync.conflictChunks
import com.korgnote.common.sync.conflictCopyOriginal
import com.korgnote.common.sync.resolveConflict
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_not_a_note_path
import korgnote.composeapp.generated.resources.vm_note_exists
import korgnote.composeapp.generated.resources.vm_note_title_needed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.getString

/**
 * The notes as files: creating, renaming and deleting them, and settling a conflict between a note
 * and its copy. Everything kept about a note by its path goes with it.
 */
class NoteFiles(
    private val store: DocumentStore,
    private val library: NoteLibrary,
    private val note: OpenNote,
    private val folds: Folds,
    private val pins: NotePins,
    private val places: ReadingPlaces,
    private val editor: ElementEditor,
    private val today: () -> LocalDate,
    /** Where [createNote] puts a note; read each time, so a change in Settings applies at once. */
    private val newNotesFolder: () -> String,
    /** The author, daily folder and extra header new notes are written with; read each time too. */
    private val noteTemplate: () -> NoteTemplateSettings,
    /** A new note's `:ID:`. */
    private val newId: () -> String,
    private val scope: CoroutineScope,
    /** Reads the notes again, and only that. */
    private val reload: suspend () -> Unit,
    /** Reads the replica again and shows what it holds. */
    private val showReplica: suspend () -> Unit,
    /** Opens a note. */
    private val open: (String) -> Unit,
    /** Opens a note, for those going on to change it: back once its text is there. */
    private val openAndWait: suspend (String) -> Unit,
    /** Rebuilds what is derived from the notes once changes stop coming. */
    private val refreshSoon: () -> Unit,
    /** Where a change goes next. Read when it is needed, by which time it is there. */
    private val server: () -> NotesSync,
) {
    /** Where a note titled [title] would be created, or null when the title makes no file name. */
    fun newNotePathFor(title: String): String? =
        NotePaths.fileNameFor(title)?.let { NotePaths.join(newNotesFolder(), it) }

    /**
     * Creates a note titled [title], tagged with [tags], in the new-notes folder, opens it, and starts
     * its first heading: a capture, so what was worth writing down gets written before anything else
     * is asked. It is headed as org-roam heads a note — its own `:ID:`, title, author and date.
     *
     * Refused, saying why, when the title makes no file name or a note already has it. Works offline;
     * the next sync pushes it.
     */
    suspend fun createNote(title: String, tags: List<String> = emptyList()): NoteFileResult {
        val path = newNotePathFor(title) ?: return NoteFileResult.Refused(getString(Res.string.vm_note_title_needed))
        if (!store.create(path, headOfNewNote(title, tags))) {
            return NoteFileResult.Refused(getString(Res.string.vm_note_exists, path))
        }
        openNewNote(path)
        return NoteFileResult.Done(path)
    }

    /** Where today's daily is, in the daily folder Settings names. */
    fun todaysDailyPath(): String = NotePaths.join(noteTemplate().dailyFolder, dailyFileName(today()))

    /**
     * Opens today's daily, creating it first — headed like any new note, titled with the date — when
     * there is none yet. A new one starts its first heading, as a new note does; one that exists is
     * only opened, since what is in it is what was being looked for.
     */
    suspend fun openToday() {
        val path = todaysDailyPath()
        if (path in library) {
            open(path)
            return
        }
        // Already in the replica, though not yet read into the library: it is there to open.
        if (!store.create(path, headOfNewNote(dailyTitle(today()), emptyList()))) {
            open(path)
            return
        }
        openNewNote(path)
    }

    /**
     * Opens the tour note ([tourNoteText]) at its top, writing it first in the new-notes folder when
     * it is not there. One already there is only opened: it may be half ticked through, or another
     * device added it. Returns its path, or null when [words]' title makes no file name.
     */
    suspend fun openTour(words: TourWords): String? {
        val path = newNotePathFor(words.title) ?: return null
        if (path in library || !store.create(path, tourNoteText(words, newId(), today()))) {
            open(path)
            return path
        }
        openNewNote(path, startHeading = false)
        return path
    }

    private fun headOfNewNote(title: String, tags: List<String>): String {
        val template = noteTemplate()
        return newNoteText(
            id = newId(),
            title = title,
            date = today(),
            author = template.author,
            tags = tags,
            extraHeader = template.extraHeader,
        )
    }

    /**
     * Opens a note just written to the replica at its first heading, then pushes it.
     *
     * Only what opening needs is waited for: the note read into the library. The links, agenda
     * and search are rebuilt a moment later, as for a tick, since a note with nothing in it yet
     * changes none of them; and the push goes on behind, since the note exists here whether or not
     * the server hears of it. Awaited, the two held the new-note dialog open for a sync round trip
     * and two scans of every note.
     */
    private suspend fun openNewNote(path: String, startHeading: Boolean = true) {
        editor.cancel()
        note.path = path
        reload()
        refreshSoon()
        openAndWait(path)
        if (startHeading) editor.addAtEnd()
        scope.launch { server().pushQuietly() }
    }

    /**
     * Renames [from] to [to], a path as typed. Content, unsynced edits and folds go with it.
     * Refused, saying why, when [to] is not a valid path or is taken.
     */
    suspend fun renameNote(from: String, to: String): NoteFileResult {
        val target = NotePaths.normalize(to) ?: return NoteFileResult.Refused(getString(Res.string.vm_not_a_note_path, to))
        if (target == from) return NoteFileResult.Done(target)
        val wasOpen = note.path == from
        // The element editor holds offsets into the note being moved; finish nothing half-way.
        if (wasOpen) editor.cancel()
        if (!store.rename(from, target)) return NoteFileResult.Refused(getString(Res.string.vm_note_exists, target))
        folds.move(from, target)
        pins.move(from, target)
        places.move(from, target)
        showAndPush(target, wasOpen)
        return NoteFileResult.Done(target)
    }

    /** True when [path] holds edits the server has not seen, which a delete would take with it. */
    suspend fun hasUnsyncedChanges(path: String): Boolean = store.isDirty(path)

    /**
     * Deletes [path] here and, at the next sync, on the server — only the version deleted here, so
     * an edit made elsewhere in the meantime brings the note back instead of being lost.
     * The server keeps a copy in its trash either way.
     */
    suspend fun deleteNote(path: String) {
        if (note.path == path) {
            editor.cancel()
            note.path = null
        }
        forgetPlaces(path)
        store.delete(path)
        showReplica()
        server().pushQuietly()
    }

    // --- Conflicts ---

    /**
     * The note [path] is a conflict copy of, or null when it is not one — or its note is gone, which
     * leaves nothing to conflict with.
     */
    fun conflictOriginalOf(path: String): String? = conflictCopyOriginal(path)?.takeIf { it in library }

    /**
     * How [copy] differs from its note, chunk by chunk, with the two texts the chunks were cut from;
     * null when it is no copy of a note here.
     */
    suspend fun conflictComparison(copy: String): ConflictComparison? {
        val original = conflictOriginalOf(copy) ?: return null
        val (note, copied) = try {
            (library.text(original) ?: return null) to (library.text(copy) ?: return null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("[DEBUG_LOG][NoteFiles] Reading $copy to compare failed: $t")
            return null
        }
        return ConflictComparison(original, copy, note, copied, conflictChunks(note, copied))
    }

    /**
     * Settles a conflict by keeping the note as it is: [copy] is deleted, here and on the server,
     * which keeps it in its trash. A reader looking at the copy is taken to the note.
     */
    suspend fun discardConflictCopy(copy: String) {
        val original = conflictOriginalOf(copy)
        val wasOpen = note.path == copy
        deleteNote(copy)
        if (wasOpen && original != null) openAndWait(original)
    }

    /**
     * Settles a conflict in favour of [copy]: its text becomes the note's, pushed as an edit of the
     * server's version, and the copy is deleted. Returns false, changing nothing, when [copy] is not
     * a conflict copy of a note here.
     */
    suspend fun useConflictCopy(copy: String): Boolean {
        val original = conflictOriginalOf(copy) ?: return false
        val wasOpen = note.path == copy || note.path == original
        // The element editor holds offsets into a text about to be replaced.
        if (wasOpen) editor.cancel()
        if (!store.replaceWithCopy(original, copy)) return false
        forgetPlaces(copy)
        showAndPush(original, wasOpen)
        return true
    }

    /**
     * Settles a conflict chunk by chunk: each chunk of [comparison] as [choices] says, at the same
     * index, becomes the note, pushed as an edit of the server's version, and the copy is deleted.
     * Every chunk the note's way is [discardConflictCopy]; every one the copy's, [useConflictCopy].
     *
     * False, changing nothing, when the note or the copy no longer holds what [comparison] was made
     * from — a sync landed while the choices were made — and they have to be made again.
     */
    suspend fun resolveConflictCopy(comparison: ConflictComparison, choices: List<ChunkChoice>): Boolean {
        val (original, copy, noteText, copyText) = comparison
        if (conflictOriginalOf(copy) != original) return false
        if (store.read(original) != noteText || store.read(copy) != copyText) {
            // What was compared is behind the replica: show what it holds, for new chunks.
            showReplica()
            return false
        }
        val content = resolveConflict(noteText, copyText, choices)
        val wasOpen = note.path == copy || note.path == original
        // The element editor holds offsets into a text about to be replaced.
        if (wasOpen) editor.cancel()
        // Checked again under the store's lock: a sync can still land between the two.
        if (!store.resolveConflict(original, copy, noteText, copyText, content)) {
            showReplica()
            if (wasOpen) note.path?.let { openAndWait(it) }
            return false
        }
        forgetPlaces(copy)
        showAndPush(original, wasOpen)
        return true
    }

    /** What is kept about a note that is gone: its folds, its pin and where it was left. */
    private fun forgetPlaces(path: String) {
        folds.forget(path)
        pins.forget(path)
        places.forget(path)
    }

    /** Shows what the replica holds now, with [path] open if the note it replaced [wasOpen], and pushes it. */
    private suspend fun showAndPush(path: String, wasOpen: Boolean) {
        if (wasOpen) note.path = path
        showReplica()
        if (wasOpen) openAndWait(path)
        server().pushQuietly()
    }
}

/** What became of creating or renaming a note. */
sealed interface NoteFileResult {
    /** It is done, and the note is at [path]. */
    data class Done(val path: String) : NoteFileResult

    /** Nothing was changed, for [reason], which is for the person to read. */
    data class Refused(val reason: String) : NoteFileResult
}

/** A note in conflict and one of its copies, as they were when compared: see [NoteFiles.conflictComparison]. */
data class ConflictComparison(
    val original: String,
    val copy: String,
    val noteText: String,
    val copyText: String,
    val chunks: List<ConflictChunk>,
)
