package com.korgnote.app.data

/**
 * An element of a WebDAV answer: its local name, with whatever namespace prefix the server chose
 * dropped, its children, and its own text.
 *
 * WebDAV answers in XML and Kotlin has no common parser, so [parseDavXml] reads just enough of it
 * for a `207 Multi-Status`. Names are matched without their namespace: the properties asked for are
 * all `DAV:` ones, and servers spell its prefix `d:`, `D:`, `lp1:` or not at all.
 */
internal class DavElement(val name: String) {
    val children = mutableListOf<DavElement>()
    internal val textBuilder = StringBuilder()

    /** The element's own text, trimmed; its children's is theirs. */
    val text: String get() = textBuilder.toString().trim()

    fun child(name: String): DavElement? = children.firstOrNull { it.name == name }

    fun all(name: String): List<DavElement> = children.filter { it.name == name }
}

/**
 * [xml] as a tree under a nameless root. Comments, the declaration and doctypes are skipped, and
 * attributes too: a multistatus needs none. Fails on a tag that never closes.
 */
internal fun parseDavXml(xml: String): DavElement {
    val root = DavElement("")
    val open = ArrayDeque<DavElement>().apply { addLast(root) }
    var at = 0
    while (at < xml.length) {
        val lt = xml.indexOf('<', at)
        if (lt < 0) {
            open.last().textBuilder.append(decodeXmlEntities(xml.substring(at)))
            break
        }
        if (lt > at) open.last().textBuilder.append(decodeXmlEntities(xml.substring(at, lt)))
        at = when {
            xml.startsWith("<!--", lt) -> after(xml, "-->", lt)
            xml.startsWith("<![CDATA[", lt) -> {
                val end = after(xml, "]]>", lt)
                open.last().textBuilder.append(xml, lt + 9, end - 3)
                end
            }
            xml.startsWith("<?", lt) || xml.startsWith("<!", lt) -> after(xml, ">", lt)
            else -> {
                val gt = tagEnd(xml, lt)
                val tag = xml.substring(lt + 1, gt).trim()
                if (tag.startsWith("/")) {
                    if (open.size > 1) open.removeLast()
                } else {
                    val element = DavElement(tag.removeSuffix("/").trim().takeWhile { !it.isWhitespace() }.substringAfter(':'))
                    open.last().children += element
                    if (!tag.endsWith("/")) open.addLast(element)
                }
                gt + 1
            }
        }
    }
    return root
}

/** Just past the [end] that follows [from]. */
private fun after(xml: String, end: String, from: Int): Int {
    val found = xml.indexOf(end, from)
    if (found < 0) throw IllegalArgumentException("The server's answer is not complete XML.")
    return found + end.length
}

/** The `>` closing the tag that opens at [lt], skipping any inside a quoted attribute value. */
private fun tagEnd(xml: String, lt: Int): Int {
    var quote: Char? = null
    for (i in lt + 1 until xml.length) {
        val c = xml[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i
        }
    }
    throw IllegalArgumentException("The server's answer is not complete XML.")
}

private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

internal fun decodeXmlEntities(text: String): String = text.replace(ENTITY) { match ->
    val name = match.groupValues[1]
    when {
        name.startsWith("#x") -> codePoint(name.substring(2).toInt(16))
        name.startsWith("#") -> codePoint(name.substring(1).toInt())
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        else -> match.value
    }
}

private fun codePoint(value: Int): String = if (value < 0x10000) {
    value.toChar().toString()
} else {
    val offset = value - 0x10000
    charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
}
