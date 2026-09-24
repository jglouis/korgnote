package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.link.OrgBacklink
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution

/**
 * Links between notes (T21): following them, the backlinks into the open note, and the way back.
 */
class LinkNavigator(
    private val library: NoteLibrary,
    private val note: OpenNote,
    /** Opens a note, at a character offset in it when there is one. */
    private val open: (path: String, offset: Int?) -> Unit,
    /** Shows a message to the user; an empty one clears it. */
    private val showMessage: (String) -> Unit,
    /** Run before going back, since the element being edited would be left behind. */
    private val beforeGoingBack: () -> Unit,
) {
    /** Resolves links against the notes as last read; rebuilt whenever they change. */
    private var index = OrgLinkIndex(emptyMap())

    /** Each note's own `:ID:` and `#+TITLE:`, which completion writes links with. */
    var noteIds: Map<String, String> = emptyMap()
        private set
    var noteTitles: Map<String, String> = emptyMap()
        private set

    /** Every link in the notes that leads to the open one. */
    var backlinks by mutableStateOf<List<OrgBacklink>>(emptyList())
        private set

    fun refresh() {
        val texts = library.texts()
        index = OrgLinkIndex(texts)
        noteIds = texts.keys.mapNotNull { path -> index.fileIdOf(path)?.let { path to it } }.toMap()
        noteTitles = texts.mapNotNull { (path, text) ->
            TITLE_LINE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { path to it }
        }.toMap()
        refreshBacklinks()
    }

    private fun refreshBacklinks() {
        backlinks = note.path?.let { index.backlinksTo(it) }.orEmpty()
    }

    /** Called when a note is opened: its backlinks, and a reading position at its top. */
    fun onOpened() {
        readingElementIndex = 0
        refreshBacklinks()
    }

    /**
     * Where to go back to: the notes a link was followed from, most recent last, each with the
     * offset of the element that was on screen. Session only, like a browser's history.
     */
    private val history = ArrayDeque<Pair<String, Int>>()
    var canGoBack by mutableStateOf(false)
        private set

    /** The element at the top of the reading view, reported by it so [goBack] can return there. */
    private var readingElementIndex = 0

    fun onReadingPositionChanged(elementIndex: Int) {
        readingElementIndex = elementIndex
    }

    /**
     * Follows a link tapped in reading mode: to the note, and the place in it, that [target] names.
     * Says why when it cannot — the note is gone, the id is unknown, or it is not a note at all.
     */
    fun follow(target: String) {
        val from = note.path ?: return
        when (val resolved = index.resolve(target, from)) {
            is OrgLinkResolution.Note -> {
                rememberHere(from)
                open(resolved.path, resolved.offset)
                showMessage(
                    resolved.missedSearch
                        ?.let { "\"$it\" is not in ${resolved.path} any more; opened the note instead." }
                        .orEmpty()
                )
            }
            is OrgLinkResolution.NotFound -> showMessage("Nothing in the notes matches [[$target]].")
            is OrgLinkResolution.NotANote -> showMessage("[[$target]] is not a note, so it cannot be opened here.")
            // Web links are opened by the platform before they get here; this is the fallback.
            is OrgLinkResolution.External -> showMessage("Open $target in a browser.")
        }
    }

    /** Returns to where the last followed link was tapped. */
    fun goBack() {
        // A note deleted or renamed since is skipped, not shown as an empty placeholder.
        var entry = history.removeLastOrNull() ?: return
        while (entry.first !in library) entry = history.removeLastOrNull() ?: run {
            canGoBack = false
            return
        }
        val (path, offset) = entry
        canGoBack = history.isNotEmpty()
        beforeGoingBack()
        open(path, offset)
    }

    /** Opens the note a backlink is in, at the link. Back returns here, as for a followed link. */
    fun openBacklink(backlink: OrgBacklink) {
        note.path?.let { rememberHere(it) }
        open(backlink.path, backlink.offset)
    }

    private fun rememberHere(from: String) {
        history.addLast(from to note.startOfElement(readingElementIndex))
        canGoBack = true
    }

    private companion object {
        val TITLE_LINE = Regex("(?im)^[ \\t]*#\\+TITLE:[ \\t]*(.+)$")
    }
}
