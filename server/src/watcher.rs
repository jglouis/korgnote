//! Watches the notes directory so an edit made in Emacs, or delivered by Syncthing, becomes a
//! journal entry, and therefore an SSE event, within a second or two.
//!
//! Without this the server only knows about changes it made itself, and a phone that syncs with
//! `?since=` is told nothing changed: it reports a good sync and never sees the edit. So the watcher
//! must not stop, and must not lose what it misses:
//!
//! - It keeps what it last saw of every note, path and etag. A plain file event is checked against
//!   it; anything that moves folders, a folder made, deleted, renamed or moved in, an overflow of
//!   events, an error, makes it build its watches again from scratch and walk the tree, journaling
//!   every difference. A renamed folder is then its old notes deleted and its new ones made, and a
//!   folder moved in with notes in it brings them.
//! - It rebuilds rather than mends: a watch follows its folder, not its name, so after `mv sub sub2`
//!   the old one cannot be trusted to say where a change was.
//! - It walks the tree every `rescan_every` as well, because a watch can miss events and say nothing:
//!   Docker Desktop's file sharing drops some made on the host, a note written straight into a folder
//!   just made among them.
//!
//! What that walk finds is never healed quietly: it is logged as a warning and counted in the
//! status, so a watch that misses events is seen to, rather than hidden behind a one-minute delay.

use crate::journal::ChangeJournal;
use crate::store::{FileStore, is_library_folder, relative_to};
use crate::text::utf16_order;
use notify::event::{AccessKind, AccessMode, ModifyKind, RenameMode};
use notify::{EventKind, RecursiveMode, Watcher};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatcherState {
    Starting,
    Watching,
    Failing,
    NotRunning,
}

/// How the watcher is doing, for the Devices page: a phone told "nothing changed" by a server that
/// stopped looking reports a good sync, so the only place to notice is here.
///
/// Times are epoch milliseconds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatcherStatus {
    pub state: WatcherState,
    /// When `state` began.
    pub since: i64,
    pub folders: usize,
    pub notes: usize,
    /// The last change to a note the watcher journaled, by an event or a walk.
    pub last_change_at: Option<i64>,
    /// The last walk of the whole tree that succeeded.
    pub last_check_at: Option<i64>,
    /// How often the tree is walked; a `last_check_at` well past it means the watcher is stuck.
    /// None when it is never walked, as in tests that show the events doing the work.
    pub check_every: Option<Duration>,
    /// Times the folders moved and the watches were built again.
    pub rebuilds: usize,
    /// Changes that no event reported and only the periodic walk found. Should stay at zero.
    pub missed: usize,
    pub last_missed_at: Option<i64>,
    pub last_missed_paths: Vec<String>,
    pub last_failure: Option<String>,
    pub last_failure_at: Option<i64>,
}

const SHOWN_PATHS: usize = 5;

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct NotesWatcher {
    root: PathBuf,
    store: Arc<FileStore>,
    journal: Arc<ChangeJournal>,
    rescan_every: Option<Duration>,
    /// How long an event may trail the change it reports. The periodic check can find an edit before
    /// its event does; that edit is late, not missed.
    late_event_grace: Duration,
    status: Arc<Mutex<WatcherStatus>>,
    stopping: Arc<AtomicBool>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl NotesWatcher {
    pub fn new(root: &Path, store: Arc<FileStore>, journal: Arc<ChangeJournal>) -> Self {
        Self::with(root, store, journal, Some(Duration::from_secs(60)), Duration::from_secs(10))
    }

    pub fn with(root: &Path, store: Arc<FileStore>, journal: Arc<ChangeJournal>, rescan_every: Option<Duration>, late_event_grace: Duration) -> Self {
        let status = WatcherStatus {
            state: WatcherState::Starting,
            since: now(),
            folders: 0,
            notes: 0,
            last_change_at: None,
            last_check_at: None,
            check_every: rescan_every,
            rebuilds: 0,
            missed: 0,
            last_missed_at: None,
            last_missed_paths: Vec::new(),
            last_failure: None,
            last_failure_at: None,
        };
        NotesWatcher {
            root: root.to_path_buf(),
            store,
            journal,
            rescan_every,
            late_event_grace,
            status: Arc::new(Mutex::new(status)),
            stopping: Arc::new(AtomicBool::new(false)),
            thread: Mutex::new(None),
        }
    }

    pub fn status(&self) -> WatcherStatus {
        self.status.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone()
    }

    pub fn start(&self) {
        if !self.root.is_dir() {
            let mut status = self.status.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            status.state = WatcherState::NotRunning;
            status.since = now();
            status.last_failure = Some(format!("{} is not a folder", self.root.display()));
            status.last_failure_at = Some(now());
            crate::warn!("Not watching {}: it is not a folder. Edits made outside the server will not reach the devices.", self.root.display());
            return;
        }
        let mut worker = Worker {
            root: self.root.clone(),
            store: self.store.clone(),
            journal: self.journal.clone(),
            rescan_every: self.rescan_every,
            late_event_grace: self.late_event_grace,
            status: self.status.clone(),
            stopping: self.stopping.clone(),
            known: HashMap::new(),
            watched: HashMap::new(),
            suspects: Vec::new(),
        };
        let handle = std::thread::Builder::new().name("notes-watcher".to_string()).spawn(move || worker.run()).expect("a thread to watch the notes with");
        *self.thread.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(handle);
    }

    pub fn stop(&self) {
        self.stopping.store(true, Ordering::SeqCst);
        if let Some(handle) = self.thread.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).take() {
            let _ = handle.join();
        }
    }
}

