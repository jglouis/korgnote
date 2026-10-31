package com.korgnote.common.org.capture

import com.korgnote.common.org.OrgSyntax
import kotlinx.datetime.LocalDateTime

/** The note a capture goes to unless another is chosen, in the folder new notes go to. */
const val INBOX_FILE_NAME = "inbox.org"

/**
 * The entry every capture makes: one, rather than templates to choose among, so that capturing asks
 * nothing but where. It reads whatever was shared — a page, a place, an event, text — and leaves out
 * the lines about what it did not have: an event is scheduled, a place has its location, a page its
 * link.
 */
private const val ENTRY = """%:description
%:scheduled
:PROPERTIES:
:LOCATION: %:location
:END:
%:range
%:link
%i"""

/**
 * The entry [captured] makes at [now], its heading marked with [keyword] — one of the destination
 * note's TODO keywords — or with none, with the place as its `:LOCATION:` when [location] says so
 * and the link under it when [link] does.
 */
fun captureEntry(
    captured: Captured,
    now: LocalDateTime,
    keyword: String? = null,
    location: Boolean = EntryParts.defaultsFor(captured).location,
    link: Boolean = EntryParts.defaultsFor(captured).link,
): RenderedCapture = renderCapture(
    "* " + keyword?.let { "$it " }.orEmpty() + ENTRY,
    captured.copy(location = placeOf(captured).takeIf { location }, link = captured.link.takeIf { link }),
    now,
)

/**
 * What of a capture a new entry keeps, where the user may choose: a place as the entry's
 * `:LOCATION:`, and its link. The rest — the title, the time, the text — it always keeps.
 */
data class EntryParts(val location: Boolean, val link: Boolean) {
    companion object {
        /**
         * A place its location rather than its link: a Maps link says little in a note, and
         * `:LOCATION:` is what Org, and the agenda, read. A page its link.
         */
        fun defaultsFor(captured: Captured) = EntryParts(location = placeOf(captured) != null, link = placeOf(captured) == null && captured.link != null)

        /** The boxes there is something behind, to offer: both only for a place shared with its link. */
        fun available(captured: Captured) = EntryParts(location = placeOf(captured) != null, link = captured.link != null)
    }
}

/**
 * Where [captured] is, for `:LOCATION:`: its address, or a place's name when that is all a map
 * shared — Google Maps sends a name and a short link, no address. Null for what is no place.
 */
fun placeOf(captured: Captured): String? =
    captured.location ?: captured.title.takeIf { it.isNotBlank() && (captured.geo != null || isMapLink(captured.link)) }

/** The keyword [entry]'s heading starts with, among [keywords]; null for none. */
fun entryKeyword(entry: String, keywords: Collection<String>): String? {
    val heading = entry.substringBefore('\n')
    val stars = OrgSyntax.heading.find(heading) ?: return null
    val first = heading.substring(stars.range.last + 1).trimStart().substringBefore(' ')
    return first.takeIf { it in keywords }
}

/**
 * [entry] with its heading's keyword — any of [keywords] — replaced by [keyword], or removed when
 * it is null. The rest of the entry stays as the user left it.
 */
fun withEntryKeyword(entry: String, keyword: String?, keywords: Collection<String>): String {
    val end = entry.indexOf('\n').let { if (it < 0) entry.length else it }
    val heading = entry.substring(0, end)
    val stars = OrgSyntax.heading.find(heading) ?: return entry
    val prefix = heading.substring(0, stars.range.last + 1)
    var title = heading.substring(stars.range.last + 1).trimStart()
    entryKeyword(heading, keywords)?.let { title = title.removePrefix(it).trimStart() }
    val marked = prefix + keyword?.let { "$it " }.orEmpty() + title
    return marked + entry.substring(end)
}
