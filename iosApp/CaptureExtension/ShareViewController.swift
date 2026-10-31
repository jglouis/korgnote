import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// KorgNote in the share sheet: what another app shares, queued for the app to file into the notes.
///
/// The extension is a process of its own and never touches the notes: it writes what was shared,
/// as it was shared, into the queue the app and it share through their App Group, in the same JSON
/// as the app's `CaptureRequest`. The app reads it as a page, a place or an event, makes the entry
/// and files it where it was sent — the inbox, or a heading — the next time it opens.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let context = extensionContext
        let items = context?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        Task { @MainActor [weak self] in
            let shared = await SharedContent.read(from: items)
            let form = CaptureForm(
                shared: shared,
                onCancel: {
                    context?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
                },
                onSave: { request in
                    do {
                        try CaptureQueue.add(request)
                        context?.completeRequest(returningItems: nil)
                    } catch {
                        context?.cancelRequest(withError: error)
                    }
                }
            )
            guard let self else { return }
            let host = UIHostingController(rootView: form)
            addChild(host)
            host.view.frame = view.bounds
            host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(host.view)
            host.didMove(toParent: self)
        }
    }
}

/// What was shared, field for field the app's `SharedContent`.
struct SharedContent: Codable {
    var subject: String?
    var text: String?
    var url: String?
    var calendar: String?

    /// Everything [items] hold that a capture can use: the first link, the text, a calendar file.
    static func read(from items: [NSExtensionItem]) async -> SharedContent {
        var shared = SharedContent()
        for item in items {
            // Safari puts the page's title here; others the text that goes with what they share.
            let words = (item.attributedTitle ?? item.attributedContentText)?.string
            if shared.subject == nil, let words, !words.isEmpty { shared.subject = words }
            for provider in item.attachments ?? [] {
                if shared.calendar == nil, let ics = await calendarText(in: provider) {
                    shared.calendar = ics
                } else if shared.url == nil, provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                          let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL,
                          !url.isFileURL {
                    shared.url = url.absoluteString
                } else if shared.text == nil, provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                          let text = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    shared.text = text
                }
            }
        }
        // Text that is only the link, as some apps share it twice.
        if shared.text?.trimmingCharacters(in: .whitespacesAndNewlines) == shared.url { shared.text = nil }
        return shared
    }

    /// An `.ics` file's text, from Mail or Calendar; nil for anything else, or too big to be one event.
    private static func calendarText(in provider: NSItemProvider) async -> String? {
        let type = UTType("com.apple.ical.ics") ?? UTType.calendarEvent
        guard provider.hasItemConformingToTypeIdentifier(type.identifier) ||
            provider.hasItemConformingToTypeIdentifier(UTType.calendarEvent.identifier) else { return nil }
        let identifier = provider.hasItemConformingToTypeIdentifier(type.identifier) ? type.identifier : UTType.calendarEvent.identifier
        guard let item = try? await provider.loadItem(forTypeIdentifier: identifier) else { return nil }
        let data: Data?
        switch item {
        case let url as URL: data = try? Data(contentsOf: url)
        case let bytes as Data: data = bytes
        case let text as String: data = text.data(using: .utf8)
        default: data = nil
        }
        guard let data, data.count <= 1 << 20 else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// One line saying what was shared, for the form to show.
    var summary: String {
        if calendar != nil { return Words.calendarFile }
        return [url, text].compactMap { $0 }.joined(separator: "\n")
    }
}

/// A capture for the queue: the app's `CaptureRequest`, in the same JSON.
struct CaptureRequest: Codable {
    let id: String
    let at: Int64
    let shared: SharedContent
    let title: String?
    let note: String?
    /// An existing heading to add it to; what goes there is the app's to work out from what was shared.
    let into: HeadingRef?
}

/// A heading to capture into, as the app's `HeadingRef`: found again by its `:ID:` or its outline.
struct HeadingRef: Codable, Hashable {
    let path: String
    /// The headings above it; missing for a top-level one, as the app leaves an empty list out.
    let outline: [String]?
    let title: String
    let id: String?

    /// `notes/trips.org › Paris`: where the heading is, under its title in the list.
    var whereItIs: String { ([path] + (outline ?? [])).joined(separator: " › ") }
}

/// The queue in the App Group's container, as the app reads it: `capture-queue/<id>.json`.
enum CaptureQueue {
    static func add(_ request: CaptureRequest) throws {
        let folder = try directory()
        let encoder = JSONEncoder()
        let data = try encoder.encode(request)
        // Whole or not at all: the app may be reading the folder at this moment.
        try data.write(to: folder.appendingPathComponent("\(request.id).json"), options: .atomic)
    }

