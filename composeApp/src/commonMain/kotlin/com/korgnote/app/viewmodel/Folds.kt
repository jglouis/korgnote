package com.korgnote.app.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.OrgElement
import com.korgnote.common.org.ancestorsOf
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.sliceOf

/**
 * Folded headings, per file, for this session only. Never persisted, never written to the note.
 *
 * Keyed by the heading line and how many identical lines come before it, not by element index:
 * adding a heading anywhere above a fold shifts every index below it, and would move the fold
 * onto the wrong heading. A fold on a heading whose line is edited is simply forgotten.
 */
class Folds(private val note: OpenNote) {

    private var byFile by mutableStateOf<Map<String, Set<HeadingKey>>>(emptyMap())

    private data class HeadingKey(val line: String, val occurrence: Int)

    private fun headingKeys(text: String, elements: List<OrgElement>): List<HeadingKey?> {
        val seen = mutableMapOf<String, Int>()
        return elements.map { element ->
            if (element.isPreamble) null else {
                val line = element.sliceOf(text).substringBefore('\n')
                val occurrence = seen[line] ?: 0
                seen[line] = occurrence + 1
                HeadingKey(line, occurrence)
            }
        }
    }

    /** Indices of the open file's folded headings, into its current elements. */
    val folded: Set<Int> by derivedStateOf {
        val folds = note.path?.let { byFile[it] }.orEmpty()
        if (folds.isEmpty()) return@derivedStateOf emptySet()
        val text = note.content
        headingKeys(text, text.orgElements()).withIndex()
            .filter { (_, key) -> key != null && key in folds }
            .map { it.index }
            .toSet()
    }

    /** Collapses or expands the heading at [index], hiding or showing its body and descendants. */
    fun toggle(index: Int) {
        setFolded { folded -> if (index in folded) folded - index else folded + index }
    }

    /** Opens every fold around [index], so what is there can be seen; and [index] itself if asked. */
    internal fun unfoldAncestorsOf(index: Int, includingItself: Boolean = false) {
        val elements = note.content.orgElements()
        val opening = elements.ancestorsOf(index).toSet() + (if (includingItself) setOf(index) else emptySet())
        if (folded.none { it in opening }) return
        setFolded { folded -> folded - opening }
    }

    /** Folds exactly [indices] of the open file, for an edit that knows where each heading went. */
    internal fun replaceAll(indices: Set<Int>) {
        setFolded { indices }
    }

    /** Carries [from]'s folds over to [to], for a note that was renamed. */
    internal fun move(from: String, to: String) {
        byFile[from]?.let { byFile = byFile - from + (to to it) }
    }

    internal fun forget(path: String) {
        byFile = byFile - path
    }

    private fun setFolded(change: (Set<Int>) -> Set<Int>) {
        val path = note.path ?: return
        val text = note.content
        val keys = headingKeys(text, text.orgElements())
        // Rebuilt from indices each time, which also drops keys whose heading no longer exists.
        val folded = change(folded).mapNotNull { keys.getOrNull(it) }.toSet()
        byFile = byFile + (path to folded)
    }
}
