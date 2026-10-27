package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.edit.continueList
import com.korgnote.common.org.nextSibling
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.previousSibling
import com.korgnote.common.org.replaceOrgElement
import com.korgnote.common.org.sliceOf
import com.korgnote.common.org.subtreeEnd
import com.korgnote.common.org.withNewOrgElement
import com.korgnote.common.org.withOrgSubtreeShifted
import com.korgnote.common.org.withOrgSubtreesSwapped
import com.korgnote.common.org.withoutOrgSubtree

/**
 * Editing a single element of the open note — a heading and its own body — as a draft that is
 * spliced back in when committed.
 */
class ElementEditor(
    private val note: OpenNote,
    private val folds: Folds,
    /** Shows a message to the user. */
    private val showMessage: (String) -> Unit,
    /** Writes the open note to the replica and pushes it. */
    private val save: suspend () -> Unit,
    /** Told whenever the draft or its caret moves; completion follows it. */
    private val draftMoved: () -> Unit,
    /** Told when an edit ends, committed or not. */
    private val editEnded: () -> Unit,
) {
    /** Index into the open note's elements, or null when not editing one. */
    var editingIndex: Int? by mutableStateOf(null)
        private set

    /**
     * The element's text and caret as it is being edited.
     *
     * The caret lives here, not in the editor composable, because completion is a function of where
     * it is: the suggestions are computed from it and the strip that shows them is pinned to the
     * screen rather than drawn next to the field, so neither of them can reach into the field for
     * it. It also means accepting a suggestion is an ordinary state change rather than a message
     * passed down into a composable.
     */
    var draftValue by mutableStateOf(TextFieldValue(""))
        private set

    /** The element's text as it is being edited. */
    val draft: String get() = draftValue.text

    /** Where the caret is; after the stars of a heading that was just added. */
    val draftCaret: Int get() = draftValue.selection.start

    /** [draftValue] while an element is being edited, null otherwise. */
    val activeDraft: TextFieldValue? get() = draftValue.takeIf { editingIndex != null }

    /** The element's text when editing began, used to refuse a splice if the document moved. */
    private var original: String? = null

    /**
     * Set when editing a heading that was just inserted, so cancelling removes it again. Until then
     * the heading is on screen but not in the replica.
     */
    internal var textBeforeInsertion: String? = null
        private set

    fun beginEdit(index: Int) {
        val element = note.content.orgElements().getOrNull(index) ?: return
        val text = element.sliceOf(note.content)
        textBeforeInsertion = null
        original = text
        editingIndex = index
        folds.unfoldAncestorsOf(index)
        setDraft(text, caret = text.length)
    }

    /** Adds a heading at the end of the document and opens it for editing. */
    fun addAtEnd() = insertAndEdit(note.content.length, level = 1)

    /**
     * Adds a sibling heading after [index]'s whole subtree and opens it for editing.
     *
     * After the subtree, not directly after the element: a heading inserted between a parent and its
     * children would take the children over. Below the preamble, it is the first heading.
     */
    fun addAfter(index: Int) {
        val elements = note.content.orgElements()
        val element = elements.getOrNull(index) ?: return
        val end = elements.getOrNull(elements.subtreeEnd(index) - 1)?.endExclusive ?: return
        insertAndEdit(end, level = element.level)
    }

    /**
     * Adds a child heading directly under [index]'s own body, as its first child, and opens it for
     * editing. First rather than last so that it appears next to the heading it was asked from.
     */
    fun addSubheading(index: Int) {
        val element = note.content.orgElements().getOrNull(index) ?: return
        insertAndEdit(element.endExclusive, level = element.level + 1)
    }

    private fun insertAndEdit(at: Int, level: Int) {
        val previousText = note.content
        val insertion = previousText.withNewOrgElement(at, level)
        val elements = insertion.text.orgElements()
        val index = elements.indexOfFirst { it.start == insertion.elementStart }
        if (index < 0) return
        val element = elements[index]

        note.content = insertion.text
        // Nothing is written to disk yet, and cancelling must not leave an empty heading behind.
        textBeforeInsertion = previousText
        original = element.sliceOf(insertion.text)
        editingIndex = index
        folds.unfoldAncestorsOf(index)
        setDraft(original.orEmpty(), caret = insertion.caret - element.start)
    }

    fun onDraftChanged(value: TextFieldValue) {
        draftValue = continuedList(draftValue, value) ?: value
        draftMoved()
    }

    /** [value] with the next list item started, when it is Enter typed at the end of one. */
    private fun continuedList(previous: TextFieldValue, value: TextFieldValue): TextFieldValue? {
        if (!previous.selection.collapsed || !value.selection.collapsed) return null
        if (value.selection.start != previous.selection.start + 1) return null
        val result = continueList(previous.text, previous.selection.start, value.text) ?: return null
        return TextFieldValue(result.text, TextRange(result.caret))
    }

    /** Convenience for callers that have no caret of their own to report; it goes to the end. */
    fun onDraftChanged(newDraft: String) =
        onDraftChanged(TextFieldValue(newDraft, TextRange(newDraft.length)))

    /** An accepted completion; the caller has already decided what comes next for suggestions. */
    internal fun replaceDraft(value: TextFieldValue) {
        draftValue = value
    }

    private fun setDraft(text: String, caret: Int) {
        draftValue = TextFieldValue(text, TextRange(caret.coerceIn(0, text.length)))
        draftMoved()
    }

    fun cancel() {
        // A heading that was inserted for this edit never made it to disk, so discarding the edit
        // discards the heading too.
        textBeforeInsertion?.let { note.content = it }
        clear()
    }

    /**
     * Forgets the edit without touching the document.
     *
     * Kept separate from [cancel] deliberately: committing must not run that method's undo, or
     * saving a newly added heading would restore the text from before it was inserted.
     */
    private fun clear() {
        textBeforeInsertion = null
        editingIndex = null
        original = null
        draftValue = TextFieldValue("")
        editEnded()
    }

    /** Splices the draft back into the document and saves. Leaves the editor open if it cannot. */
    suspend fun commit() {
        val index = editingIndex ?: return
        val original = original ?: return
        val content = note.content
        val element = content.orgElements().getOrNull(index)
        val updated = element?.let { content.replaceOrgElement(it, original, draft) }
        if (updated == null) {
            showMessage("The document changed while you were editing; nothing was written.")
            return
        }
        note.content = updated
        clear()
        save()
    }

    /**
     * Deletes the heading at [index] with its body and every sub-heading, then saves.
     *
     * Refused while an element is open in the editor, as every restructuring is. Returns whether
     * anything was deleted.
     */
    suspend fun deleteSection(index: Int): Boolean = restructure { it.withoutOrgSubtree(index) }

    /**
     * Moves the heading at [index], with its subtree, above its previous sibling or below its next
     * one — `org-move-subtree-up` and `-down` — then saves. Refused where there is no sibling that
     * way: moving out of the parent is a different operation.
     */
    suspend fun moveSection(index: Int, up: Boolean): Boolean {
        val elements = note.content.orgElements()
        val first = if (up) elements.previousSibling(index) ?: return false else index
        val second = if (up) index else elements.nextSibling(index) ?: return false
        val firstSize = second - first
        val secondSize = elements.subtreeEnd(second) - second
        val moved = restructure(
            newIndexOf = { i ->
                when (i) {
                    in first until second -> i + secondSize
                    in second until second + secondSize -> i - firstSize
                    else -> i
                }
            },
        ) { it.withOrgSubtreesSwapped(first, second) }
        if (moved) note.reveal(if (up) first else first + secondSize)
        return moved
    }

    /** Makes the heading at [index] and its subtree one level shallower — `org-promote-subtree`. */
    suspend fun promoteSection(index: Int): Boolean = shiftSection(index, -1)

    /** Makes the heading at [index] and its subtree one level deeper — `org-demote-subtree`. */
    suspend fun demoteSection(index: Int): Boolean = shiftSection(index, +1)

    /** Elements keep their order when restarred, so every fold stays on its index. */
    private suspend fun shiftSection(index: Int, delta: Int): Boolean =
        restructure(newIndexOf = { it }) { it.withOrgSubtreeShifted(index, delta) }

    /**
     * Rewrites the whole note with [edit] and saves, unless it declines by returning null.
     *
     * Refused while an element is open in the editor: the draft's position would move under it.
     * Folds are keyed by heading line, which a restructuring may change; [newIndexOf], when given,
     * carries each fold to where its heading went instead.
     */
    private suspend fun restructure(newIndexOf: ((Int) -> Int)? = null, edit: (String) -> String?): Boolean {
        if (editingIndex != null) {
            showMessage("Save or discard the element you are editing first.")
            return false
        }
        val foldedBefore = folds.folded
        note.content = edit(note.content) ?: return false
        newIndexOf?.let { folds.replaceAll(foldedBefore.map(it).toSet()) }
        save()
        return true
    }
}
