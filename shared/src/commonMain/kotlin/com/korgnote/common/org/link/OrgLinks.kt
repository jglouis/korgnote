package com.korgnote.common.org.link

import com.korgnote.common.index.NoteFacts
import com.korgnote.common.index.factsOf
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.todoKeywordsOf
import com.korgnote.common.org.sliceOf

/** Where a link leads, as far as the notes can say. */
sealed interface OrgLinkResolution {
    /**
     * A place in a note: [offset] into its text, or null for its top. [missedSearch] is set when
     * the note was found but the heading or target named after its `::` was not — the note is
     * still opened, as Org opens the file and says "No match".
     */
    data class Note(val path: String, val offset: Int?, val missedSearch: String? = null) : OrgLinkResolution

    /** Something a browser or mail client opens. */
    data class External(val url: String) : OrgLinkResolution

    /** A link to a note, or into one, that is not there. */
    data class NotFound(val link: String) : OrgLinkResolution

    /** Not a link to a note at all: a PDF, a `kbd:` macro, a link type only Emacs knows. */
    data class NotANote(val link: String) : OrgLinkResolution
}

/** A link in [path], at [offset], that leads to the note being asked about. */
data class OrgBacklink(
    val path: String,
    val offset: Int,
    /** The heading the link sits under, or the note's path when it is above every heading. */
    val context: String,
)

/**
 * Link resolution over the whole corpus, and its reverse: which links lead to a note.
 *
 * The rules are Org's, for the link types these notes use:
 * - `id:X` — the heading, or the file, whose property drawer says `:ID: X`, as org-roam writes them.
 * - `file:path` — relative to the *linking* note's folder, as Org resolves it. When that is not a
 *   note, or the path is absolute (`~/org-roam/daily/x.org`, written on a machine where the notes
 *   live elsewhere), the note whose path is the longest tail of it. `file:path::search` lands on
 *   the search, like the rest of this list.
 * - `*Heading` — a heading of the same note, ignoring its TODO keyword, priority, tags and cookies.
 * - `#custom-id` — the heading whose `:CUSTOM_ID:` it is.
 * - plain `[[text]]` — Org's fuzzy link: a `<<text>>` target, a `#+NAME:`, then a heading.
 *
 * Built from each note's [NoteFacts] each time the notes change, so it holds no note's text. The
 * `:ID:` table is the one thing computed ahead, because every `id:` link and every backlink needs it.
 */
