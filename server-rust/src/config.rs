//! How the server exposes itself, from the environment. A port of the first half of
//! `server/…/ServerSecurity.kt`: the same variables, read the same way, so that one compose file or
//! one shell profile starts either server.
//!
//! Two ports. The plain one is for the computer the server runs on: the web client, the desktop app,
//! the Devices page where devices are paired and revoked. It listens on loopback, so nothing else can
//! reach it; in the container it listens everywhere inside, the compose file publishes it on the
//! host's loopback only, and a connection from anywhere but the host is refused by its address. The
//! secure one is for the network: TLS with the server's own certificate, and nothing but the sync
//! API, for paired devices only.

use crate::pairing::{SECURE_PORT, SERVER_PORT};
use crate::store::FileStore;
use crate::text::{random_hex, trim};
use std::path::{Path, PathBuf};

pub const LOOPBACK: &str = "127.0.0.1";
pub const ALL_INTERFACES: &str = "0.0.0.0";

pub const HTTP_HOST_VARIABLE: &str = "KORGNOTE_HTTP_HOST";
pub const PORT_VARIABLE: &str = "KORGNOTE_PORT";
pub const HTTPS_PORT_VARIABLE: &str = "KORGNOTE_HTTPS_PORT";
pub const ADVERTISED_PORT_VARIABLE: &str = "KORGNOTE_ADVERTISED_PORT";
pub const STATE_DIR_VARIABLE: &str = "KORGNOTE_STATE_DIR";
pub const SERVER_NAME_VARIABLE: &str = "KORGNOTE_SERVER_NAME";
pub const ADDRESSES_VARIABLE: &str = "KORGNOTE_ADVERTISED_ADDRESSES";
pub const TOKEN_VARIABLE: &str = "KORGNOTE_TOKEN";
pub const NOTES_DIR_VARIABLE: &str = "NOTES_DIR";
/// What the Kotlin server takes as `-Dnotes.dir=…`; this one takes it as an argument spelled the same.
pub const NOTES_DIR_PROPERTY: &str = "notes.dir";
pub const WEB_DIR_VARIABLE: &str = "WASM_DIR";
pub const WEB_DIR_PROPERTY: &str = "wasm.dir";
/// Serve the API only, even when a web client build is present (the container ships one).
pub const DISABLE_WEB_VARIABLE: &str = "KORGNOTE_DISABLE_WEB";
/// Set by the container image: a missing or unwritable notes directory stops the server. In a
/// container, serving an empty library instead would hide a typo in the volume path, or a UID
/// that cannot write to the mount, behind a server that looks healthy.
pub const REQUIRE_NOTES_DIR_VARIABLE: &str = "KORGNOTE_REQUIRE_NOTES_DIR";

const DEFAULT_NOTES_DIR: &str = "notes";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerConfig {
    pub http_host: String,
    pub http_port: i32,
    pub https_host: String,
    pub https_port: i32,
    /// The port a pairing QR code tells devices to use: `https_port` unless the container publishes it as another.
    pub advertised_port: i32,
    pub state_dir: PathBuf,
    /// What a device shows the server as once paired.
    pub server_name: String,
    /// Addresses for the QR code that the server cannot find for itself, as in a container.
    pub advertised_addresses: Vec<String>,
    /// A `KORGNOTE_TOKEN` left over from before pairing, which is now ignored; worth a line in the log.
    pub leftover_token: bool,
    pub notes_dir: PathBuf,
    /// Stop when `notes_dir` is missing or cannot be written to.
    pub require_notes_dir: bool,
    /// Where to look for the web client before the places a Gradle build leaves it.
    pub web_client_dir: Option<String>,
    /// False to serve the API only.
    pub serve_web_client: bool,
}

fn home() -> String {
    std::env::var("HOME").ok().filter(|home| !home.is_empty()).or_else(|| nix::unistd::User::from_uid(nix::unistd::getuid()).ok().flatten().map(|user| user.dir.to_string_lossy().into_owned())).unwrap_or_else(|| "/".to_string())
}

impl Default for ServerConfig {
    fn default() -> Self {
        ServerConfig {
            http_host: LOOPBACK.to_string(),
            http_port: SERVER_PORT,
            https_host: ALL_INTERFACES.to_string(),
            https_port: SECURE_PORT,
            advertised_port: SECURE_PORT,
            state_dir: Path::new(&home()).join(".korgnote-server"),
            server_name: "KorgNote".to_string(),
            advertised_addresses: Vec::new(),
            leftover_token: false,
            notes_dir: PathBuf::from(DEFAULT_NOTES_DIR),
            require_notes_dir: false,
            web_client_dir: None,
            serve_web_client: true,
        }
    }
}

/// Kotlin's `toIntOrNull`: digits with an optional sign, and nothing round them.
fn to_int(text: &str) -> Option<i32> {
    text.parse::<i32>().ok()
}

