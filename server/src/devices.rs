//! The devices that may use the sync API over the network.

use crate::pairing::PairRequest;
use crate::text::{constant_time_equals, random_hex, random_uuid, sha256_hex, take_utf16, trim};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::watch;

/// A device paired with this server. Only a hash of its token is kept: the token stays on the device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    #[serde(rename = "tokenHash")]
    pub token_hash: String,
    /// Epoch milliseconds.
    #[serde(rename = "pairedAt")]
    pub paired_at: i64,
    #[serde(rename = "lastSeen", default, skip_serializing_if = "Option::is_none")]
    pub last_seen: Option<i64>,
}

pub const CODE_LIFETIME_MS: i64 = 10 * 60 * 1000;
pub const LAST_SEEN_RESOLUTION_MS: i64 = 60 * 1000;

type Clock = Box<dyn Fn() -> i64 + Send + Sync>;
type Maker = Box<dyn Fn() -> String + Send + Sync>;

struct State {
    devices: Vec<PairedDevice>,
    /// Each code with when it expires, in the order they were made.
    codes: Vec<(String, i64)>,
}

/// Device registry. Persisted in `devices.json` in the server's state directory.
pub struct DeviceRegistry {
    /// The path to the devices file. `None` means in-memory only.
    file: Option<PathBuf>,
    /// Time source for code expiration. Can be mocked for tests.
    now: Clock,
    /// Random generator for device tokens.
    new_token: Maker,
    /// Random generator for pairing codes.
    new_code: Maker,
    /// The devices list.
    state: Mutex<State>,
    /// Counter bumped on each revoked or replaced devices.
    /// A phone's open change stream subscribes to it and closes the moment the phone is revoked.
    /// Without it, a revoked phone would keep receiving changes until its next request.
    removals: watch::Sender<u64>,
}

fn system_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// What a device is called on the Devices page: the name it gave, trimmed and cut to 80 characters.
pub fn shown_name(given: &str) -> String {
    let name = take_utf16(trim(given), 80);
    if name.is_empty() { "A device".to_string() } else { name.to_string() }
}

fn hash(token: &str) -> String {
    sha256_hex(token.as_bytes())
}

impl DeviceRegistry {
    /// The devices in `file`, when there is one. A file that cannot be read as devices stops the
    /// server: starting with none would lock every phone out for good.
    pub fn open(file: Option<PathBuf>) -> io::Result<Self> {
        Self::with(file, Box::new(system_millis), Box::new(|| random_hex(32)), Box::new(|| random_hex(16)))
    }

    /// Kept in memory only, for testing purposes.
    pub fn in_memory() -> Self {
        Self::open(None).expect("no file to fail on")
    }