impl Drop for NotesWatcher {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
    }
}

/// What an event says happened to a path: made, gone, or changed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Happened {
    Created,
    Deleted,
    Modified,
}

struct Worker {
    root: PathBuf,
    store: Arc<FileStore>,
    journal: Arc<ChangeJournal>,
    rescan_every: Option<Duration>,
    late_event_grace: Duration,
    status: Arc<Mutex<WatcherStatus>>,
    stopping: Arc<AtomicBool>,
    /// What the journal last heard of each note: path to etag.
    known: HashMap<String, String>,
    /// The folders being watched, so a deleted one is recognised once it can no longer be looked at,
    /// each with which folder it was when the watch was put on it: see `still_the_folders_watched`.
    watched: HashMap<PathBuf, Option<FolderIdentity>>,
    /// Changes the periodic check found before any event, with when: journaled at once, and counted
    /// as missed only if no event for them follows within the grace.
    suspects: Vec<(String, Instant)>,
}

impl Worker {
    fn update(&self, change: impl FnOnce(&mut WatcherStatus)) {
        change(&mut self.status.lock().unwrap_or_else(|poisoned| poisoned.into_inner()));
    }

    fn stopped(&self) -> bool {
        self.stopping.load(Ordering::SeqCst)
    }

    /// Sleeps, but not past being told to stop.
    fn wait(&self, duration: Duration) {
        let until = Instant::now() + duration;
        while !self.stopped() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(100).min(until.saturating_duration_since(Instant::now())));
        }
    }

    fn run(&mut self) {
        // The first walk is the baseline: a client's first sync takes the full manifest anyway.
        self.rescan(false);
        let mut failures: u64 = 0;
        while !self.stopped() {
            let reason = match self.watch_until_the_folders_move(failures > 0) {
                Ok(Some(reason)) => {
                    failures = 0;
                    Some(reason)
                }
                Ok(None) => break,
                Err(error) => {
                    failures += 1;
                    crate::warn!("Watching {} failed; watching it again and looking for what was missed: {error}", self.root.display());
                    self.update(|status| {
                        if status.state != WatcherState::Failing {
                            status.since = now();
                        }
                        status.state = WatcherState::Failing;
                        status.last_failure = Some(error.clone());
                        status.last_failure_at = Some(now());
                    });
                    // A root that has gone away fails every time; don't spin on it.
                    self.wait(Duration::from_secs(failures.min(30)));
                    None
                }
            };
            if self.stopped() {
                break;
            }
            // Whatever ended that round, changes may have gone unseen during it. After the
            // folders moved they are expected; after a failure, the warning above explains them.
            if let Some(found) = self.rescan(true).filter(|found| !found.is_empty()) {
                crate::info!("{} in {} after {}", describe(&found), self.root.display(), reason.as_deref().unwrap_or("the failure"));
            }
        }
        self.update(|status| {
            if status.state != WatcherState::NotRunning {
                status.state = WatcherState::NotRunning;
                status.since = now();
            }
        });
    }

    /// Watches with a fresh watcher until the folder structure changes, then returns why, so the
    /// caller rescans and watches again. Plain edits are journaled here as they come. `Ok(None)` is
    /// being told to stop.
    fn watch_until_the_folders_move(&mut self, recovering: bool) -> Result<Option<String>, String> {
        // Where the notes really are: events name real paths, whatever link the folder was reached by.
        let root = fs::canonicalize(&self.root).map_err(|error| format!("{}: {error}", self.root.display()))?;
        let (sender, events) = mpsc::channel();
        let mut watcher = notify::recommended_watcher(move |event| {
            let _ = sender.send(event);
        })
        .map_err(|error| error.to_string())?;
        self.watched.clear();
        self.register_recursively(&root, &mut watcher)?;
        let was_starting = self.status.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).state == WatcherState::Starting;
        let folders = self.watched.len();
        self.update(|status| {
            if status.state != WatcherState::Watching {
                status.since = now();
            }
            status.state = WatcherState::Watching;
            status.folders = folders;
        });
        if was_starting {
            let every = self.rescan_every.map(|every| format!("; checking the whole folder every {} as well", duration_text(every))).unwrap_or_default();
            crate::info!("Watching {}: {folders} folders, {} notes{every}", self.root.display(), self.known.len());
        } else if recovering {
            crate::info!("Watching {} again: {folders} folders", self.root.display());
        } else {
            crate::debug!("Watching {folders} folders in {} again", self.root.display());
        }

        // What changed between the walk before this and the watches being in place had no watch to
        // report it: a note written into a folder just made, most of all. Rather than leave such an
        // edit to the one-minute walk, walking once more here, now that every later change has a
        // watch on it, finds it at once.
        if let Some(found) = self.rescan(true).filter(|found| !found.is_empty()) {
            crate::info!("{} in {} while the folders were being watched again", describe(&found), self.root.display());
        }

        let mut last_scan = Instant::now();
        while !self.stopped() {
            // Walked on its own clock, so a steady stream of events cannot put it off.
            if self.rescan_every.is_some_and(|every| last_scan.elapsed() >= every) {
                if !still_the_folders_watched(&self.watched) {
                    self.suspects.clear();
                    self.update(|status| status.rebuilds += 1);
                    let reason = "a folder is no longer the one that was being watched".to_string();
                    crate::info!("Folders changed in {} ({reason}); watching them again", self.root.display());
                    return Ok(Some(reason));
                }
                if let Some(found) = self.rescan(true) {
                    for path in found {
                        if !self.suspects.iter().any(|(suspect, _)| *suspect == path) {
                            self.suspects.push((path, Instant::now()));
                        }
                    }
                }
                last_scan = Instant::now();
            }
            self.judge_suspects();
            // Wait a while rather than for ever, so stopping the server does not leave a thread parked.
            let first = match events.recv_timeout(Duration::from_millis(500)) {
                Ok(event) => event,
                Err(RecvTimeoutError::Timeout) => continue,
                Err(RecvTimeoutError::Disconnected) => return Err("the watch ended without a word".to_string()),
            };
            // Coalesce the burst an editor produces when saving one file, then take every event
            // that is ready, so a checkout touching many folders is one rebuild and not dozens.
            std::thread::sleep(Duration::from_millis(100));
            let mut changed: Vec<PathBuf> = Vec::new();
            let mut folders_moved: Option<String> = None;
            let mut next = Some(first);
            while let Some(event) = next {
                let event = event.map_err(|error| error.to_string())?;
                if let Some(reason) = self.collect(&root, &event, &mut changed) {
                    folders_moved.get_or_insert(reason);
                }
                next = events.try_recv().ok();
            }
            if let Some(reason) = folders_moved {
                // The late events these wait for went with the old watcher: give them the doubt.
                self.suspects.clear();
                self.update(|status| status.rebuilds += 1);
                crate::info!("Folders changed in {} ({reason}); watching them again", self.root.display());
                return Ok(Some(reason));
            }
            for file in changed {
                self.record_if_changed(&root, &file)?;
            }
        }
        Ok(None)
    }

    /// Adds the notes `event` reports to `changed`; returns why the folders moved, when they did, and
    /// the watches must be built again instead.
    fn collect(&self, root: &Path, event: &notify::Event, changed: &mut Vec<PathBuf>) -> Option<String> {
        if event.need_rescan() {
            return Some("too many events at once".to_string());
        }
        let mut folders_moved = None;
        for (index, path) in event.paths.iter().enumerate() {
            let happened = match event.kind {
                EventKind::Create(_) => Happened::Created,
                EventKind::Remove(_) => Happened::Deleted,
                EventKind::Modify(ModifyKind::Name(RenameMode::From)) => Happened::Deleted,
                EventKind::Modify(ModifyKind::Name(RenameMode::To)) => Happened::Created,
                EventKind::Modify(ModifyKind::Name(RenameMode::Both)) => {
                    if index == 0 {
                        Happened::Deleted
                    } else {
                        Happened::Created
                    }
                }
                // A rename with no word on which end this is: what is there now says.
                EventKind::Modify(ModifyKind::Name(_)) => {
                    if fs::symlink_metadata(path).is_ok() {
                        Happened::Created
                    } else {
                        Happened::Deleted
                    }
                }
                EventKind::Modify(_) | EventKind::Any | EventKind::Other => Happened::Modified,
                EventKind::Access(AccessKind::Close(AccessMode::Write)) => Happened::Modified,
                // Reading a note is not a change to it.
                EventKind::Access(_) => continue,
            };
            if !path.starts_with(root) || self.excluded(root, path) {
                continue;
            }
            if happened == Happened::Created && path.is_dir() {
                folders_moved.get_or_insert(format!("{}/ appeared", shown(root, path)));
            } else if happened == Happened::Deleted && self.watched.contains_key(path) {
                folders_moved.get_or_insert(format!("{}/ went away", shown(root, path)));
            } else if path.file_name().is_some_and(|name| name.to_string_lossy().ends_with(".org")) && !changed.contains(path) {
                changed.push(path.clone());
            }
        }
        folders_moved
    }

    /// Journals `file` if what is on disk now is not what the journal last heard of it.
    fn record_if_changed(&mut self, root: &Path, file: &Path) -> Result<(), String> {
        let path = relative_to(root, file);
        // The event the periodic check went ahead of: late, but it came.
        self.suspects.retain(|(suspect, _)| *suspect != path);
        // Editors write through temporary files, so a single save can arrive as several events;
        // the etag is read fresh here, and a repeat of what is already known is dropped.
        let etag = self.store.meta(&path).map_err(|error| format!("{path}: {error}"))?.map(|meta| meta.etag);
        if etag.as_ref() == self.known.get(&path) {
            return Ok(());
        }
        match &etag {
            Some(etag) => self.known.insert(path.clone(), etag.clone()),
            None => self.known.remove(&path),
        };
        if !self.announced(&path, etag.as_deref()) {
            self.journal.record(&path, etag.as_deref(), etag.is_none());
        }
        let notes = self.known.len();
        self.update(|status| {
            status.notes = notes;
            status.last_change_at = Some(now());
        });
        Ok(())
    }

    /// Walks the whole tree and, when `journal_changes`, journals every note made, changed or deleted
    /// since `known` was last right. Deletions come last, so a renamed folder's notes exist anew
    /// before any client is told the old ones are gone.
    ///
    /// Returns the paths it journaled, or None when it could not walk the tree.
    fn rescan(&mut self, journal_changes: bool) -> Option<Vec<String>> {
        // A notes folder that is not there, an unmounted drive, a volume not attached yet, lists as
        // empty, which is not every note deleted. Wait for it to come back.
        if !self.root.is_dir() {
            return None;
        }
        let listed = match self.store.list() {
            Ok(listed) => listed,
            Err(error) => {
                // A folder vanishing mid-walk; the next rescan will see the tree as it settled.
                crate::debug!("Could not walk {}: {error}", self.root.display());
                return None;
            }
        };
        let mut found = Vec::new();
        if journal_changes {
            for meta in &listed {
                if self.known.get(&meta.path) != Some(&meta.etag) && !self.announced(&meta.path, Some(&meta.etag)) {
                    self.journal.record(&meta.path, Some(&meta.etag), false);
                    found.push(meta.path.clone());
                }
            }
            let there: HashSet<&str> = listed.iter().map(|meta| meta.path.as_str()).collect();
            // In the order of their names, not of a hash table, so that the order is one a client
            // could count on.
            let mut gone: Vec<&String> = self.known.keys().filter(|path| !there.contains(path.as_str())).collect();
            gone.sort_by(|a, b| utf16_order(a, b));
            for path in gone {
                if self.announced(path, None) {
                    continue;
                }
                self.journal.record(path, None, true);
                found.push(path.clone());
            }
        }
        self.known = listed.into_iter().map(|meta| (meta.path, meta.etag)).collect();
        let checked = now();
        let notes = self.known.len();
        let changed = !found.is_empty();
        self.update(|status| {
            status.notes = notes;
            status.last_check_at = Some(checked);
            if changed {
                status.last_change_at = Some(checked);
            }
        });
        Some(found)
    }

    /// Reports the suspects whose events are past the grace: those events are not coming.
    fn judge_suspects(&mut self) {
        if self.suspects.is_empty() {
            return;
        }
        let grace = self.late_event_grace;
        let overdue: Vec<String> = self.suspects.iter().filter(|(_, since)| since.elapsed() >= grace).map(|(path, _)| path.clone()).collect();
        if overdue.is_empty() {
            return;
        }
        self.suspects.retain(|(path, _)| !overdue.contains(path));
        self.report_missed(overdue);
    }

    /// Whether the journal's latest word on `path` is already this version, `etag`, or deleted when
    /// None, as it is for a save made through the API, recorded there before its file event comes.
    fn announced(&self, path: &str, etag: Option<&str>) -> bool {
        let Some(latest) = self.journal.latest(path) else { return false };
        match etag {
            None => latest.deleted,
            Some(etag) => !latest.deleted && latest.etag.as_deref() == Some(etag),
        }
    }

    /// The periodic walk found what nobody had announced, and no event for it followed: events are
    /// being missed.
    fn report_missed(&self, paths: Vec<String>) {
        crate::warn!(
            "{} in {} that the watch did not report; found by the periodic check. If this keeps happening, edits made on this computer reach the devices only once a minute.",
            describe(&paths),
            self.root.display()
        );
        let at = now();
        self.update(|status| {
            status.missed += paths.len();
            status.last_missed_at = Some(at);
            status.last_missed_paths = paths.iter().take(SHOWN_PATHS).cloned().collect();
        });
    }

    /// Watches `directory` and every folder of the library under it, each by itself, without
    /// following links. A folder that cannot be gone into is left: the event for it, or the next
    /// walk, brings another rebuild. The root failing fails the round.
    fn register_recursively(&mut self, directory: &Path, watcher: &mut notify::RecommendedWatcher) -> Result<(), String> {
        let mut pending = vec![directory.to_path_buf()];
        while let Some(folder) = pending.pop() {
            let entries = match fs::read_dir(&folder) {
                Ok(entries) => entries,
                Err(error) if folder == directory => return Err(format!("{}: {error}", folder.display())),
                Err(_) => continue,
            };
            watcher.watch(&folder, RecursiveMode::NonRecursive).map_err(|error| error.to_string())?;
            self.watched.insert(folder.clone(), identity_of(&folder));
            for entry in entries.flatten() {
                let path = entry.path();
                let is_folder = fs::symlink_metadata(&path).is_ok_and(|attributes| attributes.is_dir());
                if is_folder && is_library_folder(&relative_to(directory, &path)) {
                    pending.push(path);
                }
            }
        }
        Ok(())
    }

    /// Whether `path` is outside the library, by `is_library_folder`. Git makes and deletes folders in
    /// `.git` all the time, each of which would otherwise be a rebuild. A file gone from the disk is
    /// judged by the folder it was in, as what it was cannot be asked any more.
    fn excluded(&self, root: &Path, path: &Path) -> bool {
        let relative = relative_to(root, path);
        let folder = if path.is_dir() { relative.as_str() } else { relative.rsplit_once('/').map(|(folder, _)| folder).unwrap_or("") };
        !is_library_folder(folder)
    }
}

