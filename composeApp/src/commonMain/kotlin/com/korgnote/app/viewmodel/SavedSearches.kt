package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.app.data.SavedSearch

/**
 * Searches kept by name to be run again from the search screen's drawer.
 *
 * Kept in settings rather than in the notes, as the pins are: saving one changes nothing on disk and
 * nothing syncs. A search is known by its query — two names for the same query would be two rows
 * that do the same thing — so saving a query already saved renames it.
 */
class SavedSearches(
    load: () -> List<SavedSearch>,
    private val save: (List<SavedSearch>) -> Unit,
) {
    /** In the order they were saved. */
    var all by mutableStateOf(load())
        private set

    /** The saved search for [query], if it is one. */
    fun find(query: String): SavedSearch? = query.trim().let { q -> all.firstOrNull { it.query == q } }

    /**
     * Saves [query] as [name], at the end of the list, or renames it where it already is. A blank
     * name saves it under the query itself, which is at least what it does.
     */
    fun save(query: String, name: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val search = SavedSearch(name = name.trim().ifEmpty { q }, query = q)
        update(
            if (all.any { it.query == q }) all.map { if (it.query == q) search else it } else all + search,
        )
    }

    fun remove(query: String) {
        val q = query.trim()
        if (all.any { it.query == q }) update(all.filterNot { it.query == q })
    }

    private fun update(searches: List<SavedSearch>) {
        all = searches
        save(searches)
    }
}
