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

- Reading mode still renders every element eagerly in a `Column`. A `LazyColumn` keyed by element is
  the obvious next step and was already wanted by [06](06-editor.md#reading-mode-at-scale).
- Creating, renaming and deleting **files** is still impossible — the server rejects any path it did
  not see at startup (D11). That is the next gap once adding sections feels good.
- The calendar's "scroll to this heading" matches an element by level and title, because the agenda
  index holds parsed nodes with no source ranges. Source-mapped nodes (T3) would make it exact.
