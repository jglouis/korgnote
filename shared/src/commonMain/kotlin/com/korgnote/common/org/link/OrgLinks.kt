package com.korgnote.common.org.link

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.corpusTodoKeywords
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
 * Built from the notes' text each time the notes change, and held for no longer: an index kept
 * across saves is a thing to keep correct (G7), and a scan of this corpus is cheap. The `:ID:`
 * table is the one thing computed ahead, because every `id:` link and every backlink needs it.
 */
class OrgLinkIndex(
    private val notes: Map<String, String>,
    /** The keywords a note that declares none is read with; see [corpusTodoKeywords]. */
    fallback: OrgTodoConfig? = null,
) {
    private val fallback: OrgTodoConfig by lazy { fallback ?: corpusTodoKeywords(notes.values) }

    /** The keywords [text]'s headings are read with, so that a title is the title Org sees. */
    private fun keywordsOf(text: String): OrgTodoConfig = todoKeywordsOf(text, fallback)

    /** `:ID:` value → where it is declared. */
    private val ids: Map<String, OrgLinkResolution.Note> by lazy {
        buildMap {
            for ((path, text) in notes) {
                if (!text.contains(":ID:", ignoreCase = true)) continue
                for (m in ID_LINE.findAll(text)) {
                    // The first declaration wins, as it does in Emacs when an id is duplicated.
                    val id = m.groupValues[1]
                    if (id !in this) put(id, OrgLinkResolution.Note(path, m.range.first))
                }
            }
        }
    }

    fun resolve(link: String, from: String): OrgLinkResolution {
        val target = link.trim()
        val scheme = SCHEME.find(target)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == "id" -> {
                val (id, search) = splitSearch(target.substringAfter(':'))
                val declared = ids[id.trim()] ?: return OrgLinkResolution.NotFound(link)
                if (search == null) declared else searchIn(declared.path, search, link)
            }

            scheme in FILE_SCHEMES -> {
                val (path, search) = splitSearch(target.substringAfter(':'))
                if (!path.endsWith(".org")) return OrgLinkResolution.NotANote(link)
                val note = notePathFor(path, from) ?: return OrgLinkResolution.NotFound(link)
                if (search == null) OrgLinkResolution.Note(note, null) else searchIn(note, search, link)
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
            else -> searchIn(from, target, link)
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
        for (source in notes.keys.sorted()) {
            val text = notes.getValue(source)
            if (!text.contains("[[")) continue
            val elements by lazy { text.orgElements() }
            val keywords by lazy { keywordsOf(text) }
            for (m in OrgSyntax.link.findAll(text)) {
                val target = targetPathOf(m.groupValues[1], from = source) ?: continue
                val element = elements.lastOrNull { it.start <= m.range.first }
                val context = element?.takeUnless { it.isPreamble }?.let { orgHeadingOf(it.title.prependStars(), keywords).title }
                    ?: source
                found.getOrPut(target) { mutableListOf() } += OrgBacklink(source, m.range.first, context)
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
        return when (SCHEME.find(target)?.groupValues?.get(1)?.lowercase()) {
            "id" -> ids[splitSearch(target.substringAfter(':')).first.trim()]?.path
            in FILE_SCHEMES -> splitSearch(target.substringAfter(':')).first
                .takeIf { it.endsWith(".org") }?.let { notePathFor(it, from) }
            null -> if (target.isBarePath()) {
                target.takeIf { it.endsWith(".org") }?.let { notePathFor(it, from) }
            } else {
                // Into the same note; only when it finds something there, or every stray `[[x]]`
                // would list the note as linking to itself.
                from.takeIf { resolve(target, from) is OrgLinkResolution.Note }
            }
            else -> null
        }
    }

    /** The `:ID:` the note declares for itself, above its first heading, if it has one. */
    fun fileIdOf(path: String): String? {
        val text = notes[path] ?: return null
        val preamble = text.orgElements().firstOrNull()?.takeIf { it.isPreamble } ?: return null
        return ID_LINE.find(preamble.sliceOf(text))?.groupValues?.get(1)
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
            normalise(if (folder.isEmpty()) path else "$folder/$path")?.let { if (it in notes) return it }
        }
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." && it != "~" }
        for (k in segments.size downTo 1) {
            val tail = segments.takeLast(k)
            if (".." in tail) break
            val candidate = tail.joinToString("/")
            if (candidate in notes) return candidate
        }
        return null
    }

    /** Where [search] lands in [path]: a heading, a custom id, a target, a name, or a line. */
    private fun searchIn(path: String, search: String, link: String): OrgLinkResolution {
        val text = notes[path] ?: return OrgLinkResolution.NotFound(link)
        val offset = when {
            search.startsWith("*") -> headingOffset(text, search.substring(1))
            search.startsWith("#") -> CUSTOM_ID.findAll(text)
                .firstOrNull { it.groupValues[1] == search.substring(1).trim() }?.range?.first
            search.all { it.isDigit() } && search.isNotEmpty() -> lineOffset(text, search.toInt())
            else -> fuzzyOffset(text, search)
        }
        return if (offset == null) OrgLinkResolution.NotFound(link) else OrgLinkResolution.Note(path, offset)
    }

    private fun fuzzyOffset(text: String, search: String): Int? {
        val wanted = search.normalised()
        Regex("<<([^<>\\n]+)>>").findAll(text)
            .firstOrNull { it.groupValues[1].normalised() == wanted }?.let { return it.range.first }
        Regex("(?im)^[ \\t]*#\\+NAME:[ \\t]*(.+)$").findAll(text)
            .firstOrNull { it.groupValues[1].normalised() == wanted }?.let { return it.range.first }
        return headingOffset(text, search)
    }

    private fun headingOffset(text: String, title: String): Int? {
        val wanted = title.normalised()
        val keywords = keywordsOf(text)
        return text.orgElements().firstOrNull { element ->
            if (element.isPreamble) return@firstOrNull false
            val line = element.sliceOf(text).substringBefore('\n')
            val heading = orgHeadingOf(line, keywords)
            // Both spellings: Org leaves the keyword out of the title, but a link may have kept it.
            heading.title.normalised() == wanted ||
                listOfNotNull(heading.todo, heading.title).joinToString(" ").normalised() == wanted
        }?.start
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
        // Any case: `Mu4e:msgid:…` is a link type too. A space after the colon makes it prose —
        // `[[Note: see below]]` is a fuzzy link, not a link of type `Note`.
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+-]*):(?!\\s)")
        val FILE_SCHEMES = setOf("file", "file+sys", "file+emacs")
        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "ftp")
        val ID_LINE = Regex("(?im)^[ \\t]*:ID:[ \\t]+(\\S+)[ \\t]*$")
        val CUSTOM_ID = Regex("(?im)^[ \\t]*:CUSTOM_ID:[ \\t]+(\\S+)[ \\t]*$")
        val COOKIE = Regex("\\[\\d*/\\d*]|\\[\\d*%]")

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
            replace(COOKIE, " ").trim().split(Regex("\\s+")).joinToString(" ").lowercase()

        fun String.isBarePath(): Boolean =
            startsWith("/") || startsWith("~") || startsWith("./") || startsWith("../")

        /** [com.korgnote.common.org.OrgElement.title] has had its stars removed already. */
        fun String.prependStars(): String = "* $this"
    }
}

/**
 * Whether [target] is a link into the notes, by its shape alone: what reading mode makes
 * followable. `id:`, `file:` to an `.org`, `*Heading`, `#custom-id`, a bare `.org` path, and Org's
 * plain fuzzy links; not web links, which the platform opens, and not `kbd:`, PDFs and the like.
 */
fun isNoteLink(target: String): Boolean {
    val t = target.trim()
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+-]*):(?!\\s)").find(t)?.groupValues?.get(1)?.lowercase()
    return when (scheme) {
        "id" -> true
        "file", "file+sys", "file+emacs" -> t.substringAfter(':').substringBefore("::").endsWith(".org")
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
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+-]*):(?!\\s)").find(t)?.groupValues?.get(1)?.lowercase()
    val path = when (scheme) {
        "file", "file+sys", "file+emacs" -> t.substringAfter(':')
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
