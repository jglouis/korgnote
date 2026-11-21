//! A bounded, in-memory log of recent changes, and the generation counter clients diff against.
//!
//! It is deliberately an *optimisation*, never the source of truth. A client asking `?since=` a generation the journal no longer covers, because the server
//! restarted or the log was trimmed, gets a full manifest instead, so losing this costs bandwidth and
//! nothing else. That is what keeps it safe to hold only in memory.
//!
//! For a restart to be noticed, a new journal must never hand out a generation an old one did: one
//! counting from zero again would soon pass the number a client kept, and answer for it with only
//! the changes after it. So the counter starts at the time the journal was made, in milliseconds,
//! which is past every generation a previous run reached unless it saw more changes than it ran
//! milliseconds.

use serde::Serialize;
use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

/// Represents a single change entry for a tracked path.
///
/// A `ChangeEntry` records the state of a file or object at a specific
/// generation, including its path, optional entity tag, and whether it was
/// deleted.
///
/// The `etag` can be used to identify or validate the exact version of the
/// object when available. If `deleted` is `true`, the entry represents a
/// deletion event rather than an existing object.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ChangeEntry {
    pub generation: i64,
    pub path: String,
    pub etag: Option<String>,
    pub deleted: bool,
}

/// What changed after a generation, and the generation those changes bring a client up to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JournalDelta {
    pub generation: i64,
    pub changes: Vec<ChangeEntry>,
}

struct Inner {
    counter: i64,
    entries: VecDeque<ChangeEntry>,
}

/// A bounded journal of change entries with asynchronous notifications for readers.
///
/// `ChangeJournal` stores recent changes up to a fixed `capacity` and publishes
/// each generated [`ChangeEntry`] through a broadcast channel. The journal is
/// protected by a [`Mutex`] so updates to the stored state and event publication
/// can be coordinated.
///
/// When the journal reaches capacity, the oldest entries are discarded. This
/// prevents slow or stopped readers from blocking writers indefinitely. Readers
/// are expected to detect missed entries through the event stream and recover
/// using the journal state as needed.
///
/// Events are sent while holding the journal lock, preserving generation order
/// for subscribers.
pub struct ChangeJournal {
    capacity: usize,
    inner: Mutex<Inner>,
    /// Dropping the oldest, because a client that stopped reading must not hold up the writer: see
    /// `Events` for how it hears. Sent under the lock, so in order of generation.
    events: broadcast::Sender<ChangeEntry>,
}

/// As many events as a reader may fall behind by before it loses the oldest.
const EVENT_BUFFER: usize = 256;

impl Default for ChangeJournal {
    fn default() -> Self {
        Self::new()
    }
}

impl ChangeJournal {
    pub fn new() -> Self {
        Self::with(512, SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0))
    }

    pub fn with(capacity: usize, start: i64) -> Self {
        let (events, _) = broadcast::channel(EVENT_BUFFER);
        ChangeJournal { capacity, inner: Mutex::new(Inner { counter: start, entries: VecDeque::new() }), events }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn generation(&self) -> i64 {
        self.lock().counter
    }

    /// Changes as they happen, ending at the first one this reader lost, for the SSE stream. An app
    /// pulls just the notes it hears of, so one that missed an event would show that note stale for
    /// as long as it stays open. Its stream ending is what makes it run a full cycle.
    pub fn events_until_one_is_missed(&self) -> Events {
        // Under the lock, so that no change lands between the generation read and the subscription.
        let inner = self.lock();
        Events { receiver: self.events.subscribe(), last: inner.counter, ended: false }
    }

    /// Records a change and returns it, with its generation.
    pub fn record(&self, path: &str, etag: Option<&str>, deleted: bool) -> ChangeEntry {
        let mut inner = self.lock();
        inner.counter += 1;
        let next = ChangeEntry { generation: inner.counter, path: path.to_string(), etag: etag.map(str::to_string), deleted };
        inner.entries.push_back(next.clone());
        while inner.entries.len() > self.capacity {
            inner.entries.pop_front();
        }
        // Never refused: with no reader there is nobody to tell, and a slow one loses the oldest.
        let _ = self.events.send(next.clone());
        next
    }

    /// The latest entry still held for `path`, or None when there is none in the log.
    ///
    /// Lets the watcher tell a change the devices were already told of, a save through the API, whose
    /// own file event arrives after it, from one nobody announced.
    pub fn latest(&self, path: &str) -> Option<ChangeEntry> {
        self.lock().entries.iter().rev().find(|entry| entry.path == path).cloned()
    }

    /// What changed after `since`, or None when the journal cannot answer for that generation and
    /// the caller must fall back to a full manifest.
    ///
    /// One entry per path, its latest: a note deleted and made again is there, not gone. The
    /// generation is read under the same lock as the changes, so it covers exactly them; read apart,
    /// a change landing in between would be covered by the generation and missing from the changes.
    pub fn changes_since(&self, since: i64) -> Option<JournalDelta> {
        let inner = self.lock();
        let generation = inner.counter;
        if since > generation {
            return None;
        }
        let Some(oldest) = inner.entries.front() else {
            return if since == generation { Some(JournalDelta { generation, changes: Vec::new() }) } else { None };
        };
        // The client is further behind than the log reaches.
        if since < oldest.generation - 1 {
            return None;
        }
        let mut latest: HashMap<&str, &ChangeEntry> = HashMap::new();
        for entry in inner.entries.iter().filter(|entry| entry.generation > since) {
            latest.insert(entry.path.as_str(), entry);
        }
        let mut changes: Vec<ChangeEntry> = latest.into_values().cloned().collect();
        changes.sort_by_key(|entry| entry.generation);
        Some(JournalDelta { generation, changes })
    }
}

/// See `ChangeJournal::events_until_one_is_missed`.
pub struct Events {
    receiver: broadcast::Receiver<ChangeEntry>,
    last: i64,
    ended: bool,
}

impl Events {
    /// The next change, or None once one was lost: the reader fell further behind than the buffer
    /// holds, and what it would be told next is not what happened next.
    pub async fn next(&mut self) -> Option<ChangeEntry> {
        if self.ended {
            return None;
        }
        let received = self.receiver.recv().await;
        self.take(received.ok())
    }

    /// As `next`, for a thread that is not a task.
    pub fn blocking_next(&mut self) -> Option<ChangeEntry> {
        if self.ended {
            return None;
        }
        let received = self.receiver.blocking_recv();
        self.take(received.ok())
    }

    fn take(&mut self, received: Option<ChangeEntry>) -> Option<ChangeEntry> {
        match received {
            Some(entry) if entry.generation == self.last + 1 => {
                self.last = entry.generation;
                Some(entry)
            }
            _ => {
                self.ended = true;
                None
            }
        }
    }
}
