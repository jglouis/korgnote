package com.korgnote.common.org.template

import com.korgnote.common.org.complete.orgStamp
import kotlinx.datetime.LocalDate

/**
 * The head of a new note, as org-roam writes one: the file's own `:ID:` drawer first, so links to it
 * can go by id and survive a rename, then the keywords that say what it is.
 *
 * ```
 * :PROPERTIES:
 * :ID:       6d4a3005-6ac4-48b2-8751-0cdb35e4fd93
 * :END:
 * #+TITLE: Agenda
 * #+AUTHOR: Jean-Guillaume Louis
 * #+DATE: [2024-03-22 Fri]
 * #+filetags: :work:ecam:
 * ```
 *
 * A blank [author] and no [tags] leave their lines out. [extraHeader] — lines such as `#+TODO:` the
 * notes want in every file — follows the keywords as written, and a blank line ends the head.
 */
fun newNoteText(
    id: String,
    title: String,
    date: LocalDate,
    author: String = "",
    tags: List<String> = emptyList(),
    extraHeader: String = "",
): String = buildString {
    append(":PROPERTIES:\n")
    append(":ID:       ").append(id).append('\n')
    append(":END:\n")
    append("#+TITLE: ").append(title.trim()).append('\n')
    if (author.isNotBlank()) append("#+AUTHOR: ").append(author.trim()).append('\n')
    append("#+DATE: ").append(date.orgStamp(active = false)).append('\n')
    filetagsValue(tags)?.let { append("#+filetags: ").append(it).append('\n') }
    extraHeader.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.forEach { append(it).append('\n') }
    append('\n')
}

/** `:a:b:` for [tags], or null when there are none worth writing. */
fun filetagsValue(tags: List<String>): String? {
    val clean = tags.map { cleanTag(it) }.filter { it.isNotEmpty() }.distinct()
    return if (clean.isEmpty()) null else clean.joinToString(":", prefix = ":", postfix = ":")
}

/**
 * [typed] as a tag: what Org allows in one — letters, digits, `_`, `@`, `#` and `%` — with anything
 * else dropped, so a stray colon or space cannot split it into two.
 */
fun cleanTag(typed: String): String =
    typed.trim().trim(':').filter { it.isLetterOrDigit() || it in "_@#%" }

/** Tags as typed in one field: separated by spaces, commas or colons. */
fun parseTypedTags(typed: String): List<String> =
    typed.split(' ', ',', ':', '\t').map { cleanTag(it) }.filter { it.isNotEmpty() }.distinct()

/** A daily's title: its date, `2026-09-18`, as org-roam-dailies names them. */
fun dailyTitle(date: LocalDate): String = date.toString()

/** A daily's file name, `2026-09-18.org`. */
fun dailyFileName(date: LocalDate): String = "${dailyTitle(date)}.org"

/** The `#+TODO:` lines these notes use, offered as the extra header until Settings says otherwise. */
const val DEFAULT_EXTRA_HEADER =
    "#+TODO: TODO(t) DOING(p!) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)\n" +
        "#+TODO: EVENT(e) | FINISHED(f)"
