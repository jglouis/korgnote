package com.korgnote.common.org.link

import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.edit.STATISTICS_COOKIE
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.sliceOf

/**
 * Somewhere an org-roam link can lead: a note as a whole, or any heading in it.
 *
 * [offset] is where the heading's line starts in the note, or null for the note itself; [id] is the
 * `:ID:` it already has, or null when linking to it means giving it one first.
 */
data class LinkTarget(
    val path: String,
    val noteTitle: String,
    /** The headings above this one, outermost first; empty for a top-level heading or the note. */
    val outline: List<String>,
    /** The heading as a link's description shows it: no keyword, priority, cookie, tags or link syntax. */
    val title: String,
    val offset: Int?,
    val id: String?,
) {
    val isNote: Boolean get() = offset == null

    /** `Note › Parent`, or the note's path for a note; what the picker says under the title. */
    val where: String get() = if (isNote) path else (listOf(noteTitle) + outline).joinToString(" › ")

    /** `[[id:…][title]]`, for the id it has or is about to be given. */
    fun linkWith(id: String): String = "[[id:$id][${title.ifBlank { noteTitle }}]]"
}

/**
 * Every note in [notes] and every heading in each, in path and then document order, for picking
 * where a link goes.
 */
fun linkTargetsOf(notes: Map<String, OrgDocument>): List<LinkTarget> = buildList {
    for ((path, document) in notes.entries.sortedBy { it.key }) {
        val noteTitle = TITLE.find(document.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: path.substringAfterLast('/').removeSuffix(".org")
        add(LinkTarget(path, noteTitle, emptyList(), noteTitle, offset = null, id = fileIdIn(document.text)))
        fun walk(sections: List<OrgSection>, outline: List<String>) {
            for (section in sections) {
                val title = descriptionOf(section.title)
                add(LinkTarget(path, noteTitle, outline, title, section.range.first, section.ownId()))
                walk(section.children.filterIsInstance<OrgSection>(), outline + title)
            }
        }
        walk(document.children.filterIsInstance<OrgSection>(), emptyList())
    }
}

/**
 * The targets matching [query], word by word in any order, against the note's title and path and
 * the heading's outline. Headings of [currentPath] come first, the note being written being the one
 * most often linked within.
 */
fun List<LinkTarget>.matching(query: String, currentPath: String, limit: Int = 200): List<LinkTarget> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return asSequence()
        .filter { target ->
            val haystack = "${target.noteTitle} ${target.path} ${target.outline.joinToString(" ")} ${target.title}".lowercase()
            words.all { it in haystack }
        }
        .sortedBy { if (it.path == currentPath) 0 else 1 }
        .take(limit)
        .toList()
}

/** A heading's title as a link's description: the text a reader sees, with no brackets to end the link early. */
internal fun descriptionOf(title: String): String {
    var text = OrgSyntax.link.replace(title) { m -> m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: m.groupValues[1] }
    text = STATISTICS_COOKIE.replace(text, "")
    OrgSyntax.priority.find(text)?.let { text = text.removeRange(it.range) }
    return text.replace('[', '(').replace(']', ')').replace(Regex("\\s+"), " ").trim()
}

/** The `:ID:` of the section's own property drawer, not one of a sub-heading's. */
private fun OrgSection.ownId(): String? =
    children.asSequence()
        .filterIsInstance<OrgDrawer>()
        .filter { it.name.equals("PROPERTIES", ignoreCase = true) }
        .flatMap { it.properties }
        .firstOrNull { (key, _) -> key.equals("ID", ignoreCase = true) }
        ?.second?.trim()?.takeIf { it.isNotEmpty() }

/** The `:ID:` a note declares for itself, above its first heading. */
private fun fileIdIn(text: String): String? {
    val preamble = text.orgElements().firstOrNull()?.takeIf { it.isPreamble } ?: return null
    return FILE_ID_LINE.find(preamble.sliceOf(text))?.groupValues?.get(1)
}

/**
 * [text] with [id] as the `:ID:` of the heading whose line starts at [headingStart], as org-roam's
 * `org-id-get-create` gives one: in the heading's property drawer, made under its planning line
 * when it has none. A heading that already has an id keeps it, and the text comes back unchanged.
 */
fun withHeadingId(text: String, headingStart: Int, id: String): String {
    if (headingStart !in 0..text.length || !OrgSyntax.heading.containsMatchIn(lineAt(text, headingStart))) return text
    var lineStart = nextLine(text, headingStart)
    if (lineStart != null && OrgSyntax.planning.containsMatchIn(lineAt(text, lineStart))) {
        lineStart = nextLine(text, lineStart)
    }
    if (lineStart == null || !PROPERTIES.matches(lineAt(text, lineStart))) {
        val drawer = ":PROPERTIES:\n:ID:       $id\n:END:\n"
        // At the end of the note, after a newline of its own when the last line has none.
        if (lineStart == null) return text + (if (text.endsWith('\n')) "" else "\n") + drawer
        return text.replaceRange(lineStart, lineStart, drawer)
    }
    return withIdInDrawer(text, lineStart, id)
}

/**
 * [text] with [id] as the note's own `:ID:`, in the property drawer that opens it, made at the very
 * top when there is none — where org-roam looks for it. Unchanged when it already has one.
 */
fun withFileId(text: String, id: String): String {
    if (fileIdIn(text) != null) return text
    if (PROPERTIES.matches(lineAt(text, 0))) return withIdInDrawer(text, 0, id)
    return ":PROPERTIES:\n:ID:       $id\n:END:\n$text"
}

/** [text] with `:ID:` written into the drawer opened by the `:PROPERTIES:` line at [drawerStart]. */
private fun withIdInDrawer(text: String, drawerStart: Int, id: String): String {
    var line = nextLine(text, drawerStart)
    while (line != null) {
        val content = lineAt(text, line)
        if (ID_PROPERTY.containsMatchIn(content)) return text
        if (OrgSyntax.drawerEnd.containsMatchIn(content)) return text.replaceRange(line, line, ":ID:       $id\n")
        line = nextLine(text, line)
    }
    return text
}

private fun lineAt(text: String, start: Int): String =
    text.substring(start, text.indexOf('\n', start).let { if (it < 0) text.length else it })

/** Where the line after the one starting at [start] begins, or null when that one is the last. */
private fun nextLine(text: String, start: Int): Int? = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1)
    ?.takeIf { it < text.length }

private val TITLE = Regex("^#\\+TITLE:\\s*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
private val PROPERTIES = Regex("^\\s*:PROPERTIES:\\s*$", RegexOption.IGNORE_CASE)
private val FILE_ID_LINE = Regex("(?im)^[ \\t]*:ID:[ \\t]+(\\S+)[ \\t]*$")
private val ID_PROPERTY = Regex("^\\s*:ID:", RegexOption.IGNORE_CASE)
