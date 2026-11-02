package com.korgnote.common.org.capture

import com.korgnote.common.org.OrgSyntax
import kotlinx.datetime.TimeZone

/**
 * [shared] read for what it is: an event when it carries a calendar file, a place when its link is
 * a map's, a page when it is a link, and text otherwise. Times are moved into [zone].
 *
 * Nothing here goes to the network. A Google Maps short link says nothing of the place itself: what
 * it leads to does, when that was looked up as it was shared ([SharedContent.expanded],
 * [mapShortLinkIn]); the short link stays the link either way.
 */
fun capturedFrom(shared: SharedContent, zone: TimeZone): Captured {
    shared.calendar?.let { ics -> calendarEventFrom(ics, zone)?.let { return capturedFrom(it) } }
    val subject = shared.subject?.trim()?.takeIf { it.isNotEmpty() }
    val text = shared.text?.trim().orEmpty()
    val link = shared.url?.trim()?.takeIf { it.isNotEmpty() } ?: firstUrlIn(text)
    if (link != null) {
        // What was shared with the link, but not the link: a place's name and address, a video's
        // title, a line someone wrote about it.
        val around = text.replace(link, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        placeFrom(link, subject, around, shared.expanded?.trim())?.let { return it }
        return Captured(
            title = subject ?: around.firstOrNull()?.let(::singleLine) ?: titleOfUrl(link),
            link = link,
            text = (if (subject != null) around else around.drop(1)).joinToString("\n"),
        )
    }
    if (text.isEmpty()) return Captured(title = subject.orEmpty())
    if (subject != null) return Captured(title = singleLine(subject), text = text)
    val first = text.lineSequence().first().trim()
    return if (first.length <= TITLE_LENGTH) {
        Captured(title = first, text = text.substringAfter('\n', "").trim())
    } else {
        // Too long for a heading: the start of it names the entry, and all of it is kept below.
        Captured(title = shortened(first), text = text)
    }
}

/** A heading longer than this is a paragraph, and is cut down to name the entry. */
private const val TITLE_LENGTH = 80

private fun singleLine(text: String) = text.replace(Regex("\\s+"), " ").trim()

/** [text] cut at a word near [TITLE_LENGTH], with an ellipsis to say there is more. */
private fun shortened(text: String): String {
    val head = text.take(TITLE_LENGTH)
    // Cut where a word ends: at the limit when one ends there, at the last space before it otherwise.
    val cut = if (text.getOrNull(TITLE_LENGTH)?.isWhitespace() == true) head else head.substringBeforeLast(' ', head)
    return cut.trimEnd(',', ';', ':', '.', ' ') + "…"
}

/** The first link in [text], without the punctuation a sentence put after it. */
internal fun firstUrlIn(text: String): String? =
    listOfNotNull(OrgSyntax.url.find(text), GEO_URI.find(text)).minByOrNull { it.range.first }
        ?.value?.trimEnd('.', ',', ';', ':', '!', '?')

/** `geo:50.8466,4.3528?q=…`, which Android's map apps share and Org does not count as a link. */
private val GEO_URI = Regex("geo:-?\\d[^\\s]*")

/** A page's link as a title, when nothing else names it: `github.com/jglouis/korgnote`. */
private fun titleOfUrl(url: String): String =
    url.substringAfter("://").removePrefix("www.").substringBefore('?').substringBefore('#').trimEnd('/')

/**
 * The Google Maps short link [shared] holds, `https://maps.app.goo.gl/…`, which is all Google Maps
 * shares of a place besides its name: where it leads names the place and gives its address. Null
 * when there is none, or it was already looked up.
 */
fun mapShortLinkIn(shared: SharedContent): String? {
    if (shared.calendar != null || shared.expanded != null) return null
    val link = shared.url?.trim()?.takeIf { it.isNotEmpty() } ?: firstUrlIn(shared.text?.trim().orEmpty()) ?: return null
    return link.takeIf { isMapShortLink(it) }
}

/** Whether [url] is Google Maps' own, `https://www.google.com/maps/place/…`, as a short link leads to. */
fun isGoogleMapsPage(url: String): Boolean =
    url.startsWith("https://") && !isMapShortLink(url) && isGoogleMapsLink(url)

private fun hostOf(link: String) = link.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()

private fun pathOf(link: String) = link.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')

private fun isMapShortLink(link: String): Boolean {
    val host = hostOf(link)
    return host == "maps.app.goo.gl" || (host == "goo.gl" && pathOf(link).startsWith("maps"))
}

private fun isGoogleMapsLink(link: String): Boolean {
    val host = hostOf(link)
    return isMapShortLink(link) || host.startsWith("maps.google.") ||
        (host.removePrefix("www.").startsWith("google.") && pathOf(link).startsWith("maps"))
}

/**
 * A place, when [link] is a map's: Google Maps' short and long links, Apple Maps', and `geo:`.
 * The name comes from the link when it says — from [expanded], where a short link leads, when that
 * was looked up — from [subject] or the first line shared with it otherwise; the lines after that
 * are the address.
 */
private fun placeFrom(link: String, subject: String?, around: List<String>, expanded: String?): Captured? {
    val host = hostOf(link)
    val google = isGoogleMapsLink(link)
    // What the place is read from: where a short link leads, when that is known; the link itself otherwise.
    val read = expanded?.takeIf { google && isGoogleMapsPage(it) } ?: link
    val query = queryOf(read)
    val apple = host == "maps.apple.com" || host == "maps.apple"
    val geoUri = link.startsWith("geo:")
    if (!google && !apple && !geoUri) return null

    var name: String? = null
    var address: String? = null
    var coordinates: String? = null
    when {
        geoUri -> {
            coordinates = link.removePrefix("geo:").substringBefore('?').substringBefore(';')
            name = query["q"]?.substringAfter('(', "")?.substringBefore(')')?.takeIf { it.isNotEmpty() }
                ?: query["q"]?.takeUnless { COORDINATES.matches(it) }
        }
        apple -> {
            name = query["name"] ?: query["q"]?.takeUnless { COORDINATES.matches(it) }
            address = query["address"]
            coordinates = query["coordinate"] ?: query["ll"] ?: query["sll"]
        }
        else -> {
            // https://www.google.com/maps/place/Tour+Eiffel/@48.8583701,2.2944813,17z/…, or where a
            // short link leads: /maps/place/Eiffel+Tower,+Av.+Gustave+Eiffel,+75007+Paris,+France/data=…
            val words = Regex("/place/([^/@?]+)").find(read)?.groupValues?.get(1)?.let(::decoded)
                ?: (query["q"] ?: query["query"])?.takeUnless { COORDINATES.matches(it) }
            words?.let { nameAndAddress(singleLine(it), subject) }?.let { (named, at) -> name = named; address = at }
            // The place's own pin (`!3d…!4d…`) before where the map was centred (`@…`).
            coordinates = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)").find(read)?.let { "${it.groupValues[1]},${it.groupValues[2]}" }
                ?: Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(read)?.let { "${it.groupValues[1]},${it.groupValues[2]}" }
                ?: query["q"]?.takeIf { COORDINATES.matches(it) }
                ?: query["query"]?.takeIf { COORDINATES.matches(it) }
        }
    }
    val lines = around.toMutableList()
    if (name == null) name = subject ?: lines.removeFirstOrNull()
    else if (lines.firstOrNull() == name) lines.removeAt(0)
    if (address == null) address = lines.joinToString(", ").takeIf { it.isNotEmpty() }
    return Captured(
        title = name?.let(::singleLine) ?: address ?: titleOfUrl(link),
        link = link,
        location = address?.let(::singleLine),
        geo = coordinates?.replace(" ", "")?.takeIf { COORDINATES.matches(it) }?.let { "geo:$it" },
    )
}

