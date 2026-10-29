# 07 — Calendar, agenda and notifications

Addresses G6 and defect D7. The existing calendar is already useful; this is about giving
it a real engine underneath and the views you miss from Google Calendar, without losing
what you rely on in org-agenda.

## Where it stands

`FileTreeViewModel:230` builds `Map<LocalDate, List<Pair<String, OrgNode>>>` by walking
documents for `OrgPlanning` nodes where `planType == "SCHEDULED"`, then regex-matching
`<(\d{4})-(\d{2})-(\d{2})` out of the timestamp string. `CalendarScreen` renders a month
grid and a week row of dots, with a dialog per day.

Consequently: no `DEADLINE`, no times, no ranges, no repeaters, no inactive timestamps, no
plain body timestamps, no day view. The month/week UI itself (swipe navigation, animation,
day cells) is good and should survive.

## Timestamp model (`shared/org/agenda`)

Cover the Org syntax you actually write:

| Syntax | Meaning |
|---|---|
| `<2026-09-20 Sun>` | active date |
| `[2026-09-20 Sun]` | inactive — excluded from the agenda by default |
| `<2026-09-20 Sun 10:00>` | date and time |
| `<2026-09-20 Sun 10:00-11:30>` | time range within a day |
| `<2026-09-20 Sun>--<2026-09-22 Tue>` | multi-day range |
| `+1w`, `++1w`, `.+1d` | repeaters: shift / shift into the future / shift from completion |
| `-2d` on a `DEADLINE` | warning period: start showing it two days early |
| `--2d` on a `SCHEDULED` | delay |

```kotlin
data class OrgTimestamp(
    val start: LocalDate,
    val startTime: LocalTime?,
    val end: LocalDate?,          // multi-day range
    val endTime: LocalTime?,      // same-day time range
    val active: Boolean,
    val repeater: OrgRepeater?,
    val warning: OrgDelay?,
    val range: IntRange,          // source range — enables "drag to reschedule"
)

enum class RepeatKind { Cumulate, CatchUp, Restart }   // +1w, ++1w, .+1d
data class OrgRepeater(val kind: RepeatKind, val amount: Int, val unit: DateTimeUnit)
```

`shared/` needs `kotlinx-datetime` added for this (it is currently only a `composeApp`
dependency).

## Agenda engine

```kotlin
data class AgendaEntry(
    val file: String,
    val heading: HeadingRef,      // file + source range, so the UI can open exactly there
    val title: String,
    val todo: String?,
    val done: Boolean,
    val tags: List<String>,
    val kind: AgendaKind,         // Scheduled | Deadline | Timestamp | Closed
    val stamp: OrgTimestamp,
)

data class AgendaOccurrence(
    val entry: AgendaEntry,
    val date: LocalDate,
    val time: LocalTime?,
    val endTime: LocalTime?,
    val daysUntilDeadline: Int?,  // non-null while inside a deadline's warning window
    val isCarriedOver: Boolean,   // scheduled in the past, still not done
)

fun agenda(docs: Collection<OrgDocument>, from: LocalDate, to: LocalDate,
           options: AgendaOptions): List<AgendaOccurrence>
```

Expansion rules, each a unit test:

- **Scheduled, plain date** → one occurrence. If the date has passed and the entry is not
  DONE, keep surfacing it on today with `isCarriedOver = true` — this is the org-agenda
  behaviour you rely on, and it must be an option, not a surprise.
- **Deadline with `-Nd`** → occurrences for each day in the warning window with
  `daysUntilDeadline` counting down; default warning when unspecified is 14 days
  (`org-deadline-warning-days`).
- **Repeaters** → expand occurrences across `[from, to]`; DONE entries with a repeater show
  their *next* occurrence only.
- **Multi-day ranges** → one occurrence per day, flagged as start/middle/end so the UI can
  draw a continuous bar. This holds for `SCHEDULED: <a>--<b>` too, which is how a trip or a
  week off is usually written; org-agenda shows only its first day. A scheduled range still
  under way is on today already and is not carried over as well. In reading mode each half
  of a planning range links to its own day in the calendar.
- **Body timestamps** (active, not attached to a planning line) → `Timestamp` kind; these
  are your "meetings" and they are invisible today.
- **Inactive `[…]`** → excluded unless explicitly requested.

The result is an `AgendaIndex` in the client, rebuilt whenever the replica changes. It
replaces `scheduledByDate` and decouples the calendar from raw `OrgPlanning` nodes.

## Views

