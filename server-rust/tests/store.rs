//! The store exists so the server stops believing a snapshot taken at startup. These cover the two
//! things that made that dangerous: not seeing an external edit, and overwriting one.
//!
//! A port of `server/…/FileStoreTest.kt`, test for test.

mod common;

use common::Scratch;
use korgnote_server::store::{FileStore, WriteResult};
use std::os::unix::fs::PermissionsExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime};

fn fixture() -> (Scratch, FileStore) {
    let root = Scratch::new("store");
    let store = FileStore::new(&root.path);
    (root, store)
}

fn paths(store: &FileStore) -> Vec<String> {
    store.list().unwrap().into_iter().map(|meta| meta.path).collect()
}

fn etag(store: &FileStore, path: &str) -> String {
    store.meta(path).unwrap().unwrap().etag
}

fn text(store: &FileStore, path: &str) -> Option<String> {
    store.read_with_meta(path).map(|file| file.text)
}

fn put(store: &FileStore, path: &str, content: &str, if_match: Option<&str>, must_not_exist: bool) -> WriteResult {
    store.put(path, content.as_bytes(), if_match, must_not_exist).unwrap()
}

fn is_root() -> bool {
    // As root, nothing is out of reach, and a folder that cannot be read still can be.
    std::process::Command::new("id").arg("-u").output().is_ok_and(|out| out.stdout.starts_with(b"0\n"))
}

#[test]
fn lists_only_org_files_including_nested_ones() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO one\n");
    root.write("projects/work.org", "* TODO two\n");
    root.write("notes.txt", "not an org file");
    assert_eq!(paths(&store), ["inbox.org", "projects/work.org"]);
}

#[test]
fn sees_a_file_created_after_the_store_was_built() {
    let (root, store) = fixture();
    assert!(paths(&store).is_empty());
    root.write("later.org", "* TODO appeared\n");
    assert_eq!(paths(&store), ["later.org"]);
}

#[test]
fn sees_an_edit_made_outside_the_server() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO before\n");
    assert_eq!(text(&store, "inbox.org").as_deref(), Some("* TODO before\n"));
    root.write("inbox.org", "* TODO after\n");
    assert_eq!(text(&store, "inbox.org").as_deref(), Some("* TODO after\n"));
}

#[test]
fn the_etag_follows_the_content() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO before\n");
    let before = etag(&store, "inbox.org");
    root.write("inbox.org", "* TODO after\n");
    assert_ne!(before, etag(&store, "inbox.org"));
    root.write("inbox.org", "* TODO before\n");
    assert_eq!(before, etag(&store, "inbox.org"), "the same content should hash the same");
}

#[test]
fn the_etag_is_the_start_of_the_sha256_of_the_content() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO Buy made-up milk\n* DONE Invent a note\n");
    // What the Kotlin server says of the same text.
    assert_eq!(etag(&store, "inbox.org"), "49e847c9bb085e03afd1caeebb330db3");
    assert_eq!(store.read_with_meta("inbox.org").unwrap().meta.etag, "49e847c9bb085e03afd1caeebb330db3");
}

#[test]
fn writing_replaces_the_content_and_reports_the_new_etag() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO before\n");
    let original = etag(&store, "inbox.org");
    let WriteResult::Written(written) = put(&store, "inbox.org", "* TODO after\n", Some(&original), false) else { panic!("not written") };
    assert_eq!(root.read("inbox.org"), "* TODO after\n");
    assert_ne!(original, written.etag);
}

#[test]
fn writing_refuses_when_the_file_changed_underneath() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO before\n");
    let stale = etag(&store, "inbox.org");
    // Someone edits it in Emacs, or Syncthing delivers a new version.
    root.write("inbox.org", "* TODO edited elsewhere\n");
    assert!(put(&store, "inbox.org", "* TODO from the app\n", Some(&stale), false).is_conflict());
    assert_eq!(root.read("inbox.org"), "* TODO edited elsewhere\n", "the external edit must survive");
}

#[test]
fn writing_without_a_baseline_is_allowed() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO before\n");
    assert!(put(&store, "inbox.org", "* TODO after\n", None, false).is_written());
}

