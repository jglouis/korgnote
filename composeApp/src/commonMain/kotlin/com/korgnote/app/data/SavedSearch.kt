package com.korgnote.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A search kept to be run again: what to type, and what to call it in the list.
 *
 * The query, not its results — results are whatever the notes hold when it is run.
 */
@Serializable
data class SavedSearch(
    val name: String,
    val query: String,
)

private val json = Json { ignoreUnknownKeys = true }

/** Saved searches as one stored string, so each platform keeps them under a single key. */
internal fun encodeSavedSearches(searches: List<SavedSearch>): String =
    json.encodeToString(ListSerializer(SavedSearch.serializer()), searches)

/** The stored list, or none when there is nothing stored or it cannot be read. */
internal fun decodeSavedSearches(stored: String?): List<SavedSearch> =
    stored?.let { runCatching { json.decodeFromString(ListSerializer(SavedSearch.serializer()), it) }.getOrNull() }
        .orEmpty()
