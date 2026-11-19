//! The journal is an optimisation, never the source of truth: whenever it cannot answer, the caller
//! must fall back to a full manifest. These tests pin down exactly when that happens.
//!
//! A port of `server/…/ChangeJournalTest.kt`, test for test.

use korgnote_server::journal::{ChangeJournal, JournalDelta};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

fn journal() -> ChangeJournal {
    ChangeJournal::with(512, 0)
}

#[test]
fn every_change_gets_the_next_generation() {
    let journal = journal();
    assert_eq!(journal.record("a.org", Some("e1"), false).generation, 1);
    assert_eq!(journal.record("b.org", Some("e2"), false).generation, 2);
    assert_eq!(journal.generation(), 2);
}

#[test]
fn a_caught_up_client_is_told_nothing_changed() {
    let journal = journal();
    journal.record("a.org", Some("e1"), false);
    assert_eq!(journal.changes_since(1), Some(JournalDelta { generation: 1, changes: vec![] }));
}

#[test]
fn a_client_one_behind_gets_just_that_change() {
    let journal = journal();
    journal.record("a.org", Some("e1"), false);
    journal.record("b.org", Some("e2"), false);
    let delta = journal.changes_since(1).unwrap();
    assert_eq!(delta.changes.iter().map(|change| change.path.as_str()).collect::<Vec<_>>(), ["b.org"]);
    assert_eq!(delta.generation, 2);
}

/// A restarted server, or a client away longer than the log reaches.
#[test]
fn a_generation_the_journal_cannot_cover_asks_for_a_full_manifest() {
    let journal = ChangeJournal::with(2, 0);
    for n in 0..5 {
        journal.record(&format!("f{n}.org"), Some(&format!("e{n}")), false);
    }
    assert_eq!(journal.changes_since(1), None, "trimmed history must force a full manifest");
    assert_eq!(journal.changes_since(99), None, "a generation from the future must too");
    // What it still holds is 4 and 5: from 3 on it can answer, and from 2 it cannot.
    assert_eq!(journal.changes_since(3).unwrap().changes.len(), 2);
    assert_eq!(journal.changes_since(2), None);
}

#[test]
fn an_empty_journal_can_only_answer_for_the_generation_it_started_at() {
    let journal = ChangeJournal::with(512, 100);
    assert_eq!(journal.changes_since(100), Some(JournalDelta { generation: 100, changes: vec![] }));
    assert_eq!(journal.changes_since(0), None);
    assert_eq!(journal.changes_since(103), None);
}

#[test]
fn deletions_are_recorded_as_such() {
    let journal = journal();
    journal.record("gone.org", None, true);
    let changes = journal.changes_since(0).unwrap().changes;
    assert_eq!(changes.len(), 1);
    assert!(changes[0].deleted);
    assert_eq!(changes[0].etag, None);
}

#[test]
fn the_log_is_bounded_so_a_memory_leak_is_not_possible() {
    let journal = ChangeJournal::with(3, 0);
    for n in 0..100 {
        journal.record(&format!("f{n}.org"), Some("e"), false);
    }
    assert_eq!(journal.changes_since(97).unwrap().changes.len(), 3);
    assert_eq!(journal.changes_since(96), None);
}

/// The journal is in memory, so a restart starts a new one. A client still holding a generation
/// the old one handed out must get a full manifest from it, never a delta: counted from zero
/// again, the new journal would soon pass that number and answer with only the changes after
/// it, leaving the client without every change made in between.
#[test]
fn a_restarted_server_never_answers_for_a_generation_the_old_one_handed_out() {
    let before = ChangeJournal::new();
    for n in 0..3 {
        before.record(&format!("f{n}.org"), Some(&format!("e{n}")), false);
    }
    let handed_out = before.generation();
    // A restart takes longer than this; it is only here so the two do not start together.
    std::thread::sleep(Duration::from_millis(10));
    let after = ChangeJournal::new();
    for n in 0..10 {
        after.record(&format!("g{n}.org"), Some(&format!("e{n}")), false);
    }
    assert_eq!(after.changes_since(handed_out), None, "the new journal must not pretend to know what changed");
}

/// A phone that went away without closing its stream stops reading it long before the server can
/// tell. Its buffer filling must not hold up the save that is announcing the next change, and with
/// it every other device: events are hints, and the manifest is what a client catches up from.
#[test]
fn a_stream_nobody_is_reading_does_not_hold_up_writers() {
    let journal = journal();
    let _stalled = journal.events_until_one_is_missed();
    let started = std::time::Instant::now();
    for n in 0..1000 {
        journal.record(&format!("f{n}.org"), Some(&format!("e{n}")), false);
    }
    assert_eq!(journal.generation(), 1000);
    assert!(started.elapsed() < Duration::from_secs(5), "writers were held up");
}

