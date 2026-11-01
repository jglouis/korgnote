package com.korgnote.common.org

/** What a state change records: a time stamp (`!`), or a note with one (`@`). */
enum class LogKind { Time, Note }

/**
 * A keyword's logging, as `WAIT(w@/!)` declares it: [onEnter] when the entry moves into it (`@`, a
 * note), [onLeave] when it moves out (`/!`, a time stamp) — the second only when the state it moves
 * to records nothing of its own. Org's `org-extract-log-state-settings`.
 */
data class KeywordLogging(val onEnter: LogKind?, val onLeave: LogKind?)

/**
 * How a file logs what happens to its entries: each keyword's own setting from its `#+TODO:`
 * lines, and the file's `#+STARTUP:` options — `logdone` or `lognotedone` for a `CLOSED:` stamp
 * ([done]), `logrepeat`, `lognoterepeat` or `nologrepeat` for a repeating entry ([repeat]), and
 * `logdrawer` for a `:LOGBOOK:` ([intoDrawer]). Without them, Org's defaults: nothing on done, a
 * time stamp on repeat, notes under the heading.
 */
data class TodoLogging(
    val keywords: Map<String, KeywordLogging> = emptyMap(),
    val done: LogKind? = null,
    val repeat: LogKind? = LogKind.Time,
    val intoDrawer: Boolean = false,
) {
    /**
     * Whether state changes are tracked at all here: some keyword logs, or done is stamped. Only
     * then does Org remove a `CLOSED:` stamp from an entry that is not done any more.
     */
    val isActive: Boolean get() = keywords.isNotEmpty() || done != null

    /**
     * What moving from [previous] to [next] records by the keywords alone: [next]'s own setting on
     * entering, else [previous]'s on leaving; nothing when the keyword is cleared. Org's `dolog`.
     */
    fun forChange(previous: String?, next: String?): LogKind? {
        if (next == null) return null
        return keywords[next]?.onEnter ?: previous?.let { keywords[it]?.onLeave }
    }
}

/** How [text] logs state changes: its `#+TODO:` keywords' settings and its `#+STARTUP:` options. */
fun todoLoggingOf(text: String): TodoLogging {
    val keywords = mutableMapOf<String, KeywordLogging>()
    var done: LogKind? = null
    var repeat: LogKind? = LogKind.Time
    var intoDrawer = false
    for (line in text.lineSequence()) {
        OrgSyntax.todoKeywordsLine.find(line)?.let { m ->
            for (word in m.groupValues[1].split(' ', '\t')) {
                val setting = KEYWORD_SETTING.matchEntire(word) ?: continue
                val enter = setting.groupValues[2].toLogKind()
                val leave = setting.groupValues[3].toLogKind()
                if (enter != null || leave != null) keywords[setting.groupValues[1]] = KeywordLogging(enter, leave)
            }
        }
        STARTUP_LINE.find(line)?.let { m ->
            for (option in m.groupValues[1].trim().split(Regex("\\s+"))) {
                when (option.lowercase()) {
                    "logdone" -> done = LogKind.Time
                    "lognotedone" -> done = LogKind.Note
                    "nologdone" -> done = null
                    "logrepeat" -> repeat = LogKind.Time
                    "lognoterepeat" -> repeat = LogKind.Note
                    "nologrepeat" -> repeat = null
                    "logdrawer" -> intoDrawer = true
                    "nologdrawer" -> intoDrawer = false
                }
            }
        }
    }
    return TodoLogging(keywords, done, repeat, intoDrawer)
}

private fun String.toLogKind(): LogKind? = when (this) {
    "!" -> LogKind.Time
    "@" -> LogKind.Note
    else -> null
}

/**
 * `WAIT(w@/!)`: the keyword (group 1), its key (matched, not kept), its entering setting (group 2)
 * and, after `/`, its leaving one (group 3).
 */
private val KEYWORD_SETTING = Regex("^(${OrgSyntax.TODO_WORD})\\((?:[^!@/)])?([!@])?(?:/([!@]))?\\)$")

private val STARTUP_LINE = Regex("^[ \\t]*#\\+STARTUP:(.*)$", RegexOption.IGNORE_CASE)