    pub fn with(file: Option<PathBuf>, now: Clock, new_token: Maker, new_code: Maker) -> io::Result<Self> {
        let mut devices = Vec::new();
        if let Some(path) = file.as_ref().filter(|path| path.is_file()) {
            let text = fs::read_to_string(path)?;
            devices = serde_json::from_str(&text)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("{}: {error}", path.display())))?;
        }
        Ok(DeviceRegistry { file, now, new_token, new_code, state: Mutex::new(State { devices, codes: Vec::new() }), removals: watch::Sender::new(0) })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn all(&self) -> Vec<PairedDevice> {
        self.lock().devices.clone()
    }

    /// A code for one pairing, good for `CODE_LIFETIME_MS`; every newer one leaves it valid too.
    pub fn new_pairing_code(&self) -> String {
        let mut state = self.lock();
        let code = (self.new_code)();
        let now = (self.now)();
        state.codes.retain(|(_, expires)| *expires >= now);
        match state.codes.iter_mut().find(|(known, _)| *known == code) {
            Some(entry) => entry.1 = now + CODE_LIFETIME_MS,
            None => state.codes.push((code.clone(), now + CODE_LIFETIME_MS)),
        }
        code
    }

    /// Pairs the device `request` describes when its code is one this registry handed out and has
    /// not been used or expired, and returns it with the token to give it; None otherwise. The
    /// server asks first, between [take_code](Self::take_code) and [add](Self::add).
    pub fn pair(&self, request: &PairRequest) -> io::Result<Option<(PairedDevice, String)>> {
        if !self.take_code(&request.code) {
            return Ok(None);
        }
        self.add(request).map(Some)
    }

    /// Whether `code` is one this registry handed out and has not been used or expired; it is used
    /// now, whatever comes of the pairing it was brought for.
    pub fn take_code(&self, code: &str) -> bool {
        let mut state = self.lock();
        let Some(at) = state.codes.iter()
            .position(|(known, _)| constant_time_equals(known, code)) else { return false };
        let (_, expires) = state.codes.remove(at);
        expires >= (self.now)()
    }

    /// Adds the device `request` describes, its code already taken, and returns it with the token to
    /// give it. The entry it says it replaces goes, when it brings that entry's token.
    pub fn add(&self, request: &PairRequest) -> io::Result<(PairedDevice, String)> {
        let mut state = self.lock();
        if let (Some(id), Some(replaced_token)) = (request.replaces.as_ref(), request.replaces_token.as_ref())
            && let Some(at) = state.devices.iter().position(|device| device.id == *id)
                && constant_time_equals(&state.devices[at].token_hash, &hash(replaced_token)) {
                    state.devices.remove(at);
                    self.removals.send_modify(|count| *count += 1);
                }
        let token = (self.new_token)();
        let device = PairedDevice { id: random_uuid(), name: shown_name(&request.device_name), token_hash: hash(&token), paired_at: (self.now)(), last_seen: None };
        state.devices.push(device.clone());
        self.save(&state)?;
        Ok((device, token))
    }

    /// The device `token` belongs to, its last-seen time brought up to date; None for any other token.
    pub fn authenticate(&self, token: &str) -> io::Result<Option<PairedDevice>> {
        let mut state = self.lock();
        let hashed = hash(token);
        let Some(index) = state.devices.iter()
            .position(|device| constant_time_equals(&device.token_hash, &hashed)) else { return Ok(None) };
        let seen = (self.now)();
        // Written now and then, not on every request: a phone syncing all day is not worth a disk write each time.
        let stale = match state.devices[index].last_seen {
            None => true,
            Some(last) => seen - last >= LAST_SEEN_RESOLUTION_MS,
        };
        if stale {
            state.devices[index].last_seen = Some(seen);
            self.save(&state)?;
        }
        Ok(Some(state.devices[index].clone()))
    }

    /// Whether `token` is a paired device's, without counting it as the device being seen.
    pub fn knows(&self, token: &str) -> bool {
        let hashed = hash(token);
        self.lock().devices.iter().any(|device| constant_time_equals(&device.token_hash, &hashed))
    }

    /// Returns a watch receiver subscribed to removal updates.
    ///
    /// The returned receiver observes the current removal value and is notified
    /// whenever the removal count changes.
    pub fn removals(&self) -> watch::Receiver<u64> {
        self.removals.subscribe()
    }

    /// Revokes a device by its ID. Returns whether the device was revoked.
    pub fn revoke(&self, id: &str) -> io::Result<bool> {
        let mut state = self.lock();
        let before = state.devices.len();
        state.devices.retain(|device| device.id != id);
        let removed = state.devices.len() != before;
        if removed {
            self.removals.send_modify(|count| *count += 1);
            self.save(&state)?;
        }
        Ok(removed)
    }

    /// Saves the current state to the file.
    fn save(&self, state: &State) -> io::Result<()> {
        let Some(target) = self.file.as_ref() else { return Ok(()) };
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut name = target.file_name().map(|name| name.to_os_string()).unwrap_or_default();
        name.push(".tmp");
        let temporary = target.with_file_name(name);
        fs::write(&temporary, pretty(&state.devices))?;
        fs::rename(&temporary, target)
    }
}