class OrgLinkIndex(
    /** What is known of each note: all that a link needs, unless it searches inside one. */
    private val facts: Map<String, NoteFacts>,
    /** A note's text, if it is at hand, for a link that searches inside it. See [noteSearchedBy]. */
    private val textOf: (String) -> String?,
) {
    /** The index of [notes], every text at hand. */
    constructor(notes: Map<String, String>) : this(notes.mapValues { (path, text) -> factsOf(path, text) }, notes::get)

    /** The keywords [text]'s headings are read with, so that a title is the title Org sees. */
    private fun keywordsOf(text: String): OrgTodoConfig = todoKeywordsOf(text, DEFAULT_TODO_KEYWORDS)

    /** What a search may land on in a note, read from it only when one first looks there. */
    private inner class Anchors(private val text: String) {
        /** Every way a heading can be named, normalised, with where it starts: the first heading wins. */
        val headings: Map<String, Int> by lazy {
            val keywords = keywordsOf(text)
            buildMap {
                for (element in text.orgElements()) {
                    if (element.isPreamble) continue
                    val heading = orgHeadingOf(element.sliceOf(text).substringBefore('\n'), keywords)
                    fun spelling(words: String) = getOrPut(words.normalised()) { element.start }
                    spelling(heading.title)
                    // Org also leaves out the `COMMENT` that comments a subtree out, which a link may keep too.
                    heading.title.takeIf(OrgSyntax::isCommentedTitle)?.let { spelling(it.removePrefix("COMMENT").trim()) }
                    // Both spellings: Org leaves the keyword out of the title, but a link may have kept it.
                    spelling(listOfNotNull(heading.todo, heading.title).joinToString(" "))
                }
            }
        }

        /** Each `<<target>>`, and then each `#+NAME:`, normalised, with where it is: the first wins. */
        val named: Map<String, Int> by lazy {
            buildMap {
                for (m in OrgSyntax.target.findAll(text)) {
                    if (m.groupValues[1].length == m.groupValues[3].length) getOrPut(m.groupValues[2].normalised()) { m.range.first }
                }
                for (m in NAME_KEYWORD.findAll(text)) getOrPut(m.groupValues[1].normalised()) { m.range.first }
            }
        }
    }

    /** The anchors last looked in: a note with many links would otherwise be gone through for each. */
    private var lastAnchors: Pair<String, Anchors>? = null

    private fun anchorsIn(text: String): Anchors =
        lastAnchors?.takeIf { it.first === text }?.second ?: Anchors(text).also { lastAnchors = text to it }

    /** `:ID:` value → where it is declared. */
    private val ids: Map<String, OrgLinkResolution.Note> by lazy {
        buildMap {
            for (path in facts.keys.sorted()) {
                for (declared in facts.getValue(path).ids) {
                    // The first declaration wins, as it does in Emacs when an id is duplicated.
                    if (declared.id !in this) put(declared.id, OrgLinkResolution.Note(path, declared.offset))
                }
            }
        }
    }

    /**
     * The note whose text [resolve] looks into for [link], if it does: most links name a note and are
     * settled without reading it, but one that searches in it needs it read first.
     */
    fun noteSearchedBy(link: String, from: String): String? {
        var asked: String? = null
        resolve(link, from) { path ->
            asked = path
            null
        }
        return asked
    }

    fun resolve(link: String, from: String): OrgLinkResolution = resolve(link, from, textOf)

    /** Where [link], written in [from], leads, the text of a note it searches in coming from [text]. */
    fun resolve(link: String, from: String, text: (String) -> String?): OrgLinkResolution {
        val written = link.trim()
        // An abbreviation the linking note declares, `#+LINK: gh https://github.com/%s`, stands for
        // the link it expands to. Expanded once: what it gives is resolved as written.
        val target = facts[from]?.abbreviations?.takeIf { it.isNotEmpty() }?.let { expandAbbreviation(written, it) } ?: written
        val scheme = OrgSyntax.linkSchemeOf(target)
        return when {
            // Org's own `doi:` link, which it opens at the DOI resolver.
            scheme == "doi" -> OrgLinkResolution.External(DOI_RESOLVER + target.substringAfter(':').trim())

            scheme == "id" -> {
                val (id, search) = splitSearch(target.substringAfter(':'))
                val declared = ids[id.trim()] ?: return OrgLinkResolution.NotFound(link)
                if (search == null) declared else searchIn(declared.path, search, link, text)
            }

            scheme in OrgSyntax.fileLinkSchemes -> {
                val (path, search) = splitSearch(target.substringAfter(':'))
                if (!path.endsWith(".org")) return OrgLinkResolution.NotANote(link)
                val note = notePathFor(path, from) ?: return OrgLinkResolution.NotFound(link)
                if (search == null) OrgLinkResolution.Note(note, null) else searchIn(note, search, link, text)
                    .let { if (it is OrgLinkResolution.NotFound) OrgLinkResolution.Note(note, null, search) else it }
            }

            scheme in EXTERNAL_SCHEMES -> OrgLinkResolution.External(target)
            scheme != null -> OrgLinkResolution.NotANote(link)
            // A bare `./x.org` or `/abs/x.org` is a file link without its prefix; a bare path to
            // anything else is a file this app cannot open.
            target.isBarePath() -> if (!target.endsWith(".org")) {
                OrgLinkResolution.NotANote(link)
            } else {
                notePathFor(target, from)?.let { OrgLinkResolution.Note(it, null) }
                    ?: OrgLinkResolution.NotFound(link)
            }
            else -> searchIn(from, target, link, text)
        }
    }

    /**
     * Every link in the corpus that leads to [path], in path order and then document order.
     * A link from a note to itself counts: a heading pointing at another heading of its own note
     * is as much a link as any.
     */
    fun backlinksTo(path: String): List<OrgBacklink> = allBacklinks[path].orEmpty()

    /**
     * Backlinks for every note, from one pass over the corpus: the same cost as answering for a
     * single note, after which opening any note costs a lookup.
     */
    private val allBacklinks: Map<String, List<OrgBacklink>> by lazy {
        val found = mutableMapOf<String, MutableList<OrgBacklink>>()
        for (source in facts.keys.sorted()) {
            for (link in facts.getValue(source).links) {
                val target = if (link.withinNote) source else targetPathOf(link.target, from = source) ?: continue
                found.getOrPut(target) { mutableListOf() } += OrgBacklink(source, link.offset, link.context ?: source)
            }
        }
        found
    }

    /**
     * The note [link] leads to, without finding the place inside it — all a backlink needs, and
     * much cheaper than [resolve] across a corpus: a heading search is only ever into one note.
     */
    private fun targetPathOf(link: String, from: String): String? {
        val target = link.trim()
        return when (OrgSyntax.linkSchemeOf(target)) {
            "id" -> ids[splitSearch(target.substringAfter(':')).first.trim()]?.path
            in OrgSyntax.fileLinkSchemes -> splitSearch(target.substringAfter(':')).first
                .takeIf { it.endsWith(".org") }?.let { notePathFor(it, from) }
            null -> target.takeIf { it.isBarePath() && it.endsWith(".org") }?.let { notePathFor(it, from) }
            else -> null
        }
    }

    /** The `:ID:` the note declares for itself, above its first heading, if it has one. */
    fun fileIdOf(path: String): String? {
        return facts[path]?.fileId
    }

    /**
     * The note a `file:` path names, seen from [from].
     *
     * Relative first, as Org does it. Then by tail: the notes were written in Emacs on a machine
     * where they live at, say, `~/org-roam/`, so `~/org-roam/daily/x.org` is the note `daily/x.org`
     * here — the longest tail that names a note wins, so `daily/x.org` beats a top-level `x.org`.
     */
    private fun notePathFor(raw: String, from: String): String? {
        val path = raw.trim()
        val absolute = path.startsWith("/") || path.startsWith("~")
        if (!absolute) {
            val folder = from.substringBeforeLast('/', "")
            normalise(if (folder.isEmpty()) path else "$folder/$path")?.let { if (it in facts) return it }
        }
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." && it != "~" }
        for (k in segments.size downTo 1) {
            val tail = segments.takeLast(k)
            if (".." in tail) break
            val candidate = tail.joinToString("/")
            if (candidate in facts) return candidate
        }
        return null
    }

    /** Where [search] lands in [path]: a heading, a custom id, a target, a name, or a line. */
    private fun searchIn(path: String, search: String, link: String, textOf: (String) -> String?): OrgLinkResolution {
        if (path !in facts) return OrgLinkResolution.NotFound(link)
        val text = textOf(path) ?: return OrgLinkResolution.NotFound(link)
        val offset = when {
            search.startsWith("*") -> anchorsIn(text).headings[search.substring(1).normalised()]
            search.startsWith("#") -> CUSTOM_ID.findAll(text)
                .firstOrNull { it.groupValues[1] == search.substring(1).trim() }?.range?.first
            search.all { it.isDigit() } && search.isNotEmpty() -> lineOffset(text, search.toInt())
            else -> anchorsIn(text).let { it.named[search.normalised()] ?: it.headings[search.normalised()] }
        }
        return if (offset == null) OrgLinkResolution.NotFound(link) else OrgLinkResolution.Note(path, offset)
    }

    private fun lineOffset(text: String, line: Int): Int? {
        if (line < 1) return null
        var offset = 0
        repeat(line - 1) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return null
            offset = next + 1
        }
        return offset
    }

    private companion object {
        /** `org-link-doi-server-url`. */
        const val DOI_RESOLVER = "https://doi.org/"

        /**
         * [target] expanded by one of [declared], as `org-link-expand-abbrev` does: the
         * part after the colon replaces `%s`, or `%h` URL-encoded, or is appended when the
         * expansion has neither. Null when [target] uses none of them. Expansions
         * that run Emacs Lisp, `%(…)`, are not followed.
         */
        fun expandAbbreviation(target: String, declared: Map<String, String>): String? {
            val colon = target.indexOf(':')
            val key = if (colon > 0) target.substring(0, colon) else target
            val tag = if (colon > 0) target.substring(colon + 1) else ""
            val expansion = declared[key] ?: return null
            return when {
                "%(" in expansion -> null
                "%s" in expansion -> expansion.replace("%s", tag)
                "%h" in expansion -> expansion.replace("%h", urlEncoded(tag))
                else -> expansion + tag
            }
        }

        /** [text] percent-encoded as a URL's query or path wants it, keeping what needs no escaping. */
        fun urlEncoded(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.~") append(c.toChar())
                else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }

        const val HEX = "0123456789ABCDEF"

        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "ftp")
        val CUSTOM_ID = Regex("(?im)^[ \\t]*:CUSTOM_ID:[ \\t]+(\\S+)[ \\t]*$")
        val NAME_KEYWORD = Regex("(?im)^[ \\t]*#\\+NAME:[ \\t]*(.+)$")
        val WHITESPACE = Regex("\\s+")

        fun splitSearch(target: String): Pair<String, String?> {
            val at = target.indexOf("::")
            return if (at < 0) target to null else target.substring(0, at) to target.substring(at + 2)
        }

        /** Resolves `.` and `..`; null when the path climbs out of the notes. */
        fun normalise(path: String): String? {
            val out = ArrayDeque<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
                    else -> out.addLast(segment)
                }
            }
            return out.joinToString("/")
        }

        /** Titles compare without statistics cookies, case or runs of whitespace. */
        fun String.normalised(): String =
            replace(OrgSyntax.statistics, " ").trim().split(WHITESPACE).joinToString(" ").lowercase()

        fun String.isBarePath(): Boolean =
            startsWith("/") || startsWith("~") || startsWith("./") || startsWith("../")

    }
}

