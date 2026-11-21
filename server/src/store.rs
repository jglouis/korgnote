//! Reads and writes the notes directory, answering from the disk every time it is asked.

use crate::text::{decode_utf8, is_blank, sha256_hex, sha256_hex_of_file, utf16_order};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// A file as it is on disk right now. `etag` is a hash of the content, not of its timestamps.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMeta {
    pub path: String,
    pub size: u64,
    pub mtime_millis: i64,
    pub etag: String,
}

/// A file's text and the version it is, from one read.
#[derive(Debug, Clone)]
pub struct FileContent {
    pub meta: FileMeta,
    pub text: String,
}

#[derive(Debug)]
pub enum WriteResult {
    Written(FileMeta),
    /// The file on disk is not the version the writer based its edit on.
    Conflict(FileMeta),
    NotFound,
    /// The path is not a note of the library. Asking again would not help.
    Invalid,
    TooLarge,
    /// The disk refused. The cause is for the log: its message says where the notes are on this computer.
    Failed(io::Error),
    ReadOnly,
}

impl WriteResult {
    pub fn is_written(&self) -> bool {
        matches!(self, WriteResult::Written(_))
    }
    pub fn is_conflict(&self) -> bool {
        matches!(self, WriteResult::Conflict(_))
    }
}

pub const MAX_BYTES: usize = 32 * 1024 * 1024;
const TEMPORARY_PREFIX: &str = ".korgnote";
const TEMPORARY_SUFFIX: &str = ".tmp";
const ETAG_HEX_DIGITS: usize = 32;
/// FAT keeps two seconds, ext3 and HFS+ one; anything finer is safe under this too.
const TIMESTAMP_GRAIN_MILLIS: i64 = 2_000;
/// One rename can take three: one before it, the one it lands in, and one after it.
const MAX_WALKS: usize = 6;

/// Whether a folder, relative to the notes directory, holds notes of the library. A dot-folder does
/// not: `.korgnote-trash` holds what was deleted, `.stversions` Syncthing's old versions, `.git` git's
/// own business. Served as notes, those would reach every phone as more copies of the same headings.
pub fn is_library_folder(relative_path: &str) -> bool {
    !relative_path.split('/').any(|segment| segment.starts_with('.'))
}

/// Whether `relative_path`, `/`-separated from the notes directory, is a note of the library: an
/// `.org` file in no dot-folder. This one rule is what is listed, what can be read and written, and
/// what is watched.
pub fn is_library_path(relative_path: &str) -> bool {
    let folder = match relative_path.rfind('/') {
        Some(at) => &relative_path[..at],
        None => "",
    };
    relative_path.ends_with(".org") && is_library_folder(folder)
}

fn hash_of(content: &[u8]) -> String {
    let mut hex = sha256_hex(content);
    hex.truncate(ETAG_HEX_DIGITS);
    hex
}

fn mtime_millis_of(metadata: &fs::Metadata) -> io::Result<i64> {
    let modified = metadata.modified()?;
    Ok(match modified.duration_since(UNIX_EPOCH) {
        Ok(after) => after.as_millis() as i64,
        // Before 1970: floor, so a time is never rounded up into the next millisecond.
        Err(before) => {
            let d = before.duration();
            let millis = d.as_millis() as i64;
            if d.subsec_nanos() % 1_000_000 == 0 { -millis } else { -millis - 1 }
        }
    })
}

fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn random_u64() -> u64 {
    let mut bytes = [0u8; 8];
    // The name of a temporary file: a clash is retried, so any source will do when the system's fails.
    if getrandom::fill(&mut bytes).is_err() {
        return now_millis() as u64 ^ (std::process::id() as u64).rotate_left(32);
    }
    u64::from_le_bytes(bytes)
}

#[derive(Clone)]
struct HashEntry {
    size: u64,
    mtime_millis: i64,
    etag: String,
}

