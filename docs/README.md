# KorgNote design documentation

These documents describe KorgNote as it is built, one subsystem at a time, and why it is that way.
What is still open is in one place: the [gaps list in 08](08-roadmap.md#open-and-known-gaps).

## Product goals (the yardstick for every decision here)

| # | Goal |
|---|------|
| G1 | A personal tool to manage my own Org-mode files. The `.org` files are the product; the app is a view onto them. |
| G2 | Search, view and edit Org/text documents efficiently, with syntax colouring. |
| G3 | Completion suggestions when typing Org keywords (`TODO`, `SCHEDULED`, …). |
| G4 | Edit offline, then sync changes automatically. The KorgNote server was meant to replace Syncthing, which has no iOS client; since 1.2.0 it is one of three places the notes can live: the server, a folder on the phone (typically kept in step by Syncthing), or a WebDAV server (experimental, since 1.3.0). See `NotesSource.kt`. |
| G5 | Minimal bandwidth usage, while staying reasonably maintainable. |
| G6 | A calendar closer to Google Calendar than to a bare list: `DEADLINE`, date ranges, hours in a day — plus reminders/notifications on the phone. |
| G7 | Maintainable by one person: one stack (Compose Multiplatform + JVM server), not two. Still true: the Android widgets are Glance, in Kotlin, and the iOS app is about 33 lines of Swift, beside a share extension of about 280. |

## Map

| Document | What it answers |
|----------|-----------------|
| [03-target-architecture.md](03-target-architecture.md) | The shape: local-first, files as source of truth, server as a dumb sync endpoint. The web target, and the alternatives rejected. |
| [04-org-core.md](04-org-core.md) | The shared Org model: lossless, source-mapped, one grammar instead of two. |
| [05-sync-protocol.md](05-sync-protocol.md) | Offline store, sync protocol, conflicts, auth, bandwidth budget. |
| [06-editor.md](06-editor.md) | Editing at scale, keyword completion, search. |
| [07-calendar-agenda.md](07-calendar-agenda.md) | Timestamp/agenda engine, the calendar's views, local notifications, widgets. |
| [08-roadmap.md](08-roadmap.md) | What is open, the defects the plan began from (D1–D12), and why each change was made, by task number. T30–T42 record what shipped without a plan: pairing, the folder and WebDAV sources, widgets, tables, French, the dark theme, clocking, resuming. |
| [09-element-editing.md](09-element-editing.md) | Editing one heading at a time as a range splice, the only editing surface: the menu, folding, keywords and what they log, priorities, refile, links, tables, resuming. |
| [10-capture.md](10-capture.md) | **Built (1.5.0).** Capturing from other apps: share sheet, selected text, calendar files, `korgnote://capture`. No templates: one dialog that asks where, and shows what. |
| [11-clocking.md](11-clocking.md) | Org's clocking: `CLOCK:` lines in the logbook, the running-clock bar, totals beside headings, clocked time in the calendar and its report, Android's ongoing notification. |
| [notes/editor-perf.md](notes/editor-perf.md) | The measurements behind the editor's performance work, and what they taught. |

Documents 04–07 and 09–11 are self-contained, one per subsystem. Where the code went another way than was
first planned, [08](08-roadmap.md) records why.

## Keeping these true

- **No dated status lines.** A document says what is, not what was so on a day. What is open goes in the
  [gaps list](08-roadmap.md#open-and-known-gaps), and a date belongs only beside a measurement.
- **One home per fact.** Each subsystem document owns its feature, and 08 records only why it changed. A
  fact wanted elsewhere is a link.
- **A shipped feature updates its subsystem document in the same commit.**
- **Keep the file names and the T- and D-numbers.** Code comments, tests and the app cite them, and git
  keeps whatever else is taken out.
