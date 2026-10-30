# T12a — Editor performance diagnostic: results

Measured 2026-09-20 on a ~45 KB Org file (`chars=45777`). Target used for these numbers:
_(fill in: desktop JVM / iOS / wasm)_ — slowness was originally reported as worst on iOS
and wasm.

## Verdict

**Cost A (highlighting) dominates. Cost B (text layout) is not a problem.**

With `visualTransformation` disabled, typing in the same file is *smooth*. Since a Compose
text field lays out the whole document either way, that rules out text layout as a
bottleneck at this size — and therefore rules out **editor virtualisation**, which was the
expensive branch of T12b.

## Measurements

Instrumented `OrgSyntaxHighlightTransformation.filter`:

```
[PERF] filter chars=45777 spans=3116 took=28.200917ms
[PERF] filter chars=45777 spans=3116 took=37.715625ms
[PERF] filter chars=45777 spans=3116 took=27.866166ms
[PERF] filter chars=45778 spans=3116 took=37.938292ms
…
```

| Metric | Value |
|---|---|
| Document size | 45,777 chars (~45 KB) |
| Spans produced | 3,116 |
| Single `filter` call | 23–40 ms, median ~25 ms |
| Calls per keystroke | **3, all on identical text** |
| Highlight cost per keystroke | **~72–94 ms** |
| Frame budget (60 fps) | 16.7 ms |
| Cost per KB | ~0.55 ms |

Two things the log makes explicit:

1. **All three calls per keystroke run on the same text** — the character count is
   identical across each group of three. Two of the three are pure waste. `filter` also
   fires on caret movement, where the text has not changed at all.
2. **The 25 ms is a lower bound on the true cost.** It measures only *building* the
   `AnnotatedString`; Compose's own cost to resolve and lay out 3,116 spans is additional
   and not captured here.

## Fix ladder

| Step | Effort | Expected per-keystroke highlight cost |
|---|---|---|
| Today | — | ~72–94 ms |
| **Memoise `filter`** on the source text | ~6 lines | ~25 ms (3 calls → 1; caret movement and scrolling become free) |
| **Viewport-scoped spans** | T12b | ~1–3 ms, and spans drop from 3,116 to ~100 |

Memoisation is a guaranteed 3× because the redundant calls are provably on identical text.
It is not sufficient on its own: one full pass still costs ~25 ms against a 16.7 ms budget.

Viewport scoping is what makes it smooth, and more importantly makes cost **independent of
document size**. At the current ~0.55 ms/KB, a 200 KB file would cost ~110 ms per call and
~330 ms per keystroke; scoped to the viewport it stays flat.

## Implementation notes for T12b

The transformed text must contain the **entire** document, or the field renders only part of
it. So do not shrink the text — attach spans only to visible lines:

```kotlin
AnnotatedString(text = text.text, spanStyles = spansForVisibleLinesOnly)
```

Full text, ~100 spans. Cheaper to build *and* cheaper for Compose to lay out.

Getting the visible range into the transformation, given `filter` is not a composable and
cannot subscribe to state:

1. `App.kt` already has the outer `rememberScrollState()`; capture `TextLayoutResult` via
   `onTextLayout` and the field's position via `onGloballyPositioned`.
2. Map scroll offset → visible line via `TextLayoutResult.getLineForVerticalPosition`, then
   → char offsets via `getLineStart` / `getLineEnd`. Pad by a screen above and below.
3. Pass the range as a **constructor parameter** and key the `remember` on it:
   `remember(headingColor, visibleRange) { OrgSyntaxHighlightTransformation(headingColor, visibleRange) }`.
   A new instance on range change triggers a refilter; no state subscription needed inside
   `filter`.
4. Gate updates so the range only changes when it moves meaningfully (e.g. by a line),
   otherwise filter → layout → state → filter can loop.

`buildOrgHighlightedString` then needs a variant that appends unstyled text for lines
outside the range. Its `LineState` (block type, drawer) must still be correct at the
viewport start — do a cheap pre-scan from the top of the document that only tests for
`BEGIN_`/`END_`/drawer markers (2 regexes per line, no `AnnotatedString` building) rather
than a full highlight pass.

## Consequences for the roadmap

- T12b no longer needs block virtualisation; the UX problems that came with it (cross-block
  selection, find/replace, caret movement over boundaries, undo across splices) are all
  avoided.
- Span-count reduction (merging adjacent same-style spans) is **not** needed as a separate
  optimisation: viewport scoping cuts spans ~30× on its own.
- Memoisation can land immediately, independent of everything else.

---

# Follow-up: what actually happened

Recorded after the work was done. The fix ladder above was right about *span production*; a second,
unrelated defect was hiding behind it.

## Results of the ladder

| Step | Measured |
|---|---|
| Before | ~72–94 ms per keystroke (3 × ~25 ms) |
| Memoise `filter` | 3 calls → 1; the redundant two drop to ~1.3 µs |
| Per-line cache | the remaining call ~25 ms → ~4 ms |
| **Net** | **~75 ms → ~4 ms per keystroke, ~19×** |

Typing was still slow. Instrumenting keystroke→pixels latency showed why: `filter` was ~1 % of the
cost. The rest was Compose laying out text carrying 3,119 spans.

| Mode | Spans | keystroke→pixels |
|---|---|---|
| Full | 3,119 | ~262 ms |
| No inline styling | 2,737 | ~190 ms |
| Viewport-scoped | 133 | ~40 ms |
| No highlighting | 0 | ~27 ms |

Latency tracks span count linearly, ~0.06–0.10 ms per span. Viewport scoping captures ~95 % of the
available win. The ~27 ms floor is laying out 45 KB in one field and cannot be tuned away — that is
what per-element editing ([09](../09-element-editing.md)) removed instead.

## The disappearing-space defect

A separate problem, found while testing the above: typed spaces on heading lines did not appear
until the next character was typed. Visual only — saved text was always correct.

**Cause:** a span that changes text metrics makes the field mis-place its caret on the keystroke
that first applies it. Typing the space that turns a line into a heading jumps it from the body size
to 1.3em; the caret does not move, and a space has no glyph, so nothing seems to happen. Bold is
equally guilty — it selects a different font face. Reproduced on desktop, iOS and web; not on
Android, which uses a different text backend.

**Fix:** `editorSpanStyle` keeps colour, background and underline/strikethrough only.

## Process notes, because they cost time

Three hypotheses were wrong before the right one, each plausible and each confidently argued:

1. *Input loss* — wrong; the saved text was always correct.
2. *Whitespace-only style runs measured as zero width* — wrong; measuring through Skia showed every
   character, spaces included, had non-zero advance. A change shipped on this hypothesis fixed
   nothing. It was independently defensible and was kept, with its own test.
3. *Trailing-whitespace trimming* — wrong; plain unstyled text showed the same trimming, so it could
   not explain a difference that appeared only with styling.

What worked, and should be reached for sooner next time:

- **Lay text out headlessly through the same engine.** `Paragraph(...)` with
  `createFontFamilyResolver()` in a `jvmTest` source set measures real per-character advances and
  caret positions without a window. Desktop shares Skia with iOS and web, so it reproduces their
  behaviour.
- **Bisect with runtime switches, not rebuilds.** Chips selecting Full / Colour-only /
  Colour+decorations / Colour+bold / Identity / Off isolated the cause in a handful of keystrokes
  after reasoning had failed repeatedly.
- **Measure end-to-end, not the component you suspect.** `filter` timing looked fine long before
  typing felt fine; keystroke→pixels was the number that mattered.
