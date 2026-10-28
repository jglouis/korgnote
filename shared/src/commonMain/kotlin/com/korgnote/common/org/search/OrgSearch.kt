package com.korgnote.common.org.search

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.sliceOf

/**
 * A query over the corpus.
 *
 * Bare words match text, anywhere: body lines, heading titles, and the file's own path. `tag:work`
 * — or the Org-shaped shorthand `:work:` — narrows to headings carrying that tag, inherited the way
 * Org inherits it, so a tag on a parent heading or in `#+FILETAGS:` counts for everything beneath
 * it. Words can be quoted to search for a phrase: `tag:work "invoice due"`.
 *
 * Every term must match — terms narrow, they do not widen — and matching ignores case throughout,
 * for tags as well as text.
 *
 * Text and tags are the two the notes actually need today. The structured queries sketched in
 * `docs/06-editor.md` (`todo:NEXT`, `deadline:<=7d`) are more prefixes in [Companion.parse] plus
 * more predicates in [searchOrgFile]; nothing here assumes there will only ever be two.
 */
data class SearchQuery(
    /** Words that must all appear, already lowercased. */
    val text: List<String> = emptyList(),
    /** Tags the containing heading must all carry, already lowercased and without colons. */
    val tags: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty() && tags.isEmpty()

    companion object {
        /** Parses user input. Anything that is not a recognised prefix is a word to find. */
        fun parse(input: String): SearchQuery {
            val text = mutableListOf<String>()
            val tags = mutableListOf<String>()
            for (token in tokenize(input)) {
                val tagged = tagTermsOf(token)
                if (tagged != null) tags += tagged else text += token.lowercase()
            }
            return SearchQuery(text = text, tags = tags)
        }

        private val TAG_PREFIXES = listOf("tag:", "tags:")

        /** The tags a token names, or null when it is an ordinary word. */
        private fun tagTermsOf(token: String): List<String>? {
            val prefix = TAG_PREFIXES.firstOrNull { token.startsWith(it, ignoreCase = true) }
            val body = when {
                prefix != null -> token.substring(prefix.length)
                // `:work:` is how Org itself writes a tag, so it is accepted as typed. The length
                // guard keeps a lone `:` — or a `::` typed mid-word — a search for that text.
                token.length > 2 && token.startsWith(':') && token.endsWith(':') -> token
                else -> return null
            }
            // `:work:home:` names two tags, the way a heading's own tag line does.
            val named = body.split(':').mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            return named.takeIf { it.isNotEmpty() }
        }

        /** Splits on whitespace, keeping `"a phrase"` together. */
        private fun tokenize(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            for (ch in input) {
                when {
                    ch == '"' -> quoted = !quoted
                    ch.isWhitespace() && !quoted -> {
                        if (current.isNotEmpty()) {
                            tokens += current.toString()
                            current.clear()
                        }
                    }

                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }
    }
}

/**
 * One match, with enough to show it and to open the file exactly where it is.
 *
 * [offset] is the character offset of the match in the file — the same coordinate every other part
 * of the app uses to identify a place in a document, so opening a result reuses the machinery the
 * calendar already has rather than needing its own.
 *
 * [breadcrumb] runs from the outermost heading down to the one containing the match, and is empty
 * for a match in the text before the first heading, or in the path itself. [match] is the range to
 * highlight inside [lineText]; it is empty when the whole line is the result, which is the case for
 * a tag-only query and for a file matched by its path.
 */
data class SearchHit(
    val path: String,
    val breadcrumb: List<String>,
    val tags: List<String>,
    /** 1-based, counted in the file. */
    val line: Int,
    val offset: Int,
    val lineText: String,
    val match: IntRange,
    val kind: Kind,
) {
    enum class Kind {
        /** The file's own name carries the words; [lineText] is the path and there is no line. */
        PATH,

        /** The heading itself is the result, because the query only named tags. */
        HEADING,

        /** A line carrying the words. [match] is where they start. */
        LINE,
    }
}

/**
 * Searches one file.
 *
 * One pass: the document is split into elements — the same block- and drawer-aware split the editor
 * uses, so a `* heading` inside a source block is code here too — and each element's lines are
 * scanned while a stack of enclosing headings supplies the breadcrumb and the inherited tags.
 *
 * [limit] caps the hits returned so that a one-word query over a large corpus stops early rather
 * than building a list nobody will scroll through.
 */
fun searchOrgFile(
    path: String,
    text: String,
    query: SearchQuery,
    limit: Int = Int.MAX_VALUE,
): List<SearchHit> {
    if (query.isEmpty || limit <= 0) return emptyList()
    val hits = mutableListOf<SearchHit>()

    // A file whose name carries the words is itself a result: this is what the file filter in the
    // drawer does, and it stays available here rather than becoming a second kind of search.
    // Only for a text query — a tag says something about a heading, not about a file name.
    if (query.tags.isEmpty() && query.text.all { path.contains(it, ignoreCase = true) }) {
        hits += SearchHit(
            path = path,
            breadcrumb = emptyList(),
            tags = emptyList(),
            line = 1,
            offset = 0,
            lineText = path,
            match = IntRange.EMPTY,
            kind = SearchHit.Kind.PATH,
        )
    }

    // Splitting into elements and parsing headings is most of the cost, and most files cannot match
    // at all. A line hit needs every word in the file, and a tag — on a heading or in `#+FILETAGS:`
    // — is text in the same file, so a file missing any term is passed over unparsed. This is the
    // same case-insensitive test `matchIn` applies per line, so it never drops a real hit.
    if ((query.text + query.tags).any { !text.contains(it, ignoreCase = true) }) return hits

    val fileTags = fileTagsOf(text)
    val ancestors = mutableListOf<Heading>()
    var line = 1

    for (element in text.orgElements()) {
        val slice = element.sliceOf(text)
        if (!element.isPreamble) {
            val headingLine = slice.lineSequence().firstOrNull().orEmpty()
            val heading = orgHeadingOf(headingLine)
            while (ancestors.isNotEmpty() && ancestors.last().level >= element.level) ancestors.removeAt(ancestors.size - 1)
            ancestors += Heading(element.level, heading.title, heading.tags)
        }

        val tags = fileTags + ancestors.flatMap { it.tags }
        val carriesTags = query.tags.all { wanted -> tags.any { it.equals(wanted, ignoreCase = true) } }
        if (carriesTags && hits.size < limit) {
            val breadcrumb = ancestors.map { it.title }
            if (query.text.isEmpty()) {
                // Nothing to find inside the element: the heading itself is the result. The
                // preamble has no heading to offer, so a tag-only query passes over it.
                if (!element.isPreamble) {
                    hits += SearchHit(
                        path = path,
                        breadcrumb = breadcrumb,
                        tags = tags,
                        line = line,
                        offset = element.start,
                        lineText = slice.lineSequence().firstOrNull().orEmpty(),
                        match = IntRange.EMPTY,
                        kind = SearchHit.Kind.HEADING,
                    )
                }
            } else {
                collectLineHits(path, slice, element.start, line, breadcrumb, tags, query, limit, hits)
            }
        }

        // Counted whether or not the element produced hits, so a skipped element does not shift
        // the line numbers of everything after it.
        line += slice.count { it == '\n' }
        if (hits.size >= limit) break
    }
    return hits
}

/**
 * Searches every file, in path order.
 *
 * A linear scan, deliberately: a few megabytes of text match in milliseconds, and an index is a
 * dependency to keep correct on every save. `OrgSearchTests` pins the corpus-sized case — main
 * code cannot link to a test class, so that name is written out rather than referenced.
 */
fun searchOrg(files: Map<String, String>, query: SearchQuery, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchHit> {
    if (query.isEmpty) return emptyList()
    val hits = mutableListOf<SearchHit>()
    for (path in files.keys.sorted()) {
        if (hits.size >= limit) break
        hits += searchOrgFile(path, files.getValue(path), query, limit - hits.size)
    }
    return hits
}

/** Convenience for the common case: parse the user's input and run it. */
fun searchOrg(files: Map<String, String>, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchHit> =
    searchOrg(files, SearchQuery.parse(query), limit)

/** Enough to fill a long screen several times over; past this, narrow the query instead. */
const val DEFAULT_SEARCH_LIMIT = 200

private class Heading(val level: Int, val title: String, val tags: List<String>)

/** Adds a hit for every line of [slice] carrying all of the query's words. */
private fun collectLineHits(
    path: String,
    slice: String,
    sliceStart: Int,
    firstLine: Int,
    breadcrumb: List<String>,
    tags: List<String>,
    query: SearchQuery,
    /** Total hits wanted, counted against [into], which may already hold some. */
    limit: Int,
    into: MutableList<SearchHit>,
) {
    var lineStart = 0
    var lineNumber = firstLine
    while (lineStart <= slice.length) {
        val newline = slice.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) slice.length else newline
        val lineText = slice.substring(lineStart, lineEnd)

        val match = matchIn(lineText, query.text)
        if (match != null) {
            into += SearchHit(
                path = path,
                breadcrumb = breadcrumb,
                tags = tags,
                line = lineNumber,
                offset = sliceStart + lineStart + match.first,
                lineText = lineText,
                match = match,
                kind = SearchHit.Kind.LINE,
            )
            if (into.size >= limit) return
        }

        if (newline < 0) return
        lineStart = newline + 1
        lineNumber++
    }
}

/**
 * The range to highlight when [line] contains every word, else null.
 *
 * Every word has to be there — that is what makes adding a word narrow the results — and the
 * earliest of them is the one highlighted, so the snippet is anchored where reading would start.
 */
private fun matchIn(line: String, words: List<String>): IntRange? {
    var earliest: IntRange? = null
    for (word in words) {
        val at = line.indexOf(word, ignoreCase = true)
        if (at < 0) return null
        if (earliest == null || at < earliest.first) earliest = at until (at + word.length)
    }
    return earliest
}

/**
 * Tags every heading in the file inherits, from `#+FILETAGS:`.
 *
 * Only the text before the first heading is looked at, which is where Org honours the keyword.
 */
fun fileTagsOf(text: String): List<String> {
    val tags = mutableListOf<String>()
    for (line in text.lineSequence()) {
        // The same heading rule the element splitter uses: two places disagreeing about where the
        // preamble ends is worse than either being individually right.
        if (OrgSyntax.heading.containsMatchIn(line)) break
        val value = OrgSyntax.filetagsKeyword.find(line)?.groupValues?.get(1) ?: continue
        value.split(':', ' ', '\t').forEach { tag -> tag.trim().takeIf { it.isNotEmpty() }?.let { tags += it } }
    }
    return tags
}
