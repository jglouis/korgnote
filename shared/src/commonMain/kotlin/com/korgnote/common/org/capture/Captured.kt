package com.korgnote.common.org.capture

/**
 * What another app handed over to be captured, as the platform received it and before anything is
 * made of it: the share sheet's text and subject, a link given apart from the text (iOS does), or
 * the contents of a calendar file.
 *
 * Kept raw on purpose. A capture is queued as this, so that what it becomes can still change — the
 * heading chosen, a better reading of a Maps link — without the other app being asked again.
 */
@kotlinx.serialization.Serializable
data class SharedContent(
    /** Android's `EXTRA_SUBJECT`, a page's title on iOS. */
    val subject: String? = null,
    /** Android's `EXTRA_TEXT` or selected text; text shared on iOS. */
    val text: String? = null,
    /** A link shared as one rather than inside [text]. */
    val url: String? = null,
    /** An `.ics` file's contents: an invitation, or an event a calendar exported. */
    val calendar: String? = null,
)

/**
 * A capture as the entry sees it: what was shared, read for what it is. Each field is one of the
 * escapes in [renderCapture]; any of them may be empty, and an entry line that only holds empty
 * ones is left out.
 */
data class Captured(
    /** What the entry is called: a page's title, a place's name, an event's summary. `%:description`. */
    val title: String = "",
    /** Where it came from, when it is somewhere: a page, a place on a map. `%:link`. */
    val link: String? = null,
    /** Text to keep under the heading. `%i`. */
    val text: String = "",
    /** A place's address, or where an event is. `%:location`. */
    val location: String? = null,
    /** Coordinates, as `geo:48.8584,2.2945`, when what was shared gave them. `%:geo`. */
    val geo: String? = null,
    /** When it happens, as an active Org timestamp or range: an event's. `%:when`. */
    val time: String? = null,
) {
    /** [time] when it is one stamp, which Org can schedule; a range across days cannot be. `%:scheduled`. */
    val schedulable: String? get() = time?.takeIf { "--" !in it }

    /** [time] when it is a range across days, written as it is instead. `%:range`. */
    val range: String? get() = time?.takeIf { "--" in it }
}
