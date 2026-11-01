# 11 — Clocking

KorgNote clocks time as Emacs does (`C-c C-x C-i`, `C-c C-x C-o`): *Clock in* on a heading writes a
`CLOCK:` line into its `:LOGBOOK:`, and *Clock out* closes it with what it spans. The file is the only
state, so a clock started on the phone runs on in Emacs, or on another device, once the note has
synced, and the other way round.

```org
* TODO Write the report
:LOGBOOK:
CLOCK: [2026-11-01 Sun 14:05]
CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:45] =>  1:15
:END:
```

The proposal this came from, with the reasoning and the later phases, is the shared doc
*Org clocking in KorgNote — proposal* (2026-11-01). Phases 1 and 2 are built; this document says how.

## What is written

All in `shared/…/org/edit/Clocking.kt`, each a range replacement like every other edit.

| Action | The file afterwards |
|---|---|
| `clockIn` | `CLOCK: [now]` at the top of the heading's `:LOGBOOK:`, wherever that drawer is in its entry; with none, a drawer after the planning line and the property drawer, indented as they are. A heading whose clock runs is left alone |
| `clockOut` | That line becomes `CLOCK: [start]--[now] =>  1:15`. The start is kept as written (`dim.` from a French Emacs stays). A stop before the start stops at the start |
| `cancelClock` | The open line removed, and the drawer with it when nothing else is in it, as `org-clock-cancel` leaves it |
| `updateClockDurations` | On save, a closed `CLOCK:` line over what changed gets its `=> H:MM` summed again; one without a sum gets one; an end before the start is summed negative, `-1:30`, as `C-c C-c` on the line does. Lines away from the edit are left as they are |

The sum is `%2d:%02d`, as `org-clock-out` writes it: ` 1:15`, `10:15`, `26:00`. Times are wall-clock,
as in the stamps; a clock across a daylight-saving change is off by the hour, as in Emacs. A clock
in and out within a minute writes `0:00` and is kept, since Emacs keeps them too unless told
otherwise.

Clocks are read anywhere in a heading's entry (its line up to its first sub-heading), not only in
the drawer, because Emacs sums them wherever they are. A heading's total is its subtree's.

## In the app

- **The heading's long-press menu** has *Clock in*, or *Clock out* and *Cancel clock* when its clock
  runs. Clocking in stops every other running clock at the same minute, in this note or another
  (`NotesViewModel.clockInAt`). Refused while an element is being edited, as the note would change
  under it.
- **The total beside a heading**, its subtree's clocks included: `1 h 15`, or `1 h 15 / 2 h` against an
  `:Effort:` (one Org can read — `1:30`, `45`, `2h`, `1h 30min` — is shown the same way; any other as
  written). On screen a time is `1 min`, `45 min`, `2 h`, `1 h 05` (`clockDuration`): Emacs's `0:01`,
  kept in the file, read as minutes and seconds. Its own running clock lights it and adds a timer; the total counts up each minute.
- **The running-clock bar**, on every tab, above the navigation bar (at the foot of the screen on a
  wide window, where the tabs are down the side): the heading, how long it has run, and a stop
  button. A tap opens the heading. After `CLOCK_LEFT_RUNNING_MINUTES` (8 hours) it turns to a warning,
  and its stop button offers *Clock out now*, *Clock out at…* (a time of day, taken as its latest
  moment between the start and now) and *Cancel clock*.
- **Several clocks running** — two devices clocked in while apart, or Emacs and the phone — are all
  found (`NoteClocks`, from every note in the library) and the bar says how many; a tap lists them,
  each to open or stop.
- **Highlighting**: in a `CLOCK:` line the keyword is bold and the stamps are stamps, in the editor
  (`orgSpans`) and in reading (`appendDrawerLine`), where each stamp links to its day.

## Android's notification

`AndroidClockNotifier` (composeApp, androidMain) keeps an ongoing, silent notification on its own
low-importance channel, *Running clock*: the unicorn, the heading, a chronometer from the clock's
start, and *Clock out*. The view model tells it whenever the running clocks change, and once at
start so that a notification left from before is corrected.

*Clock out* is a broadcast to `ClockOutReceiver` (androidApp), which hands the stop, with the minute
it was tapped, to `ClockOutWorker`. The worker finds the heading by its title and start in the notes
where they are kept (`notesStore`, as a capture is filed), writes the stop, takes the notification
down, then syncs as a capture does and puts the notification back up if a clock started elsewhere
came in with the sync.

iOS and the web have the bar only: a lock-screen Live Activity needs a Swift widget extension (G7).

## In the calendar (phase 2)

The agenda index also collects every clock in the notes (`clockedSpans`, in
`shared/…/org/agenda/Clocked.kt`), in the same pass off the main thread, and keeps them across days
(`AgendaIndex.clocks`). Only notes with a `CLOCK:` in them are parsed for it.

- **The day and the week** draw each clock in the hours it took (`ClockedBlockView`), outlined in the
  theme's secondary colour with a timer, where entries are filled in the colour of their kind: work
  done beside work planned. A clock shares the width with a meeting it overlaps, as two meetings do
  (`layoutDay`); one shorter than a quarter of an hour is drawn a quarter of an hour tall, where an
  event gets half an hour, so a run of short clocks stays one under the other. A clock across
  midnight is in both days (`on`), and a running one grows to now, lit in the primary colour. A tap
  opens the heading. Only clocks started that day decide where the grid opens, so one left running
  overnight does not scroll it to midnight.
- **"13 h 45 clocked"** above the day or the week, when anything was, opens the report for those days.
- **The agenda's clock report** (`ClockReport`), under its seven days: by note, then by heading in
  note order, a heading's running clock lit and counted to now, and the total. Emacs's agenda `R`,
  not a full clocktable.
- **Clock in, and change the keyword, from the calendar**: a long-press on an entry in the agenda,
  the day or the week, or on a clocked block, offers *Clock in* (or *Clock out* when its clock runs)
  and then the note's keywords, the current one ticked, and *No keyword*, as the note's own keyword
  menu does (`CalendarActions`, which the app gives). `NotesViewModel.clockIn(path, offset)` and
  `setTodo(path, offset, keyword)` work on a note that is not open: a keyword change is logged and
  repeated as from the note, and its statistics cookies counted again; one that asks for a note
  (`@`) opens its note first, so the note is asked for there. The month is left out: its chips are
  too small to aim a long-press at.

Emacs runs one clock at a time, and so does KorgNote: clocking in stops the clock running. Several
run only when devices clocked in apart, which Emacs calls dangling clocks and asks to resolve.

## Not built

A TODO keyword change on clocking in, an Android clock widget, editing `:Effort:`, a consistency
check for overlapping clocks and gaps, and regenerating `#+BEGIN: clocktable` blocks (shown as
written).
