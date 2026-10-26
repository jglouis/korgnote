package com.korgnote.common.org.edit

/**
 * A note's own description of itself — the `#+TITLE:`, `#+AUTHOR:`, `#+DATE:` and `#+FILETAGS:`
 * above its first heading — as the details form edits it. Values are as written, tags as a list.
 */
data class FileKeywords(
    val title: String = "",
    val author: String = "",
    val date: String = "",
    val tags: List<String> = emptyList(),
)

/** What [text] says about itself; blank for a keyword it does not have. */
fun fileKeywordsOf(text: String): FileKeywords {
    val values = mutableMapOf<String, String>()
    for (line in preambleLines(text)) {
        val match = FILE_KEYWORD.matchEntire(line) ?: continue
        values.getOrPut(match.groupValues[1].uppercase()) { match.groupValues[2].trim() }
    }
    return FileKeywords(
        title = values["TITLE"].orEmpty(),
        author = values["AUTHOR"].orEmpty(),
        date = values["DATE"].orEmpty(),
        tags = values["FILETAGS"].orEmpty().split(':', ' ', '\t').filter { it.isNotEmpty() },
    )
}

/**
 * [text] saying [keywords] about itself.
 *
 * Each line is changed where it is, keeping how its keyword is spelt, and only when its value did
 * change; a blank value removes its line; a new one goes after the others of these four, or else
 * after the note's other keywords, or after its `:ID:` drawer, or at the top. The rest of the file —
 * its other keywords, the blank lines, every heading — is left exactly as it was.
 */
fun withFileKeywords(text: String, keywords: FileKeywords): String {
    var result = text
    result = withFileKeyword(result, "TITLE", "#+TITLE", keywords.title.trim())
    result = withFileKeyword(result, "AUTHOR", "#+AUTHOR", keywords.author.trim())
    result = withFileKeyword(result, "DATE", "#+DATE", keywords.date.trim())
    val tags = keywords.tags.map { it.trim(':', ' ') }.filter { it.isNotEmpty() }.distinct()
    val filetags = if (tags.isEmpty()) "" else tags.joinToString(":", prefix = ":", postfix = ":")
    result = withFileKeyword(result, "FILETAGS", "#+filetags", filetags)
    return result
}

private fun withFileKeyword(text: String, key: String, spelling: String, value: String): String {
    var lineStart = 0
    var afterLastKeyword = -1
    var afterLastFileKeyword = -1
    var afterTopDrawer = -1
    var inTopDrawer = false
    var index = 0
    while (lineStart < text.length) {
        val end = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val next = if (end < text.length) end + 1 else end
        val line = text.substring(lineStart, end)
        if (line.startsWith("*")) break
        val match = FILE_KEYWORD.matchEntire(line)
        if (match != null && match.groupValues[1].equals(key, ignoreCase = true)) {
            val written = line.substring(0, line.indexOf(':') + 1)
            return when {
                value.isEmpty() -> text.removeRange(lineStart, next)
                match.groupValues[2].trim() == value -> text
                else -> text.replaceRange(lineStart, end, "$written $value")
            }
        }
        if (match != null) afterLastFileKeyword = next
        if (KEYWORD_LINE.containsMatchIn(line)) afterLastKeyword = next
        // The `:PROPERTIES:` drawer org-roam puts first, holding the note's `:ID:`.
        if (index == 0 && line.trim().equals(":PROPERTIES:", ignoreCase = true)) inTopDrawer = true
        if (inTopDrawer && line.trim().equals(":END:", ignoreCase = true)) {
            inTopDrawer = false
            afterTopDrawer = next
        }
        lineStart = next
        index++
    }
    if (value.isEmpty()) return text
    val at = when {
        afterLastFileKeyword >= 0 -> afterLastFileKeyword
        afterLastKeyword >= 0 -> afterLastKeyword
        afterTopDrawer >= 0 -> afterTopDrawer
        else -> 0
    }
    // After a last line with no newline of its own, the new line needs one before it.
    val lead = if (at == text.length && at > 0 && !text.endsWith('\n')) "\n" else ""
    return text.substring(0, at) + lead + "$spelling: $value\n" + text.substring(at)
}

/** The lines above the first heading, where Org reads a file's keywords. */
private fun preambleLines(text: String): Sequence<String> = text.lineSequence().takeWhile { !it.startsWith("*") }

private val FILE_KEYWORD = Regex("^#\\+(TITLE|AUTHOR|DATE|FILETAGS):(.*)$", RegexOption.IGNORE_CASE)
private val KEYWORD_LINE = Regex("^#\\+\\w+:")
