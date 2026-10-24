package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.OrgTimestamp
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.CompletionContext
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.org.complete.CompletionResult
import com.korgnote.common.org.complete.applyTo
import com.korgnote.common.org.complete.complete
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoKeywordsOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Completion in the element editor (T13): what the strip offers for the draft, and accepting it.
 *
 * The draft itself belongs to [ElementEditor]; this reads it through [draft] and writes an accepted
 * item back through [replaceDraft], and is told through [onDraftChanged] when it moves.
 */
class CompletionController(
    private val library: NoteLibrary,
    private val note: OpenNote,
    private val links: LinkNavigator,
    private val today: () -> LocalDate,
    /** The draft being edited, or null when no element is. */
    private val draft: () -> TextFieldValue?,
    private val replaceDraft: (TextFieldValue) -> Unit,
) {
    /**
     * What the editor's completion draws on.
     *
     * Rebuilt when the replica or the open file changes, never per keystroke: the tags and paths
     * are corpus-wide, and walking every document for them on each character typed is exactly the
     * cost the engine was kept pure to avoid.
     */
    var context by mutableStateOf(CompletionContext(today = today()))
        private set

    /**
     * What the strip is showing, in order, or empty when there is nothing to offer.
     *
     * Recomputed on every keystroke, which is affordable because it is a rule check over one line
     * of one element; the corpus-wide material it draws on is in [context] and is not.
     */
    var suggestions by mutableStateOf<List<CompletionItem>>(emptyList())
        private set

    /** Which suggestion a hardware keyboard has moved to; the strip highlights it. */
    var selectedIndex by mutableStateOf(0)
        private set

    /** The item waiting on a date picker, or null when none is open. */
    var pendingDatePicker: CompletionItem? by mutableStateOf(null)
        private set

    /**
     * The stamp the open picker is changing, or null when it is writing a new one: what the picker
     * starts from, so that moving a date does not begin with finding it again.
     */
    val pickerStamp: OrgTimestamp?
        get() = pendingDatePicker?.let { item -> draft()?.text?.replacedBy(item) }?.let(::parseOrgTimestamp)

    /** Esc silences the suggestions for the current text; typing brings them back. */
    private var dismissed = false

    /**
     * The open file's TODO keywords — its own `#+TODO:`, or what the notes declare when it has
     * none — for everything that renders or edits it, as well as for completion.
     */
    val todoKeywords: OrgTodoConfig get() = context.todoKeywords

    /** The draft moved: typing brings dismissed suggestions back, since Esc silences only this one. */
    fun onDraftChanged() {
        dismissed = false
        refreshSuggestions()
    }

    /** The edit ended: nothing to offer, and no picker left waiting on it. */
    fun reset() {
        pendingDatePicker = null
        dismissed = false
        refreshSuggestions()
    }

    private fun refreshSuggestions() {
        val value = draft()
        suggestions = when {
            value == null || dismissed -> emptyList()
            // A selection is not a caret: completing over one would silently delete it.
            !value.selection.collapsed -> emptyList()
            else -> complete(value.text.toOrgDocument(), value.selection.start, context)
        }
        // The list is rebuilt under the selection on every keystroke, so it starts from the top.
        selectedIndex = 0
    }

    /** Accepts [item]: a range replacement in the draft, with the caret where the item asks. */
    fun accept(item: CompletionItem) {
        // The picker is the one suggestion that cannot answer for itself; it asks first.
        if (item.kind == CompletionItem.Kind.DATE_PICKER) {
            pendingDatePicker = item
            return
        }
        val text = draft()?.text ?: return
        apply(item.applyTo(text))
    }

    /** Accepts whatever the keyboard has moved to. True when there was something to accept. */
    fun acceptSelected(): Boolean {
        val item = suggestions.getOrNull(selectedIndex) ?: return false
        accept(item)
        return true
    }

    fun moveSelection(delta: Int) {
        val count = suggestions.size
        if (count == 0) return
        selectedIndex = ((selectedIndex + delta) % count + count) % count
    }

    fun dismiss() {
        dismissed = true
        suggestions = emptyList()
    }

    /**
     * Puts the date — and the time, when one was picked — where the suggestion would have gone. A
     * stamp it replaces keeps the rest of what it said, such as a repeater.
     */
    fun onDatePicked(date: LocalDate, time: LocalTime? = null) {
        val item = pendingDatePicker ?: return
        pendingDatePicker = null
        val text = draft()?.text ?: return
        apply(item.applyTo(text, restamp(text.replacedBy(item).orEmpty(), date, time)))
    }

    /** The text [item] stands in for, or null when the draft no longer reaches that far. */
    private fun String.replacedBy(item: CompletionItem): String? =
        if (item.replace.last < length) substring(item.replace) else null

    fun dismissDatePicker() {
        pendingDatePicker = null
    }

    private fun apply(result: CompletionResult) {
        replaceDraft(TextFieldValue(result.text, TextRange(result.caret)))
        // Not dismissed: one completion often leads to the next — a planning line wants a date.
        dismissed = false
        refreshSuggestions()
    }

    fun refreshContext() {
        val path = note.path
        val document = path?.let { library[it] }
        val corpusKeywords = library.corpusKeywords
        context = CompletionContext(
            todoKeywords = document?.let { todoKeywordsOf(it.text, corpusKeywords) } ?: corpusKeywords,
            knownTags = tagsByUse(),
            filePaths = library.documents.keys.sorted(),
            currentPath = path.orEmpty(),
            noteIds = links.noteIds,
            noteTitles = links.noteTitles,
            headings = document?.sectionTitles().orEmpty(),
            today = today(),
        )
    }

    /** Every tag in the corpus, most-used first — which is the order they are worth offering in. */
    private fun tagsByUse(): List<String> {
        val counts = mutableMapOf<String, Int>()
        for (document in library.documents.values) {
            for (section in document.allNodes().filterIsInstance<OrgSection>()) {
                for (tag in section.tags) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    private fun OrgDocument.sectionTitles(): List<String> =
        allNodes().filterIsInstance<OrgSection>().map { it.title }
}
