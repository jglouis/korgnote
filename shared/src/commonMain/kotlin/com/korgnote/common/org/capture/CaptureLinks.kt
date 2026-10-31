package com.korgnote.common.org.capture

/** What a `korgnote://capture` link asks to capture. */
data class CaptureLink(val shared: SharedContent)

/**
 * [uri] read as a capture link, or null when it is not one:
 *
 * ```
 * korgnote://capture?template=Task&url=https%3A%2F%2Forgmode.org&title=Org%20mode&body=Selected%20text
 * ```
 *
 * The parameters are org-protocol's `capture` ones, so a bookmarklet written for Emacs needs only
 * its scheme changed; each may be missing, and `template` is read past, there being none here.
 * Shortcuts, Tasker or any web page can open one, which is why what it asks for is always shown
 * before anything is saved.
 */
fun captureLinkOf(uri: String): CaptureLink? {
    val link = uri.trim()
    if (!link.startsWith(PREFIX, ignoreCase = true)) return null
    val after = link.substring(PREFIX.length)
    if (after.isNotEmpty() && after[0] != '?' && after[0] != '/') return null
    val query = queryOf(link)
    fun param(name: String) = query[name]?.trim()?.takeIf { it.isNotEmpty() }
    return CaptureLink(SharedContent(subject = param("title"), text = param("body"), url = param("url")))
}

private const val PREFIX = "korgnote://capture"