#[test]
fn writing_rejects_paths_outside_the_root() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO one\n");
    let outside = Scratch::with("outside", &[("secret.org", "private\n")]);
    let absolute = outside.file("secret.org").display().to_string();
    for attempt in ["../secret.org", "../../secret.org", "a/../../secret.org", absolute.as_str()] {
        let result = put(&store, attempt, "clobbered\n", None, false);
        assert!(matches!(result, WriteResult::Invalid | WriteResult::NotFound), "traversal attempt should not be accepted: {attempt} (was {result:?})");
    }
    assert_eq!(outside.read("secret.org"), "private\n", "nothing outside the root may be touched");
}

#[test]
fn reading_rejects_paths_outside_the_root() {
    let (_root, store) = fixture();
    assert!(text(&store, "../secret.org").is_none());
    assert!(store.meta("../secret.org").unwrap().is_none());
}

#[test]
fn a_note_over_the_limit_is_too_large() {
    let (_root, store) = fixture();
    let over = vec![b'x'; 32 * 1024 * 1024 + 1];
    assert!(matches!(store.put("big.org", &over, None, false).unwrap(), WriteResult::TooLarge));
    assert!(matches!(store.put("big.org", &over[..over.len() - 1], None, false).unwrap(), WriteResult::Written(_)));
}

#[test]
fn what_is_wrong_with_a_note_too_large_is_said_in_the_same_order_whether_it_was_read_or_not() {
    let (root, store) = fixture();
    assert!(matches!(store.put_too_large("big.org"), WriteResult::TooLarge));
    assert!(matches!(store.put_too_large("notes.txt"), WriteResult::Invalid));
    let read_only = FileStore::with_mode(&root.path, true);
    assert!(matches!(read_only.put_too_large("notes.txt"), WriteResult::ReadOnly));
}

#[test]
fn only_org_files_can_be_written() {
    let (root, store) = fixture();
    root.write("notes.txt", "plain\n");
    assert!(matches!(put(&store, "notes.txt", "changed\n", None, false), WriteResult::Invalid));
    assert_eq!(root.read("notes.txt"), "plain\n");
}

#[test]
fn writing_a_missing_file_reports_not_found() {
    let (_root, store) = fixture();
    assert!(matches!(put(&store, "nothing-here.org", "x\n", Some("any"), false), WriteResult::NotFound));
}

#[test]
fn a_read_only_store_refuses_writes() {
    let (root, _) = fixture();
    root.write("inbox.org", "* TODO one\n");
    let read_only = FileStore::with_mode(&root.path, true);
    assert!(matches!(put(&read_only, "inbox.org", "changed\n", None, false), WriteResult::ReadOnly));
    assert!(matches!(read_only.delete("inbox.org", Some("any")).unwrap(), WriteResult::ReadOnly));
    assert_eq!(root.read("inbox.org"), "* TODO one\n");
}

#[test]
fn a_folder_that_refuses_the_write_is_a_failure_and_leaves_nothing_behind() {
    if is_root() {
        return;
    }
    let (root, store) = fixture();
    root.write("sub/a.org", "* a\n");
    std::fs::set_permissions(root.file("sub"), std::fs::Permissions::from_mode(0o555)).unwrap();
    let written = put(&store, "sub/a.org", "* b\n", None, false);
    let deleted = store.delete("sub/a.org", None).unwrap();
    std::fs::set_permissions(root.file("sub"), std::fs::Permissions::from_mode(0o755)).unwrap();
    assert!(matches!(written, WriteResult::Failed(_)), "{written:?}");
    assert!(matches!(deleted, WriteResult::Failed(_)), "{deleted:?}");
    assert_eq!(root.read("sub/a.org"), "* a\n");
    assert_eq!(std::fs::read_dir(root.file("sub")).unwrap().count(), 1);
}

#[test]
fn a_write_leaves_no_temporary_files_behind() {
    let (root, store) = fixture();
    root.write("inbox.org", "* TODO one\n");
    put(&store, "inbox.org", "* TODO two\n", None, false);
    let strays: Vec<_> = std::fs::read_dir(&root.path).unwrap().flatten().filter(|entry| entry.file_name().to_string_lossy().starts_with(".korgnote")).collect();
    assert!(strays.is_empty(), "temporary files should be moved into place, not left: {strays:?}");
}

fn mode_of(root: &Scratch, relative: &str) -> u32 {
    std::fs::metadata(root.file(relative)).unwrap().permissions().mode() & 0o777
}

