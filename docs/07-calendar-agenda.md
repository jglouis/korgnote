# 07 — Calendar, agenda and notifications

Addresses G6 and defect D7. The existing calendar is already useful; this is about giving
it a real engine underneath and the views you miss from Google Calendar, without losing
what you rely on in org-agenda.

## Where it stands

Built (T15–T18, and the widgets, T33). When this was written, `FileTreeViewModel:230` picked
`SCHEDULED` dates out of the notes with a regex, and the calendar was a month grid and a week row
of dots: no `DEADLINE`, no times, no ranges, no repeaters, no body timestamps, no day view. That
is all gone.

Now `shared/…/org/agenda/` parses every stamp (`OrgTimestamp.kt`) and expands entries into
occurrences (`Agenda.kt`). The app keeps an `AgendaIndex`, rebuilt when the notes change, and the
calendar tab draws agenda, day, week and month views from it. Reminders are planned from the same
entries (`Reminders.kt`), and two Android widgets draw them on the home screen. The sketches below
are the design as it was written, corrected where the code went another way. What is not built,
and the known gaps, are noted where they apply.

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
| `-2d` on a `SCHEDULED` | delay: do not show it until two days after its date |
| `--2d` | the same, on a repeating stamp, for the first repeat only |

```kotlin
data class OrgTimestamp(
    val start: LocalDate,
    val startTime: LocalTime? = null,
    val end: LocalDate? = null,          // multi-day range
    val endTime: LocalTime? = null,      // same-day time range
    val active: Boolean = true,
    val repeater: OrgRepeater? = null,
    val delay: OrgDelay? = null,         // -2d: a warning on a DEADLINE, a delay on a SCHEDULED
    val range: IntRange = IntRange.EMPTY, // source range — enables "drag to reschedule"
)

enum class OrgTimeUnit { HOUR, DAY, WEEK, MONTH, YEAR }   // h d w m y
data class OrgDuration(val amount: Int, val unit: OrgTimeUnit)

enum class RepeatKind { Cumulate, CatchUp, Restart }   // +1w, ++1w, .+1d
data class OrgRepeater(val kind: RepeatKind, val every: OrgDuration)
data class OrgDelay(val by: OrgDuration, val firstOnly: Boolean)   // firstOnly: the -- form
```

The design called the delay `warning`; it is `delay`, because the same `-2d` is a warning on a
`DEADLINE` and a delay on a `SCHEDULED`. Units are Org's own rather than `DateTimeUnit`.
`kotlinx-datetime` was added to `shared/` for this (T15).

## Agenda engine

```kotlin
data class AgendaEntry(
    val file: String,
    val headingOffset: Int,       // where the heading starts: the app opens a note there
    val title: String,
    val todo: String?,
    val done: Boolean,
    val tags: List<String>,
    val priority: Char?,
    val kind: AgendaKind,         // Scheduled | Deadline | Timestamp | Closed
    val stamp: OrgTimestamp,
)

enum class DaySpan { Single, Start, Middle, End }

data class AgendaOccurrence(
    val entry: AgendaEntry,
    val date: LocalDate,
    val time: LocalTime?,
    val endTime: LocalTime?,
    val daysUntilDeadline: Int? = null,  // non-null while inside a deadline's warning window
    val isCarriedOver: Boolean = false,  // in the past, still not done
    val span: DaySpan = DaySpan.Single,  // which day of a multi-day range this is
)

fun agenda(files: Map<String, String>, from: LocalDate, to: LocalDate,
           options: AgendaOptions): List<AgendaOccurrence>
```

It takes the notes' text by path rather than parsed documents: entries are read from raw text,
element by element, as search reads them. An entry opens at `headingOffset`, the same coordinate
a search hit carries, rather than through a `HeadingRef`.

Expansion rules, each a unit test:

- **Scheduled, plain date** → one occurrence. If the date has passed and the entry is not
  DONE, keep surfacing it on today with `isCarriedOver = true` — this is the org-agenda
  behaviour you rely on, and it must be an option, not a surprise.
- **Scheduled with `-Nd`** → shown N days after its date; with `--Nd`, only the first repeat is
  delayed.
