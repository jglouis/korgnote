# 10 — Capture

Org's `org-capture` on a phone: put something down from wherever it is — a page in the browser, a
place on a map, an event in the calendar, a line of text — without opening the notes, and have it
land in the inbox, in a note or a new one, or in a heading that already exists: a place as its
`:LOCATION:`, a page as a link under it, an event as its `SCHEDULED:`. On branch
`feature/capture`; not released.

A capture should cost nothing, so the dialog asks only where, and shows what:

1. **Destination**: one button, the inbox until changed, opening a search of every note and heading
   with "New note …" for a note made for it. In a heading: *Add to it* (boxes: Location, Link,
   Scheduled, Text) or *Sub-entry*. For a new entry, the destination's own TODO keywords, and
   "None", to mark it with; and for a place, Location and Link boxes, Location alone ticked.

A place is its `:LOCATION:` rather than its link, a link only when asked for: its name and
address, `Eiffel Tower - Av. Gustave Eiffel, 75007 Paris, France`, name first so that typing it
completes the place next time; for Google Maps the address is looked up from where its short link
leads (`MapLinks`); the name alone when that cannot be found (offline), as the location must say
something (`placeOf`).
2. **Capture**: the entry, shown as the note shows Org, and edited, on a tap, with the note's own
   element editor. A capture of nothing — the launcher's quick note — opens in the editor.

No templates: one entry (`captureEntry`) reads whatever was shared and leaves out what it was not.
Not org-capture to the letter, on purpose, and not its `org-capture-templates` either: what made
templates worth having in Emacs — where the entry goes, what it is — the dialog now asks directly.

Inside the app, capturing is capturing an event: the calendar tab's button, "Capture an event",
opens the phone's calendar. It was in the files' drawer, which is about notes rather than dates. What is anywhere else is captured from where it is: the share sheet, the text selection,
a link, the launcher.

An event's time is a `SCHEDULED:`, as Org files an appointment: under a new entry's heading, and as
the heading's own when added to one. A range across days, which Org cannot schedule, stays a plain
timestamp.

## Where it stands

| Entry point | Android | iOS |
|---|---|---|
| Share sheet (a page, a Maps place, text) | `CaptureActivity`, `ACTION_SEND` | `CaptureExtension`, a share extension |
| Calendar file (`.ics` from Mail, Gmail) | `ACTION_SEND` / `ACTION_VIEW` of `text/calendar` | the share extension accepts `public.calendar-event` |
| Selected text | `ACTION_PROCESS_TEXT`: "Capture in KorgNote" in the selection menu | the share sheet on the selection |
| `korgnote://capture?…` (org-protocol's `capture`) | `CaptureActivity` | the app's sheet (`CaptureLinks`) |
| The phone's calendar (Google Calendar's events, which no app shares) | the calendar's "Capture an event", and the launcher's "Event" shortcut: `CalendarContract.Instances`, asked for `READ_CALENDAR` on first use; the picker asks the calendars' accounts to sync (`requestSync`), which Android ignores for an account the app may not see, so Android's account chooser is shown once to make the Google account visible ("Fetch new events" after a no) | the calendar's "Capture an event": EventKit, full access asked on first use; Google's events are there once the account is added in Settings; the picker asks for a fetch (`refreshSourcesIfNecessary`) |
| ↳ events just added elsewhere | the phone's copy of Google's calendars syncs on Google's push, which a phone may not get (a Galaxy A52s went ten hours without one on 2026-11-01): the open picker reads again on each change of the provider (a `ContentObserver`) | Google's calendars are fetched on the schedule in Settings, never pushed: the open picker reads again on `EKEventStoreChangedNotification` |
| Launcher | long-press shortcuts "Capture" (a quick note) and "Event" (`CaptureShortcut`) | — |

