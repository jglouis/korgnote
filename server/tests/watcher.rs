//! The watcher against a real folder. A phone that syncs with `?since=` learns of an edit made on
//! the computer only through it, so a watcher that stops, or misses a folder, is a phone told that
//! nothing changed.
//!
//! The periodic rescan is off unless a test turns it on, so these show the events doing the work.

mod common;

use common::Scratch;
use korgnote_server::journal::{ChangeEntry, ChangeJournal};
use korgnote_server::store::FileStore;
use korgnote_server::watcher::{NotesWatcher, WatcherState};
use std::sync::Arc;
use std::time::{Duration, Instant};

const WAIT: Duration = Duration::from_secs(20);
/// Longer than a file event takes to come on any system these run on.
const GRACE: Duration = Duration::from_secs(4);

struct Fixture {
    root: Scratch,
    journal: Arc<ChangeJournal>,
    watcher: NotesWatcher,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        self.watcher.stop();
    }
}

fn append(root: &Scratch, relative: &str, text: &str) {
    use std::io::Write;
    std::fs::OpenOptions::new().append(true).open(root.file(relative)).unwrap().write_all(text.as_bytes()).unwrap();
}

impl Fixture {
    /// The notes every test starts with, before anything is watched.
    fn notes() -> Scratch {
        Scratch::with("watcher", &[("a.org", "* a\n"), ("sub/b.org", "* b\n")])
    }

    /// Starts the watcher and waits until it is watching: until an edit it should see is journaled.
    fn start(root: Scratch, rescan_every: Option<Duration>) -> Fixture {
        let journal = Arc::new(ChangeJournal::new());
        let store = Arc::new(FileStore::new(&root.path));
        let watcher = NotesWatcher::with(&root.path, store, journal.clone(), rescan_every, GRACE);
        watcher.start();
        let fixture = Fixture { root, journal, watcher };
        let start = fixture.journal.generation();
        let until = Instant::now() + WAIT;
        let mut round = 0;
        while !fixture.changes_since(start).iter().any(|change| change.path == "probe.org") {
            assert!(Instant::now() < until, "the watcher never started watching");
            fixture.root.write("probe.org", &format!("* probe {round}\n"));
            round += 1;
            std::thread::sleep(Duration::from_millis(250));
        }
        fixture
    }

    fn changes_since(&self, since: i64) -> Vec<ChangeEntry> {
        self.journal.changes_since(since).map(|delta| delta.changes).unwrap_or_default()
    }

