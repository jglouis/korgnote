package com.korgnote.common.org.agenda

/**
 * Which entries the calendar shows: those with one of [keywords], if any are given, and with one of
 * [tags], if any are given. Within keywords or within tags one is enough; between the two, both are
 * needed — `TODO` or `NEXT`, and `work` — as narrowing the agenda by a keyword and then a tag does in
 * Org.
 *
 * Tags compare ignoring case, as search compares them; keywords as written, since `TODO` and `todo`
 * are different words to Org.
 */
data class AgendaFilter(val keywords: Set<String> = emptySet(), val tags: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = keywords.isEmpty() && tags.isEmpty()

    fun matches(todo: String?, tags: List<String>): Boolean =
        (keywords.isEmpty() || todo in keywords) &&
            (this.tags.isEmpty() || tags.any { tag -> this.tags.any { it.equals(tag, ignoreCase = true) } })

    /** With [keyword] added, or taken out when it is there already. */
    fun toggleKeyword(keyword: String) = copy(keywords = if (keyword in keywords) keywords - keyword else keywords + keyword)

    /** With [tag] added, or taken out when it is there already, in whatever case. */
    fun toggleTag(tag: String): AgendaFilter {
        val present = tags.firstOrNull { it.equals(tag, ignoreCase = true) }
        return copy(tags = if (present != null) tags - present else tags + tag)
    }

    companion object {
        val NONE = AgendaFilter()
    }
}