/// A save used to leave every note readable by its owner only, whatever it was before.
#[test]
fn a_saved_note_keeps_its_permissions() {
    let (root, store) = fixture();
    root.write("shared.org", "* A\n");
    std::fs::set_permissions(root.file("shared.org"), std::fs::Permissions::from_mode(0o664)).unwrap();
    put(&store, "shared.org", "* A, edited\n", None, false);
    assert_eq!(mode_of(&root, "shared.org"), 0o664);
}

#[test]
fn a_note_created_from_the_app_is_readable_like_the_others() {
    let (root, store) = fixture();
    put(&store, "inbox/new.org", "* New\n", None, true);
    assert_eq!(mode_of(&root, "inbox/new.org"), 0o644);
}

/// A folder linked into the notes from elsewhere is outside them. Reading or editing a note
/// through it was already refused; creating one must be too, or a new note lands outside the root.
#[test]
fn a_note_cannot_be_created_through_a_link_that_leaves_the_root() {
    let (root, store) = fixture();
    let outside = Scratch::new("outside");
    std::os::unix::fs::symlink(&outside.path, root.file("linked")).unwrap();
    assert!(matches!(put(&store, "linked/new.org", "* escaped\n", None, true), WriteResult::Invalid));
    assert_eq!(std::fs::read_dir(&outside.path).unwrap().count(), 0, "nothing may be written outside the root");
}

// --- The hash cache: where timestamps are coarse, a file can change and keep its size and time ---

fn set_modified(root: &Scratch, relative: &str, at: SystemTime) {
    std::fs::File::options().write(true).open(root.file(relative)).unwrap().set_modified(at).unwrap();
}

/// TODO to DONE keeps the size, and on a filesystem with one-second timestamps an edit made in the
/// same second as the last one keeps the time too. The old etag would then pass `If-Match`, and a
/// phone's push would overwrite the edit.
#[test]
fn an_edit_in_the_same_second_as_the_last_is_seen() {
    let (root, store) = fixture();
    let now = SystemTime::now();
    root.write("inbox.org", "* TODO one\n");
    set_modified(&root, "inbox.org", now);
    let before = etag(&store, "inbox.org");

    root.write("inbox.org", "* DONE one\n");
    set_modified(&root, "inbox.org", now);

    assert_ne!(before, etag(&store, "inbox.org"));
    assert!(put(&store, "inbox.org", "* TODO one, from a phone\n", Some(&before), false).is_conflict());
    assert_eq!(root.read("inbox.org"), "* DONE one\n");
}

/// What the cache is for: a file left alone a while is not read again to be listed.
#[test]
fn a_file_left_alone_is_not_hashed_again() {
    let (root, store) = fixture();
    let a_minute_ago = SystemTime::now() - Duration::from_secs(60);
    root.write("inbox.org", "* TODO one\n");
    set_modified(&root, "inbox.org", a_minute_ago);
    let before = etag(&store, "inbox.org");

    // Swapped behind the store's back so that only a fresh read could tell.
    root.write("inbox.org", "* DONE one\n");
    set_modified(&root, "inbox.org", a_minute_ago);

    assert_eq!(before, etag(&store, "inbox.org"));
}

/// The cache is the store's own business: a write through it is known at once, whatever the clock says.
#[test]
fn a_write_through_the_store_is_never_answered_from_an_old_hash() {
    let (root, store) = fixture();
    let a_minute_ago = SystemTime::now() - Duration::from_secs(60);
    root.write("inbox.org", "* TODO one\n");
    set_modified(&root, "inbox.org", a_minute_ago);
    let before = etag(&store, "inbox.org");
    let WriteResult::Written(written) = put(&store, "inbox.org", "* DONE one\n", Some(&before), false) else { panic!("not written") };
    set_modified(&root, "inbox.org", a_minute_ago);
    assert_eq!(etag(&store, "inbox.org"), written.etag);
    assert_ne!(written.etag, before);
}

// --- What the library is: Syncthing's old versions and the like are not notes ---

