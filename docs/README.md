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
| G4 | Edit offline, then sync changes with the server automatically. Replaces Syncthing, which has no iOS client. |
| G5 | Minimal bandwidth usage, while staying reasonably maintainable. |
| G6 | A calendar closer to Google Calendar than to a bare list: `DEADLINE`, date ranges, hours in a day — plus reminders/notifications on the phone. |
| G7 | Maintainable by one person: one stack (Compose Multiplatform + JVM server), not two. |

## Reading order

| Document | What it answers |
|----------|-----------------|
| [01-current-state.md](01-current-state.md) | What exists today, module by module, with file references and a list of known defects. |
| [02-assessment.md](02-assessment.md) | **Are we going in the right direction?** Verdict per design choice against G1–G7, including "is the REST API too cute?". |
| [03-target-architecture.md](03-target-architecture.md) | The target shape: local-first, files as source of truth, server as a dumb sync endpoint. |
| [04-org-core.md](04-org-core.md) | The shared Org model: lossless, source-mapped, one grammar instead of two. |
| [05-sync-protocol.md](05-sync-protocol.md) | Offline store, sync protocol, conflicts, auth, bandwidth budget. |
| [06-editor.md](06-editor.md) | Editing at scale, keyword completion, search. |
| [07-calendar-agenda.md](07-calendar-agenda.md) | Timestamp/agenda engine, day & week views, local notifications. |
| [08-roadmap.md](08-roadmap.md) | The work, sequenced into independently shippable tasks with acceptance criteria. |

## How to use these with an agent

Documents 04–07 are self-contained designs, one per subsystem.
[08-roadmap.md](08-roadmap.md) slices those designs into tasks (T1, T2, …) sized for one
session each. A task prompt should normally be: *"Read `docs/0X-….md` and
`docs/08-roadmap.md`, implement task TN, and nothing else."*

Tasks are ordered so that each one leaves the app in a working state. Where a task
depends on another, the dependency is stated explicitly.

## Status

Written 2026-09-20 against commit `2cc66f7`, plus the uncommitted raw-text editing fix
described in [01-current-state.md](01-current-state.md#in-flight-uncommitted-change).
Producing these documents changed no code.