/// Which folder a path leads to: the device it is on, and its number there.
type FolderIdentity = (u64, u64);

fn identity_of(folder: &Path) -> Option<FolderIdentity> {
    use std::os::unix::fs::MetadataExt;
    fs::metadata(folder).ok().map(|metadata| (metadata.dev(), metadata.ino()))
}

/// Whether every watched path still leads to the folder the watch was put on. A watch follows its
/// folder, not its name, and some ways of losing the folder say nothing: a volume unmounted and
/// mounted again leaves every watch dead, on Linux without an event that reaches here. This is how
/// the watcher comes to know, and builds its watches again.
fn still_the_folders_watched(watched: &HashMap<PathBuf, Option<FolderIdentity>>) -> bool {
    watched.iter().all(|(folder, identity)| identity_of(folder) == *identity)
}

fn shown(root: &Path, file: &Path) -> String {
    let relative = relative_to(root, file);
    if relative.is_empty() { ".".to_string() } else { relative }
}

fn describe(paths: &[String]) -> String {
    let shown = paths.iter().take(SHOWN_PATHS).cloned().collect::<Vec<_>>().join(", ");
    let more = if paths.len() > SHOWN_PATHS { format!(" and {} more", paths.len() - SHOWN_PATHS) } else { String::new() };
    let noun = if paths.len() == 1 { "change" } else { "changes" };
    format!("{} {noun} ({shown}{more})", paths.len())
}