impl ServerConfig {
    pub fn describe(&self) -> String {
        format!(
            "Paired devices: https on {}:{}. This computer only: http on {}:{} (web client, desktop app, and the Devices page at /devices). State in {}.{}",
            self.https_host,
            self.https_port,
            self.http_host,
            self.http_port,
            absolute(&self.state_dir).display(),
            if self.leftover_token { format!(" {TOKEN_VARIABLE} is set but no longer used: pair devices on the Devices page instead.") } else { String::new() },
        )
    }

    /// From the process's own arguments and environment.
    pub fn from_process() -> Self {
        let arguments: Vec<String> = std::env::args().skip(1).collect();
        let property = move |name: &str| {
            arguments.iter().find_map(|argument| {
                let argument = argument.strip_prefix("-D").or_else(|| argument.strip_prefix("--")).unwrap_or(argument);
                argument.strip_prefix(name).and_then(|rest| rest.strip_prefix('=')).map(str::to_string)
            })
        };
        Self::from_environment(&property, &|name| std::env::var(name).ok())
    }

    /// What is set by a property, `-Dnotes.dir=…`, wins over the environment.
    pub fn from_environment(property: &dyn Fn(&str) -> Option<String>, read: &dyn Fn(&str) -> Option<String>) -> Self {
        let value = |name: &str| read(name).map(|v| trim(&v).to_string()).filter(|v| !v.is_empty());
        let property_value = |name: &str| property(name).map(|v| trim(&v).to_string()).filter(|v| !v.is_empty());
        let is_set = |name: &str| value(name).is_some_and(|v| matches!(v.to_lowercase().as_str(), "1" | "true" | "yes"));
        let expanded = |path: &str| expand_path(path, read);
        let defaults = ServerConfig::default();
        let https_port = value(HTTPS_PORT_VARIABLE).and_then(|v| to_int(&v)).unwrap_or(defaults.https_port);
        ServerConfig {
            http_host: value(HTTP_HOST_VARIABLE).unwrap_or(defaults.http_host),
            http_port: value(PORT_VARIABLE).and_then(|v| to_int(&v)).unwrap_or(defaults.http_port),
            https_host: defaults.https_host,
            https_port,
            advertised_port: value(ADVERTISED_PORT_VARIABLE).and_then(|v| to_int(&v)).unwrap_or(https_port),
            state_dir: value(STATE_DIR_VARIABLE).map(|v| PathBuf::from(expanded(&v))).unwrap_or(defaults.state_dir),
            server_name: value(SERVER_NAME_VARIABLE).unwrap_or(defaults.server_name),
            advertised_addresses: value(ADDRESSES_VARIABLE)
                .map(|v| v.split([',', ' ']).map(|address| trim(address).to_string()).filter(|address| !address.is_empty()).collect())
                .unwrap_or_default(),
            leftover_token: value(TOKEN_VARIABLE).is_some(),
            notes_dir: PathBuf::from(expanded(&property_value(NOTES_DIR_PROPERTY).or_else(|| value(NOTES_DIR_VARIABLE)).unwrap_or_else(|| DEFAULT_NOTES_DIR.to_string()))),
            require_notes_dir: is_set(REQUIRE_NOTES_DIR_VARIABLE),
            web_client_dir: property_value(WEB_DIR_PROPERTY).or_else(|| value(WEB_DIR_VARIABLE)),
            serve_web_client: !is_set(DISABLE_WEB_VARIABLE),
        }
    }

    /// The notes directory; an empty, read-only library when it does not exist. An `Err` is a reason
    /// not to start at all.
    ///
    /// Built once, but it reads the disk on every request rather than caching contents.
    pub fn open_store(&self) -> Result<FileStore, String> {
        let shown = absolute(&self.notes_dir);
        if self.require_notes_dir {
            if !self.notes_dir.is_dir() {
                return Err(format!("Notes directory '{}' does not exist and {REQUIRE_NOTES_DIR_VARIABLE} is set. Is the volume mounted?", shown.display()));
            }
            // Write a file rather than ask whether it may be: through Docker Desktop's file sharing a
            // directory can report itself writable and still refuse the write.
            let probe = self.notes_dir.join(format!(".korgnote-write-check{}.tmp", random_hex(8)));
            let writable = std::fs::File::create(&probe).is_ok();
            let _ = std::fs::remove_file(&probe);
            if !writable {
                return Err(format!("Notes directory '{}' is not writable by this user. Run as the UID/GID that owns it.", shown.display()));
            }
        }
        if self.notes_dir.is_dir() {
            crate::info!("Serving .org files from {}", shown.display());
            return Ok(FileStore::new(&self.notes_dir));
        }
        crate::warn!(
            "Notes directory '{}' not found: serving an empty library, read-only and unwatched. Create it, or set {NOTES_DIR_VARIABLE}, and restart the server.",
            shown.display()
        );
        Ok(FileStore::with_mode(&self.notes_dir, true))
    }

