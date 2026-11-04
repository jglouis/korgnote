package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.edit.DraftLink
import com.korgnote.common.org.edit.linkAt
import com.korgnote.common.org.edit.orgLinkOf
import com.korgnote.common.org.edit.withLink
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.link.withFileId
import com.korgnote.common.org.link.withHeadingId
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.toOrgDocument

/**
 * Writing links into the element being edited, from the link picker and the link form: what they
 * offer, and the link they end up with in the draft. A link to a heading with no `:ID:` gives it one.
 */
class LinkForm(
    private val library: NoteLibrary,
    private val note: OpenNote,
    private val editor: ElementEditor,
    private val completion: CompletionController,
    private val changes: NoteChanges,
    /** A new `:ID:`. */
    private val newId: () -> String,
    /** Rebuilds what is derived from the notes once changes stop coming. */
    private val refreshSoon: () -> Unit,
    /** Where a heading's new id goes next. Read when it is needed, by which time it is there. */
    private val server: () -> NotesSync,
) {
    /**
     * Every note and heading a link can go to, for the heading-link picker: the open note as it
     * stands on screen, so that its offsets are the ones the editor holds. The open note itself is
     * left out as a whole, and so is a heading with no title yet, such as the one just added.
     */
    fun linkTargets(): List<LinkTarget> {
        val documents = library.documents.toMutableMap()
        note.path?.let { documents[it] = note.content.toOrgDocument(library.fallbackKeywords) }
        return linkTargetsOf(documents).filter { target ->
            !(target.isNote && target.path == note.path) && target.title.isNotBlank()
        }
    }

    /**
     * Writes a link to [target] at the caret of the element being edited, as org-roam's
     * `org-roam-node-insert` does: by its `:ID:`, which a heading without one is given first.
     *
     * The id goes where the heading is. In another note it is saved and pushed at once, as Emacs
     * would; harmless if the link is then discarded. In the open note it waits for the element's
     * save, which is the save that writes the link — and the heading being edited gets it in its
     * draft.
     */
    suspend fun insertLinkTo(target: LinkTarget) = writeLinkTo(target, description = null, replacing = null)

    /**
     * [insertLinkTo], showing [description] where that is not null, and in place of [replacing] —
     * a link being changed — rather than of the selection.
     */
    private suspend fun writeLinkTo(target: LinkTarget, description: String?, replacing: TextRange?) {
        val index = editor.editingIndex ?: return
        val draft = editor.activeDraft ?: return
        val id = target.id ?: newId()
        val link = if (description == null) target.linkWith(id) else orgLinkOf("id:$id", description)
        val range = replacing ?: draft.selection
        val from = range.min
        var text = draft.text.replaceRange(from, range.max.coerceAtMost(draft.text.length), link)
        var caret = from + link.length
        val path = target.path
        if (target.id != null) {
            // Already has one: nothing to write but the link.
        } else if (path == note.path) {
            val element = note.content.orgElements().getOrNull(index)
            if (element != null && target.offset == element.start) {
                val withId = withHeadingId(text, 0, id)
                if (text.firstDifferenceWith(withId) < caret) caret += withId.length - text.length
                text = withId
            } else {
                note.content = withHeadingId(note.content, target.offset ?: return, id)
            }
        } else {
            val written = changes.writeOtherNote(path) { before ->
                target.offset?.let { withHeadingId(before, it, id) } ?: withFileId(before, id)
            }
            if (!written) return
            refreshSoon()
            server().pushQuietly()
        }
        // The edit may have ended while the other note was written; the link then has nowhere to go.
        if (editor.editingIndex != index) return
        editor.replaceDraft(TextFieldValue(text, TextRange(caret)))
        completion.onDraftChanged()
    }

    /**
     * The link the link form opens on: the one the caret is in, or a new one in place of the
     * selection, showing the words selected.
     */
    fun linkToEdit(): LinkEdit? {
        val draft = editor.activeDraft ?: return null
        if (draft.selection.collapsed) {
            linkAt(draft.text, draft.selection.start)?.let { return LinkEdit(it, isNew = false) }
        }
        val selected = draft.text.substring(draft.selection.min, draft.selection.max)
        return LinkEdit(
            DraftLink(draft.selection.min, draft.selection.max, "", if ('\n' in selected) "" else selected),
            isNew = true,
        )
    }

    /** Writes what the link form says in place of [edit]'s link; to a note by its id, given one first. */
    suspend fun saveLink(edit: LinkEdit, target: String, description: String, note: LinkTarget? = null) {
        val range = TextRange(edit.link.start, edit.link.end)
        if (note != null) return writeLinkTo(note, description, range)
        val draft = editor.activeDraft ?: return
        if (edit.link.end > draft.text.length) return
        val result = withLink(draft.text, edit.link.start, edit.link.end, target, description)
        editor.replaceDraft(TextFieldValue(result.text, TextRange(result.caret)))
        completion.onDraftChanged()
    }

    /** [edit]'s link turned back into the words it shows, or its target when it shows nothing else. */
    fun removeLink(edit: LinkEdit) {
        val draft = editor.activeDraft ?: return
        if (edit.link.end > draft.text.length) return
        val words = edit.link.description.ifBlank { edit.link.target }
        val text = draft.text.replaceRange(edit.link.start, edit.link.end, words)
        editor.replaceDraft(TextFieldValue(text, TextRange(edit.link.start + words.length)))
        completion.onDraftChanged()
    }

    /** What the link form says a note link leads to: the note's or heading's title, or the link as written. */
    fun noteLinkTitle(target: String): String {
        val id = target.trim().takeIf { it.startsWith("id:", ignoreCase = true) }?.substring(3)?.trim()
        val found = id?.let { wanted -> linkTargets().firstOrNull { it.id == wanted } }
        return found?.title ?: target
    }

    /** Where this text and [other] first differ. */
    private fun String.firstDifferenceWith(other: String): Int =
        indices.firstOrNull { it >= other.length || this[it] != other[it] } ?: length
}