| View | Contents | Why |
|---|---|---|
| **Day** | hour grid (configurable day start/end), timed events as blocks, all-day strip on top (scrolling past a third of the screen), "now" line; pinch (or Ctrl and the wheel) to stretch or squeeze the hours, kept between day and week | the missing piece; where Google Calendar beats org-agenda |
| **Week** | 7 columns, all-day strip, hour grid, drag horizon; on a phone held upright the days scroll sideways, three and a bit on screen | planning the week |
| **Month** | existing grid, but with per-day chips: count, deadline vs scheduled colours, DONE de-emphasised; each week as tall as its busiest day, scrolling when they need more than the screen | overview; keep the current swipe/animation work |
| **Agenda list** | grouped by day, `TODO`-keyword and tag chips, overdue section on top | the closest thing to `org-agenda`, and probably your daily driver |

Rendering rules worth fixing early: deadlines and scheduled items must be visually distinct
(colour + icon), DONE items dimmed or hidden by a filter, and every occurrence tappable to
open the document at `heading.range`.

Editing from the calendar becomes possible once `org.edit` exists: dragging an event calls
`setPlanning(heading, SCHEDULED, newStamp)`, which rewrites only the timestamp's source
range.

## Notifications

Schedule **local** notifications on the device from the agenda — no push server, no APNs or
FCM setup, and reminders keep working offline. This is a significant maintainability win
(G7) and should be the default plan.

```kotlin
expect class NotificationScheduler {
    suspend fun requestPermission(): Boolean
    suspend fun replaceAll(reminders: List<Reminder>)   // idempotent: cancel + reschedule
}

data class Reminder(val id: String, val at: Instant, val title: String,
                    val body: String, val deepLink: String)   // file + offset
```

| Platform | Implementation | Constraint |
|---|---|---|
| iOS | `UNUserNotificationCenter` + `UNCalendarNotificationTrigger` | **64 pending notifications maximum.** Schedule the nearest ~60 and refresh on every foreground/sync. |
| Android | `WorkManager` for ordinary reminders; `AlarmManager.setExactAndAllowWhileIdle` only where precision matters | `POST_NOTIFICATIONS` runtime permission (API 33+); exact alarms need extra permission (API 31+) — prefer inexact. |
| Desktop | optional; tray notification | low value, do last |
| Web | no-op scheduler | Browser notifications only fire while a tab is open, so reminders are not a web feature; everything else in the web client is full-featured ([02](02-assessment.md#the-web-target-keep-it-as-a-full-online-client)) |

Policy: a reminder per timed occurrence at a configurable lead time (default 10 minutes),
plus an optional daily digest in the morning listing today's agenda. Rescheduling is
idempotent — always "cancel all, schedule the next N" — so a sync that changes nothing
leaves notifications untouched.

## Home-screen widgets (Android)

Two Glance widgets in `androidApp/.../widget`: the **agenda** (overdue, then the week from today,
as the app's agenda list) and the **month** (a dot per entry on its own day, up to three; arrows
move the widget's own month). A tapped entry opens its note at its heading, as a reminder does;
a tapped day or title opens the app's calendar there (`CalendarTaps`).

- **What they draw from.** The app hands over the `AgendaEntry` list, not occurrences, whenever
  the agenda is rebuilt (`AgendaWidgets`, called from `NoteAgenda.show`), with when the notes
  last synced. It is kept as JSON in the app's files (`WidgetStore`). The widgets work out the
  days when they draw, so overdue items and deadline warnings are right on a new day without
  the app; a non-waking alarm redraws them around midnight, and on a clock or zone change.
- **Sync button.** Starts a one-off `WorkManager` job that syncs as the app does on opening (the
  replica with the server, or a look at the folder), rebuilds the entries and redraws. No
  periodic background sync: the widget says how fresh it is ("Updated 14:32") instead. If the
  app is open meanwhile it reads the notes again (`WidgetSyncs`), so nothing on screen is saved
  back over what the sync brought.
- **Not yet:** ticking off a TODO from the widget, a picker preview image, iOS (WidgetKit would
  read the same entries from an App Group).

## Later, if it earns its place

- **CalDAV export** so the same agenda appears in your phone's native calendar app. This is
  read-only publishing of `AgendaOccurrence`s and does not affect the sync design; it is
  the cheapest way to get your Org agenda in front of other tools.
- **Capture**: create a dated entry from the calendar into an inbox file — natural once
  `PUT` can create files ([05](05-sync-protocol.md), D11).