    /// The notes' headings, as the app last left them; none until the app has opened once.
    static func headings() -> [HeadingRef] {
        guard let folder = try? directory(),
              let data = try? Data(contentsOf: folder.appendingPathComponent("headings.index")) else { return [] }
        return (try? JSONDecoder().decode([HeadingRef].self, from: data)) ?? []
    }

    private static func directory() throws -> URL {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "KorgNoteAppGroup") as? String,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let folder = container.appendingPathComponent("capture-queue", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}

/// The sheet: a title over what was shared, a line to add, and where it goes.
struct CaptureForm: View {
    let shared: SharedContent
    let onCancel: () -> Void
    let onSave: (CaptureRequest) -> Void

    @State private var title: String
    @State private var note = ""
    @State private var intoHeading = false
    @State private var into: HeadingRef?
    private let headings = CaptureQueue.headings()
    private let capturedAt = Date()

    init(shared: SharedContent, onCancel: @escaping () -> Void, onSave: @escaping (CaptureRequest) -> Void) {
        self.shared = shared
        self.onCancel = onCancel
        self.onSave = onSave
        _title = State(initialValue: shared.subject ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(Words.title, text: $title)
                    TextField(Words.note, text: $note, axis: .vertical)
                        .lineLimit(2...6)
                } footer: {
                    Text(shared.summary).lineLimit(4)
                }
                Section {
                    Picker(Words.where_, selection: $intoHeading) {
                        Text(Words.newEntry).tag(false)
                        Text(Words.intoHeading).tag(true)
                    }
                    .pickerStyle(.segmented)
                    if intoHeading {
                        NavigationLink {
                            HeadingList(headings: headings, chosen: $into)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(into?.title ?? Words.chooseHeading)
                                if let into { Text(into.whereItIs).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                } footer: {
                    if intoHeading { Text(Words.intoFooter) }
                }
                Section {
                } footer: {
                    Text(Words.filedLater)
                }
            }
            .navigationTitle(Words.capture)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(Words.cancel, action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Words.save) {
                        let typed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(CaptureRequest(
                            id: UUID().uuidString,
                            at: Int64(capturedAt.timeIntervalSince1970 * 1000),
                            shared: shared,
                            // Only a title typed over what was shared: left alone, the app names it.
                            title: typed.isEmpty || typed == shared.subject ? nil : typed,
                            note: note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : note,
                            into: intoHeading ? into : nil
                        ))
                    }
                    .disabled(intoHeading && into == nil)
                }
            }
        }
    }
}

/// The notes' headings, searched by the words of their title and where they are, to pick one.
struct HeadingList: View {
    let headings: [HeadingRef]
    @Binding var chosen: HeadingRef?
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let words = query.lowercased().split(separator: " ").map(String.init)
        let shown = headings.filter { heading in
            let haystack = "\(heading.title) \(heading.whereItIs)".lowercased()
            return words.allSatisfy { haystack.contains($0) }
        }.prefix(200)
        List(Array(shown), id: \.self) { heading in
            Button {
                chosen = heading
                dismiss()
            } label: {
                VStack(alignment: .leading) {
                    Text(heading.title).foregroundStyle(.primary)
                    Text(heading.whereItIs).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .overlay {
            if headings.isEmpty { Text(Words.noHeadings).foregroundStyle(.secondary).padding() }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
        .navigationTitle(Words.chooseHeading)
    }
}

/// What the sheet says, in English or in French as the app does.
enum Words {
    private static let french = Locale.preferredLanguages.first?.hasPrefix("fr") == true

    static let capture = french ? "Capturer" : "Capture"
    static let title = french ? "Titre" : "Title"
    static let note = french ? "Une note (facultatif)" : "A note (optional)"
    static let cancel = french ? "Annuler" : "Cancel"
    static let save = french ? "Enregistrer" : "Save"
    static let calendarFile = french ? "Un événement de calendrier" : "A calendar event"
    static let where_ = french ? "Où" : "Where"
    static let newEntry = french ? "Nouvelle entrée" : "New entry"
    static let intoHeading = french ? "Dans un titre" : "Into a heading"
    static let chooseHeading = french ? "Choisir un titre" : "Choose a heading"
    static let noHeadings = french ? "Ouvrez KorgNote une fois pour voir vos titres ici." : "Open KorgNote once to see your headings here."
    static let intoFooter = french
        ? "Un lieu devient son :LOCATION:, un lien ou une date s’ajoute sous le titre."
        : "A place becomes its :LOCATION:; a link or a date goes under the heading."
    static let filedLater = french
        ? "KorgNote le range dans vos notes à sa prochaine ouverture."
        : "KorgNote files it into your notes the next time it opens."
}