/// A filesystem-backed store rooted at a specific directory.
///
/// `FileStore` manages files below `root`, optionally enforcing read-only access.
/// It also maintains an in-memory cache of file hash metadata and uses internal
/// synchronization to coordinate write operations.
///
/// The store records the filesystem volume for `root` when available, allowing
/// callers or internal logic to detect whether paths belong to the same volume.
///
/// # Fields
///
/// - `root`: Root directory used by the store.
/// - `read_only`: Whether the store disallows mutating operations.
/// - `hashes`: Cached hash metadata indexed by path or key.
/// - `writing`: Mutex used to serialize write operations.
/// - `volume_of`: Function used to determine the volume identifier for a path.
/// - `volume`: Cached volume identifier for `root`, if available.
pub struct FileStore {
    pub root: PathBuf,
    pub read_only: bool,
    hashes: Mutex<HashMap<String, HashEntry>>,
    writing: Mutex<()>,
    volume_of: fn(&Path) -> Option<u64>,
    volume: Option<u64>,
}

/// The volume `path` is on, through links: its device number; None for a path that is not there.
pub fn volume_of(path: &Path) -> Option<u64> {
    use std::os::unix::fs::MetadataExt;
    fs::metadata(path).ok().map(|metadata| metadata.dev())
}

impl FileStore {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self::with_mode(root, false)
    }

    pub fn with_mode(root: impl Into<PathBuf>, read_only: bool) -> Self {
        Self::with_volume_of(root, read_only, volume_of)
    }

    /// As [Self::with_mode], telling volumes apart with `volume_of`.
    pub fn with_volume_of(root: impl Into<PathBuf>, read_only: bool, volume_of: fn(&Path) -> Option<u64>) -> Self {
        // A relative path is taken from the working directory.
        let root = normalize(&root.into());
        let volume = if read_only { None } else { volume_of(&root) };
        FileStore { root, read_only, hashes: Mutex::new(HashMap::new()), writing: Mutex::new(()), volume_of, volume }
    }

    fn is_our_temporary_file(path: &Path) -> bool {
        path.file_name()
            .and_then(|name| name.to_str())
            .map(|name| name.starts_with(TEMPORARY_PREFIX) && name.ends_with(TEMPORARY_SUFFIX))
            .unwrap_or(false)
    }

    /// Every note currently on disk, in path order.
    ///
    /// This store's own writes leave temporary files that go while the walk is on its way to them, and
    /// that says nothing about the notes. Anything else that stops a read fails the whole list. A note
    /// or folder gone may only have been renamed, to a name the walk will not see, and left out of a
    /// complete manifest, a note is taken for deleted and goes from every phone.
    ///
    /// A rename can also go unnoticed: a folder too big to be read in one go can hide both names from
    /// the walk, or show it both, with no error. So the tree is walked until a walk has lost nothing the
    /// one before it listed, as one rename can only spoil the walk it lands in. Notes that are new do not
    /// count against it: a big checkout or first upload would fail every listing while it lasted, and a
    /// path too many is a stale name for a moment, where a path too few is a note deleted. Notes that keep
    /// going fail the list.
    ///
    /// A notes directory that was there when the store was made and is gone, or is no longer on the
    /// volume it was on, fails the list too: listed, it would be every note deleted. One that was
    /// never there, the read-only store's, is an empty library.
    pub fn list(&self) -> io::Result<Vec<FileMeta>> {
        if !self.root.is_dir() {
            if self.read_only {
                return Ok(Vec::new());
            }
            return Err(io::Error::new(io::ErrorKind::NotFound, "The notes folder is gone. Is the volume it is on unmounted?"));
        }
        if self.volume.is_some() && (self.volume_of)(&self.root) != self.volume {
            return Err(io::Error::other("The notes folder is no longer on the volume it was on when the server started. Is that volume unmounted?"));
        }
        let mut previous: Vec<String> = self.walk()?.into_iter().map(|meta| meta.path).collect();
        for _ in 0..MAX_WALKS - 1 {
            let latest = self.walk()?;
            let paths: HashSet<&str> = latest.iter().map(|meta| meta.path.as_str()).collect();
            if previous.iter().all(|path| paths.contains(path.as_str())) {
                return Ok(latest);
            }
            previous = latest.into_iter().map(|meta| meta.path).collect();
        }
        Err(io::Error::other(format!("The notes kept going during {MAX_WALKS} walks")))
    }

    fn walk(&self) -> io::Result<Vec<FileMeta>> {
        let root_real = fs::canonicalize(&self.root)?;
        let mut found = Vec::new();
        // From where the root really is. A walk that started at the root as it is named would take a
        // root that is a link for a file, and list a complete, empty library, which takes every
        // note off every phone.
        self.walk_directory(&root_real, &root_real, &mut found)?;
        found.sort_by(|a, b| utf16_order(&a.path, &b.path));
        Ok(found)
    }

    /// As `Files.walkFileTree` goes, without following links: a link is a file to it, whatever it
    /// points at, and every folder is gone into, a dot-folder too, so that one that cannot be read
    /// fails the listing here as it does there.
    ///
    /// The folders still to go into are kept in a list, not on the stack: a device can make folders
    /// a couple of thousand deep with one push, and a walk that called itself for each would run a
    /// thread out of stack, which ends the server.
    fn walk_directory(&self, directory: &Path, root_real: &Path, found: &mut Vec<FileMeta>) -> io::Result<()> {
        let mut pending = vec![directory.to_path_buf()];
        while let Some(folder) = pending.pop() {
            let entries = match fs::read_dir(&folder) {
                Ok(entries) => entries,
                // A folder named as this store names its temporary files, gone since it was seen.
                Err(error) if error.kind() == io::ErrorKind::NotFound && folder != directory && Self::is_our_temporary_file(&folder) => continue,
                Err(error) => return Err(error),
            };
            for entry in entries {
                let file = entry?.path();
                let attributes = match fs::symlink_metadata(&file) {
                    Ok(attributes) => attributes,
                    Err(error) if error.kind() == io::ErrorKind::NotFound && Self::is_our_temporary_file(&file) => continue,
                    Err(error) => return Err(error),
                };
                if attributes.is_dir() {
                    pending.push(file);
                    continue;
                }
                if attributes.is_file() || file.is_file() {
                    let path = relative_to(root_real, &file);
                    // The same check as `resolve`, so that what is listed can also be fetched: a link
                    // to a file outside the root cannot, and a phone would keep trying. But not
                    // `resolve` itself, whose None also means a file that went just now, which here
                    // has to fail the listing and not leave the note out.
                    if self.candidate_for(&path).is_some() && fs::canonicalize(&file)?.starts_with(root_real) {
                        found.push(self.meta_of(&path, &file)?);
                    }
                }
            }
        }
        Ok(())
    }

    /// `path`'s text and the version that text is, from a single read: read apart, an edit landing in
    /// between pairs the old etag with the new text, and the client's next push is a false conflict.
    pub fn read_with_meta(&self, path: &str) -> Option<FileContent> {
        let file = self.resolve(path)?;
        if !file.is_file() {
            return None;
        }
        let read = || -> io::Result<FileContent> {
            let mtime = mtime_millis_of(&fs::metadata(&file)?)?;
            let bytes = fs::read(&file)?;
            let meta = FileMeta { path: path.to_string(), size: bytes.len() as u64, mtime_millis: mtime, etag: hash_of(&bytes) };
            Ok(FileContent { meta, text: decode_utf8(bytes) })
        };
        read().ok()
    }

    /// The version `path` is at, or None when it is not a note that is there. An error is the disk
    /// refusing to say.
    pub fn meta(&self, path: &str) -> io::Result<Option<FileMeta>> {
        let Some(file) = self.resolve(path) else { return Ok(None) };
        if !file.is_file() {
            return Ok(None);
        }
        self.meta_of(path, &file).map(Some)
    }

    /// The sync API's write: creates or replaces `path` according to the conditional headers.
    ///
    /// `if_match` is "I edited the version with this hash": a mismatch is a `Conflict`, not an error,
    /// because the two versions can still be merged. `must_not_exist` is `If-None-Match: *`, the capture
    /// case: create it, or tell me it is already there.
    ///
    /// The write goes to a temporary file in the same directory and is then moved into place, so a
    /// crash or a dying connection cannot leave a half-written note.
    pub fn put(&self, path: &str, content: &[u8], if_match: Option<&str>, must_not_exist: bool) -> io::Result<WriteResult> {
        if self.read_only {
            return Ok(WriteResult::ReadOnly);
        }
        let Some(file) = self.resolve(path) else { return Ok(WriteResult::Invalid) };
        if content.len() > MAX_BYTES {
            return Ok(WriteResult::TooLarge);
        }

        let _held = self.writing.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let exists = file.is_file();
        if must_not_exist && exists {
            return Ok(WriteResult::Conflict(self.meta_of(path, &file)?));
        }
        if let Some(if_match) = if_match {
            if !exists {
                return Ok(WriteResult::NotFound);
            }
            let current = self.meta_of(path, &file)?;
            if current.etag != if_match {
                return Ok(WriteResult::Conflict(current));
            }
        }

        let write = || -> io::Result<WriteResult> {
            let parent = file.parent().unwrap_or(&self.root);
            fs::create_dir_all(parent)?;
            let temporary = create_temporary_file(parent)?;
            let moved = (|| -> io::Result<()> {
                fs::write(&temporary, content)?;
                keep_permissions(&temporary, if exists { Some(&file) } else { None });
                if must_not_exist {
                    // Not an atomic move, which replaces whatever it finds: an editor may have
                    // made this file since the check, and a new note must never replace one.
                    if fs::symlink_metadata(&file).is_ok() {
                        return Err(io::Error::from(io::ErrorKind::AlreadyExists));
                    }
                }
                fs::rename(&temporary, &file)
            })();
            match moved {
                Ok(()) => {}
                Err(appeared) if must_not_exist && appeared.kind() == io::ErrorKind::AlreadyExists => {
                    let _ = fs::remove_file(&temporary);
                    return Ok(WriteResult::Conflict(self.meta_of(path, &file)?));
                }
                Err(failure) => {
                    let _ = fs::remove_file(&temporary);
                    return Err(failure);
                }
            }
            self.forget_hash(path);
            Ok(WriteResult::Written(self.meta_of(path, &file)?))
        };
        Ok(write().unwrap_or_else(WriteResult::Failed))
    }

    /// What `put` answers for a note that is over the limit before any of it is looked at: the same,
    /// in the same order, with nothing held in memory to say it.
    pub fn put_too_large(&self, path: &str) -> WriteResult {
        if self.read_only {
            return WriteResult::ReadOnly;
        }
        if self.resolve(path).is_none() {
            return WriteResult::Invalid;
        }
        WriteResult::TooLarge
    }

    /// Moves `path` into `.korgnote-trash/<date>/`, rather than unlinking it.
    ///
    /// Cheap insurance: a sync bug that deletes the wrong note is recoverable, and a few kilobytes of
    /// text is not worth the risk of being right.
    pub fn delete(&self, path: &str, if_match: Option<&str>) -> io::Result<WriteResult> {
        if self.read_only {
            return Ok(WriteResult::ReadOnly);
        }
        let Some(file) = self.resolve(path) else { return Ok(WriteResult::Invalid) };
        let _held = self.writing.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if !file.is_file() {
            return Ok(WriteResult::NotFound);
        }
        let current = self.meta_of(path, &file)?;
        if let Some(if_match) = if_match
            && current.etag != if_match {
                return Ok(WriteResult::Conflict(current));
            }
        let trash = || -> io::Result<()> {
            let day = crate::clock::date(crate::clock::local(crate::clock::now_millis()));
            let target = self.root.join(".korgnote-trash").join(day).join(path);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::rename(&file, unique_trash_path(&target))
        };
        Ok(match trash() {
            Ok(()) => {
                self.forget_hash(path);
                WriteResult::Written(current)
            }
            Err(failure) => WriteResult::Failed(failure),
        })
    }

    fn forget_hash(&self, path: &str) {
        self.hashes.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(path);
    }

    fn meta_of(&self, path: &str, file: &Path) -> io::Result<FileMeta> {
        let metadata = fs::metadata(file)?;
        let size = metadata.len();
        let mtime = mtime_millis_of(&metadata)?;
        let cached = self.hashes.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get(path).cloned();
        let etag = match cached {
            Some(entry) if entry.size == size && entry.mtime_millis == mtime => entry.etag,
            _ => {
                let mut etag = sha256_hex_of_file(file)?;
                etag.truncate(ETAG_HEX_DIGITS);
                // Where timestamps are coarse, an edit moments from now could keep this size and this
                // time: TODO to DONE does. So a file only just changed is hashed again next time, as
                // a stale etag here would pass `If-Match` and let a push overwrite that edit.
                if now_millis() - mtime >= TIMESTAMP_GRAIN_MILLIS {
                    self.hashes
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner())
                        .insert(path.to_string(), HashEntry { size, mtime_millis: mtime, etag: etag.clone() });
                }
                etag
            }
        };
        Ok(FileMeta { path: path.to_string(), size, mtime_millis: mtime, etag })
    }

    pub fn relative_path_of(&self, file: &Path) -> String {
        relative_to(&self.root, file)
    }

    /// Turns a request path into a file inside the root, or None when it is not one.
    ///
    /// Rejects absolute paths, empty or `.`/`..` segments, anything that `is_library_path` says is not a
    /// note, and anything that still resolves outside the root once symlinks are followed.
    fn resolve(&self, path: &str) -> Option<PathBuf> {
        let candidate = self.candidate_for(path)?;
        let root_real = fs::canonicalize(&self.root).unwrap_or_else(|_| self.root.clone());
        // Through links, the file — or, for a new one, the nearest folder it would be made in — must
        // still be inside the root: a folder linked in from elsewhere is not part of the library.
        let existing = candidate.ancestors().find(|ancestor| ancestor.exists())?;
        let existing_real = fs::canonicalize(existing).ok()?;
        if !existing_real.starts_with(&root_real) {
            return None;
        }
        Some(candidate)
    }

    /// Where `path` would be in the root going by its name alone, or None when no note can be called that.
    fn candidate_for(&self, path: &str) -> Option<PathBuf> {
        if is_blank(path) {
            return None;
        }
        if path.split('/').any(|segment| is_blank(segment) || segment == "." || segment == ".." || segment.contains('\\')) {
            return None;
        }
        if !is_library_path(path) {
            return None;
        }
        // No path on this system holds a NUL, so no note's path does.
        if path.contains('\0') {
            return None;
        }
        Some(self.root.join(path))
    }
}