    fn await_change(&self, since: i64, what: &str, matches: impl Fn(&ChangeEntry) -> bool) {
        let until = Instant::now() + WAIT;
        while !self.changes_since(since).iter().any(&matches) {
            assert!(Instant::now() < until, "no {what} in the journal: {:?}", self.changes_since(since));
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    /// The watches are built again after the folders change, so edit until one is heard, which only
    /// a watch can do in time.
    fn edit_until_heard(&self, path: &str) {
        let after = self.journal.generation();
        let until = Instant::now() + WAIT;
        let mut last_edit = Instant::now() - Duration::from_secs(10);
        while !self.changes_since(after).iter().any(|change| change.path == path && !change.deleted) {
            assert!(Instant::now() < until, "no edit of {path} in the journal: {:?}", self.changes_since(after));
            if last_edit.elapsed() >= Duration::from_secs(2) {
                append(&self.root, path, "* edited\n");
                last_edit = Instant::now();
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

#[test]
fn deleting_a_folder_deletes_its_notes_and_the_watcher_carries_on() {
    let f = Fixture::start(Fixture::notes(), None);
    let before = f.journal.generation();
    std::fs::remove_dir_all(f.root.file("sub")).unwrap();
    f.await_change(before, "deletion of sub/b.org", |change| change.path == "sub/b.org" && change.deleted);
    f.edit_until_heard("a.org");
}

#[test]
fn renaming_a_folder_moves_its_notes_and_their_new_paths_are_watched() {
    let f = Fixture::start(Fixture::notes(), None);
    let before = f.journal.generation();
    std::fs::rename(f.root.file("sub"), f.root.file("sub2")).unwrap();
    f.await_change(before, "sub2/b.org", |change| change.path == "sub2/b.org" && !change.deleted);
    f.await_change(before, "deletion of sub/b.org", |change| change.path == "sub/b.org" && change.deleted);
    // The new name is told before the old one is said to be gone, so that no device is ever without the note.
    let changes = f.changes_since(before);
    let made = changes.iter().find(|change| change.path == "sub2/b.org").unwrap().generation;
    let gone = changes.iter().find(|change| change.path == "sub/b.org").unwrap().generation;
    assert!(made < gone, "{changes:?}");
    f.edit_until_heard("sub2/b.org");
}

#[test]
fn a_folder_moved_in_brings_the_notes_already_in_it() {
    let f = Fixture::start(Fixture::notes(), None);
    let outside = Scratch::with("watcher-outside", &[("project/deep/d.org", "* d\n")]);
    let before = f.journal.generation();
    std::fs::rename(outside.file("project"), f.root.file("project")).unwrap();
    f.await_change(before, "project/deep/d.org", |change| change.path == "project/deep/d.org" && !change.deleted);
    f.edit_until_heard("project/deep/d.org");
}

#[test]
fn a_note_written_straight_into_a_new_folder_is_seen() {
    let f = Fixture::start(Fixture::notes(), None);
    let before = f.journal.generation();
    f.root.write("new/c.org", "* c\n");
    f.await_change(before, "new/c.org", |change| change.path == "new/c.org" && !change.deleted);
}

/// A note written while the watches are being built again is not left to the one-minute walk.
/// Here, with that walk off, each of these must still be heard.
#[test]
fn notes_written_while_the_folders_are_watched_again_are_not_left_to_the_periodic_check() {
    let f = Fixture::start(Fixture::notes(), None);
    let before = f.journal.generation();
    for n in 0..40 {
        f.root.write(&format!("burst/deep{}/n{n}.org", n % 5), &format!("* n{n}\n"));
        std::thread::sleep(Duration::from_millis(15));
    }
    for n in 0..40 {
        let path = format!("burst/deep{}/n{n}.org", n % 5);
        f.await_change(before, &path, |change| change.path == path && !change.deleted);
    }
    assert_eq!(f.watcher.status().missed, 0);
}

#[test]
fn a_note_renamed_is_its_old_name_gone_and_its_new_one_made() {
    let f = Fixture::start(Fixture::notes(), None);
    let before = f.journal.generation();
    std::fs::rename(f.root.file("a.org"), f.root.file("a-renamed.org")).unwrap();
    f.await_change(before, "a-renamed.org", |change| change.path == "a-renamed.org" && !change.deleted);
    f.await_change(before, "deletion of a.org", |change| change.path == "a.org" && change.deleted);
    assert_eq!(f.watcher.status().rebuilds, 0, "a note moving is not the folders moving");
}

/// A note may be called `.org` and nothing more, and an edit to it is an edit to a note. Rust's
/// `Path::extension` says such a name has none, so that is not how a note is told.
#[test]
fn a_note_named_dot_org_and_nothing_more_is_heard() {
    let root = Fixture::notes();
    root.write(".org", "* a note with no name before its dot\n");
    let f = Fixture::start(root, None);
    let before = f.journal.generation();
    append(&f.root, ".org", "* edited\n");
    f.await_change(before, "edit of .org", |change| change.path == ".org" && !change.deleted);
}

#[test]
fn the_rescan_finds_what_no_event_reported() {
    // A link is a note, and what it points at is its content, but `.git` is not watched: an edit
    // to the target there raises no event for the link. Only a walk of the tree finds it, as it
    // finds an edit whose event was dropped.
    let root = Fixture::notes();
    root.write(".git/target.org", "* e\n");
    std::os::unix::fs::symlink(root.file(".git/target.org"), root.file("e.org")).unwrap();
    let f = Fixture::start(root, Some(Duration::from_secs(1)));

    let before = f.journal.generation();
    f.root.write(".git/target.org", "* e, edited\n");
    f.await_change(before, "e.org", |change| change.path == "e.org" && !change.deleted);

    // And it says so, rather than healing it quietly, once no event has come for it.
    let until = Instant::now() + WAIT;
    while f.watcher.status().missed == 0 {
        assert!(Instant::now() < until, "the missed change was never reported");
        std::thread::sleep(Duration::from_millis(100));
    }
    let status = f.watcher.status();
    assert_eq!(status.missed, 1);
    assert_eq!(status.last_missed_paths, ["e.org"]);
    assert!(status.last_missed_at.is_some());
}

/// Syncthing keeps old versions in `.stversions`: neither a change to a note, nor a folder that moved.
#[test]
fn a_dot_folder_is_not_watched() {
    let root = Fixture::notes();
    std::fs::create_dir_all(root.file(".stversions")).unwrap();
    let f = Fixture::start(root, Some(Duration::from_secs(1)));
    assert_eq!(f.watcher.status().folders, 2, "the root and sub/");

    let before = f.journal.generation();
    f.root.write(".stversions/a~1.org", "* old a\n");
    f.root.write(".new/x.org", "* x\n");
    // A real edit to wait for, by which time anything the two above caused has been seen.
    append(&f.root, "a.org", "* edited\n");
    f.await_change(before, "edit of a.org", |change| change.path == "a.org" && !change.deleted);
    std::thread::sleep(GRACE + Duration::from_secs(2));

    assert_eq!(f.changes_since(before).iter().map(|change| change.path.as_str()).collect::<Vec<_>>(), ["a.org"]);
    assert_eq!(f.watcher.status().rebuilds, 0, "a dot folder appearing moved no folder of the library");
    assert_eq!(f.watcher.status().missed, 0);
}

#[test]
fn a_save_the_server_already_announced_is_not_counted_as_missed_nor_told_twice() {
    let f = Fixture::start(Fixture::notes(), Some(Duration::from_millis(500)));
    let before = f.journal.generation();
    // What the sync API does on a save: write, then journal at once. The file event comes later,
    // and a check that runs in between must not take the save for an edit nobody announced.
    let store = FileStore::new(&f.root.path);
    for round in 0..3 {
        f.root.write("sub/b.org", &format!("* b, saved through the API {round}\n"));
        f.journal.record("sub/b.org", Some(&store.meta("sub/b.org").unwrap().unwrap().etag), false);
        std::thread::sleep(Duration::from_secs(1));
    }
    // Past the grace, so a wrong suspicion would have been reported by now.
    std::thread::sleep(GRACE + Duration::from_secs(2));
    assert_eq!(f.watcher.status().missed, 0, "missed: {:?}", f.watcher.status().last_missed_paths);
    assert_eq!(f.journal.generation(), before + 3, "the watcher announced none of them again");
}

#[test]
fn the_status_says_what_is_watched() {
    let f = Fixture::start(Fixture::notes(), None);
    let status = f.watcher.status();
    assert_eq!(status.state, WatcherState::Watching);
    assert_eq!(status.folders, 2, "the root and sub/");
    assert_eq!(status.notes, 3, "a.org, sub/b.org and the probe");
    assert!(status.last_check_at.is_some());
    assert!(status.last_change_at.is_some());
    assert_eq!(status.check_every, None);

    std::fs::create_dir_all(f.root.file("more")).unwrap();
    let until = Instant::now() + WAIT;
    while f.watcher.status().folders != 3 {
        assert!(Instant::now() < until, "the new folder was never watched: {:?}", f.watcher.status());
        std::thread::sleep(Duration::from_millis(100));
    }
    assert_eq!(f.watcher.status().rebuilds, 1);
}

#[test]
fn a_folder_that_is_not_there_is_not_watched_and_says_so() {
    let root = Scratch::new("watcher");
    let missing = root.file("nothing-here");
    let journal = Arc::new(ChangeJournal::new());
    let watcher = NotesWatcher::new(&missing, Arc::new(FileStore::new(&missing)), journal);
    watcher.start();
    let status = watcher.status();
    assert_eq!(status.state, WatcherState::NotRunning);
    assert!(status.last_failure.unwrap().ends_with("is not a folder"));
}

/// The folder going away is not every note deleted: an unmounted drive comes back with them all.
#[test]
fn the_notes_folder_going_away_is_a_failure_and_not_every_note_deleted() {
    let holder = Scratch::new("watcher-holder");
    std::fs::create_dir_all(holder.file("notes/sub")).unwrap();
    holder.write("notes/a.org", "* a\n");
    let journal = Arc::new(ChangeJournal::new());
    let root = holder.file("notes");
    let watcher = NotesWatcher::with(&root, Arc::new(FileStore::new(&root)), journal.clone(), Some(Duration::from_millis(300)), GRACE);
    watcher.start();
    let until = Instant::now() + WAIT;
    while watcher.status().state != WatcherState::Watching {
        assert!(Instant::now() < until, "never watching");
        std::thread::sleep(Duration::from_millis(50));
    }
    let before = journal.generation();
    std::fs::rename(&root, holder.file("unmounted")).unwrap();
    let until = Instant::now() + WAIT;
    while watcher.status().state != WatcherState::Failing {
        assert!(Instant::now() < until, "the folder going was never noticed: {:?}", watcher.status());
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(journal.generation(), before, "nothing was told as deleted");

    // And it is watched again when it is back, with what changed meanwhile.
    std::fs::write(holder.file("unmounted/a.org"), "* a, edited while away\n").unwrap();
    std::fs::rename(holder.file("unmounted"), &root).unwrap();
    let until = Instant::now() + Duration::from_secs(40);
    while watcher.status().state != WatcherState::Watching || journal.changes_since(before).is_none_or(|delta| delta.changes.is_empty()) {
        assert!(Instant::now() < until, "it never recovered: {:?}", watcher.status());
        std::thread::sleep(Duration::from_millis(100));
    }
    let changes = journal.changes_since(before).unwrap().changes;
    assert_eq!(changes.iter().map(|change| (change.path.as_str(), change.deleted)).collect::<Vec<_>>(), [("a.org", false)]);
    assert!(watcher.status().last_failure.is_some(), "the problem is still told, as one since recovered");
    watcher.stop();
}