/// Pretty-prints the devices indented by four spaces, as earlier releases wrote the file.
fn pretty(devices: &[PairedDevice]) -> Vec<u8> {
    let mut out = Vec::new();
    let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut serializer = serde_json::Serializer::with_formatter(&mut out, formatter);
    devices.serialize(&mut serializer).expect("devices are plain data");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};

    struct Fixture {
        clock: Arc<AtomicI64>,
        registry: DeviceRegistry,
    }

    fn fixture() -> Fixture {
        let clock = Arc::new(AtomicI64::new(1_000_000));
        let (tokens, codes) = (Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)));
        let now = clock.clone();
        let registry = DeviceRegistry::with(
            None,
            Box::new(move || now.load(Ordering::SeqCst)),
            Box::new(move || format!("token-{}", tokens.fetch_add(1, Ordering::SeqCst) + 1)),
            Box::new(move || format!("code-{}", codes.fetch_add(1, Ordering::SeqCst) + 1)),
        )
        .unwrap();
        Fixture { clock, registry }
    }

    #[test]
    fn a_code_buys_a_token() {
        let f = fixture();
        let code = f.registry.new_pairing_code();
        let (device, token) = f.registry.pair(&PairRequest::new(&code, "Phone")).unwrap().unwrap();
        assert_eq!(device.name, "Phone");
        assert_eq!(token, "token-1");
        assert_eq!(f.registry.authenticate(&token).unwrap().unwrap().id, device.id);
        assert_eq!(f.registry.all()[0].last_seen, Some(1_000_000));
    }

    #[test]
    fn a_code_works_once() {
        let f = fixture();
        let code = f.registry.new_pairing_code();
        assert!(f.registry.pair(&PairRequest::new(&code, "Phone")).unwrap().is_some());
        assert!(f.registry.pair(&PairRequest::new(&code, "Phone")).unwrap().is_none());
        assert!(f.registry.pair(&PairRequest::new("made-up", "Phone")).unwrap().is_none());
    }

    #[test]
    fn a_code_expires() {
        let f = fixture();
        let code = f.registry.new_pairing_code();
        f.clock.fetch_add(CODE_LIFETIME_MS + 1, Ordering::SeqCst);
        assert!(f.registry.pair(&PairRequest::new(&code, "Phone")).unwrap().is_none());
    }

    #[test]
    fn a_code_is_good_to_its_last_millisecond() {
        let f = fixture();
        let code = f.registry.new_pairing_code();
        f.clock.fetch_add(CODE_LIFETIME_MS, Ordering::SeqCst);
        assert!(f.registry.pair(&PairRequest::new(&code, "Phone")).unwrap().is_some());
    }

    #[test]
    fn a_newer_code_leaves_an_older_one_valid() {
        let f = fixture();
        let first = f.registry.new_pairing_code();
        let second = f.registry.new_pairing_code();
        assert!(f.registry.pair(&PairRequest::new(&second, "B")).unwrap().is_some());
        assert!(f.registry.pair(&PairRequest::new(&first, "A")).unwrap().is_some());
    }

    #[test]
    fn a_revoked_device_is_refused_at_once() {
        let f = fixture();
        let (device, token) = f.registry.pair(&PairRequest::new(&f.registry.new_pairing_code(), "Phone")).unwrap().unwrap();
        assert!(f.registry.revoke(&device.id).unwrap());
        assert!(f.registry.authenticate(&token).unwrap().is_none());
        assert!(!f.registry.revoke(&device.id).unwrap());
    }

    #[test]
    fn pairing_again_replaces_the_old_entry_when_it_can_prove_it_is_that_device() {
        let f = fixture();
        let (first, first_token) = f.registry.pair(&PairRequest::new(&f.registry.new_pairing_code(), "Phone")).unwrap().unwrap();
        let mut wrong = PairRequest::new(&f.registry.new_pairing_code(), "Phone");
        wrong.replaces = Some(first.id.clone());
        wrong.replaces_token = Some("not-its-token".into());
        f.registry.pair(&wrong).unwrap().unwrap();
        assert_eq!(f.registry.all().len(), 2, "a wrong token replaces nothing");

        let mut right = PairRequest::new(&f.registry.new_pairing_code(), "Phone");
        right.replaces = Some(first.id.clone());
        right.replaces_token = Some(first_token);
        let (third, _) = f.registry.pair(&right).unwrap().unwrap();
        let all = f.registry.all();
        assert_eq!(all.len(), 2);
        assert!(!all.iter().any(|device| device.id == first.id));
        assert!(all.iter().any(|device| device.id == third.id));
    }

    #[test]
    fn a_name_is_trimmed_cut_and_never_empty() {
        let f = fixture();
        let pair = |name: &str| f.registry.pair(&PairRequest::new(&f.registry.new_pairing_code(), name)).unwrap().unwrap().0.name;
        assert_eq!(pair("  Phone  "), "Phone");
        assert_eq!(pair(" \t\u{00a0} "), "A device");
        assert_eq!(pair(""), "A device");
        assert_eq!(pair(&"N".repeat(200)).len(), 80);
        // Sixty faces are 120 UTF-16 units: forty of them fit in eighty.
        assert_eq!(pair(&"😀".repeat(60)).chars().count(), 40);
    }

    #[test]
    fn last_seen_is_written_now_and_then() {
        let f = fixture();
        let (_, token) = f.registry.pair(&PairRequest::new(&f.registry.new_pairing_code(), "Phone")).unwrap().unwrap();
        f.registry.authenticate(&token).unwrap();
        f.clock.fetch_add(LAST_SEEN_RESOLUTION_MS - 1, Ordering::SeqCst);
        assert_eq!(f.registry.authenticate(&token).unwrap().unwrap().last_seen, Some(1_000_000));
        f.clock.fetch_add(1, Ordering::SeqCst);
        assert_eq!(f.registry.authenticate(&token).unwrap().unwrap().last_seen, Some(1_000_000 + LAST_SEEN_RESOLUTION_MS));
    }

    #[test]
    fn devices_are_kept_across_restarts() {
        let dir = std::env::temp_dir().join(format!("korgnote-devices-{}", random_hex(8)));
        let file = dir.join("devices.json");
        let registry = DeviceRegistry::open(Some(file.clone())).unwrap();
        let (device, token) = registry.pair(&PairRequest::new(&registry.new_pairing_code(), "Phone")).unwrap().unwrap();
        let written = fs::read_to_string(&file).unwrap();
        assert!(!written.contains(&token), "only its hash is written");
        assert!(!written.contains("lastSeen"), "a device never seen has no time for it");
        assert!(written.starts_with("[\n    {\n        \"id\": \""), "{written}");

        let restarted = DeviceRegistry::open(Some(file.clone())).unwrap();
        assert_eq!(restarted.authenticate(&token).unwrap().unwrap().id, device.id);
        assert!(!dir.join("devices.json.tmp").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_earlier_releases_file_is_read() {
        let dir = std::env::temp_dir().join(format!("korgnote-devices-{}", random_hex(8)));
        fs::create_dir_all(&dir).unwrap();
        let file = dir.join("devices.json");
        fs::write(
            &file,
            "[\n    {\n        \"id\": \"0b5b1b1e-0000-4000-8000-000000000001\",\n        \"name\": \"Phone\",\n        \"tokenHash\": \"abc\",\n        \"pairedAt\": 1795076041234,\n        \"lastSeen\": 1795076042000,\n        \"someNewField\": true\n    }\n]",
        )
        .unwrap();
        let registry = DeviceRegistry::open(Some(file)).unwrap();
        assert_eq!(registry.all()[0].last_seen, Some(1795076042000));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_that_is_not_devices_stops_the_server() {
        let dir = std::env::temp_dir().join(format!("korgnote-devices-{}", random_hex(8)));
        fs::create_dir_all(&dir).unwrap();
        let file = dir.join("devices.json");
        fs::write(&file, "not json").unwrap();
        assert!(DeviceRegistry::open(Some(file)).is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}
