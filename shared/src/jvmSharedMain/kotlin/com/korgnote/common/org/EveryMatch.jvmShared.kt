package com.korgnote.common.org

internal actual fun Regex.everyMatch(text: String): Sequence<FoundMatch> = sequence {
    val matcher = toPattern().matcher(text)
    while (matcher.find()) {
        yield(FoundMatch(matcher.start() until matcher.end(), List(matcher.groupCount() + 1) { matcher.group(it).orEmpty() }))
    }
}
