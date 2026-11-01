package com.korgnote.common.org

/**
 * The priorities a file's headings can have, `[#A]` to `[#C]` unless its `#+PRIORITIES:` line says
 * otherwise, and the one a heading without a cookie counts as — Org's `org-priority-highest`,
 * `-lowest` and `-default`, which are `A`, `C` and `B`.
 */
data class OrgPriorities(val highest: Char, val lowest: Char, val default: Char) {
    /** Every priority, most important first: `A`, `B`, `C`. */
    val range: List<Char> get() = (highest..lowest).toList()

    /**
     * Where [priority] stands against the default, for sorting: negative for more important, zero
     * for the default and for none, positive for less. Measured from the default, it compares the
     * headings of files with different ranges sensibly.
     */
    fun orderOf(priority: Char?): Int = (priority ?: default) - default
}

/** Org's own: `A` to `C`, with `B` for a heading that has none. */
val DEFAULT_PRIORITIES = OrgPriorities('A', 'C', 'B')

/**
 * The priorities [text] declares with `#+PRIORITIES: highest lowest default`, such as `A E C` or
 * `1 5 3`, or Org's own when it declares none — or one that cannot be read: a priority here is a
 * single letter or digit, the highest comes first, and the default lies between the two.
 */
fun prioritiesOf(text: String): OrgPriorities {
    val value = PRIORITIES_KEYWORD.find(text)?.groupValues?.get(1) ?: return DEFAULT_PRIORITIES
    val words = value.trim().split(Regex("\\s+"))
    if (words.size != 3 || words.any { it.length != 1 }) return DEFAULT_PRIORITIES
    val (highest, lowest, default) = words.map { it.single().uppercaseChar() }
    val sameKind = listOf(highest, lowest, default).all { it in 'A'..'Z' } || listOf(highest, lowest, default).all { it in '0'..'9' }
    if (!sameKind || highest > lowest || default !in highest..lowest) return DEFAULT_PRIORITIES
    return OrgPriorities(highest, lowest, default)
}

private val PRIORITIES_KEYWORD = Regex("(?im)^[ \\t]*#\\+PRIORITIES:[ \\t]*(.*)$")
