# Org mode support

What KorgNote does with each part of [Org mode](https://orgmode.org/manual/), feature by feature.

| Status | Meaning |
|---|---|
| **Yes** | Read, shown and usable in the app. |
| **Partly** | Some of it: the note says which part. |
| **As text** | Kept in the file untouched, and shown as written. |
| **No** | Not there. The text is still kept as it is, never rewritten. |

Whatever the status, the app never rewrites what it does not understand: an edit changes only the
lines it touches ([04](04-org-core.md#the-rule)).

## Structure

| Feature | Status | Notes |
|---|---|---|
| Headings and nesting | **Yes** | Add, move, promote, demote, delete and refile from a long press on a heading. |
| Folding | **Partly** | A chevron folds a heading's subtree, for the session. No fold-all; `#+STARTUP: overview`, `content` and `VISIBILITY` are not read. |
| TODO keywords, `#+TODO:` | **Yes** | Per file, several lines, with the `\|` split. `#+SEQ_TODO:` and `#+TYP_TODO:` too. A tap on the keyword offers the file's keywords. |
| Fast-access keys, `TODO(t)` | **Partly** | Read, and the `(w@/!)` logging part is honoured; keywords are picked from a menu, not by key. |
| Priorities, `#+PRIORITIES:` | **Yes** | Letters or numbers 0–64. A tap on `[#A]` offers the file's range. The agenda sorts by them. |
| Tags | **Yes** | With completion from the tags in use, and a colour per tag. |
| `#+FILETAGS:` | **Yes** | |
| Tag inheritance | **Yes** | From parent headings and `#+FILETAGS:`, in search and the agenda. |
| `#+TAGS:` | **Partly** | Groups (`[ ]`, `{ }`) count in search. Fast keys are not read, nor is the list used for completion. |
| Property drawers | **Partly** | Folded, opened with a tap, edited as text. `:Effort:` has a dialog, `:LOCATION:` opens a map. `:ID:`, `:CUSTOM_ID:`, `:COOKIE_DATA:`, `:LAST_REPEAT:` and `:REPEAT_TO_STATE:` are read or written. |
| `#+PROPERTY:`, property inheritance | **No** | |
| `:LOGBOOK:` and other drawers | **Yes** | Folded, with their contents highlighted. State changes and clocks are logged into `:LOGBOOK:`. |
| Plain, ordered and description lists | **Yes** | `[@n]` counters, numbering as Org numbers. Enter continues a list. |
| Checkboxes | **Yes** | A tap ticks a box and its children, and sets `[-]` on its parents, as `C-c C-c` does. |
| Statistics cookies, `[1/3]` `[33%]` | **Yes** | Brought up to date on a save, tick or keyword change. `:COOKIE_DATA:` is honoured. |
| Footnotes | **Partly** | Definitions are shown; references are coloured but can't be tapped. Inline `[fn::…]` and multi-paragraph definitions show as written. |
| Inline tasks | **No** | A line of 15 stars is a deep heading. |
| Horizontal rules | **Yes** | |
| Comments, `#` and `#+BEGIN_COMMENT` | **Yes** | Shown dimmed. |
| `COMMENT` headings | **Partly** | Left out of the agenda, but shown as any heading. |
| `:ARCHIVE:` tag | **Partly** | Left out of the agenda; still found by search. |

## Markup

| Feature | Status | Notes |
|---|---|---|
| Emphasis | **Yes** | Bold, italic, underline, strike-through, `=verbatim=` and `~code~`. |
| Entities, `\alpha` | **Partly** | A subset, Greek, arrows, maths and typography, shown as symbols. Others as typed. |
| Subscripts and superscripts | **Partly** | `H_{2}O` and `x^{2}` only, not the bare `x_2`. |
| `<<targets>>` | **Yes** | Shown as their words; links can land on them. |
| Radio targets, `<<<x>>>` | **As text** | Shown as their words, but other mentions are not linked. |
| LaTeX fragments and environments | **As text** | Environments in monospace. |
| Inline images | **No** | An image link opens the file, where [File links](09-element-editing.md#links-between-notes) is set up. |
| Macros, citations | **As text** | |

## Blocks

| Feature | Status | Notes |
|---|---|---|
| Source blocks | **Yes** | Highlighted for shell, Lisps, C, C++, C#, Java, Kotlin, JavaScript, TypeScript, Go, Rust, Zig, Lua, Python, SQL, JSON, YAML, TOML/INI, XML/HTML and Dockerfile. |
| Example, export, quote, verse and center blocks | **Yes** | |
| `#+RESULTS:` | **As text** | |
| Babel: running code, tangling | **No** | Out of scope. |
| Dynamic blocks, `#+BEGIN: clocktable` | **As text** | Never regenerated. |
| `#+INCLUDE:` | **As text** | Not expanded. |
| `<s`, `<q`, `<e` structure templates | **Yes** | Offered by completion, as `org-tempo` does. |

## Tables

| Feature | Status | Notes |
|---|---|---|
| Tables | **Yes** | Edited as a grid, a field per cell: rows and columns inserted, moved and deleted, rules added. Saved aligned as Emacs aligns them. |
| `#+TBLFM:` formulas | **Partly** | Calc-style, computed on *Recalculate*: `$n`, `@r$c`, `@<`, `@>`, relative rows, `@I`, ranges, `;%.2f` and `;N`; `vsum`, `vmean`, `vmedian`, `vmin`, `vmax`, `vcount`, `vprod`, `vvar`, `vsdev`, `min`, `max`, `round`, `if`, `exp`, `ln`, `log`, `pv`, `fv`, `pmt`, `npv` and date parts. Not Elisp formulas, named fields, constants, `remote()` or trigonometry. |
| Alignment cookies, `<l>` `<c>` `<r>` | **Partly** | Honoured when the table is saved, not on screen. |
| Width cookies, `<10>` | **No** | Cells are not cut. |
| Column groups | **As text** | An ordinary row. |
| `table.el` tables | **No** | |

## Links

| Feature | Status | Notes |
|---|---|---|
| `id:`, `*heading`, `#custom-id`, `<<target>>`, `#+NAME:` | **Yes** | The back arrow returns from a followed link. |
| `file:` | **Yes** | With `::search`. A file that is not a note opens through [File links](09-element-editing.md#links-between-notes). |
| Web links, `mailto:`, `doi:` | **Yes** | Opened outside the app. |
| `#+LINK:` abbreviations | **Yes** | `%s` and `%h`, not the `%(…)` Elisp form. |
| Descriptions, `[[target][description]]` | **Yes** | With a link editor and a picker of notes and headings. |
| Backlinks | **Yes** | *Linked from* at the end of each note. |
| `attachment:`, `elisp:`, `shell:` and other types | **No** | A tap says why the link leads nowhere. |
| Attachments, `org-attach` | **No** | |

## Dates and the agenda

| Feature | Status | Notes |
|---|---|---|
| Active and inactive timestamps | **Yes** | A tap opens that day in the calendar. The heading's long-press menu, or the suggestions while editing, make one active or inactive. |
| Time ranges, date ranges | **Yes** | `<… 10:00-11:00>` and `<a>--<b>`. A `DEADLINE` over a range shows on its first day only. |
| `SCHEDULED`, `DEADLINE`, `CLOSED` | **Yes** | With warning periods (`-3d`; 14 days by default) and overdue items carried to today. |
| Repeaters, `+1w` `.+1d` `++1m` | **Yes** | Marking done moves the dates on and logs `:LAST_REPEAT:`. |
| Delays, `-2d` `--2d` | **Yes** | |
| Habits | **Partly** | `.+2d/4d` repeats; there is no consistency graph, and `STYLE: habit` is not read. |
| Diary sexps, `<%%(…)>` | **As text** | Not in the agenda. |
| Agenda | **Yes** | Day, week and month views and a list, with reminders, a morning digest and Android widgets. Narrowed by TODO keyword and tag, as `/` does. |
| TODO list, `t` | **Yes** | Every heading still to do, dated or not, by keyword, from the top of the agenda. |
| Search, agenda queries | **Partly** | Words, `tag:`, `todo:` (`todo:open` for any keyword still to do) and `priority:`, saved by name. Not the match syntax (`+work-boss/NEXT`), property searches or custom agenda commands. |
| `#+CATEGORY:` | **No** | |
| Agenda files | **No** | Every note is in the agenda. |

## Tasks and time

| Feature | Status | Notes |
|---|---|---|
| Changing state | **Yes** | From a menu, in the note or in the calendar. |
| Logging | **Partly** | `(w@/!)`, `logdone`, `lognotedone`, `logrepeat`, `lognoterepeat`, `nologrepeat`, `logdrawer`. Not the per-subtree `:LOGGING:` property. |
| Refile | **Yes** | Under another heading, in this note or another. |
| Archiving | **No** | |
| Effort, `:Effort:` | **Yes** | Set from a dialog, shown against the time clocked. |
| Clocking | **Yes** | Clock in, out or cancel; `CLOCK:` lines in `:LOGBOOK:`; totals, a running-clock bar, and clocked time in the calendar. See [11](11-clocking.md). |
| Clock tables | **No** | The calendar's clock report stands in. |
| Column view | **No** | |

## Capture and the rest

| Feature | Status | Notes |
|---|---|---|
| Capture | **Partly** | No `org-capture` templates, by design. Instead: shared links, text and places, and calendar events, into a note or under a heading. See [10](10-capture.md). |
| `#+TITLE:`, `#+AUTHOR:`, `#+DATE:`, `#+CAPTION:` | **Yes** | Title, byline and captions. Other keywords show as written. |
| `#+SETUPFILE:` | **No** | |
| `#+STARTUP:` | **Partly** | Its logging options only. |
| Export and publishing | **No** | Out of scope. |
| Sparse trees | **No** | Search lists the matching headings instead. |
| Narrowing | **No** | Editing one heading at a time comes close. |
| Encryption, `org-crypt` | **No** | Encrypted text shows as written. |
| org-roam | **Partly** | New notes and dailies get an `:ID:`, and `id:` links work. `ROAM_ALIASES` and `ROAM_REFS` are kept, not read. |
