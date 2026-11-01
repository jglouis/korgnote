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
*Org clocking in KorgNote — proposal* (2026-11-01). Phase 1 is built; this document says how.

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

## Not built

From the proposal's later phases: clocked time in the calendar's day and week views, the agenda's
clock report, *Clock in* from calendar entries, a TODO keyword change on clocking in, an Android
clock widget, editing `:Effort:`, and regenerating `#+BEGIN: clocktable` blocks (shown as written).
