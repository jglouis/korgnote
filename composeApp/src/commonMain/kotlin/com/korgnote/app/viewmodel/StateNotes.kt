package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.LogKind
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.stateChangeLog
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoLoggingOf
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_edit_conflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.getString

/**
 * A keyword change waiting on the note its keyword's `@` asks for: which heading, by where it
 * starts and the line it was, and the keyword.
 */
data class PendingStateNote(val offset: Int, val headingLine: String, val title: String, val keyword: String, val closing: Boolean)

/**
 * Changing a heading's keyword, and the note a keyword's `@` asks for, written in a field of its own
 * with what the element editor offers, as Org's note buffer is.
 */
class StateNotes(
    private val scope: CoroutineScope,
    private val library: NoteLibrary,
    private val note: OpenNote,
    links: LinkNavigator,
    private val changes: NoteChanges,
    today: () -> LocalDate,
    /** The wall-clock time state changes are stamped with. */
    private val now: () -> LocalDateTime,
    author: () -> String,
    /** The open note's keywords, as completion knows them. */
    private val todoKeywords: () -> OrgTodoConfig,
    /** Opens a note, for those going on to change it: back once its text is there. */
    private val openAndWait: suspend (String) -> Unit,
    /** Shows a message to the user. */
    private val showMessage: (String) -> Unit,
) {
    /** The change waiting on a note, or null when none is. */
    var pending by mutableStateOf<PendingStateNote?>(null)
        private set

    /** The note being written for [pending], and its caret. */
    var draft by mutableStateOf(TextFieldValue(""))
        private set

    /** Completion in the note, as in the element editor: the same suggestions, over the note's own text. */
    val completion: CompletionController = CompletionController(
        library,
        note,
        links,
        today,
        draft = { draft.takeIf { pending != null } },
        replaceDraft = { onDraftChanged(it) },
        author = author,
    )

    fun onDraftChanged(value: TextFieldValue) {
        draft = value
        completion.onDraftChanged()
    }

    /**
     * Sets the keyword of the heading at [offsetInDocument], or clears it when [keyword] is null, as
     * [NoteChanges.toggleCheckboxAt] ticks a checkbox: the file is saved and pushed the same way.
     *
     * The change is logged under the heading with the time it was made, as Org logs a state change.
     * Marking a repeating entry done repeats it, as Org does: it goes back to its first keyword and
     * its dates move on to the next occurrence.
     */
    fun setTodoAt(offsetInDocument: Int, keyword: String?) {
        val document = note.content.toOrgDocument(library.fallbackKeywords)
        val heading = document.nodeAt<OrgSection>(offsetInDocument) ?: return
        // A keyword with `@` wants a note: the change waits for it, as Org's note buffer does.
        if (keyword != null && document.stateChangeLog(heading, keyword, todoKeywords()) == LogKind.Note) {
            val line = document.text.substring(heading.range.first).substringBefore('\n')
            val closing = todoLoggingOf(document.text).forChange(heading.todo, keyword) == null
            pending = PendingStateNote(heading.range.first, line, heading.title, keyword, closing)
            draft = TextFieldValue("")
            completion.refreshContext()
            completion.reset()
            return
        }
        changes.changeTodoStateAt(heading.range.first, keyword)
    }

    /** The keywords of the note at [path]: its own `#+TODO:`, or Org's own when it declares none. */
    fun keywordsOf(path: String): OrgTodoConfig =
        if (path == note.path) todoKeywords() else library[path]?.todoKeywords ?: library.fallbackKeywords

    /**
     * [setTodoAt] for the heading at [headingOffset] of the note at [path], open or not: from the
     * calendar, say. A keyword that asks for a note (`@`) opens the note first, so that the note
     * is asked for over the heading it goes under, as it is from the note itself; any other change
     * is made in the note where it is, logged and repeated as Org does.
     */
    fun setTodo(path: String, headingOffset: Int, keyword: String?) {
        if (path == note.path) {
            if (changes.refuseWhileEditing()) return
            setTodoAt(headingOffset, keyword)
            return
        }
        val document = library[path] ?: return
        val heading = document.nodeAt<OrgSection>(headingOffset) ?: return
        val keywords = document.todoKeywords
        if (keyword != null && document.stateChangeLog(heading, keyword, keywords) == LogKind.Note) {
            if (changes.refuseWhileEditing()) return
            scope.launch {
                openAndWait(path)
                if (note.path == path) setTodoAt(headingOffset, keyword)
            }
            return
        }
        val at = now()
        scope.launch {
            changes.changeNote(path) { doc ->
                val target = doc.nodeAt<OrgSection>(headingOffset) ?: return@changeNote doc
                doc.changeTodoState(target, keyword, at, keywords).withStatistics(keywords, since = doc)
            }
        }
    }

    /** Changes the keyword [pending] waits on, logging what was written. */
    fun save() {
        val waiting = pending ?: return
        pending = null
        completion.reset()
        // The note may have taken a while: the heading must still be where, and what, it was.
        val line = note.content.drop(waiting.offset).substringBefore('\n')
        if (line != waiting.headingLine) {
            scope.launch { showMessage(getString(Res.string.vm_edit_conflict)) }
            return
        }
        changes.changeTodoStateAt(waiting.offset, waiting.keyword, draft.text)
    }

    /** Drops the change [pending] waits on: the keyword stays as it was. */
    fun cancel() {
        pending = null
        completion.reset()
    }
}
