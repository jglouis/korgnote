package com.korgnote.common.org

internal actual fun Regex.everyMatch(text: String): Sequence<FoundMatch> =
    findAll(text).map { FoundMatch(it.range, it.groupValues) }
