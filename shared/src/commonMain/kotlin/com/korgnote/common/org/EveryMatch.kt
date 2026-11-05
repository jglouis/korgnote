package com.korgnote.common.org

/** One match found by [everyMatch], as [MatchResult] says it. */
class FoundMatch(val range: IntRange, val groupValues: List<String>)

/**
 * The same matches as [Regex.findAll], for going through a whole note.
 *
 * On Android, `findAll` makes a new matcher for each match, and each holds a copy of the text outside the
 * Java heap until a collection happens to come. For thousands of matches in a note of megabytes that came to
 * gigabytes, which the heap's limit knows nothing of: the system killed the app with a 233 MB heap and 2.3 GB
 * besides. This keeps one matcher for all of them.
 */
internal expect fun Regex.everyMatch(text: String): Sequence<FoundMatch>
