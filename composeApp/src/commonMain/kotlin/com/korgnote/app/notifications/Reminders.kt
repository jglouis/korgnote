package com.korgnote.app.notifications

import com.korgnote.app.ui.buildOrgInlineString
import com.korgnote.app.ui.hhmm
import com.korgnote.app.ui.timeLabel
import com.korgnote.common.org.agenda.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/*
 * Local reminders from the agenda (T18): worked out here, the same on every platform, and handed
 * to a platform scheduler that only has to replace what it holds with this list. Nothing goes
 * through a server, so reminders fire with the network off and the app closed.
 */

/** What the user chose in Settings. */
data class ReminderSettings(
    /** Remind before each timed entry. */
    val enabled: Boolean = true,
    /** How long before an entry's time its reminder fires. */
    val leadMinutes: Int = 10,
    /** A morning notification listing the day's agenda. */
    val digestEnabled: Boolean = false,
    val digestTime: LocalTime = LocalTime(8, 0),
) {
    /** One string, so each platform stores it under a single key. */
    fun encode(): String = listOf(enabled, leadMinutes, digestEnabled, digestTime.hhmm()).joinToString(";")

    companion object {
        /** The stored form, or the defaults for anything missing or unreadable. */
        fun decode(stored: String?): ReminderSettings {
            val parts = stored?.split(';') ?: return ReminderSettings()
            val defaults = ReminderSettings()
            return ReminderSettings(
                enabled = parts.getOrNull(0)?.toBooleanStrictOrNull() ?: defaults.enabled,
                leadMinutes = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 24 * 60) ?: defaults.leadMinutes,
                digestEnabled = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: defaults.digestEnabled,
                digestTime = parts.getOrNull(3)?.let(::parseTime) ?: defaults.digestTime,
            )
        }

        /** `8:00` or `08:00`; null for anything else. */
        fun parseTime(text: String): LocalTime? {
            val (h, m) = text.trim().split(':').takeIf { it.size == 2 } ?: return null
            val hour = h.toIntOrNull() ?: return null
            val minute = m.toIntOrNull() ?: return null
            return if (hour in 0..23 && minute in 0..59) LocalTime(hour, minute) else null
        }
    }
}

/**
 * One notification to show at [at], wall-clock time on the device.
 *
 * [id] is stable for the same entry at the same time, so a platform can tell a reminder it already
 * holds from a new one. [file] and [offset] are where tapping it opens: the heading of the entry,
 * or nothing for the daily digest.
 */
@Serializable
data class Reminder(
    val id: String,
    val at: LocalDateTime,
    val title: String,
    val body: String,
    val file: String? = null,
    val offset: Int? = null,
)

/** How far ahead reminders are planned; the next time the app runs moves the window on. */
const val REMINDER_HORIZON_DAYS = 14

/**
 * The most reminders held at once. iOS keeps at most 64 pending notifications and silently drops
 * the rest; staying under it leaves room for the system's own.
 */
const val MAX_REMINDERS = 60

/**
 * The reminders to hold, soonest first: one [ReminderSettings.leadMinutes] before each timed entry
 * that is not done, and a digest each morning that has something on it — every one still in the
 * future, within [REMINDER_HORIZON_DAYS], and no more than [MAX_REMINDERS].
 *
 * Soonest first matters for the cap: when there are more than fit, it is next month's that wait for
 * a later run, not tomorrow's.
 */
fun planReminders(entries: List<AgendaEntry>, now: LocalDateTime, settings: ReminderSettings): List<Reminder> {
    val today = now.date
    val last = today.plus(REMINDER_HORIZON_DAYS, DateTimeUnit.DAY)
    // Nothing is carried over: a reminder is for something that is about to happen, and what is
    // overdue is the agenda list's business.
    val occurrences = entries.occurrences(today, last, AgendaOptions(today = today, carryOver = false))
        .filter { !it.entry.done && it.isOnOwnDate }

    val reminders = mutableListOf<Reminder>()
    if (settings.enabled) {
        for (occurrence in occurrences) {
            val time = occurrence.time ?: continue
            // A multi-day range reminds once, when it starts.
            if (occurrence.span != DaySpan.Single && occurrence.span != DaySpan.Start) continue
            val at = LocalDateTime(occurrence.date, time).minusMinutes(settings.leadMinutes)
            if (at <= now) continue
            reminders += Reminder(
                id = "entry:${occurrence.entry.file}:${occurrence.entry.stamp.range.first}:${occurrence.date}",
                at = at,
                title = occurrence.entry.plainHeadline(),
                body = listOfNotNull(occurrence.timeLabel(), occurrence.entry.kind.word(), occurrence.entry.file)
                    .joinToString(" · "),
                file = occurrence.entry.file,
                offset = occurrence.entry.headingOffset,
            )
        }
    }
    if (settings.digestEnabled) {
        for ((date, onDay) in occurrences.groupBy { it.date }) {
            val at = LocalDateTime(date, settings.digestTime)
            if (at <= now) continue
            reminders += Reminder(
                id = "digest:$date",
                at = at,
                title = if (onDay.size == 1) "Today: 1 entry" else "Today: ${onDay.size} entries",
                body = onDay.joinToString("\n") { it.digestLine() },
            )
        }
    }
    return reminders.sortedWith(compareBy({ it.at }, { it.id })).take(MAX_REMINDERS)
}

/** The heading as it reads, without markup: a notification cannot show a link or emphasis. */
private fun AgendaEntry.plainHeadline(): String =
    listOfNotNull(todo, buildOrgInlineString(title, viewMode = true).text).joinToString(" ")

private fun AgendaKind.word(): String? = when (this) {
    AgendaKind.Deadline -> "Deadline"
    AgendaKind.Scheduled -> "Scheduled"
    AgendaKind.Timestamp, AgendaKind.Closed -> null
}

private fun AgendaOccurrence.digestLine(): String {
    val lead = timeLabel() ?: entry.kind.word() ?: "All day"
    return "$lead  ${entry.plainHeadline()}"
}

/** Wall-clock arithmetic, in UTC so that no daylight-saving jump moves the result. */
private fun LocalDateTime.minusMinutes(minutes: Int): LocalDateTime =
    (toInstant(TimeZone.UTC) - minutes.minutes).toLocalDateTime(TimeZone.UTC)

// --- Platforms ---------------------------------------------------------------------------------

/**
 * Holds the device's pending reminders.
 *
 * [replaceAll] is the whole contract: afterwards the device holds exactly [reminders]. Everything
 * else — asking for permission, surviving a reboot, the iOS limit — is each platform's business.
 */
interface NotificationScheduler {
    suspend fun replaceAll(reminders: List<Reminder>)
}

/** Desktop and web: a browser only notifies while a tab is open, so reminders are not a web feature. */
object NoopNotificationScheduler : NotificationScheduler {
    override suspend fun replaceAll(reminders: List<Reminder>) = Unit
}

/** Where a tapped notification asks to be opened. */
data class ReminderTarget(val file: String, val offset: Int)

/**
 * Taps on a notification, from the platform code that receives them to the app that opens the
 * note. A process-wide mailbox because a tap can arrive before the app's screen exists, and is
 * collected once it does.
 */
object ReminderTaps {
    val pending = MutableStateFlow<ReminderTarget?>(null)
}