    /// The build of the web client to serve at `/`, if it should be served and one can be found.
    pub fn find_web_client(&self) -> Option<PathBuf> {
        if !self.serve_web_client {
            crate::info!("Not serving the web client: {DISABLE_WEB_VARIABLE} is set");
            return None;
        }
        // The repository root and a module's folder, which is where the server is started from in a checkout.
        let built = ["composeApp", "../composeApp"]
            .iter()
            .flat_map(|module| [format!("{module}/build/dist/wasmJs/productionExecutable"), format!("{module}/build/dist/wasmJs/developmentExecutable")]);
        let found = self.web_client_dir.iter().cloned().chain(built).map(PathBuf::from).find(|candidate| candidate.exists());
        match &found {
            Some(found) => crate::info!("Serving the web client from {}", absolute(found).display()),
            None => crate::info!("No web client to serve, looking from {}", std::env::current_dir().map(|here| here.display().to_string()).unwrap_or_default()),
        }
        found
    }
}

fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() { path.to_path_buf() } else { std::env::current_dir().map(|here| here.join(path)).unwrap_or_else(|_| path.to_path_buf()) }
}

/// `~` at the start of `path`, and any Windows-style `%NAME%` that `read` knows.
fn expand_path(path: &str, read: &dyn Fn(&str) -> Option<String>) -> String {
    let from_home = if path == "~" || path.starts_with("~/") || path.starts_with("~\\") { format!("{}{}", home(), &path[1..]) } else { path.to_string() };
    let mut out = String::new();
    let mut rest = from_home.as_str();
    while let Some(start) = rest.find('%') {
        let after = &rest[start + 1..];
        match after.find('%') {
            // `%%` is not a name, and stays as it is; the second `%` may yet open one.
            Some(0) => {
                out.push_str(&rest[..=start]);
                rest = after;
            }
            Some(end) => {
                let name = &after[..end];
                match read(name) {
                    Some(value) => out.push_str(&format!("{}{}", &rest[..start], value)),
                    None => out.push_str(&rest[..start + end + 2]),
                }
                rest = &after[end + 1..];
            }
            None => break,
        }
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn from(environment: &[(&str, &str)]) -> ServerConfig {
        let environment: HashMap<String, String> = environment.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        ServerConfig::from_environment(&|_| None, &|name| environment.get(name).cloned())
    }

    #[test]
    fn the_defaults_serve_this_computer_plain_and_the_network_over_tls() {
        let config = from(&[]);
        assert_eq!(config.http_host, LOOPBACK);
        assert_eq!(config.http_port, 8080);
        assert_eq!(config.https_host, ALL_INTERFACES);
        assert_eq!(config.https_port, 8443);
        assert_eq!(config.advertised_port, 8443);
        assert!(!config.leftover_token);
        assert_eq!(config.notes_dir, PathBuf::from("notes"));
        assert_eq!(config.web_client_dir, None);
        assert_eq!(config.server_name, "KorgNote");
        assert_eq!(config.state_dir, Path::new(&home()).join(".korgnote-server"));
    }

    #[test]
    fn an_old_token_is_ignored_but_mentioned() {
        let config = from(&[(TOKEN_VARIABLE, "s3cret")]);
        assert!(config.leftover_token);
        assert!(config.describe().contains("no longer used"));
        assert!(!from(&[]).describe().contains("no longer used"));
    }

    #[test]
    fn the_container_tells_where_it_is_reached() {
        let config = from(&[(ADDRESSES_VARIABLE, "192.168.1.20, 10.0.0.5"), (ADVERTISED_PORT_VARIABLE, "9443")]);
        assert_eq!(config.advertised_addresses, vec!["192.168.1.20", "10.0.0.5"]);
        assert_eq!(config.advertised_port, 9443);
        assert_eq!(config.https_port, 8443);
    }

    #[test]
    fn the_advertised_port_follows_the_secure_one_unless_said() {
        assert_eq!(from(&[(HTTPS_PORT_VARIABLE, "9000")]).advertised_port, 9000);
        assert_eq!(from(&[(HTTPS_PORT_VARIABLE, "not a port")]).https_port, 8443);
        assert_eq!(from(&[(PORT_VARIABLE, " 18080 ")]).http_port, 18080);
    }

    #[test]
    fn a_property_wins_over_the_environment() {
        let environment: HashMap<&str, &str> = [(NOTES_DIR_VARIABLE, "from-env"), (WEB_DIR_VARIABLE, "web-from-env")].into();
        let properties: HashMap<&str, &str> = [(NOTES_DIR_PROPERTY, "from-property"), (WEB_DIR_PROPERTY, "web-from-property")].into();
        let read = |name: &str| environment.get(name).map(|v| v.to_string());
        let property = |name: &str| properties.get(name).map(|v| v.to_string());

        let both = ServerConfig::from_environment(&property, &read);
        assert_eq!(both.notes_dir, PathBuf::from("from-property"));
        assert_eq!(both.web_client_dir.as_deref(), Some("web-from-property"));

        let env_only = ServerConfig::from_environment(&|_| None, &read);
        assert_eq!(env_only.notes_dir, PathBuf::from("from-env"));
        assert_eq!(env_only.web_client_dir.as_deref(), Some("web-from-env"));
    }

    #[test]
    fn a_switch_is_set_by_any_of_its_words() {
        for word in ["1", "true", "YES", " yes "] {
            let config = from(&[(REQUIRE_NOTES_DIR_VARIABLE, word), (DISABLE_WEB_VARIABLE, word)]);
            assert!(config.require_notes_dir, "{word}");
            assert!(!config.serve_web_client, "{word}");
        }
        let off = from(&[(REQUIRE_NOTES_DIR_VARIABLE, "0"), (DISABLE_WEB_VARIABLE, "0")]);
        assert!(!off.require_notes_dir);
        assert!(off.serve_web_client);
    }

    #[test]
    fn a_home_in_the_path_of_a_folder_is_expanded() {
        let config = from(&[(NOTES_DIR_VARIABLE, "~/my-notes"), (STATE_DIR_VARIABLE, "~/my-state")]);
        assert_eq!(config.notes_dir, Path::new(&home()).join("my-notes"));
        assert_eq!(config.state_dir, Path::new(&home()).join("my-state"));
        assert_eq!(from(&[(NOTES_DIR_VARIABLE, "~other/notes")]).notes_dir, PathBuf::from("~other/notes"));
    }

    #[test]
    fn a_name_between_percent_signs_is_expanded_when_it_is_known() {
        let config = from(&[(NOTES_DIR_VARIABLE, "%BASE%/notes/%UNKNOWN%/x"), ("BASE", "/srv")]);
        assert_eq!(config.notes_dir, PathBuf::from("/srv/notes/%UNKNOWN%/x"));
        assert_eq!(from(&[(NOTES_DIR_VARIABLE, "100%/notes")]).notes_dir, PathBuf::from("100%/notes"));
    }

    #[test]
    fn a_missing_notes_directory_is_an_empty_read_only_library() {
        let config = ServerConfig { notes_dir: PathBuf::from("temp_notes_does_not_exist"), ..ServerConfig::default() };
        let store = config.open_store().unwrap();
        assert!(store.read_only);
        assert!(store.list().unwrap().is_empty());
        assert!(!Path::new("temp_notes_does_not_exist").exists());
    }

    #[test]
    fn a_required_notes_directory_that_is_missing_fails_startup() {
        let config = ServerConfig { notes_dir: PathBuf::from("temp_notes_does_not_exist"), require_notes_dir: true, ..ServerConfig::default() };
        assert!(config.open_store().err().unwrap().contains("does not exist"));
    }

    #[test]
    fn a_required_notes_directory_that_is_read_only_fails_startup() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("korgnote-read-only-{}", random_hex(8)));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o555)).unwrap();
        let config = ServerConfig { notes_dir: dir.clone(), require_notes_dir: true, ..ServerConfig::default() };
        let result = config.open_store();
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o755)).unwrap();
        // As root, nothing is out of reach, and the folder is served.
        if !nix::unistd::getuid().is_root() {
            assert!(result.err().unwrap().contains("not writable"));
        }
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_required_notes_directory_that_exists_is_served_and_left_as_it_was() {
        let dir = std::env::temp_dir().join(format!("korgnote-required-{}", random_hex(8)));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("only.org"), "* Only").unwrap();
        let config = ServerConfig { notes_dir: dir.clone(), require_notes_dir: true, ..ServerConfig::default() };
        let store = config.open_store().unwrap();
        assert!(!store.read_only);
        assert_eq!(store.list().unwrap().into_iter().map(|meta| meta.path).collect::<Vec<_>>(), vec!["only.org"]);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1, "the write check left nothing behind");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_web_client_is_looked_for_where_it_is_said_to_be_unless_turned_off() {
        let dir = std::env::temp_dir().join(format!("korgnote-web-{}", random_hex(8)));
        std::fs::create_dir_all(&dir).unwrap();
        let on = ServerConfig { web_client_dir: Some(dir.display().to_string()), ..ServerConfig::default() };
        assert_eq!(on.find_web_client(), Some(dir.clone()));
        let off = ServerConfig { serve_web_client: false, ..on };
        assert_eq!(off.find_web_client(), None);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