/**
 * Google's words for a place, `Eiffel Tower, Av. Gustave Eiffel, 75007 Paris, France`, as its name
 * and its address: the name is [subject] when they start with it — Google Maps' own name for it —
 * what comes before the first comma otherwise.
 */
private fun nameAndAddress(words: String, subject: String?): Pair<String, String?> {
    val known = subject?.let(::singleLine)
    val name = when {
        known != null && (words == known || words.startsWith("$known,")) -> known
        else -> words.substringBefore(',').trim()
    }
    return name to words.removePrefix(name).trimStart(',', ' ').trim().takeIf { it.isNotEmpty() }
}

private val COORDINATES = Regex("^\\s*-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?\\s*$")

/** [link]'s query, decoded. */
internal fun queryOf(link: String): Map<String, String> =
    link.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) null else pair.substring(0, at) to decoded(pair.substring(at + 1))
    }.toMap()

/** [text] with a URL's `+` and `%XX` escapes undone, as UTF-8; left as it is when they are broken. */
internal fun decoded(text: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '+' -> { bytes += ' '.code.toByte(); i++ }
            c == '%' && i + 2 <= text.lastIndex -> {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return text
                bytes += byte.toByte()
                i += 3
            }
            c == '%' -> return text
            else -> { bytes += c.toString().encodeToByteArray().toList(); i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}