#[test]
fn a_note_in_a_dot_folder_is_not_part_of_the_library() {
    let (root, store) = fixture();
    root.write("a.org", "* a\n");
    root.write(".stversions/a~20260101.org", "* old a\n");
    root.write("sub/.hidden/b.org", "* b\n");

    assert_eq!(paths(&store), ["a.org"]);
    assert!(store.meta(".stversions/a~20260101.org").unwrap().is_none());
    assert!(text(&store, ".stversions/a~20260101.org").is_none());
    assert!(store.meta("sub/.hidden/b.org").unwrap().is_none());
}

#[test]
fn a_dot_folder_cannot_be_written_to_or_deleted_from() {
    let (root, store) = fixture();
    root.write(".korgnote-trash/old.org", "* old\n");

    assert!(matches!(put(&store, ".korgnote-trash/planted.org", "* planted\n", None, false), WriteResult::Invalid));
    assert!(matches!(put(&store, ".new/planted.org", "* planted\n", None, true), WriteResult::Invalid));
    assert!(matches!(store.delete(".korgnote-trash/old.org", Some("any")).unwrap(), WriteResult::Invalid));
    assert!(!root.exists(".korgnote-trash/planted.org"));
    assert!(!root.exists(".new"));
    assert_eq!(root.read(".korgnote-trash/old.org"), "* old\n");
}

/// The app's own folder storage hides these too, but a server's library has always included them.
#[test]
fn a_dot_file_outside_a_dot_folder_stays_in_the_library() {
    let (root, store) = fixture();
    root.write(".plan.org", "* plan\n");
    root.write("sub/.other.org", "* other\n");
    assert_eq!(paths(&store), [".plan.org", "sub/.other.org"]);
    assert_eq!(text(&store, ".plan.org").as_deref(), Some("* plan\n"));
}

/// Listed, it reached every phone as a note that could not be fetched.
#[test]
fn a_link_to_a_file_outside_the_root_is_not_listed() {
    let (root, store) = fixture();
    let outside = Scratch::with("outside", &[("o.org", "* outside\n")]);
    std::os::unix::fs::symlink(outside.file("o.org"), root.file("linked.org")).unwrap();
    root.write("a.org", "* a\n");
    assert_eq!(paths(&store), ["a.org"]);
    assert!(store.meta("linked.org").unwrap().is_none());
}

#[test]
fn a_link_to_a_note_inside_the_root_is_a_note_too() {
    let (root, store) = fixture();
    root.write("a.org", "* a\n");
    std::os::unix::fs::symlink(root.file("a.org"), root.file("also-a.org")).unwrap();
    assert_eq!(paths(&store), ["a.org", "also-a.org"]);
    assert_eq!(text(&store, "also-a.org").as_deref(), Some("* a\n"));
}

/// The Kotlin store lists nothing through such a link, and says the library is complete. This one
/// goes where the link leads.
#[test]
fn a_notes_folder_that_is_a_link_is_listed_and_written_through() {
    let real = Scratch::with("real-notes", &[("a.org", "* a\n"), ("sub/b.org", "* b\n")]);
    let holder = Scratch::new("holder");
    let link = holder.file("notes");
    std::os::unix::fs::symlink(&real.path, &link).unwrap();
    let store = FileStore::new(&link);
    assert_eq!(paths(&store), ["a.org", "sub/b.org"]);
    assert_eq!(text(&store, "sub/b.org").as_deref(), Some("* b\n"));
    assert!(put(&store, "new/c.org", "* c\n", None, true).is_written());
    assert_eq!(real.read("new/c.org"), "* c\n");
    assert!(store.delete("a.org", Some(&etag(&store, "a.org"))).unwrap().is_written());
    assert!(!real.exists("a.org"));
}

/// The manifest is in the order Kotlin puts strings in, which is not Rust's for what lies above the
/// basic plane.
#[test]
fn the_list_is_in_the_order_kotlin_sorts_in() {
    let (root, store) = fixture();
    for name in ["z last ascii.org", "\u{ff5e} fullwidth.org", "\u{1F600} emoji.org", "\u{e000} private.org", "a.org"] {
        root.write(name, "* n\n");
    }
    assert_eq!(paths(&store), ["a.org", "z last ascii.org", "\u{1F600} emoji.org", "\u{e000} private.org", "\u{ff5e} fullwidth.org"]);
}

