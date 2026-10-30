# KorgNote design documentation

These documents describe where KorgNote is today, where it should go, and how to get
there in independent steps. They are written to be handed to an implementer (human or
agent) one document — or one task — at a time.

## Product goals (the yardstick for every decision here)

| # | Goal |
|---|------|
| G1 | A personal tool to manage my own Org-mode files. The `.org` files are the product; the app is a view onto them. |
| G2 | Search, view and edit Org/text documents efficiently (syntax colouring already exists). |
| G3 | Completion suggestions when typing Org keywords (`TODO`, `SCHEDULED`, …). |
| G4 | Edit offline, then sync changes automatically. The KorgNote server was meant to replace Syncthing, which has no iOS client; since 1.2.0 it is one of three places the notes can live: the server, a folder on the phone (typically kept in step by Syncthing), or a WebDAV server (experimental, since 1.3.0). See `NotesSource.kt`. |
| G5 | Minimal bandwidth usage, while staying reasonably maintainable. |
| G6 | A calendar closer to Google Calendar than to a bare list: `DEADLINE`, date ranges, hours in a day — plus reminders/notifications on the phone. |
| G7 | Maintainable by one person: one stack (Compose Multiplatform + JVM server), not two. Still true: the Android widgets are Glance, in Kotlin, and the iOS shell is about 33 lines of Swift. |

## Reading order

| Document | What it answers |
|----------|-----------------|
| [01-current-state.md](01-current-state.md) | **Historical.** What existed on 2026-09-20, module by module, with the defects it listed and what became of each. |
| [02-assessment.md](02-assessment.md) | **Are we going in the right direction?** Verdict per design choice against G1–G7, including "is the REST API too cute?". |
| [03-target-architecture.md](03-target-architecture.md) | The target shape: local-first, files as source of truth, server as a dumb sync endpoint. |
| [04-org-core.md](04-org-core.md) | The shared Org model: lossless, source-mapped, one grammar instead of two. |
| [05-sync-protocol.md](05-sync-protocol.md) | Offline store, sync protocol, conflicts, auth, bandwidth budget. |
| [06-editor.md](06-editor.md) | Editing at scale, keyword completion, search. |
| [07-calendar-agenda.md](07-calendar-agenda.md) | Timestamp/agenda engine, day & week views, local notifications. |
| [09-element-editing.md](09-element-editing.md) | **Built.** Editing one heading at a time as a range splice — now the primary editing surface. |
| [08-roadmap.md](08-roadmap.md) | The work, sequenced into tasks with acceptance criteria, and **current status**. |

## How to use these with an agent

Documents 04–07 and 09 are self-contained designs, one per subsystem. All of them are built now;
where the code went another way than the design, the document says so, and
[08-roadmap.md](08-roadmap.md) records why.
[08-roadmap.md](08-roadmap.md) slices those designs into tasks (T1, T2, …) sized for one
session each. A task prompt should normally be: *"Read `docs/0X-….md` and
`docs/08-roadmap.md`, implement task TN, and nothing else."*

Tasks are ordered so that each one leaves the app in a working state. Where a task
depends on another, the dependency is stated explicitly.

## Status

Written 2026-09-20 against commit `2cc66f7`; revised the same day once the editor work landed, and
again on 2026-10-30, against 1.4.1.

For what is built versus planned, see the status table in
[08-roadmap.md](08-roadmap.md#status). The short version: every phase of the plan is built, T1
included — the server reads its notes from disk on every request. The loose ends are the two
text-editing spikes (T0/T0b), part of the web client (T10b) and reminders on an iPhone (T18). A good deal that was never
planned shipped beside it (pairing, the folder and WebDAV sources, widgets, tables, French, the dark
theme); 08 lists it as T30–T40, with what is still open.
