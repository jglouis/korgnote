package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.orgElements

/**
 * The note on screen: which one it is, and its text as it now stands, which may be ahead of the
 * replica while an element is being edited.
 *
 * Opening one is coordinated by [NotesViewModel], since everything else follows the open note.
 */
class OpenNote {
    /** Observable because derived state reads it: the open file's folds depend on which file it is. */
    var path: String? by mutableStateOf(null)
        internal set

    var label by mutableStateOf(NO_FILE_LABEL)
        private set

    var content by mutableStateOf("")
        internal set

    /**
     * Element to bring into view, set when a file is opened at a particular place — a calendar
     * entry, a search hit, a link. Consumed once, via [onScrolledToElement].
     */
    var scrollToElementIndex: Int? by mutableStateOf(null)
        private set

    fun onScrolledToElement() {
        scrollToElementIndex = null
    }

    /**
     * Element to keep in sight after it moved within the note, set by a restructuring. Unlike
     * [scrollToElementIndex] it scrolls only when the element went off screen. Consumed once, via
     * [onRevealed].
     */
    var revealElementIndex: Int? by mutableStateOf(null)
        private set

    internal fun reveal(index: Int) {
        revealElementIndex = index
    }

    fun onRevealed() {
        revealElementIndex = null
    }

    /** The part of opening [path] that needs no text; a placeholder stands in when there is none. */
    internal fun beginOpening(path: String, hasText: Boolean) {
        label = "Selected: $path"
        this.path = path
        scrollToElementIndex = null
        if (!hasText) content = "* ${path}\nNo content yet."
    }

    /**
     * Shows [text], brought into view at the element containing [scrollToOffset]. Returns that
     * element's index, or null when there is nowhere to scroll to.
     */
    internal fun show(text: String, scrollToOffset: Int?): Int? {
        content = text
        scrollToElementIndex = scrollToOffset?.let { text.elementIndexOfOffset(it) }
        return scrollToElementIndex
    }

    /** Nothing open, because there is nothing to open. */
    internal fun close() {
        path = null
        content = ""
        label = NO_FILE_LABEL
    }

    /** Where the element at [index] starts, or the top of the note when there is no such element. */
    fun startOfElement(index: Int): Int = content.orgElements().getOrNull(index)?.start ?: 0

    companion object {
        const val NO_FILE_LABEL = "No file selected"
    }
}

/**
 * The element containing [offset], or null when the text no longer reaches that far.
 *
 * This is how a search result or an agenda entry finds its place: each carries a character
 * offset, and every element already knows the range of text it renders.
 */
internal fun String.elementIndexOfOffset(offset: Int): Int? =
    orgElements().indexOfFirst { offset >= it.start && offset < it.endExclusive }.takeIf { it >= 0 }
