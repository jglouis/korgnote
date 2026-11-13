package com.korgnote.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Where the reader was in a note: the element at the top of the screen, by the offset in the
 * note's text where it starts, and how far into that element the screen had scrolled, in pixels.
 *
 * An offset rather than an element index, as Back keeps it: a heading added or removed far above
 * moves every index below it, where the offset moves only by what was typed.
 */
@Serializable
data class ReadingPlace(
    val path: String,
    val offset: Int,
    val pixels: Int = 0,
)

/**
 * The screen the app was last left on, to open on again: the tab, and the calendar's view as it
 * was last seen there — which may be from before another tab was chosen.
 *
 * [savedOn] is the day the calendar was last seen, as an ISO date: its period is only taken back on
 * that same day, since a calendar opened the next morning is wanted on that morning — and the
 * agenda's never, which is the days from today on.
 */
@Serializable
data class LastScreen(
    val tab: String? = null,
    val calendarMode: String? = null,
    val calendarDate: String? = null,
    val savedOn: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** Reading places as one stored string, most recently read first. */
internal fun encodeReadingPlaces(places: List<ReadingPlace>): String =
    json.encodeToString(ListSerializer(ReadingPlace.serializer()), places)

/** The stored places, or none when there is nothing stored or it cannot be read. */
internal fun decodeReadingPlaces(stored: String?): List<ReadingPlace> =
    stored?.let { runCatching { json.decodeFromString(ListSerializer(ReadingPlace.serializer()), it) }.getOrNull() }
        .orEmpty()

internal fun encodeLastScreen(screen: LastScreen): String = json.encodeToString(LastScreen.serializer(), screen)

internal fun decodeLastScreen(stored: String?): LastScreen =
    stored?.let { runCatching { json.decodeFromString(LastScreen.serializer(), it) }.getOrNull() } ?: LastScreen()