- **Deadline with `-Nd`** → occurrences for each day in the warning window with
  `daysUntilDeadline` counting down; default warning when unspecified is 14 days
  (`org-deadline-warning-days`). A deadline missed and not done is carried to today, counting how
  late it is. That holds for a repeating one too: until it is marked done it shows as overdue, and
  no warning of its next repeat takes its place — its date moves on when it is done, not when it
  passes.
- **Repeaters** → expand occurrences across `[from, to]`; DONE entries with a repeater show
  their *next* occurrence only.
- **Multi-day ranges** → one occurrence per day, flagged as start/middle/end so the UI can
  draw a continuous bar. This holds for `SCHEDULED: <a>--<b>` too, which is how a trip or a
  week off is usually written; org-agenda shows only its first day. A scheduled range still
  under way is on today already and is not carried over as well. In reading mode each half
  of a planning range links to its own day in the calendar.
  *Known gap:* two kinds of range are drawn on their first day only — a done entry with a
  repeater, which shows just its next occurrence, and a `DEADLINE: <a>--<b>`.
- **Body timestamps** (active, not attached to a planning line) → `Timestamp` kind; these
  are your "meetings" and they are invisible today.
- **Inactive `[…]`** → excluded unless explicitly requested.

The result is an `AgendaIndex` in the client, rebuilt whenever the replica changes. It
replaced `scheduledByDate` and decouples the calendar from raw `OrgPlanning` nodes.

The index is built as of one day, but the calendar keeps its own clock, ticking each minute, and
reads the index as of that day (`AgendaIndex.asOf(today)`). So an app left open past midnight
shows the new day's overdue items and warnings without the notes changing. No setting exposes
`carryOver` or `includeInactive`: both are options of the engine only.

## Views

| View | Contents | Why |
|---|---|---|
| **Day** | hour grid (configurable day start/end), timed events as blocks, all-day strip on top (scrolling past a third of the screen), "now" line; pinch (or Ctrl and the wheel) to stretch or squeeze the hours, kept between day and week | the missing piece; where Google Calendar beats org-agenda |
| **Week** | 7 columns, all-day strip, hour grid, drag horizon; on a phone held upright the days scroll sideways, three and a bit on screen | planning the week |
| **Month** | existing grid, but with per-day chips: count, deadline vs scheduled colours, DONE de-emphasised; each week as tall as its busiest day, scrolling when they need more than the screen | overview; keep the current swipe/animation work |
| **Agenda list** | grouped by day, `TODO`-keyword and tag chips, overdue section on top | the closest thing to `org-agenda`, and probably your daily driver |

Rendering rules worth fixing early: deadlines and scheduled items must be visually distinct
(colour + icon), DONE items dimmed or hidden by a filter, and every occurrence tappable to
open the document at `headingOffset`.

**As built** (T17 and after). All four views exist, and each period is a page that turns under
the finger. Pinch, or Ctrl and the wheel, stretches the hours in day and week. The week's all-day
strip shows three rows — ranges first, as bars — and a "+N" for the rest of a day, which opens
that day. In the month, too, a multi-day range is one bar across its days. In reading mode a
`SCHEDULED` or `DEADLINE` stamp opens the calendar on its day. **Not built:** configurable day
start and end hours (the grid opens an hour before now, or at 7:00 on any other day —
`dayStartHour` in `CalendarViews.kt`, fixed) and the week's drag horizon.

Editing from the calendar becomes possible once `org.edit` exists: dragging an event calls
`setPlanning(heading, SCHEDULED, newStamp)`, which rewrites only the timestamp's source
range. **Not built:** `setPlanning` exists (T7), but nothing in the calendar drags yet.

## Notifications

Schedule **local** notifications on the device from the agenda — no push server, no APNs or
FCM setup, and reminders keep working offline. This is a significant maintainability win
(G7) and should be the default plan.

```kotlin
interface NotificationScheduler {
    suspend fun replaceAll(reminders: List<Reminder>)   // afterwards the device holds exactly these
}

data class Reminder(
    val id: String,              // stable for the same entry at the same time
    val at: LocalDateTime,       // wall-clock time on the device
    val title: String,
    val body: String,
    val file: String? = null,    // where a tap opens: the entry's heading;
    val offset: Int? = null,     //   none for the digest
    val lines: List<ReminderLine> = emptyList(),   // the digest's entries, one per line
    val summary: String? = null, // the digest's count: "5 entries"
)
```