/// A duration in its largest units: `1m`, `30s`, `1m 30s`, `500ms`.
pub fn duration_text(duration: Duration) -> String {
    let millis = duration.as_millis();
    if millis == 0 {
        return "0s".to_string();
    }
    if millis < 1000 {
        return format!("{millis}ms");
    }
    let seconds = duration.as_secs();
    let (days, hours, minutes, secs) = (seconds / 86_400, seconds % 86_400 / 3600, seconds % 3600 / 60, seconds % 60);
    let mut parts = Vec::new();
    if days > 0 {
        parts.push(format!("{days}d"));
    }
    if hours > 0 {
        parts.push(format!("{hours}h"));
    }
    if minutes > 0 {
        parts.push(format!("{minutes}m"));
    }
    let fraction = millis % 1000;
    if secs > 0 || fraction > 0 {
        if fraction == 0 {
            parts.push(format!("{secs}s"));
        } else {
            let text = format!("{secs}.{fraction:03}");
            parts.push(format!("{}s", text.trim_end_matches('0')));
        }
    }
    parts.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_duration_is_written_in_its_largest_units() {
        assert_eq!(duration_text(Duration::from_secs(60)), "1m");
        assert_eq!(duration_text(Duration::from_secs(30)), "30s");
        assert_eq!(duration_text(Duration::from_secs(90)), "1m 30s");
        assert_eq!(duration_text(Duration::from_millis(500)), "500ms");
        assert_eq!(duration_text(Duration::from_millis(1500)), "1.5s");
        assert_eq!(duration_text(Duration::from_secs(3600)), "1h");
        assert_eq!(duration_text(Duration::from_secs(90_000)), "1d 1h");
    }

    #[test]
    fn a_folder_replaced_under_its_name_is_no_longer_the_folder_watched() {
        let root = std::env::temp_dir().join(format!("korgnote-identity-{}", crate::text::random_hex(8)));
        fs::create_dir_all(root.join("sub")).unwrap();
        let watched: HashMap<PathBuf, Option<FolderIdentity>> = [root.clone(), root.join("sub")].into_iter().map(|folder| (folder.clone(), identity_of(&folder))).collect();
        assert!(still_the_folders_watched(&watched));
        fs::write(root.join("sub/a.org"), "* a\n").unwrap();
        assert!(still_the_folders_watched(&watched), "a note changing is not the folder changing");
        // As a volume mounted again is: the same name, another folder.
        fs::rename(root.join("sub"), root.join("sub-old")).unwrap();
        fs::create_dir(root.join("sub")).unwrap();
        assert!(!still_the_folders_watched(&watched));
        fs::remove_dir_all(root.join("sub")).unwrap();
        assert!(!still_the_folders_watched(&watched), "nor is one that is gone");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn changes_are_described_with_the_first_few_named() {
        let paths: Vec<String> = (0..7).map(|n| format!("n{n}.org")).collect();
        assert_eq!(describe(&paths[..1]), "1 change (n0.org)");
        assert_eq!(describe(&paths), "7 changes (n0.org, n1.org, n2.org, n3.org, n4.org and 2 more)");
    }
}