/// Past what is buffered, a slow reader loses events, and an app pulls only the notes it hears of.
/// So the stream ends at the first one lost: an app that loses its stream runs a full cycle.
#[test]
fn a_reader_that_falls_behind_sees_its_events_end_at_the_first_one_lost() {
    let journal = Arc::new(journal());
    let mut events = journal.events_until_one_is_missed();
    let reader = std::thread::spawn(move || {
        let mut seen = Vec::new();
        while let Some(entry) = events.blocking_next() {
            std::thread::sleep(Duration::from_millis(1));
            seen.push(entry.generation);
        }
        seen
    });
    std::thread::sleep(Duration::from_millis(200));
    for n in 0..1000 {
        journal.record(&format!("f{n}.org"), Some(&format!("e{n}")), false);
    }
    let seen = reader.join().unwrap();
    assert!(seen.len() < 1000, "nothing was lost, so this tells nothing");
    assert_eq!(seen, (1..=seen.len() as i64).collect::<Vec<_>>(), "what it did see was in order, with none missing");
}

/// Devices and the watcher record at once. None of that may read as an event lost.
#[test]
fn a_reader_that_keeps_up_with_many_writers_sees_every_event() {
    let journal = Arc::new(journal());
    let seen = Arc::new(AtomicUsize::new(0));
    let ended = Arc::new(AtomicUsize::new(0));
    let mut events = journal.events_until_one_is_missed();
    let (counter, flag) = (seen.clone(), ended.clone());
    std::thread::spawn(move || {
        while events.blocking_next().is_some() {
            counter.fetch_add(1, Ordering::SeqCst);
        }
        flag.store(1, Ordering::SeqCst);
    });
    let writers: Vec<_> = (0..8)
        .map(|writer| {
            let journal = journal.clone();
            std::thread::spawn(move || {
                for n in 0..25 {
                    journal.record(&format!("w{writer}-{n}.org"), Some("e"), false);
                }
            })
        })
        .collect();
    writers.into_iter().for_each(|writer| writer.join().unwrap());
    let until = std::time::Instant::now() + Duration::from_secs(5);
    while seen.load(Ordering::SeqCst) < 200 && std::time::Instant::now() < until {
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(seen.load(Ordering::SeqCst), 200);
    assert_eq!(ended.load(Ordering::SeqCst), 0, "the events ended with none lost");
}

/// A reader hears of what happens after it started listening, and of nothing before.
#[test]
fn a_reader_hears_only_what_happens_after_it_came() {
    let journal = journal();
    journal.record("before.org", Some("e0"), false);
    let mut events = journal.events_until_one_is_missed();
    journal.record("after.org", Some("e1"), false);
    assert_eq!(events.blocking_next().unwrap().path, "after.org");
}

/// Only where a note ended up matters: deleted and made again, it is there.
#[test]
fn a_path_changed_several_times_is_reported_once_as_its_latest_change() {
    let journal = journal();
    journal.record("a.org", Some("e1"), false);
    journal.record("a.org", None, true);
    journal.record("a.org", Some("e2"), false);
    let changes = journal.changes_since(0).unwrap().changes;
    assert_eq!(changes.len(), 1);
    assert_eq!(changes[0].etag.as_deref(), Some("e2"));
    assert!(!changes[0].deleted);
}

#[test]
fn changes_are_told_in_the_order_they_last_happened() {
    let journal = journal();
    journal.record("a.org", Some("e1"), false);
    journal.record("b.org", Some("e2"), false);
    journal.record("a.org", Some("e3"), false);
    journal.record("c.org", Some("e4"), false);
    let delta = journal.changes_since(0).unwrap();
    assert_eq!(delta.changes.iter().map(|change| change.path.as_str()).collect::<Vec<_>>(), ["b.org", "a.org", "c.org"]);
}

#[test]
fn the_latest_word_on_a_path_is_what_the_watcher_asks_for() {
    let journal = journal();
    assert_eq!(journal.latest("a.org"), None);
    journal.record("a.org", Some("e1"), false);
    journal.record("b.org", Some("e2"), false);
    journal.record("a.org", None, true);
    let latest = journal.latest("a.org").unwrap();
    assert!(latest.deleted);
    assert_eq!(latest.generation, 3);
}

/// The wire's field names and order: phones already out there read them.
#[test]
fn a_change_is_written_as_the_apps_read_it() {
    let journal = journal();
    assert_eq!(serde_json::to_string(&journal.record("a.org", Some("e1"), false)).unwrap(), r#"{"generation":1,"path":"a.org","etag":"e1","deleted":false}"#);
    assert_eq!(serde_json::to_string(&journal.record("a.org", None, true)).unwrap(), r#"{"generation":2,"path":"a.org","etag":null,"deleted":true}"#);
}