Seen working on 2026-10-31 against a scratch server, which received the entries: on a Galaxy A52s
(Android 14) from Chrome's share sheet, Google Maps' share button and Chrome's text-selection menu;
on an emulator (the app closed, a local calendar's events); and on an
iPhone simulator (a link, and a page shared from Safari through the extension). Not yet on an iPhone.

What the apps send, as seen on the phone: Chrome, the page's URL as the text and its title as the
subject. Google Maps, only a short link (`maps.app.goo.gl/…`) as the text, and the place's name as
`EXTRA_TITLE` — no address and no coordinates. The short link redirects to
`https://www.google.com/maps/place/Eiffel+Tower,+Av.+Gustave+Eiffel,+75007+Paris,+France/data=…`
(seen 2026-11-02), so `MapLinks` asks for the short link, reads the redirect without following it,
and keeps it as `SharedContent.expanded`: the name, then the address, comes from there, and the
short link stays the link. That one request, before the sheet shows (3 s at most), is all
that goes to the network; on iOS, where the extension does not render the entry, the app makes it
when it files the capture. Chrome's own share sheet lists a
few apps; KorgNote is under its "More", in Android's.

Not built: the calendar from iOS's share extension (EventKit is in the app); App Intents for
Shortcuts and Siri; a Quick Settings tile; images and files.

## Into an existing heading

The sheet's second choice. The heading is picked from a search of every heading in the notes, as the
link picker does it, and kept as a `HeadingRef` — its `:ID:` when it has one, its note and outline
otherwise — since the capture may be filed later, after the note changed. What goes there is a box
each, ticked by default for what was shared (`HeadingAdditions.defaultsFor`):

| Shared | Ticked | Written |
|---|---|---|
| a place | Location (Link on request) | `:LOCATION:` in the heading's drawer (`setProperty`); `- [[link][name]]` |
| an event | Scheduled, and Location when it has one | the heading's `SCHEDULED:` (a range across days: a plain timestamp) |
| a page | Link | `- [[link][title]]` |
| text | Text | the text; with a link — a quote shared from a page — both are ticked |

Lines go at the end of the heading's own content, above its first sub-heading (`captureInto`). A
heading gone by the time the capture is filed sends it to the inbox as a new entry rather than
losing it. iOS's extension offers the headings the app last left in the App Group
(`headings.index`), and leaves what to add to the app's defaults.

## One pipeline

```
 share sheet ─┐
 selection ───┤                         shared/…/org/capture (pure, tested on the JVM)
 korgnote:// ─┼─► CaptureRequest ─► queue ─► capturedFrom ─► captureEntry ─► fileCapture ─► note
 calendar ────┘   (what was shared,         (page, place,   (one entry,      (at the end, under
                   raw, and when)            event, text)    as edited)       a heading, or into it)
```

- **`SharedContent`** is what the platform received, kept raw: subject, text, URL, calendar file.
- **`capturedFrom`** reads it for what it is: a calendar file is an event (`CalendarEvents.kt`:
  times into the device's zone, an Org range, a repeater when the RRULE is one Org can say); a
  Google or Apple Maps link, or `geo:`, is a place (name, address, coordinates when the link has
  them; for a Google Maps short link, where it leads, when `MapLinks` looked that up); a link is a
  page (its title, or the words shared with it); anything else is text. Reading it goes nowhere.
- **`captureEntry`** makes the one entry, through `renderCapture`'s escapes (org-capture's, and
  KorgNote's `%:location`, `%:scheduled`, `%:range`). A line whose escapes all came to nothing goes,
  and a property drawer left empty with it, so one entry serves a page, a place and an event. The
  sheet sends it as shown, or as edited.
- **`fileCapture`** adds the entry at the end of the note, `fileUnder` as a sub-entry of a heading,
  and `captureInto` adds to a heading. It only ever appends, so two devices capturing into one inbox while apart merge:
  `merge` keeps two insertions at one place (tested in `CaptureTest`).

## Why a queue

A capture is a `CaptureRequest` (JSON) in a queue folder, and only the app files it
(`CaptureFiler.fileWaiting`, one filing at a time in the process). On iOS this is forced: the share
extension is a separate process with a small memory budget, which must not write the replica while
the app syncs it. It writes the request into the App Group both share
(`group.<bundle id>`, named in both Info.plists as `KorgNoteAppGroup`) and the app files it when it
opens or comes back. So on iOS a shared page reaches other devices only once the app has run.
The app leaves the headings (`headings.index`) in the queue folder for the extension to offer, on
iOS only.

On Android the same queue is used for the same reason in miniature: the sheet closes as soon as the
capture is queued, and `CaptureWorker` files and syncs it, with the app open or not.

**The cost: Swift.** The extension is ~250 lines of SwiftUI, beside the ~33 the iOS shell had (G7).
Compose in an extension was possible but heavy for its memory budget, and the extension only needs
three fields; reading what was shared, and the entry, stay in Kotlin, in the app.

## Testing

- `shared`: `CaptureTest` (reading, calendar files, the entry and its keyword, filing, headings, merging).
- `composeApp`: `CaptureFilerTest` (queue to notes; the JSON the Swift extension writes).
- iOS: `CaptureUITests`, an XCUITest target, drives a link capture and a Safari share on a
  simulator paired with a scratch server; see its header for the command. iOS 27 asks before
  `simctl openurl` opens the app, so links need it (or a tap).
- Android: a throwaway AVD paired with a scratch server through `adb reverse`, intents sent with
  `am start -a android.intent.action.SEND …` to `CaptureActivity`.

## Open questions

1. One inbox file with headings (now), or a file per capture?
2. Network enrichment: a Maps short link's address is looked up, always (the user asked for the
   address, 2026-11-02; the request only tells Google its own link was opened). A page's title
   when none was shared is not: opt-in, or never?
3. The share extension on iOS asks for a title and a note in fields: the app's sheet shows the entry
   instead. Should the extension show the Org too, which needs it rendered without the app?