#[test]
fn what_a_path_may_be_is_what_the_kotlin_store_takes() {
    let (root, store) = fixture();
    // Each of these was put to the Kotlin server: taken, or refused as no path of a note.
    for taken in [".org", " .org", "\u{0085}/x.org", "\u{200b}/x.org", "x\ny.org", "tab\t.org", "he\"ard é.org", "😀.org", "semi;colon.org", "dots...org", "...org", "a/.org", "a../x.org", "x.org.org"] {
        assert!(put(&store, taken, "* planted\n", None, false).is_written(), "{taken:?}");
    }
    for refused in ["", " ", "notes.txt", ".new/x.org", "sub/.x/y.org", "../escape.org", "a/../../escape.org", "inbox.org/", "x/./y.org", "trailing.org ", "UPPER.ORG", " /x.org", "\u{00a0}/x.org", "\u{001c}/x.org", "nul\0.org", "a\\b.org", "..a/x.org", "/etc/x.org", "a//b.org"] {
        assert!(matches!(put(&store, refused, "* planted\n", None, false), WriteResult::Invalid), "{refused:?}");
        assert!(text(&store, refused).is_none(), "{refused:?}");
    }
    assert!(root.exists("a../x.org"));
}

#[test]
fn a_folder_named_like_a_note_is_not_one() {
    let (root, store) = fixture();
    root.write("folder.org/inside.org", "* in a folder named like a note\n");
    assert_eq!(paths(&store), ["folder.org/inside.org"]);
    assert!(text(&store, "folder.org").is_none());
    assert!(matches!(put(&store, "folder.org", "* over a folder\n", None, false), WriteResult::Failed(_)));
    assert!(matches!(put(&store, "folder.org", "* over a folder\n", None, true), WriteResult::Failed(_)));
    assert!(matches!(store.delete("folder.org", Some("abc")).unwrap(), WriteResult::NotFound));
    assert!(root.exists("folder.org/inside.org"));
    assert_eq!(std::fs::read_dir(&root.path).unwrap().count(), 1, "no temporary file was left");
}

/// A link to nothing: a write goes over it, and a creation finds something there and cannot say what.
#[test]
fn a_link_to_nothing_is_written_over_and_not_created_over() {
    let (root, store) = fixture();
    std::os::unix::fs::symlink(root.file("nowhere.org"), root.file("dangling.org")).unwrap();
    assert!(text(&store, "dangling.org").is_none());
    assert!(matches!(put(&store, "dangling.org", "* over\n", None, true), WriteResult::Failed(_)));
    assert!(root.file("dangling.org").is_symlink());
    assert!(put(&store, "dangling.org", "* over\n", None, false).is_written());
    assert!(!root.file("dangling.org").is_symlink());
    assert_eq!(root.read("dangling.org"), "* over\n");
}

#[test]
fn a_note_under_a_note_is_a_failure_of_the_disk() {
    let (root, store) = fixture();
    root.write("work.org", "* work\n");
    assert!(matches!(put(&store, "work.org/under.org", "* under\n", None, false), WriteResult::Failed(_)));
    assert!(text(&store, "work.org/under.org").is_none());
    assert!(matches!(store.delete("work.org/under.org", Some("abc")).unwrap(), WriteResult::NotFound));
    assert_eq!(root.read("work.org"), "* work\n");
}

#[test]
fn deleting_moves_the_note_to_the_trash_and_keeps_each_one() {
    let (root, store) = fixture();
    for round in 0..3 {
        let content = format!("* round {round}\n");
        assert!(put(&store, "sub/gone.org", &content, None, true).is_written());
        assert!(store.delete("sub/gone.org", Some(&etag(&store, "sub/gone.org"))).unwrap().is_written());
    }
    assert!(!root.exists("sub/gone.org"));
    let mut trashed: Vec<String> = common::files_under(&root.file(".korgnote-trash")).iter().map(|file| file.file_name().unwrap().to_string_lossy().into_owned()).collect();
    trashed.sort();
    assert_eq!(trashed, ["gone.org", "gone.org.2", "gone.org.3"]);
    let day = std::fs::read_dir(root.file(".korgnote-trash")).unwrap().flatten().next().unwrap().file_name().to_string_lossy().into_owned();
    assert_eq!(day.len(), 10, "{day}");
    assert_eq!(std::fs::read_to_string(root.file(&format!(".korgnote-trash/{day}/sub/gone.org.2"))).unwrap(), "* round 1\n");
    assert!(paths(&store).is_empty(), "the trash is not part of the library");
}

