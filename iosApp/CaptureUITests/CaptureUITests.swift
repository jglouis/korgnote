import EventKit
import XCTest

/// Capturing on an iPhone, end to end, on a simulator with no notes of value: through a
/// `korgnote://capture` link, and through the share sheet from Safari.
///
/// Run against a scratch server, never the real one, with its pairing link and a folder for the
/// screenshots passed through `xcodebuild`'s `TEST_RUNNER_` variables:
///
/// ```
/// TEST_RUNNER_KORGNOTE_PAIRING_LINK='korgnote://pair?…' TEST_RUNNER_KORGNOTE_SHOTS=/tmp/shots \
///   xcodebuild test -parallel-testing-enabled NO -project iosApp.xcodeproj -scheme CaptureUITests \
///   -destination 'id=<simulator>'
/// ```
final class CaptureUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.korgnote.korgnote.korgnote")
    private let environment = ProcessInfo.processInfo.environment

    override func setUp() {
        continueAfterFailure = false
    }

    func testCaptureFromALink() throws {
        app.launch()
        pairIfAsked()
        shot("1-notes")

        // A link, as Shortcuts or a bookmarklet would open: the sheet shows the entry to save.
        app.open(URL(string: "korgnote://capture?title=Buy%20a%20plumber%27s%20tape&body=For%20the%20kitchen%20sink")!)
        let save = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Save to'")).firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 15), "the capture sheet")
        shot("2-link-sheet")
        save.tap()
        sleep(3)
        shot("3-after-link")
    }

    func testShareAPageFromSafari() throws {
        app.launch()
        pairIfAsked()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.launch()
        safari.open(URL(string: environment["KORGNOTE_PAGE"] ?? "https://orgmode.org/manual/Capture.html")!)
        sleep(5)
        shot("4-safari")
        dump(safari, "safari")
        // From Safari 26, Share is in the page menu, behind the address field's button.
        let share = safari.buttons["Share"]
        if !share.exists {
            let menu = safari.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'menu' OR identifier CONTAINS[c] 'PageFormat'")).firstMatch
            if menu.exists { menu.tap() }
            sleep(2)
            dump(safari, "safari-menu")
            shot("4b-menu")
        }
        XCTAssertTrue(share.waitForExistence(timeout: 10), "Safari's share button")
        share.tap()
        sleep(2)
        shot("5-share-sheet")
        let korgnote = safari.descendants(matching: .any)["KorgNote"].firstMatch
        if !korgnote.waitForExistence(timeout: 10) { dump(safari, "share-sheet") }
        XCTAssertTrue(korgnote.exists, "KorgNote in the share sheet")
        korgnote.tap()
        sleep(3)
        shot("6-extension")
        let save = safari.buttons["Save"].firstMatch
        if !save.waitForExistence(timeout: 10) { dump(safari, "extension") }
        XCTAssertTrue(save.exists, "the extension's Save")
        save.tap()
        sleep(2)

        // Back in the app, it files what the extension queued.
        app.activate()
        sleep(8)
        shot("7-back-in-app")
    }

    func testShareAPageIntoAHeading() throws {
        app.launch()
        pairIfAsked()
        // The app leaves its headings for the extension when it shows the notes.
        sleep(5)
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.launch()
        safari.open(URL(string: "https://orgmode.org/manual/Capture-templates.html")!)
        sleep(5)
        let share = safari.buttons["Share"]
        if !share.exists {
            safari.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'menu' OR identifier CONTAINS[c] 'PageFormat'")).firstMatch.tap()
            sleep(2)
        }
        XCTAssertTrue(share.waitForExistence(timeout: 10), "Safari's share button")
        share.tap()
        sleep(2)
        safari.descendants(matching: .any)["KorgNote"].firstMatch.tap()
        sleep(3)
        safari.buttons["Into a heading"].firstMatch.tap()
        safari.buttons["Choose a heading"].firstMatch.tap()
        sleep(2)
        let search = safari.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10), "the heading list's search")
        search.tap()
        search.typeText("things")
        sleep(1)
        shot("8-heading-list")
        safari.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Things to see'")).firstMatch.tap()
        sleep(2)
        shot("9-extension-into")
        safari.buttons["Save"].firstMatch.tap()
        sleep(2)
        app.activate()
        sleep(8)
    }

    func testCaptureAnEventFromTheCalendar() throws {
        // An event of the test's own, in the throwaway simulator's calendar, which simctl let it write.
        let store = EKEventStore()
        XCTAssertEqual(EKEventStore.authorizationStatus(for: .event), .fullAccess, "simctl privacy grant calendar, for the runner")
        let event = EKEvent(eventStore: store)
        event.title = "Dentist"
        event.location = "12 rue de la Loi, Bruxelles"
        var start = DateComponents(year: 2026, month: 11, day: 2, hour: 10)
        start.calendar = Calendar.current
        event.startDate = start.date!
        event.endDate = start.date!.addingTimeInterval(45 * 60)
        event.calendar = store.defaultCalendarForNewEvents
        try store.save(event, span: .thisEvent)

        app.launch()
        pairIfAsked()
        app.buttons["Open drawer"].firstMatch.tap()
        sleep(1)
        // In the app, capturing is capturing an event: the calendar opens at once.
        app.buttons["Capture an event"].firstMatch.tap()
        // By where things are rather than by the tree, which XCUITest walks for minutes in a Compose
        // dialog: the search field, then the first event it leaves.
        sleep(5)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.319)).tap()
        sleep(1)
        app.typeText("Dentist")
        sleep(2)
        shot("10-calendar-picker")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.42)).tap()
        sleep(2)
        shot("11-event-sheet")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Save to'")).firstMatch.tap()
        sleep(6)
        try? store.remove(event, span: .thisEvent)
    }

    /// Pairs with the scratch server on a first start, and goes through the welcome screens.
    private func pairIfAsked() {
        guard let link = environment["KORGNOTE_PAIRING_LINK"] else { return }
        let next = app.buttons["Continue"]
        sleep(5)
        // Past the welcome screens already: paired on an earlier run.
        guard app.staticTexts["Welcome to KorgNote"].exists || next.exists else { return }
        app.open(URL(string: link)!)
        let deadline = Date().addingTimeInterval(30)
        while !(next.exists && next.isEnabled) && Date() < deadline { sleep(1) }
        shot("0-paired")
        next.tap()
        let notNow = app.buttons["Not now"]
        if notNow.waitForExistence(timeout: 10) { notNow.tap() }
        sleep(5)
    }

    /// [application]'s element tree into the screenshots' folder, to find what to tap.
    private func dump(_ application: XCUIApplication, _ name: String) {
        guard let folder = environment["KORGNOTE_SHOTS"] else { return }
        try? application.debugDescription.write(
            to: URL(fileURLWithPath: folder).appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
    }

    /// A screenshot into the folder `KORGNOTE_SHOTS` names, to look at after the run.
    private func shot(_ name: String) {
        guard let folder = environment["KORGNOTE_SHOTS"] else { return }
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: folder).appendingPathComponent("\(name).png"))
    }
}
