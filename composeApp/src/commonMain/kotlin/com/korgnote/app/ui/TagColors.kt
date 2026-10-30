package com.korgnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Each tag's colour, so that `work` reads as `work` at a glance wherever it is shown.
 *
 * A tag nobody chose a colour for gets one from its name: the same on every device and every run,
 * with nothing to store or sync. The colours chosen in Settings replace that, per device.
 */
object TagColors {
    /** One colour of the palette, with its light and dark values, after [OrgColors]. */
    data class Swatch(val light: Int, val dark: Int)

    /** Distinct enough to tell apart next to each other, and readable on both themes. */
    val palette: List<Swatch> = listOf(
        Swatch(0xC62828, 0xEF9A9A), // red
        Swatch(0xAD1457, 0xF48FB1), // pink
        Swatch(0x7B1FA2, 0xCE93D8), // purple
        Swatch(0x4527A0, 0xB39DDB), // deep purple
        Swatch(0x283593, 0x9FA8DA), // indigo
        Swatch(0x1565C0, 0x90CAF9), // blue
        Swatch(0x00838F, 0x80DEEA), // cyan
        Swatch(0x00695C, 0x80CBC4), // teal
        Swatch(0x2E7D32, 0xA5D6A7), // green
        Swatch(0x558B2F, 0xC5E1A5), // light green
        Swatch(0xEF6C00, 0xFFCC80), // orange
        Swatch(0x5D4037, 0xBCAAA4), // brown
        Swatch(0x455A64, 0xB0BEC5), // blue grey
    )

    /**
     * Colours chosen in Settings, as `RRGGBB` by tag; a palette colour is kept as its light value.
     * Observable, so the chips follow a change as soon as Settings saves it.
     */
    var chosen: Map<String, Int> by mutableStateOf(emptyMap())

    /** The colour [tag] is drawn in, for the theme in use. */
    fun of(tag: String): Color {
        val rgb = chosen[tag]
        val swatch = if (rgb != null) palette.firstOrNull { it.light == rgb } else automatic(tag)
        val value = when {
            swatch != null -> if (OrgColors.dark) swatch.dark else swatch.light
            else -> rgb!!
        }
        return Color(0xFF000000.toInt() or value)
    }

    /**
     * The palette colour [tag] gets from its name alone. A hash of our own rather than
     * [String.hashCode], which is not promised to be the same on every platform.
     */
    fun automatic(tag: String): Swatch {
        var hash = 0x811C9DC5.toInt()
        for (ch in tag.lowercase()) {
            hash = (hash xor ch.code) * 0x01000193
        }
        return palette[(hash.toUInt() % palette.size.toUInt()).toInt()]
    }
}

/**
 * The colour of each TODO keyword in the agenda, the calendar's and the home-screen widget's alike.
 *
 * Hashing the name, as tags do, gives `TODO` and `EVENT` the same colour. The open keywords take
 * [palette] in alphabetical order instead, which only moves when a keyword is added; a finished one
 * is grey, whatever it is.
 */
class KeywordSwatches(openKeywords: Collection<String>) {
    private val index = openKeywords.distinct().sorted().withIndex().associate { (i, keyword) -> keyword to i }

    /** [keyword]'s colour, or null when it is no keyword the agenda has open. */
    fun of(keyword: String, done: Boolean): TagColors.Swatch? {
        if (done) return DONE
        val i = index[keyword] ?: return null
        return palette[i % palette.size]
    }

    companion object {
        /** Away from the agenda kinds' own colours, so a keyword's edge does not vanish into its chip's tint. */
        val palette = listOf(5, 10, 7, 1, 8, 11, 6, 0).map { TagColors.palette[it] }

        val DONE = TagColors.Swatch(0x9E9E9E, 0x9E9E9E)
    }
}
