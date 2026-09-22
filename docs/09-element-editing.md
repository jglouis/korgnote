# 09 — Element editing

**Status: built and in use.** This document describes what exists, not a plan.

Reading mode is the primary surface. Each element — a heading with its own body — can be edited
in place, and editing one is a text replacement over that element's character range.

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
fun String.orgCheckboxesBefore(offset: Int): Int
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

### A deliberate compromise

`isHeadingLine` uses the same rule as `toOrgDocument`'s section detection — a line starting with `*`
— rather than Org's stricter "stars followed by whitespace". That is wrong for a `*` line inside a
`#+BEGIN_SRC` block, but **consistency with the parser matters more than either being individually
right**: if the splitter and the parser disagreed, the range offered for editing would not be the
range written back, which corrupts a file. A shared quirk merely splits an element oddly.

`OrgElementsTests.headingCountMatchesTheParser` asserts they agree across a corpus, so a future
grammar change cannot silently break the splice. **T5/T6 should retire this** by moving both onto
the real grammar together.

## The surface

`composeApp/…/ui/OrgDocumentView.kt`

Each element is parsed and rendered **from its own slice** of the text rather than from a single
parse of the document. That costs a little extra parsing and buys the property the screen depends
on: every rendered element knows the range it came from.

| Interaction | Effect |
|---|---|
| Long-press an element, or tap its pencil | Opens it in an inline editor, in place |
| Tap its **+** | Inserts a sibling heading of the same level directly after it, and opens that |
| **+** FAB in reading mode | Appends a heading at the end of the file, and opens it |
| Save FAB | Splices the draft back and saves |
| Discard FAB | Drops the draft; if the heading was *just inserted*, removes it again |

Plain taps are deliberately left alone — checkboxes and `:LOCATION:` links already use them.

A new heading is inserted as bare stars (`* `, or `** ` to match the level) with the caret after
them. There is no template: completion ([06](06-editor.md#completion-g3)) is the better answer to
"pre-fill SCHEDULED".

Checkbox ordinals are per-element in rendering but reported document-wide, using
`orgCheckboxesBefore` to offset each element's numbering.

State lives in `FileTreeViewModel`: `editingElementIndex`, `elementDraft`, `elementDraftCaret`, plus
the text before an insertion so that discarding removes it. Covered by `ElementEditingTest`.

## Consequences

- **The whole-file editor stays** as an escape hatch for restructuring, with the viewport scoping
  and per-line cache from [06](06-editor.md). It is no longer the main path.
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
  `App.kt` gives up its own scroll while a document is being read, and keeps it for the whole-file
  editor and every other screen. Each element gets an item even when it renders nothing, so an
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

- Creating, renaming and deleting **files** is still impossible — the server rejects any path it did
  not see at startup (D11). That is the next gap once adding sections feels good.
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