/**
 * Whether [target] is a link into the notes, by its shape alone: what reading mode makes
 * followable. `id:`, `file:` to an `.org`, `*Heading`, `#custom-id`, a bare `.org` path, and Org's
 * plain fuzzy links; not web links, which the platform opens, and not `kbd:`, PDFs and the like.
 */
fun isNoteLink(target: String): Boolean {
    val t = target.trim()
    return when (OrgSyntax.linkSchemeOf(t)) {
        "id" -> true
        in OrgSyntax.fileLinkSchemes -> t.substringAfter(':').substringBefore("::").endsWith(".org")
        null -> if (t.startsWith("/") || t.startsWith("~") || t.startsWith("./") || t.startsWith("../")) {
            t.substringBefore("::").endsWith(".org")
        } else {
            t.isNotEmpty()
        }
        else -> false
    }
}

/**
 * The file [target] links to when that is a file elsewhere on the machine rather than a note: a
 * `file:` link to a PDF or an image, or a bare `~/…` or `/…` path to one. Its `::search`, which only
 * Emacs can follow inside such a file, is dropped. Null for anything else — notes, web links, and
 * paths relative to the note, which name files kept beside the notes, where only notes are synced.
 */
fun fileLinkPath(target: String): String? {
    if (isNoteLink(target)) return null
    val t = target.trim()
    val path = when (OrgSyntax.linkSchemeOf(t)) {
        in OrgSyntax.fileLinkSchemes -> t.substringAfter(':')
        null -> t
        else -> return null
    }.substringBefore("::").trim()
    return path.takeIf { it.startsWith("/") || it.startsWith("~") }
}

/**
 * [target] relative to [from]'s folder, as a `file:` link written in [from] must be for Org to
 * find it: `notes/b.org` from `daily/a.org` is `../notes/b.org`.
 */
fun relativeNotePath(target: String, from: String): String {
    val fromFolder = from.split('/').dropLast(1)
    val targetParts = target.split('/')
    var common = 0
    while (common < fromFolder.size && common < targetParts.size - 1 && fromFolder[common] == targetParts[common]) {
        common++
    }
    val up = List(fromFolder.size - common) { ".." }
    return (up + targetParts.drop(common)).joinToString("/")
}
