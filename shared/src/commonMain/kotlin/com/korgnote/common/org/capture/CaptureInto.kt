package com.korgnote.common.org.capture

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.edit.PlanningKind
import com.korgnote.common.org.edit.setPlanning
import com.korgnote.common.org.edit.setProperty
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.descriptionOf
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.org.toOrgDocument
import kotlinx.serialization.Serializable

/**
 * A heading to capture into, named so it can be found again when the capture is filed, which may
 * be later, after the note changed: by its `:ID:` when it has one, by its place in the outline
 * otherwise.
 */
@Serializable
data class HeadingRef(
    val path: String,
    /** The headings above it, outermost first, as [LinkTarget.outline] has them. */
    val outline: List<String> = emptyList(),
    val title: String,
    val id: String? = null,
) {
    companion object {
        /** The heading [target] names; null for a note, which is not a heading. */
        fun of(target: LinkTarget): HeadingRef? =
            if (target.isNote) null else HeadingRef(target.path, target.outline, target.title, target.id)
    }
}

/**
 * What of a capture goes into an existing heading: each a box to tick, rather than Org to write.
 */
@Serializable
data class HeadingAdditions(
    /** The place, as the heading's `:LOCATION:`. */
    val location: Boolean = false,
    /** The link, as a list item with its title: `- [[link][title]]`. */
    val link: Boolean = false,
    /** When, as the heading's `SCHEDULED:`, or as a timestamp for an event across days. */
    val time: Boolean = false,
    /** The text, under what is there. */
    val text: Boolean = false,
) {
    val any: Boolean get() = location || link || time || text

    companion object {
        /**
         * What [captured] most likely went to a heading for: a place its location — and its link,
         * the way back to the map — an event its time, a page its link, and words their words.
         */
        fun defaultsFor(captured: Captured): HeadingAdditions {
            val place = captured.location != null || captured.geo != null || isMapLink(captured.link)
            return when {
                captured.time != null -> HeadingAdditions(time = true, location = captured.location != null)
                // As a new entry does: the place as the heading's location, its link only if asked.
                place -> HeadingAdditions(location = true)
                // A quote shared with its page: the words are what was selected, the link where.
                captured.link != null -> HeadingAdditions(link = true, text = captured.text.isNotBlank())
                else -> HeadingAdditions(text = captured.text.isNotBlank() || captured.title.isNotBlank())
            }
        }
    }
}

/** What [additions] can take from [captured]: the boxes with something behind them. */
fun HeadingAdditions.Companion.available(captured: Captured) = HeadingAdditions(
    location = captured.location != null || isMapLink(captured.link),
    link = captured.link != null,
    time = captured.time != null,
    text = captured.text.isNotBlank() || captured.title.isNotBlank(),
)

/**
 * [file] with [captured] added to the heading [heading] names, as [additions] says: the location
 * set as its `:LOCATION:` property, and the rest — a link, a time, text — added after its own
 * content, above its first sub-heading. Null when the note no longer has that heading.
 *
 * The property goes where Org puts one, which may change a line another device changed too; the
 * rest only adds lines, which merges.
 */
fun captureInto(file: String, heading: HeadingRef, captured: Captured, additions: HeadingAdditions): String? {
    var text = file
    var section = findHeading(text, heading) ?: return null
    if (additions.time) captured.schedulable?.let { stamp ->
        text = document(text).setPlanning(section.section, PlanningKind.SCHEDULED, stamp).text
        section = findHeading(text, heading) ?: return null
    }
    if (additions.location) {
        val place = captured.location ?: captured.title
        if (place.isNotBlank()) {
            text = document(text).setProperty(section.section, "LOCATION", oneLineOf(place)).text
            section = findHeading(text, heading) ?: return null
        }
    }
    val lines = buildList {
        if (additions.time) captured.range?.let { add(it) }
        if (additions.link) captured.link?.let { link ->
            val title = captured.title.replace('[', '(').replace(']', ')').trim()
            add(if (title.isEmpty() || title == link) "- [[$link]]" else "- [[$link][$title]]")
        }
        if (additions.text) {
            // A title the user typed for words is words too; a page's title is already its link's.
            val words = if (captured.link == null && captured.title.isNotBlank()) {
                listOf(captured.title, captured.text).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                captured.text
            }
            words.trim('\n').lines().forEach { add(if (OrgSyntax.heading.containsMatchIn(it)) " $it" else it) }
        }
    }
    if (lines.isEmpty()) return text
    return insertBeforeChildren(text, section, lines.joinToString("\n") + "\n")
}

/** [heading] in [text], with where its own content ends: before its first sub-heading. */
private class Found(val section: OrgSection, val ownEnd: Int)