#[test]
fn deleting_a_version_that_has_since_changed_is_refused() {
    let (root, store) = fixture();
    root.write("a.org", "* A\n");
    let stale = etag(&store, "a.org");
    root.write("a.org", "* A, edited in Emacs\n");
    assert!(store.delete("a.org", Some(&stale)).unwrap().is_conflict());
    assert_eq!(root.read("a.org"), "* A, edited in Emacs\n");
    assert!(matches!(store.delete("never.org", Some("abc")).unwrap(), WriteResult::NotFound));
}

// --- What a walk meets on the way ---

/// A write's temporary file goes while the walk is on its way to it: nothing to do with this manifest.
#[test]
fn a_listing_is_not_broken_by_a_write_going_on_beside_it() {
    let (root, store) = fixture();
    for n in 0..50 {
        root.write(&format!("n{n}.org"), &format!("* note {n}\n"));
    }
    let store = Arc::new(store);
    let stop = Arc::new(AtomicBool::new(false));
    let writers: Vec<_> = (0..4)
        .map(|writer| {
            let (store, stop) = (store.clone(), stop.clone());
            std::thread::spawn(move || {
                let mut round = 0;
                while !stop.load(Ordering::Relaxed) {
                    round += 1;
                    store.put(&format!("w{writer}.org"), format!("* round {round}\n").as_bytes(), None, false).unwrap();
                }
            })
        })
        .collect();
    let mut failure = None;
    for _ in 0..1000 {
        match store.list() {
            Ok(listed) if listed.iter().filter(|meta| meta.path.starts_with('n')).count() == 50 => {}
            other => {
                failure = Some(format!("{:?}", other.map(|listed| listed.len())));
                break;
            }
        }
    }
    stop.store(true, Ordering::Relaxed);
    writers.into_iter().for_each(|writer| writer.join().unwrap());
    assert_eq!(failure, None);
}

fn rename_back_and_forth(root: &Scratch, a: &'static str, b: &'static str, stop: Arc<AtomicBool>) -> std::thread::JoinHandle<()> {
    let (a, b) = (root.file(a), root.file(b));
    std::thread::spawn(move || {
        let mut forward = true;
        while !stop.load(Ordering::Relaxed) {
            let (from, to) = if forward { (&a, &b) } else { (&b, &a) };
            std::fs::rename(from, to).unwrap();
            forward = !forward;
        }
    })
}

/// The walk reads the old name and finds it gone, and the new one was never in what it read. Going
/// on would list a complete library without those notes, and every phone would delete them.
#[test]
fn a_folder_renamed_during_a_listing_fails_it_rather_than_losing_its_notes() {
    let (root, store) = fixture();
    (0..30).for_each(|n| root.write(&format!("top{n}.org"), "* top\n"));
    (0..40).for_each(|folder| (0..5).for_each(|n| root.write(&format!("d{folder}/n{n}.org"), "* n\n")));
    (0..20).for_each(|n| root.write(&format!("mover-a/m{n}.org"), "* m\n"));
    let stop = Arc::new(AtomicBool::new(false));
    let renamer = rename_back_and_forth(&root, "mover-a", "mover-b", stop.clone());
    let mut wrong = None;
    for _ in 0..1000 {
        if let Ok(listed) = store.list()
            && listed.len() != 250 {
                wrong = Some(listed.len());
                break;
            }
    }
    stop.store(true, Ordering::Relaxed);
    renamer.join().unwrap();
    assert_eq!(wrong, None);
}

/// The walk has seen the note, and it goes before the walk has finished with it. With nothing left
/// in the folder to fail on, leaving it out gives a complete manifest one note short.
#[test]
fn a_note_renamed_during_a_listing_fails_it_rather_than_being_left_out() {
    let (root, store) = fixture();
    (0..70).for_each(|n| root.write(&format!("n{n}.org"), "* n\n"));
    root.write("mover-a.org", "* m\n");
    let stop = Arc::new(AtomicBool::new(false));
    let renamer = rename_back_and_forth(&root, "mover-a.org", "mover-b.org", stop.clone());
    let mut wrong = None;
    for _ in 0..2000 {
        if let Ok(listed) = store.list()
            && listed.len() != 71 {
                wrong = Some(listed.len());
                break;
            }
    }
    stop.store(true, Ordering::Relaxed);
    renamer.join().unwrap();
    assert_eq!(wrong, None);
}

