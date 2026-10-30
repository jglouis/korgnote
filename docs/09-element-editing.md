# 09 — Element editing

**Status: built and in use.** This document describes what exists, not a plan.

Reading mode is the only surface. Each element — a heading with its own body — can be edited
in place, and editing one is a text replacement over that element's character range. There is no
whole-file editor: T22 retired it once the long-press menu could restructure a note.

## Why it exists

It began as a product observation rather than a performance idea: most edits are "add a TODO with
some metadata and a description", not "restructure this file". Editing one element at a time turned
out to dissolve the problem that
[06-editor.md](06-editor.md#performance-where-the-time-actually-goes) had been fighting.

The whole-file editor bottomed out at ~40 ms per keystroke on a 45 KB file, against a ~27 ms floor
that is simply the cost of laying out that much text in one field. The roadmap's answer was block
virtualisation, costed as a fortnight of work with a nasty tail: selection across block boundaries,
find-and-replace spanning blocks, caret movement over edges, undo across splices.

Those costs all came from wanting the split to be *invisible* — to pretend it was still one
document. Making it explicit removes them: when you have said "I am editing this item", nobody
expects to select into the next one. An element is typically 0.5–2 KB, so the cost is a few
milliseconds and, more importantly, **independent of file size**.

It is also a familiar idiom rather than an invention: it is `org-narrow-to-subtree`.

## The model

`shared/src/commonMain/kotlin/com/korgnote/common/org/OrgElements.kt`

```kotlin
data class OrgElement(val start: Int, val endExclusive: Int, val level: Int, val title: String)

fun String.orgElements(): List<OrgElement>
fun String.replaceOrgElement(element: OrgElement, expected: String, replacement: String): String?
fun String.withNewOrgElement(at: Int, level: Int): OrgInsertion
fun String.withoutOrgSubtree(index: Int): String?
fun String.withOrgSubtreesSwapped(first: Int, second: Int): String?
fun String.withOrgSubtreeShifted(index: Int, delta: Int): String?
```

- An element runs from its heading line to the **next heading of any level**, so a child heading is
  an element of its own rather than part of its parent. Text before the first heading is the
  preamble, at index 0 with level 0.
- Elements cover the whole document with no gaps or overlaps — asserted by test.
- All offsets are into the document text, so every operation is a pure text edit and the rest of the
  file is byte-identical by construction. This is the same principle that fixed the original
  whitespace-rewriting bug.

### Two guards, both tested

`replaceOrgElement` **refuses** when the text at the element's range no longer matches what editing
started from. A stale range would overwrite something the user never saw.

It also **re-adds a trailing newline** when one is needed to keep the following element on its own
line, so deleting the last newline while editing cannot weld two headings together.

### A sync during an edit

On its own, that refusal was too strict once a sync could change the note mid-edit. Found on a
phone: a heading being added could not be saved once Emacs had touched the note, and discarding it
showed the note as it was before Emacs. So `ElementEditor` no longer refuses just because the note
moved:

- While an element is open, a note a sync brings is **set aside** (`noteChangedElsewhere`) instead of
  replacing the text under the caret, which made a new heading vanish from the screen.
- `commit` splices the draft into the note the edit began on, then makes the same edit on the newer
  note. A heading added for this edit goes where it went: at the end, or before the line that
  followed it, when that line is found exactly once in the newer note. Any other edit — or a new
  heading whose place cannot be found again — is merged three ways, line by line, over the note
  before the edit: the same `merge` a save goes through with the server.
- Only a change to the same lines is still refused, and the editor stays open with a message.
- Discarding shows the newer note.

Covered by `EditRebaseTest`.

### The splitter and the parser agree

The splitter must find headings exactly where `toOrgDocument` finds sections. If the two disagreed,
the range offered for editing would not be the range written back, which corrupts a file.

They now share Org's own rule. `isHeadingLine` is `OrgSyntax.heading` — stars, then whitespace — so
`*bold* text` at the start of a line is a paragraph. And the splitter treats blocks and drawers as
opaque, as the parser does: a `* ` line inside `#+BEGIN_SRC` is code, and a heading closes a drawer
that was never ended. This replaced an earlier compromise, in which both took any line starting with
`*` for a heading: consistent, but wrong, and `*bold* text` became a heading in reading mode.

`OrgElementsTests.headingCountMatchesTheParser` and
`OrgGrammarTests.theSplitterAgreesWithTheParserOnTheNewConstructs` assert that they agree, so a
future grammar change cannot silently break the splice.

## The surface

`composeApp/…/ui/OrgDocumentView.kt`

Each element is parsed and rendered **from its own slice** of the text rather than from a single
parse of the document. That costs a little extra parsing and buys the property the screen depends
on: every rendered element knows the range it came from.

| Interaction | Effect |
|---|---|
| Tap an element | Opens it in an inline editor, in place |
| Long-press an element | A menu: *Add heading below*, *Add sub-heading*, *Move up*, *Move down*, *Promote*, *Demote*, *Delete heading* |
| *Add heading below* | Inserts a sibling of the same level after the element's **whole subtree**, and opens it |
| *Add sub-heading* | Inserts a child one level deeper directly under the element's own body (its first child), and opens it |
| *Delete heading* | After a confirmation that counts the sub-headings, removes the heading, its body and its whole subtree (`org-cut-subtree`), and saves |
| *Move up* / *Move down* | Swaps the heading's whole subtree with its previous or next sibling (`org-move-subtree-up`/`-down`), and saves. Disabled where there is no sibling that way |
| *Promote* / *Demote* | Adds or removes a star on every heading in the subtree (`org-promote-subtree`/`org-demote-subtree`), and saves. *Promote* is disabled at level 1 |
| Tap a link into the notes | Opens its target — see [08 T21](08-roadmap.md#t21--following-links-between-notes---done); the back arrow returns |
| *Linked from* at the end | Every link elsewhere that leads to this note; tapping one opens it at the link |
| Tap a heading's TODO keyword | A menu of the file's keywords; picking one changes it, logs the change under the heading, and saves. Marking a repeating entry done repeats it — see [Repeated tasks](#repeated-tasks) |
| Tap a checkbox | Ticks it as Org does, and saves — see [Checkboxes and cookies](#checkboxes-and-cookies) |
| Tap a table | Opens it in a grid editor — see [Tables](#tables) |
| Chevron on a heading | Folds or unfolds the heading's subtree — see [Folding](#folding) |
| **+** FAB in reading mode | Appends a heading at the end of the file, and opens it |
| Double-arrow FAB above it | Scrolls to the end of the note; shown only while there is more below |
| Save, on the strip while editing | Splices the draft back and saves |
| Discard, on the strip while editing | Drops the draft; if the heading was *just inserted*, removes it again |

Save and Discard sit at the end of the completion strip pinned to the bottom of the screen
([06](06-editor.md#where-the-strip-goes-and-why-it-matters)). They used to be floating buttons,
drawn over the document: the last element of a file, with nothing below it to scroll up with, stayed
under them while it was being edited. A third button, save-and-add-a-heading, was dropped then; the
long-press menu and the **+** at the foot of the note still add one.

**A tap edits unless something inside the element wants it.** A checkbox, a link, a table, a
drawer's toggle and the fold chevron are clickables of their own, and Compose hands the tap to the
innermost one first, so the element's own tap only sees what they leave. That replaces an earlier rule —
"plain taps are left alone" — which protected those targets by giving up the tap altogether. A
scroll or fling never becomes a tap. For screen readers the element's tap is labelled *Edit* and
its long-press *More actions*, since there is no longer a pencil to find.

**There used to be a pencil and a +** on every element. With a few hundred elements that was a few
hundred identical pairs, taking width from the text and making a note look like a form. Editing now
has the tap, and adding after one particular element is rare next to reading, so it moved into the
long-press menu.

*Delete heading* takes the subtree for the same reason Org does: a heading's sub-headings belong
to it, and leaving them behind would re-parent them under whatever heading came before. The
confirmation says how many go with it, since they may be folded or off screen. It is refused while
another element is open in the editor, whose position the cut would move.

*Move*, *Promote* and *Demote* are what the whole-file editor used to be kept for. Like delete, they
act on the whole subtree, are refused while an element is open in the editor, and change nothing but
the text they move or the stars they rewrite. A move never leaves the parent — there is no refile —
and keeps a missing final newline missing. Folds go with the headings, by index, since restarring
changes the heading lines they are otherwise keyed by. A moved heading that leaves the screen is
scrolled back into view.

*Add heading below* goes after the subtree rather than directly after the element: a heading
inserted between a parent and its children would take the children over. Below the preamble it
makes the first heading; the preamble offers no sub-heading, as it is not a heading. Typing a new
`* ` line inside a draft also adds a heading when the draft is saved, because the splice is plain
text — tested, and needing no UI.

A new heading is inserted as bare stars (`* `, or `** ` to match the level) with the caret after
them. There is no template: completion ([06](06-editor.md#completion-g3)) is the better answer to
"pre-fill SCHEDULED".

A tap on a checkbox, a keyword or a table reports **an offset**, not an ordinal. Each element is
parsed from its own slice, so its nodes carry slice-relative ranges; `OrgDocumentView` adds the
element's start, and the view model finds the node at that offset in the whole note (`nodeAt`). T7
deleted the ordinal path, `orgCheckboxesBefore` with it: an ordinal shifted whenever anything above
it changed.

State lives in `ElementEditor`, owned by `NotesViewModel`: `editingIndex` and `draftValue`, plus
the text before an insertion so that discarding removes it, and the newer note a sync set aside. Covered by `ElementEditingTest`, which
has a test for every way of adding, moving, restarring and deleting a heading, and
`ReadingModeInteractionTest` for the gestures.

### Folding

Each heading with something beneath it — a body, or descendants — has a chevron. Folding it
collapses its **subtree**, like `org-cycle` between folded and expanded: the heading shows only its
heading line, and every element inside its subtree renders nothing. Elements are flat, so the subtree
is computed (`subtreeEnd`, next to `orgElements`) as everything up to the next heading at the same
level or above. A hidden element keeps its item, so *an item's index is its element's index* still
holds and scroll targets need no translation.

Anything that takes you to an element opens the folds around it: a calendar, search or reminder jump
unfolds the target's ancestors and the target itself (the match may be in its body); opening an
element for editing, or inserting one, unfolds its ancestors. Discarding an insertion does not fold
anything again.

Fold state is per file, for the session, in the view model. It is never persisted and never written
into the note, and everything starts expanded. It is keyed by **the heading line plus how many
identical lines precede it**, not by list index — adding a heading anywhere above a fold shifts
every index below it. That is a deliberate change from the roadmap's "position in the tree", which
has the same problem whenever a sibling is added before the fold's branch. The cost is that editing a
folded heading's own line forgets its fold. Covered by `FoldingTest`.

Not included: fold-all or unfold-all, folds that survive a restart, and `#+STARTUP: overview` or
`VISIBILITY`. Add them if using the app shows that they are needed.

### Repeated tasks

Picking a done keyword from the keyword menu, for an entry that is not done yet and has a repeating
active stamp, does what Org's `org-auto-repeat-maybe` does with its default settings
([manual](https://orgmode.org/manual/Repeated-tasks.html)), all in `changeTodoState`:

- **Every repeating active stamp in the entry moves** — the planning line, the heading line and the
  body, up to the next heading, but not inactive stamps or child headings. `+1w` moves one interval;
  `++1w` moves whole intervals until it is past today (past now, for `h`); `.+1d` moves one interval
  from today (from now, for `h`). The time, a `10:00-11:30` range, the repeater and the warning
  are kept.
- **The keyword goes back** to the first keyword of the `#+TODO:` sequence the done keyword is in,
  or to the entry's `REPEAT_TO_STATE` property when that names a keyword.
- **The completion is recorded** as Emacs records it: `:LAST_REPEAT: [stamp]` in the property drawer
  (created when there is none), and the usual `- State "DONE" from "TODO"` note.

Only the menu repeats. Typing `DONE` into a heading in the editor is a text edit and is left as
typed, as in Org. A `+0d` repeater, or `+1h` on a stamp without a time, does not repeat, and the
change is an ordinary one. A month on from the 31st lands on the month's last day; Emacs spills
into the next month instead. Covered by the *Repeated tasks* tests in `OrgEditTests` and by
`ElementEditingTest`.

### Checkboxes and cookies

A tapped checkbox goes through `toggleCheckboxAt` and `tickAsOrgDoes` (`shared/…/org/edit/OrgEdit.kt`).
Ticking a box ticks or unticks every box under it, and every box above it then says what its
children say — `[X]` when all are ticked, `[ ]` when none are, `[-]` when some are — as Org's
`C-c C-c` leaves them. Org would put a parent straight back to what its children say, so a tap there
would do nothing; ticking them all ends where Org would. Every marker is three characters, so
nothing else in the file moves. Covered by `CheckboxHierarchyTest`.

A save, a tick and a keyword change then bring the **statistics cookies** up to date — `[2/5]` and
`[40%]` — with `updateStatisticsCookies(text, keywords, before)` in
`shared/…/org/edit/StatisticsCookies.kt`. Only the cookies on headings and list items over the lines
that changed are touched; one elsewhere is left as it is, even if it counts differently, since Emacs
may keep it that way on purpose with other settings. The rest of the note stays byte for byte as it
was. This replaced a rule that rewrote every cookie in the note on every save, so an edit under one
heading could change a cookie under another.

A heading's cookie counts its direct sub-headings that carry a keyword, done against all, or, with
none of those, the checkboxes of its own lists; a list item's counts the boxes directly under it.
Those are Org's defaults. A heading's `:COOKIE_DATA:` property is honoured: `todo` or `checkbox`
picks what is counted, and `recursive` counts at every depth below. Covered by
`StatisticsCookiesTest` and `StatisticsOnSaveTest`.

### Tables

A table is all but impossible to keep aligned as text on a phone keyboard, so a tap on one opens it
over the whole screen as a grid, a field per cell (`composeApp/…/ui/TableEditor.kt`, `TableGrid` in
`shared/…/org/edit/TableEdit.kt`). Menus insert, move and remove rows and columns, and add a rule
under a header. The editor's Table button opens the same grid on an empty table and writes it in at
the caret (`insertTable`).

The table's `#+TBLFM:` line comes with it. Cells a formula fills are shaded and open their formula
when tapped. Nothing is computed until *Recalculate* is pressed, as in Emacs; it computes every
formula or, when one cannot be computed, nothing, and says why. Numbers follow Calc's arithmetic and
printing, so a cell KorgNote computes is the one Emacs would write. Inserting, moving or deleting rows
and columns renumbers what the formulas read. A Formulas panel names what cannot be computed, what is
out of date, and slips that are easy to make in Emacs. The calculator is in `shared/…/org/table/`;
trigonometry is left out, since Calc answers it in degrees with exact special cases.

Saving is a range replacement over the table's lines and its formula line (`replaceOrgTable`),
aligned as Emacs aligns it (`toAlignedLines`): a column is as wide as its widest cell as displayed,
and right-aligned when at least half its filled cells look like numbers, unless an `<l>`, `<c>` or
`<r>` cookie says otherwise. A `|` typed in a cell is written
`\vert{}`. The same guard as an element's applies: nothing is written when nothing changed, and the
save is refused when the table's text is no longer what it was when the grid opened, say after a
sync. Opening a table is refused while an element is open in the editor.

Covered by `TableEditTest`, `TableFormulaEditTest`, `TableCalcTest`, `CalcNumberTest`,
`TableEditingTest` and `TableEditorFormulaTest`.

## Consequences

- **The whole-file editor is gone** (T22). It stayed for a while as an escape hatch for
  restructuring; once moving, promoting and demoting were in the long-press menu, and the preamble
  was editable as an element, nothing was left that only it could do. Its viewport highlighting went
  with it. Two surfaces meant every editor decision was taken twice or forgotten once.
- **Scroll position across read/edit no longer matters** — you never transition between two
  full-document renderings, so there is nothing to preserve.
- **Capture is now within reach**: adding a heading from a template into a designated inbox is a
  small extension of `withNewOrgElement`.

## Known gaps

- ~~Reading mode renders every element eagerly~~ **Fixed.** `OrgDocumentView` is a `LazyColumn`, so
  opening a document composes only what is on screen. Measured warm on desktop JVM against the real
  notes directory, biggest file 60 515 chars / 283 elements:

  | reading mode | first composition |
  |---|---|
  | eager `Column` (every element) | 234 ms |
  | `LazyColumn` (visible elements) | 38 ms |
  | empty document (framework floor) | 7 ms |

  A lazy list cannot be nested inside a vertically scrolling parent, so the screen `Column` in
  `App.kt` gives up its own scroll while a document is being read, and keeps it for every other
  screen. Each element gets an item even when it renders nothing, so an
  item's index is its element's index and a scroll target needs no translation — which is also why
  the calendar's jump uses `scrollToItem` rather than `BringIntoViewRequester`: the target is
  normally far off screen and has not been composed yet, so there is no node to bring anywhere.
  The same holds for the **+** FAB: its new heading sits at the end of the list, so the view
  scrolls to whichever element is being edited when that item is not already visible.

  Worth recording, because it cost time to learn: the slowness was **not** caused by T3. Measured on
  the real corpus, every path T3 touched is negligible — the server answers `/api/org-content` in
  33 ms for 1.26 MB, parsing all 366 files client-side costs 12 ms, and opening one file costs 14 ms
  of parsing. Two plausible-sounding culprits were also measured and cleared: the per-element edit
  and add affordances (removing the icons entirely changed 1449 ms to 1132 ms), and Compose
  stability — T3 really did make `OrgNode` unstable by giving it a `var range`, which the compiler
  report confirms, but it changed recomposition not at all (13/5/5 ms either way), because `remember`
  hands back the same instance. Only eager composition ever mattered.

- ~~Creating, renaming and deleting **files** is still impossible~~ **Fixed** by T20; see
  [05](05-sync-protocol.md#creating-renaming-and-deleting-t20).
- The calendar's "scroll to this heading" is now exact, using the source range T3 gives every node:
  the element containing the section's first character is the one to show. It falls back to a title
  search when that range no longer fits the file, which happens if the file changed after the agenda
  indexed it. This was two separate bugs, both fixed and both covered by `CalendarNavigationTest`:

  - The lookup ran at the call site in `App.kt`, immediately after `onFileSelected` — which fetches
    the file's text *asynchronously*. So it searched the file being left, not the one being opened.
    It now lives in `FileTreeViewModel.onFileSelected(path, scrollToHeading)` and runs when the text
    arrives.
  - The match was `element.title.endsWith(section.title)`, and those two titles are not the same
    kind of string: `OrgSection.title` has the TODO keyword and the trailing `:tags:` stripped,
    while `OrgElement.title` keeps both. Any heading carrying a tag therefore never matched, and
    `Review` would happily match `Final Review`. Heading lines are now split by one shared
    `orgHeadingOf`, used by both the parser and this lookup, so the two cannot drift again — the
    smallest instance of the "one grammar" idea behind T6.
