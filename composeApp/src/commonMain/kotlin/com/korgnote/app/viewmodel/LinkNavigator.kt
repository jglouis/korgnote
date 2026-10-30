package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.link.OrgBacklink
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_link_emacs_only
import korgnote.composeapp.generated.resources.vm_link_file_beside_notes
import korgnote.composeapp.generated.resources.vm_link_kbd
import korgnote.composeapp.generated.resources.vm_link_no_app
import korgnote.composeapp.generated.resources.vm_link_not_found
import korgnote.composeapp.generated.resources.vm_link_open_in_browser
import korgnote.composeapp.generated.resources.vm_link_search_missed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * Links between notes (T21): following them, the backlinks into the open note, and the way back.
 */
class LinkNavigator(
    /** Where a message is looked up in the app's language before it is shown. */
    private val scope: CoroutineScope,
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

    fun refresh() = show(Tables(library.texts()))

    /** [refresh], with the tables already built: away from the main thread, say. */
    fun show(tables: Tables) {
        index = tables.index
        noteIds = tables.noteIds
        noteTitles = tables.noteTitles
        refreshBacklinks()
    }

    /**
     * Everything [refresh] reads off the notes. Built from a copy of their text and touching no
     * state, so it can be built on any thread.
     */
    class Tables(texts: Map<String, String>) {
        val index = OrgLinkIndex(texts)
        val noteIds = texts.keys.mapNotNull { path -> index.fileIdOf(path)?.let { path to it } }.toMap()
        val noteTitles = texts.mapNotNull { (path, text) ->
            TITLE_LINE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { path to it }
        }.toMap()

        init {
            // Every note's backlinks are found in one pass, on the first ask. Ask now, so that pass
            // is made wherever the tables are built rather than wherever they are shown.
            index.backlinksTo("")
        }
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
     * Says why when it cannot — the note is gone, the id is unknown, or it is not a note at all —
     * and returns whether it went anywhere.
     */
    fun follow(target: String): Boolean {
        // With no note open, as when a link in the calendar is tapped at launch, a relative link
        // is read from the top of the notes.
        val from = note.path
        return when (val resolved = index.resolve(target, from.orEmpty())) {
            is OrgLinkResolution.Note -> {
                from?.let { rememberHere(it) }
                open(resolved.path, resolved.offset)
                val missed = resolved.missedSearch
                if (missed == null) showMessage("") else say { getString(Res.string.vm_link_search_missed, missed, resolved.path) }
                true
            }
            is OrgLinkResolution.NotFound -> {
                say { getString(Res.string.vm_link_not_found, target) }
                false
            }
            is OrgLinkResolution.NotANote -> {
                say { whyNotANote(target) }
                false
            }
            // Web links are opened by the platform before they get here; this is the fallback.
            is OrgLinkResolution.External -> {
                say { getString(Res.string.vm_link_open_in_browser, target) }
                false
            }
        }
    }

    /** A web or mail link the device has no app for. */
    fun onExternalLinkFailed(target: String) {
        say { getString(Res.string.vm_link_no_app, target) }
    }

    /** Shows the message [text] looks up, once it has. */
    private fun say(text: suspend () -> String) {
        scope.launch { showMessage(text()) }
    }

    /** Why [target], a link to something other than a note, leads nowhere here. */
    private suspend fun whyNotANote(target: String): String =
        when (val scheme = SCHEME.find(target.trim())?.groupValues?.get(1)?.lowercase()) {
            // A file elsewhere on the machine is opened through File links, before it gets here;
            // one kept beside the notes never reaches this device.
            null, "file", "file+sys", "file+emacs" -> getString(Res.string.vm_link_file_beside_notes, target)
            // Its keys are usually in the description, `[[kbd:][C-c C-c]]`, leaving nothing to quote.
            "kbd" -> getString(Res.string.vm_link_kbd)
            else -> getString(Res.string.vm_link_emacs_only, target, scheme)
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

        /** A link's type, read as [OrgLinkIndex] reads it. */
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+-]*):(?!\\s)")
    }
}