/// `file` as it is called from `root`, with `/` between its parts whatever the system uses.
pub fn relative_to(root: &Path, file: &Path) -> String {
    let relative = file.strip_prefix(root).unwrap_or(file);
    relative.components().map(|part| part.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/")
}

/// `path` made absolute and without `.` and `..`, by its name alone, without asking the disk.
fn normalize(path: &Path) -> PathBuf {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().map(|here| here.join(path)).unwrap_or_else(|_| path.to_path_buf())
    };
    let mut out = PathBuf::new();
    for part in absolute.components() {
        match part {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// A new, empty file in `directory` that only its owner can read.
fn create_temporary_file(directory: &Path) -> io::Result<PathBuf> {
    loop {
        let candidate = directory.join(format!("{TEMPORARY_PREFIX}{}{TEMPORARY_SUFFIX}", random_u64()));
        match fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&candidate) {
            Ok(_) => return Ok(candidate),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        }
    }
}

/// Gives a freshly written temporary file the permissions the note should have.
///
/// The temporary file is readable by its owner only, and the move carries that onto the note, so
/// every save would turn a `rw-r--r--` note into `rw-------`, and every note created from the app
/// would be born that way. A replaced note keeps its own mode; a new one gets the usual `rw-r--r--`.
fn keep_permissions(temporary: &Path, replacing: Option<&Path>) {
    let mode = match replacing {
        Some(file) => match fs::metadata(file) {
            Ok(metadata) => metadata.permissions().mode() & 0o777,
            // Gone since it was looked at: the temporary file keeps its own mode.
            Err(_) => return,
        },
        None => 0o644,
    };
    let _ = fs::set_permissions(temporary, fs::Permissions::from_mode(mode));
}

/// Deleting the same path twice in a day must not overwrite the first copy.
fn unique_trash_path(target: &Path) -> PathBuf {
    if !target.exists() {
        return target.to_path_buf();
    }
    let name = target.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    let mut index = 2;
    loop {
        let candidate = target.with_file_name(format!("{name}.{index}"));
        if !candidate.exists() {
            return candidate;
        }
        index += 1;
    }
}