As built, the scheduler is an interface, not an `expect class`, and it has no
`requestPermission`. Asking is left to the welcome screens and to Settings, which shows "Allow
notifications" while they cannot be shown; a scheduler only checks. Allowing them from Settings
hands the plan over again (`NoteAgenda.onNotificationsAllowed`), since iOS drops the reminders it
was not allowed to show. `at` is a wall-clock time rather than an `Instant`: each platform turns
it into an instant when it sets the alarm, and Android sets them again after a time-zone change.

| Platform | Implementation | Constraint |
|---|---|---|
| iOS | `UNUserNotificationCenter` + `UNCalendarNotificationTrigger` | **64 pending notifications maximum.** Schedule the nearest ~60 and refresh on every foreground/sync. |
| Android | `AlarmManager.setExactAndAllowWhileIdle`, one alarm per reminder (`USE_EXACT_ALARM`); `setAndAllowWhileIdle` only if exact alarms are refused | `POST_NOTIFICATIONS` runtime permission (API 33+). The design preferred inexact alarms; measured, they came up to an hour late, so exact alarms won ([08](08-roadmap.md), T18). |
| Desktop | optional; tray notification | low value, do last — **not built** |
| Web | no-op scheduler | Browser notifications only fire while a tab is open, so reminders are not a web feature; everything else in the web client is full-featured ([02](02-assessment.md#the-web-target-keep-it-as-a-full-online-client)) |

Policy: a reminder per timed occurrence at a configurable lead time (default 10 minutes),
plus an optional daily digest in the morning listing today's agenda. Rescheduling is
idempotent — always "cancel all, schedule the next N" — so a sync that changes nothing
leaves notifications untouched. The plan is made again on every change to the notes and on
returning to the app, and on Android after every sync made with the app closed — hourly, after a
failed push once the network is back, and from a widget's sync button — so a meeting added on the
computer gets its reminder without the app being opened (`planRemindersFrom`).

**The morning digest, as built.** Off by default; when on, it comes at 08:00 unless Settings' clock
picker says otherwise. There is one for each day in the next 14 that has something on it. Its title
is the date written out ("Thursday 24 September"), each line an entry — its time, or "Deadline",
"Scheduled" or "All day", then the heading — and it says how many there are ("5 entries"). Android
lays it out as an inbox: six lines, then "N more in the calendar". The digests are written when the
plan is made, not when they fire, so an edit reaches them only when the app plans again.
*Known gap:* a digest is built from each day's own entries (`carryOver = false`, `isOnOwnDate`), so
overdue items and deadline warnings are left out — less than the "today's agenda" above promises,
and less than the agenda list shows.

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
- **What they show.** The agenda widget puts each entry's TODO keyword, in its colour, on its
  status line, as the app's agenda does. The month widget's arrows draw the new month at once.
  Both have a preview in the launcher's widget picker (`WidgetPreviews.kt`,
  `res/layout-v31/*_preview.xml`).
- **Sync button.** Starts a one-off `WorkManager` job that syncs as the app does on opening (the
  replica with the server or with WebDAV, or a look at the folder), rebuilds the entries and
  redraws, and plans reminders from the notes it brought. The same runs every hour in the
  background (`BackgroundSyncWorker`); the widget still says how fresh it is ("Updated 14:32"). If
  the app is open meanwhile it reads the notes again (`WidgetSyncs`), so nothing on screen is saved
  back over what the sync brought.
- **Not yet:** ticking off a TODO from the widget, iOS (WidgetKit would read the same entries from
  an App Group).

## Later, if it earns its place

- **CalDAV export** so the same agenda appears in your phone's native calendar app. This is
  read-only publishing of `AgendaOccurrence`s and does not affect the sync design; it is
  the cheapest way to get your Org agenda in front of other tools.
- **Capture**: create a dated entry from the calendar into an inbox file. Capture from other apps
  is in [10](10-capture.md); from the calendar, and from the phone's own calendar, not yet.