/// Too many names to read in one go, so a rename between two reads can hide both names from the walk,
/// and nothing fails: a complete manifest without the folder's notes. One rename, at a random moment
/// of one listing, as a person would do it. Both names at once are allowed: nothing is taken for
/// deleted, and the next delta settles it.
#[test]
fn a_rename_beside_a_big_folder_during_a_listing_loses_no_notes() {
    let (root, store) = fixture();
    (0..3000).for_each(|n| root.write(&format!("note-{n}-with-a-title-of-a-realistic-length.org"), "* n\n"));
    (0..20).for_each(|n| root.write(&format!("projects/m{n}.org"), "* m\n"));
    let started = std::time::Instant::now();
    store.list().unwrap();
    let mut listing = started.elapsed();
    let mut forward = true;
    let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
    for round in 0..50 {
        let (from, to) = if forward { ("projects", "archive") } else { ("archive", "projects") };
        forward = !forward;
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let wait = Duration::from_nanos((seed >> 16) % (listing.as_nanos() as u64).max(1));
        let (from, to) = (root.file(from), root.file(to));
        let renamer = std::thread::spawn(move || {
            let until = std::time::Instant::now() + wait;
            while std::time::Instant::now() < until {
                std::hint::spin_loop();
            }
            std::fs::rename(from, to).unwrap();
        });
        let started = std::time::Instant::now();
        let listed = store.list();
        listing = started.elapsed();
        renamer.join().unwrap();
        if let Ok(listed) = listed {
            assert!(listed.len() >= 3020, "round {round}: only {} of 3020 listed", listed.len());
        }
    }
}

/// Waiting for walks to agree would fail every listing for as long as the notes kept coming.
#[test]
fn notes_arriving_during_a_listing_do_not_fail_it() {
    let (root, store) = fixture();
    (0..500).for_each(|n| root.write(&format!("note-{n}.org"), "* n\n"));
    let store = Arc::new(store);
    let stop = Arc::new(AtomicBool::new(false));
    let arrivals = {
        let (store, stop) = (store.clone(), stop.clone());
        std::thread::spawn(move || {
            let mut n = 0;
            while !stop.load(Ordering::Relaxed) {
                store.put(&format!("new-{n}.org"), b"* new\n", None, false).unwrap();
                n += 1;
            }
        })
    };
    let listed: Vec<_> = (0..5).map(|_| store.list().map(|listed| listed.len())).collect();
    stop.store(true, Ordering::Relaxed);
    arrivals.join().unwrap();
    for count in listed {
        assert!(count.unwrap() >= 500);
    }
}

/// Left out of a complete manifest, the note would be taken for deleted, and go from every phone.
/// So what cannot be read fails the listing, as a server that cannot say.
#[test]
fn a_note_that_cannot_be_read_fails_the_listing_rather_than_leaving_it() {
    if is_root() {
        return;
    }
    let (root, store) = fixture();
    root.write("a.org", "* a\n");
    root.write("locked.org", "* locked\n");
    std::fs::set_permissions(root.file("locked.org"), std::fs::Permissions::from_mode(0o000)).unwrap();
    assert!(store.list().is_err());
    assert!(text(&store, "locked.org").is_none(), "a read finds nothing");
    assert!(store.meta("locked.org").is_err(), "and its version cannot be told");
}

#[test]
fn a_folder_that_cannot_be_read_fails_the_listing_rather_than_leaving_its_notes() {
    if is_root() {
        return;
    }
    let (root, store) = fixture();
    root.write("a.org", "* a\n");
    root.write("locked/b.org", "* b\n");
    std::fs::set_permissions(root.file("locked"), std::fs::Permissions::from_mode(0o000)).unwrap();
    assert!(store.list().is_err());
    assert!(text(&store, "a.org").is_some(), "a note beside it is still read");
}

/// A dot-folder holds no notes, but the Kotlin store's walk goes into it all the same, and one it
/// cannot read fails the listing there too.
#[test]
fn a_dot_folder_that_cannot_be_read_fails_the_listing_as_it_does_in_kotlin() {
    if is_root() {
        return;
    }
    let (root, store) = fixture();
    root.write("a.org", "* a\n");
    root.write(".git/objects/x", "x");
    std::fs::set_permissions(root.file(".git"), std::fs::Permissions::from_mode(0o000)).unwrap();
    assert!(store.list().is_err());
}

