package com.korgnote.common.index

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.everyMatch
import com.korgnote.common.org.agenda.ClockedSpan
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.org.agenda.clockedSpans
import com.korgnote.common.org.edit.LastClocked
import com.korgnote.common.org.edit.RunningClock
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.lastClocked
import com.korgnote.common.org.edit.runningClocks
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution
import com.korgnote.common.org.link.descriptionOf
import com.korgnote.common.org.link.fileIdIn
import com.korgnote.common.org.ownId
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.orgNoteTitle
import com.korgnote.common.org.search.fileTagsOf
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.replica.contentHash
import kotlinx.serialization.Serializable

/**
 * What the app needs to know about a note without having its text: everything the calendar, the
 * pickers, completion and the backlinks show. A small fraction of the note's size, which is what lets
 * the text itself be read only when somebody asks for it.
 *
 * Worked out from the text alone, so it can be thrown away and worked out again at any time.
 */
@Serializable
data class NoteFacts(
    /** The note's `#+TITLE:`; null when it has none, and what is shown is then made from its name. */
    val title: String? = null,
    /** The `:ID:` of the note as a whole. */
    val fileId: String? = null,
    /** The keywords it declares with `#+TODO:`; null when it declares none and Org's own apply. */
    val todoKeywords: OrgTodoConfig? = null,
    /** How often each tag is used. */
    val tags: Map<String, Int> = emptyMap(),
    /** How often each `:LOCATION:` is named. */
    val locations: Map<String, Int> = emptyMap(),
    /** Every `:ID:` it declares, in document order. */
    val ids: List<DeclaredId> = emptyList(),
    /** Every link in it that may lead to a note, in document order. */
    val links: List<NoteLink> = emptyList(),
    /** Its `#+LINK:` abbreviations. */
    val abbreviations: Map<String, String> = emptyMap(),
    /** Its headings, for the pickers, in document order; at most [MAX_HEADINGS]. */
    val headings: List<HeadingFact> = emptyList(),
    val agenda: List<AgendaEntry> = emptyList(),
    val clocked: List<ClockedSpan> = emptyList(),
    val running: List<RunningClock> = emptyList(),
    val lastClocked: List<LastClocked> = emptyList(),
    /** Why nothing is known about it, when something got in the way of reading it. */
    val leftOut: LeftOut? = null,
    /**
     * The hash of the text these were worked out from. An offset in them is only good in that text,
     * and the note may have moved on since: see [isFrom].
     */
    val textHash: Long? = null,
) {
    /**
     * Roughly what these facts hold on the heap, for telling when there are too many: each string's
     * characters at two bytes, and an object's worth for everything in a list.
     */
    val weight: Long by lazy {
        fun chars(text: String?) = (text?.length ?: 0) * 2L + 24
        var total = 200L + chars(title) + chars(fileId)
        for ((tag, _) in tags) total += chars(tag) + 48
        for ((place, _) in locations) total += chars(place) + 48
        for (id in ids) total += chars(id.id) + 24
        for (link in links) total += chars(link.target) + chars(link.context) + 32
        for ((key, value) in abbreviations) total += chars(key) + chars(value) + 48
        for (heading in headings) total += chars(heading.title) + chars(heading.id) + 32
        for (entry in agenda) total += chars(entry.title) + 240
        for (span in clocked) total += chars(span.title) + 120
        for (clock in running) total += chars(clock.title) + 64
        for (clock in lastClocked) total += chars(clock.title) + 64
        total
    }

    /** Whether the calendar has anything to show from this note, or the clocks. */
    val isDated: Boolean
        get() = agenda.isNotEmpty() || clocked.isNotEmpty() || running.isNotEmpty() || lastClocked.isNotEmpty()

    /** Whether these facts, and any offset from them, are about [text]. */
    fun isFrom(text: String): Boolean = textHash == null || textHash == contentHash(text)

    /** The keywords its headings are read with. */
    val keywords: OrgTodoConfig get() = todoKeywords ?: DEFAULT_TODO_KEYWORDS

    companion object {
        /**
         * How many of a note's headings the pickers offer. A generated log can run to many thousands,
         * nobody links to the five-thousandth by name, and each one is held in memory all the time.
         */
        const val MAX_HEADINGS = 1000
    }
}

/** Why a note has no [NoteFacts] to speak of. It can still be searched and, if it fits, opened. */
@Serializable
enum class LeftOut {
    /** Bigger than this device can safely hold in memory: see [NoteBudgets.maxNoteBytes]. */
    TooLarge,

    /** Reading or scanning it failed: running out of memory, on this device, most likely. */
    Unreadable,

    /** It would have pushed all the notes' facts together over [NoteBudgets.facts]. */
    NoRoom,
}

/** An `:ID:` a note declares, and where. */
@Serializable
data class DeclaredId(val id: String, val offset: Int)

/** A link in a note. */
@Serializable
data class NoteLink(
    /** As written: `id:…`, `file:…`, or a bare path or heading. */
    val target: String,
    val offset: Int,
    /** The heading it is under, or null above the first heading: a backlink is shown with it. */
    val context: String?,
    /** Whether it was found to lead to a place in the same note, which is only ever known with its text. */
    val withinNote: Boolean = false,
)

/** A heading a link can go to. */
@Serializable
data class HeadingFact(
    /** How many headings it is under: the outline a picker shows is made from this. */
    val depth: Int,
    val title: String,
    val offset: Int,
    val id: String? = null,
)

