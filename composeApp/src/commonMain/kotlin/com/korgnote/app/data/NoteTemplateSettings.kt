package com.korgnote.app.data

import com.korgnote.common.org.template.DEFAULT_EXTRA_HEADER
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a new note or daily is started with, as chosen in Settings. */
@Serializable
data class NoteTemplateSettings(
    /** `#+AUTHOR:`; left out when blank. */
    val author: String = "",
    /** Where dailies go, relative to the notes directory, as org-roam-dailies keeps them. */
    val dailyFolder: String = DEFAULT_DAILY_FOLDER,
    /** Lines written into every new note after its keywords: the `#+TODO:` lines, typically. */
    val extraHeader: String = DEFAULT_EXTRA_HEADER,
) {
    /** One string, so each platform stores it under a single key. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_DAILY_FOLDER = "daily"

        private val json = Json { ignoreUnknownKeys = true }

        /** The stored form, or the defaults for anything missing or unreadable. */
        fun decode(stored: String?): NoteTemplateSettings =
            stored?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: NoteTemplateSettings()
    }
}

/** Tag colours as one stored string: a tag per line, then a tab and its colour as `RRGGBB`. */
internal fun encodeTagColors(colors: Map<String, Int>): String =
    colors.entries.joinToString("\n") { (tag, rgb) -> "$tag\t${(rgb and 0xFFFFFF).toString(16).padStart(6, '0')}" }

internal fun decodeTagColors(stored: String?): Map<String, Int> =
    stored.orEmpty().split('\n').mapNotNull { line ->
        val at = line.indexOf('\t')
        if (at <= 0) return@mapNotNull null
        val rgb = line.substring(at + 1).trim().toIntOrNull(16) ?: return@mapNotNull null
        line.substring(0, at) to rgb
    }.toMap()