// --- Two writers at once: the check and the move must be one step ---

/// Runs `writers` at the same moment and returns what each came back with.
fn together<T: Send + 'static>(writers: Vec<Box<dyn FnOnce() -> T + Send>>) -> Vec<T> {
    let start = Arc::new(std::sync::Barrier::new(writers.len()));
    let handles: Vec<_> = writers
        .into_iter()
        .map(|writer| {
            let start = start.clone();
            std::thread::spawn(move || {
                start.wait();
                writer()
            })
        })
        .collect();
    handles.into_iter().map(|handle| handle.join().unwrap()).collect()
}

const ROUNDS: usize = 200;

/// Two devices pushing from the same version: one wins and the other must merge, or an edit is lost.
#[test]
fn of_two_pushes_from_the_same_version_only_one_wins() {
    let (root, store) = fixture();
    let store = Arc::new(store);
    for round in 0..ROUNDS {
        root.write("race.org", &format!("* base {round}\n"));
        let version = etag(&store, "race.org");
        let push = |text: String| -> Box<dyn FnOnce() -> WriteResult + Send> {
            let (store, version) = (store.clone(), version.clone());
            Box::new(move || store.put("race.org", text.as_bytes(), Some(&version), false).unwrap())
        };
        let results = together(vec![push(format!("* from one {round}\n")), push(format!("* from two {round}\n"))]);
        assert_eq!(results.iter().filter(|result| result.is_written()).count(), 1, "round {round}: {results:?}");
        assert_eq!(results.iter().filter(|result| result.is_conflict()).count(), 1, "round {round}: {results:?}");
    }
}

/// A note made at the same path on two devices: both must survive, one as a conflict copy.
#[test]
fn of_two_creations_of_the_same_path_only_one_wins() {
    let (_root, store) = fixture();
    let store = Arc::new(store);
    for round in 0..ROUNDS {
        let create = |text: &'static str| -> Box<dyn FnOnce() -> WriteResult + Send> {
            let store = store.clone();
            Box::new(move || store.put(&format!("new-{round}.org"), text.as_bytes(), None, true).unwrap())
        };
        let results = together(vec![create("* from one\n"), create("* from two\n")]);
        assert_eq!(results.iter().filter(|result| result.is_written()).count(), 1, "round {round}: {results:?}");
        assert_eq!(results.iter().filter(|result| result.is_conflict()).count(), 1, "round {round}: {results:?}");
    }
}

/// A delete against a version a save has just replaced must not take the save to the trash.
#[test]
fn a_delete_and_a_push_from_the_same_version_do_not_both_win() {
    let (root, store) = fixture();
    let store = Arc::new(store);
    for round in 0..ROUNDS {
        root.write("race.org", &format!("* base {round}\n"));
        let version = etag(&store, "race.org");
        let (pushing, deleting, pushed_version, deleted_version) = (store.clone(), store.clone(), version.clone(), version.clone());
        let results = together::<WriteResult>(vec![
            Box::new(move || pushing.put("race.org", format!("* edited {round}\n").as_bytes(), Some(&pushed_version), false).unwrap()),
            Box::new(move || deleting.delete("race.org", Some(&deleted_version)).unwrap()),
        ]);
        assert_eq!(results.iter().filter(|result| result.is_written()).count(), 1, "round {round}: {results:?}");
    }
}

/// One push can make folders hundreds deep, and every listing after it goes down them all. A walk
/// that called itself for each would run out of stack, which ends the server; so this one is
/// listed on a thread with very little.
#[test]
fn folders_many_levels_deep_are_listed_without_running_out_of_stack() {
    let (_root, store) = fixture();
    // As deep as a path may be on macOS, where it may be 1,024 bytes; Linux allows four times that.
    let depth = 300;
    let deep = format!("{}x.org", "a/".repeat(depth));
    assert!(put(&store, &deep, "* deep\n", None, true).is_written());
    let listed = std::thread::Builder::new().stack_size(64 * 1024).spawn(move || paths(&store)).unwrap().join().unwrap();
    assert_eq!(listed, [deep]);
}