/** What is known about the note at [path], worked out from its [text]. */
fun factsOf(path: String, text: String, document: OrgDocument = text.toOrgDocument()): NoteFacts {
    val nodes = document.allNodes()
    val sections = nodes.filterIsInstance<OrgSection>()

    val tags = mutableMapOf<String, Int>()
    for (tag in fileTagsOf(text)) tags[tag] = (tags[tag] ?: 0) + 1
    for (section in sections) for (tag in section.tags) tags[tag] = (tags[tag] ?: 0) + 1

    val locations = mutableMapOf<String, Int>()
    if (text.contains(":LOCATION:", ignoreCase = true)) {
        for (drawer in nodes.filterIsInstance<OrgDrawer>()) {
            for ((key, value) in drawer.properties) {
                if (key.equals("LOCATION", ignoreCase = true) && value.isNotBlank()) {
                    locations[value] = (locations[value] ?: 0) + 1
                }
            }
        }
    }

    val abbreviations = abbreviationsIn(text)
    val keywords = todoKeywordsOf(text, DEFAULT_TODO_KEYWORDS)
    val clocks = text.contains("CLOCK:")
    return NoteFacts(
        title = orgNoteTitle(text),
        fileId = fileIdIn(text),
        todoKeywords = keywords.takeUnless { it == DEFAULT_TODO_KEYWORDS },
        tags = tags,
        locations = locations,
        ids = declaredIdsIn(text),
        links = linksIn(path, text, abbreviations),
        abbreviations = abbreviations,
        headings = headingsOf(document),
        agenda = agendaEntries(path, text),
        clocked = clockedSpans(path, text),
        running = if (clocks) document.runningClocks() else emptyList(),
        lastClocked = if (clocks) document.lastClocked() else emptyList(),
        textHash = contentHash(text),
    )
}

/** What the pickers need of the note [text], the one being edited: it may be ahead of its file. */
fun pickerFactsOf(text: String): NoteFacts {
    val document = text.toOrgDocument()
    return NoteFacts(title = orgNoteTitle(text), fileId = fileIdIn(text), headings = headingsOf(document))
}

private fun headingsOf(document: OrgDocument): List<HeadingFact> {
    val headings = ArrayList<HeadingFact>()
    fun walk(sections: List<OrgSection>, depth: Int) {
        for (section in sections) {
            if (headings.size >= NoteFacts.MAX_HEADINGS) return
            headings += HeadingFact(depth, descriptionOf(section.title), section.range.first, section.ownId)
            walk(section.children.filterIsInstance<OrgSection>(), depth + 1)
        }
    }
    walk(document.children.filterIsInstance<OrgSection>(), 0)
    return headings
}

private val LINK_KEYWORD = Regex("(?im)^[ \\t]*#\\+LINK:[ \\t]+(\\S+)[ \\t]+(\\S.*?)[ \\t]*$")

/** [text]'s `#+LINK:` abbreviations: the first declaration of each wins. */
private fun abbreviationsIn(text: String): Map<String, String> {
    if (!text.contains("#+LINK:", ignoreCase = true)) return emptyMap()
    val declared = mutableMapOf<String, String>()
    for (m in LINK_KEYWORD.everyMatch(text)) declared.getOrPut(m.groupValues[1]) { m.groupValues[2] }
    return declared
}

private fun declaredIdsIn(text: String): List<DeclaredId> {
    if (!text.contains(":ID:", ignoreCase = true)) return emptyList()
    return OrgSyntax.idProperty.everyMatch(text).map { DeclaredId(it.groupValues[1], it.range.first) }.toList()
}

/**
 * The links in [text] that may lead to a note. Which note, for an `id:` or a `file:`, depends on the
 * other notes and is settled later by [OrgLinkIndex]. A plain `[[text]]` can only lead into this one,
 * and whether it finds anything there is settled now, the text being at hand.
 */
private fun linksIn(path: String, text: String, abbreviations: Map<String, String>): List<NoteLink> {
    if (!text.contains("[[")) return emptyList()
    val elements by lazy { text.orgElements() }
    val keywords by lazy { todoKeywordsOf(text, DEFAULT_TODO_KEYWORDS) }
    val alone by lazy {
        OrgLinkIndex(mapOf(path to NoteFacts(abbreviations = abbreviations))) { if (it == path) text else null }
    }
    // The links come in document order, so the element each is in only ever moves on.
    var at = -1
    val found = ArrayList<NoteLink>()
    for (m in OrgSyntax.link.everyMatch(text)) {
        val target = m.groupValues[1].trim()
        val scheme = OrgSyntax.linkSchemeOf(target)
        val withinNote = when {
            scheme == "id" -> false
            scheme in OrgSyntax.fileLinkSchemes -> {
                if (!target.substringAfter(':').substringBefore("::").endsWith(".org")) continue
                false
            }
            scheme != null -> continue
            target.startsWith("/") || target.startsWith("~") || target.startsWith("./") || target.startsWith("../") -> {
                if (!target.endsWith(".org")) continue
                false
            }
            // Into the same note, and only when it finds something there, or every stray `[[x]]`
            // would list the note as linking to itself.
            alone.resolve(target, path) is OrgLinkResolution.Note -> true
            else -> continue
        }
        while (at + 1 < elements.size && elements[at + 1].start <= m.range.first) at++
        val element = elements.getOrNull(at)
        val context = element?.takeUnless { it.isPreamble }?.let { orgHeadingOf("* ${it.title}", keywords).title }
        found += NoteLink(target, m.range.first, context, withinNote)
    }
    return found
}