private fun document(text: String): OrgDocument = text.toOrgDocument(todoKeywordsOf(text, DEFAULT_TODO_KEYWORDS))

/** The heading [ref] names in [text]: the one with its `:ID:`, or else the first with its outline and title. */
private fun findHeading(text: String, ref: HeadingRef): Found? {
    val sections = mutableListOf<Pair<OrgSection, List<String>>>()
    fun walk(children: List<*>, outline: List<String>) {
        for (child in children) {
            if (child !is OrgSection) continue
            sections += child to outline
            walk(child.children, outline + descriptionOf(child.title))
        }
    }
    walk(document(text).children, emptyList())
    val match = ref.id?.let { id -> sections.firstOrNull { (section, _) -> idOf(section) == id } }
        ?: sections.firstOrNull { (section, outline) -> sameOutline(outline, ref.outline) && sameTitle(descriptionOf(section.title), ref.title) }
        ?: return null
    val section = match.first
    val firstChild = section.children.firstOrNull { it is OrgSection }
    return Found(section, firstChild?.range?.first ?: (section.range.last + 1))
}

/**
 * Whether two titles name one heading. The picker reads a heading with the keywords every note
 * declares, and a note read alone knows only its own: `EVENT test` there is `test` here.
 */
private fun sameTitle(a: String, b: String): Boolean =
    a == b || withoutKeyword(a) == b || a == withoutKeyword(b) || withoutKeyword(a) == withoutKeyword(b)

private fun sameOutline(a: List<String>, b: List<String>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) -> sameTitle(x, y) }

/** [title] without a first word in capitals, which a TODO keyword is. */
private fun withoutKeyword(title: String): String =
    KEYWORD.find(title)?.let { title.substring(it.range.last + 1) } ?: title

private val KEYWORD = Regex("^[A-Z][A-Z_-]+\\s+")

/**
 * [file] with [entry] filed as a new entry under the heading [heading] names: after what is there,
 * a level below it. Null when the note no longer has that heading.
 */
fun fileUnder(file: String, heading: HeadingRef, entry: String): String? {
    val found = findHeading(file, heading) ?: return null
    val level = found.section.level
    // The end of the heading's whole subtree: its last line, children included.
    val end = (found.section.range.last + 1).coerceAtMost(file.length)
    var at = end
    while (at > 0) {
        val lineStart = file.lastIndexOf('\n', at - 2) + 1
        if (lineStart >= at || file.substring(lineStart, at).isNotBlank()) break
        at = lineStart
    }
    val body = entry.trimEnd('\n').lines().joinToString("\n") { line ->
        if (OrgSyntax.heading.containsMatchIn(line)) "*".repeat(level) + line else line
    } + "\n"
    val before = file.substring(0, at)
    return before + (if (before.isEmpty() || before.endsWith('\n')) "" else "\n") + body + file.substring(at)
}

private fun idOf(section: OrgSection): String? =
    section.children.asSequence()
        .filterIsInstance<com.korgnote.common.org.OrgDrawer>()
        .filter { it.name.equals("PROPERTIES", ignoreCase = true) }
        .flatMap { it.properties }
        .firstOrNull { (key, _) -> key.equals("ID", ignoreCase = true) }
        ?.second?.trim()

/** [text] with [lines] at the end of [found]'s own content, before the blank lines that end it. */
private fun insertBeforeChildren(text: String, found: Found, lines: String): String {
    var at = found.ownEnd.coerceAtMost(text.length)
    // The section's last line may have no newline, at the end of the note.
    if (at == text.length && text.isNotEmpty() && !text.endsWith('\n')) return text + "\n" + lines
    while (at > 0) {
        val lineStart = text.lastIndexOf('\n', at - 2) + 1
        if (lineStart >= at || text.substring(lineStart, at).isNotBlank()) break
        at = lineStart
    }
    return text.substring(0, at) + lines + text.substring(at)
}

private fun oneLineOf(text: String) = text.replace(Regex("\\s*\\n\\s*"), " ").trim()

/** Whether [link] is a map's: a place shared from Google or Apple Maps, or `geo:`. */
internal fun isMapLink(link: String?): Boolean {
    if (link == null) return false
    if (link.startsWith("geo:")) return true
    val host = link.substringAfter("://", "").substringBefore('/').lowercase()
    val path = link.substringAfter("://", "").substringAfter('/', "")
    return host == "maps.app.goo.gl" || (host == "goo.gl" && path.startsWith("maps")) || host.startsWith("maps.google.") ||
        (host.removePrefix("www.").startsWith("google.") && path.startsWith("maps")) || host == "maps.apple.com" || host == "maps.apple"
}
