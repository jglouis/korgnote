//! Who may use what: paired devices over TLS, and the plain port for this computer only.

use crate::devices::DeviceRegistry;
use std::collections::HashSet;
use std::io;
use std::net::IpAddr;
use std::path::Path;
use std::sync::{Arc, Mutex};

/// Who may use what, as the server enforces it: `devices` for the network, and the plain port for
/// this computer only.
pub struct ServerAccess {
    pub devices: Arc<DeviceRegistry>,
    /// What a pairing QR code says about this server; None where devices cannot pair.
    pub pairing: Option<PairingDetails>,
    /// Addresses besides loopback that the plain port takes as this computer: in a container, the
    /// network's gateway, which is where the host's published port arrives from.
    pub this_computer: HashSet<String>,
}

impl ServerAccess {
    /// No network side at all: what the tests of the sync API want.
    pub fn local_only() -> Self {
        ServerAccess { devices: Arc::new(DeviceRegistry::in_memory()), pairing: None, this_computer: HashSet::new() }
    }
}

/// The server's side of a pairing link, all but the one-time code and the addresses of the moment.
pub struct PairingDetails {
    pub server_id: String,
    pub server_name: String,
    pub fingerprint: String,
    pub port: i32,
    /// Addresses given at startup; the Devices page adds what the computer's own interfaces say, when it can see them.
    pub configured_addresses: Vec<String>,
    /// This computer's own addresses; none inside a container. Given in tests.
    interfaces: Box<dyn Fn() -> Vec<String> + Send + Sync>,
    /// Set by `scripts/pair.sh`, which asks the host for its addresses: the newest a QR code was made with.
    last_addresses: Mutex<Vec<String>>,
}

pub const NO_ADDRESS_ADVICE: &str = "The server does not know an address phones can reach it at, so a pairing code would lead nowhere. \
Type this computer's address on your network (such as 192.168.1.20, in its Wi-Fi settings) below. \
In a container, start it with KORGNOTE_ADVERTISED_ADDRESSES set to that address, so it is known from the start.";

impl PairingDetails {
    pub fn new(server_id: &str, server_name: &str, fingerprint: &str, port: i32, configured_addresses: Vec<String>) -> Self {
        Self::with_interfaces(server_id, server_name, fingerprint, port, configured_addresses, Box::new(interface_addresses))
    }

    pub fn with_interfaces(
        server_id: &str,
        server_name: &str,
        fingerprint: &str,
        port: i32,
        configured_addresses: Vec<String>,
        interfaces: Box<dyn Fn() -> Vec<String> + Send + Sync>,
    ) -> Self {
        PairingDetails {
            server_id: server_id.to_string(),
            server_name: server_name.to_string(),
            fingerprint: fingerprint.to_string(),
            port,
            configured_addresses,
            interfaces,
            last_addresses: Mutex::new(Vec::new()),
        }
    }

    pub fn last_addresses(&self) -> Vec<String> {
        self.last_addresses.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone()
    }

    pub fn set_last_addresses(&self, addresses: Vec<String>) {
        *self.last_addresses.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = addresses;
    }

    /// Where devices may find this server, best guesses first.
    pub fn addresses(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for address in self.last_addresses().into_iter().chain(self.configured_addresses.iter().cloned()).chain((self.interfaces)()) {
            if !out.contains(&address) {
                out.push(address);
            }
        }
        out
    }

    /// What to do about knowing no address, which no QR code can be made without; None when one is known.
    pub fn missing_address_advice(&self) -> Option<&'static str> {
        if self.addresses().is_empty() { Some(NO_ADDRESS_ADVICE) } else { None }
    }
}

fn is_ascii_alphanumeric_or(c: u8, others: &[u8]) -> bool {
    c.is_ascii_alphanumeric() || others.contains(&c)
}

fn is_ipv4_shaped(text: &str) -> bool {
    let parts: Vec<&str> = text.split('.').collect();
    parts.len() == 4 && parts.iter().all(|part| (1..=3).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit()))
}

fn is_host_name(text: &str) -> bool {
    if text.is_empty() || text.len() > 253 {
        return false;
    }
    text.split('.').all(|label| {
        let bytes = label.as_bytes();
        (1..=63).contains(&bytes.len())
            && bytes[0].is_ascii_alphanumeric()
            && bytes[bytes.len() - 1].is_ascii_alphanumeric()
            && bytes.iter().all(|b| is_ascii_alphanumeric_or(*b, b"-"))
    })
}

fn is_ipv6_shaped(text: &str) -> bool {
    let (address, zone) = match text.split_once('%') {
        Some((address, zone)) => (address, Some(zone)),
        None => (text, None),
    };
    !address.is_empty()
        && address.bytes().all(|b| b.is_ascii_hexdigit() || b == b':' || b == b'.')
        && zone.is_none_or(|zone| !zone.is_empty() && zone.bytes().all(|b| b.is_ascii_alphanumeric()))
}

/// Whether `text` can be where a phone finds this server: an IPv4 or IPv6 address, or a host name.
/// Anything else typed in the Devices page's address box, a URL, a port, a stray word, would only
/// put a code in the QR code that the phone cannot use.
pub fn is_pairing_address(text: &str) -> bool {
    (is_ipv4_shaped(text) && text.split('.').all(|part| part.parse::<u32>().is_ok_and(|n| n <= 255)))
        // IPv6 has at least two colons, which an IPv4 address with a port (`192.168.1.20:8443`) has not.
        || (text.bytes().filter(|b| *b == b':').count() >= 2 && is_ipv6_shaped(text))
        || (is_host_name(text) && !text.bytes().all(|b| b.is_ascii_digit() || b == b'.'))
}

/// This computer's IPv4 addresses on the networks it is on. None inside a container, whose
/// interfaces are the container's own: there the run script passes the host's in.
pub fn interface_addresses() -> Vec<String> {
    if Path::new("/.dockerenv").exists() {
        return Vec::new();
    }
    use nix::net::if_::InterfaceFlags;
    let Ok(interfaces) = nix::ifaddrs::getifaddrs() else { return Vec::new() };
    let mut out = Vec::new();
    for interface in interfaces {
        let flags = interface.flags;
        let up = flags.contains(InterfaceFlags::IFF_UP) && flags.contains(InterfaceFlags::IFF_RUNNING);
        // A name with a colon is a second address given the way Linux once did it, an alias, not an interface.
        if !up || flags.contains(InterfaceFlags::IFF_LOOPBACK) || flags.contains(InterfaceFlags::IFF_POINTOPOINT) || interface.interface_name.contains(':') {
            continue;
        }
        let Some(address) = interface.address.as_ref().and_then(|address| address.as_sockaddr_in()).map(|address| address.ip()) else { continue };
        if address.is_private() {
            let text = address.to_string();
            if !out.contains(&text) {
                out.push(text);
            }
        }
    }
    out
}

fn bare_host(host: &str) -> String {
    let h = crate::text::trim(host);
    let h = h.strip_prefix('[').unwrap_or(h);
    let h = h.strip_suffix(']').unwrap_or(h);
    h.to_lowercase()
}

/// Whether `host`, from a request's `Host` header, names this computer: `localhost` or a loopback
/// address. A page served from any other name, one that resolves to 127.0.0.1 to get past the
/// browser's same-origin rule, is refused.
pub fn names_this_computer(host: &str) -> bool {
    let h = bare_host(host);
    // Exactly an address: `127.evil.example` starts the same way, and anyone can point it at 127.0.0.1.
    h == "localhost" || h == "::1" || h.strip_prefix("127.").is_some_and(|rest| {
        let parts: Vec<&str> = rest.split('.').collect();
        parts.len() == 3 && parts.iter().all(|part| (1..=3).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit()))
    })
}

/// Whether a connection from `address` comes from this computer: loopback, or one of `also_this_computer`.
pub fn connects_from_this_computer(address: IpAddr, also_this_computer: &HashSet<String>) -> bool {
    let address = address.to_canonical();
    address.is_loopback() || also_this_computer.contains(&address.to_string())
}

/// The gateway of the default route in `routes`, the text of `/proc/net/route`; None when it has
/// none. Addresses there are hexadecimal and little-endian: `010011AC` is 172.17.0.1.
pub fn default_gateway(routes: &str) -> Option<String> {
    routes
        .lines()
        .skip(1)
        .map(|line| line.split_whitespace().collect::<Vec<_>>())
        .find(|fields| fields.len() > 2 && fields[1] == "00000000" && fields[2] != "00000000")
        .and_then(|fields| u64::from_str_radix(fields[2], 16).ok())
        .map(|gateway| (0..4).map(|byte| ((gateway >> (8 * byte)) & 0xff).to_string()).collect::<Vec<_>>().join("."))
}

/// Inside a container on a network of its own, the default gateway, from `/proc/net/route`: the
/// host's published port reaches the server from there. Empty anywhere else.
///
/// A container on the host's own network (`--network host`) has `/.dockerenv` too, but there the
/// default gateway is the network's router, and a connection from it is not this computer's. What
/// tells the two apart is the interfaces: a container on one network has one besides loopback,
/// while a host running containers has its own and the bridge they hang off (`docker0`). Two or
/// more, and no gateway is trusted: the plain port then answers loopback only.
pub fn container_gateway() -> HashSet<String> {
    if !Path::new("/.dockerenv").exists() {
        return HashSet::new();
    }
    let interfaces: Vec<String> = nix::ifaddrs::getifaddrs()
        .map(|addresses| {
            addresses
                .filter(|interface| !interface.flags.contains(nix::net::if_::InterfaceFlags::IFF_LOOPBACK))
                .filter(|interface| interface.address.as_ref().is_some_and(|address| address.as_sockaddr_in().is_some() || address.as_sockaddr_in6().is_some()))
                .map(|interface| interface.interface_name)
                .collect()
        })
        .unwrap_or_default();
    if !on_one_network(&interfaces) {
        crate::warn!(
            "In a container that sees {} network interfaces, which is the host's network or several networks: the plain port answers loopback only, not the host's published port.",
            distinct(&interfaces).len()
        );
        return HashSet::new();
    }
    std::fs::read_to_string("/proc/net/route").ok().and_then(|routes| default_gateway(&routes)).into_iter().collect()
}

fn distinct(names: &[String]) -> HashSet<&str> {
    names.iter().map(String::as_str).collect()
}

/// Whether the interfaces with an address, loopback left out and named once for each address, are
/// one: a container on a single network of its own.
pub fn on_one_network(names: &[String]) -> bool {
    distinct(names).len() == 1
}

/// What a request says of itself, as far as who may make it goes.
pub struct Asking<'a> {
    pub method: &'a str,
    /// The path as it was sent, before anything was decoded.
    pub raw_path: &'a str,
    /// The `Host` header, or None when there is none.
    pub host: Option<&'a str>,
    pub origin: Option<&'a str>,
    pub authorization: Option<&'a str>,
    pub remote: IpAddr,
    /// The port the connection arrived on.
    pub local_port: u16,
    /// Whether it arrived on the port TLS is served on.
    pub secure: bool,
}

pub const NOT_FOUND_OVER_TLS: &str = "Not found. The web client and the Devices page are on the computer the server runs on.";
pub const NOT_PAIRED: &str = "This device is not paired, or was revoked. Pair it again from the Devices page.";
pub const THIS_COMPUTER_ONLY: &str = "This port serves the computer the server runs on only.";
pub const BY_THE_NAME_LOCALHOST: &str = "This port serves the computer the server runs on, by the name localhost.";
pub const OWN_PAGES_ONLY: &str = "Changes are accepted from this server's own pages only.";

/// The host and the port a `Host` header names: what is before the last colon,
/// and what is after it, 80 when there is no colon. With no header at all, `localhost` and the port
/// the connection arrived on.
fn host_and_port(asking: &Asking) -> (String, Option<u32>) {
    match asking.host {
        None => ("localhost".to_string(), Some(asking.local_port as u32)),
        Some(header) => match header.rsplit_once(':') {
            Some((host, port)) => (host.to_string(), port.parse::<u32>().ok()),
            None => (header.to_string(), Some(80)),
        },
    }
}

/// The host and port of an `Origin` header, read strictly: a scheme, `://`, a host, and a port or
/// the scheme's own. None for anything else, `null` included, which is then never taken for one
/// of this server's own pages. No browser writes an `Origin` of one of those pages any other way
/// than this reads.
fn origin_host_and_port(origin: &str) -> Option<(String, u32)> {
    let (scheme, authority) = origin.split_once("://")?;
    let mut scheme_bytes = scheme.bytes();
    if !scheme_bytes.next()?.is_ascii_alphabetic() || !scheme_bytes.all(|b| is_ascii_alphanumeric_or(b, b"+-.")) {
        return None;
    }
    if authority.is_empty() || authority.bytes().any(|b| b"/?#@\\".contains(&b) || b.is_ascii_whitespace() || b.is_ascii_control()) {
        return None;
    }
    let (host, port) = if authority.starts_with('[') {
        let end = authority.find(']')?;
        match &authority[end + 1..] {
            "" => (&authority[..=end], None),
            rest => (&authority[..=end], Some(rest.strip_prefix(':')?)),
        }
    } else {
        match authority.rsplit_once(':') {
            Some((host, port)) => (host, Some(port)),
            None => (authority, None),
        }
    };
    if host.is_empty() || host.contains(':') && !host.starts_with('[') {
        return None;
    }
    let port = match port {
        Some(digits) if !digits.is_empty() && digits.len() <= 5 && digits.bytes().all(|b| b.is_ascii_digit()) => digits.parse::<u32>().ok().filter(|port| *port <= 65535)?,
        Some(_) => return None,
        None => match scheme.to_ascii_lowercase().as_str() {
            "http" | "ws" => 80,
            "https" | "wss" => 443,
            _ => return None,
        },
    };
    Some((host.to_string(), port))
}

/// The token an `Authorization` header carries: what follows `Bearer `, or the whole header.
pub fn bearer_token(header: &str) -> &str {
    crate::text::trim(header.strip_prefix("Bearer ").unwrap_or(header))
}

/// Why a request may not go on, as a status and a sentence; None when it may.
///
/// Over TLS, only the sync API answers, and only to a paired device's token, pairing itself
/// excepted, which is how a device gets one. The plain port answers this computer only, which its
/// loopback binding already ensures; a browser on this computer is held to it as well, since any page
/// it has open could otherwise send requests here: the `Host` must name this computer, which stops a
/// page reaching it through a name of its own (DNS rebinding), and a change must come from a page of
/// this server's own, which stops one changing notes from another site.
pub fn refusal_of(asking: &Asking, access: &ServerAccess) -> io::Result<Option<(u16, &'static str)>> {
    if asking.secure {
        if asking.raw_path == "/api/pair" {
            return Ok(None);
        }
        // What may be reached, not what may not: this is the path as it was sent, while routing ignores
        // empty segments and decodes the rest, so `/api//local/` and `/api/%6Cocal/` reach `/api/local/`.
        if !asking.raw_path.starts_with("/api/v1/") {
            return Ok(Some((404, NOT_FOUND_OVER_TLS)));
        }
        return Ok(match asking.authorization.map(bearer_token) {
            Some(token) if !token.is_empty() && access.devices.authenticate(token)?.is_some() => None,
            _ => Some((401, NOT_PAIRED)),
        });
    }
    // Where the connection comes from, which the caller cannot write, before what it says about
    // itself: in a container this port listens on every interface inside, and another container on
    // the same network could otherwise claim to be localhost.
    if !connects_from_this_computer(asking.remote, &access.this_computer) {
        return Ok(Some((403, THIS_COMPUTER_ONLY)));
    }
    let (host, port) = host_and_port(asking);
    if !names_this_computer(&host) {
        return Ok(Some((403, BY_THE_NAME_LOCALHOST)));
    }
    if !matches!(asking.method, "GET" | "HEAD" | "OPTIONS")
        && let Some(origin) = asking.origin {
            let own = origin_host_and_port(origin).is_some_and(|(origin_host, origin_port)| origin_host.eq_ignore_ascii_case(&host) && Some(origin_port) == port);
            if !own {
                return Ok(Some((403, OWN_PAGES_ONLY)));
            }
        }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_are_addresses_or_host_names() {
        for ok in ["192.168.1.20", "10.0.0.5", "fe80::1", "fe80::1%en0", "2001:db8::42", "laptop.local", "jglm3max", "01.02.03.004", "::1", "::", "a-b.c"] {
            assert!(is_pairing_address(ok), "{ok}");
        }
        for bad in [
            "", "http://192.168.1.20", "192.168.1.20:8443", "999.1.1.1", "192.168.1", "two words", "-x", "x-", "a_b.local", "1.2.3.4.5", ":", "fe80::1%",
            "fe80::zz", "12345", "1.1.1.1.", ".local", "a..b", "256.1.1.1",
        ] {
            assert!(!is_pairing_address(bad), "{bad}");
        }
        assert!(is_pairing_address(&format!("{}.local", "x".repeat(63))));
        assert!(!is_pairing_address(&format!("{}.local", "x".repeat(64))));
    }

    #[test]
    fn the_containers_gateway_is_read_from_its_routes() {
        let routes = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n\
                      eth0\t00000000\t010011AC\t0003\t0\t0\t0\t00000000\t0\t0\t0\n\
                      eth0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n";
        assert_eq!(default_gateway(routes).as_deref(), Some("172.17.0.1"));
        assert_eq!(default_gateway("Iface\tDestination\tGateway\n"), None);
    }

    #[test]
    fn only_a_container_on_one_network_trusts_its_gateway() {
        let names = |list: &[&str]| list.iter().map(|name| name.to_string()).collect::<Vec<_>>();
        // A container on the default bridge: eth0, with an IPv4 and an IPv6 address.
        assert!(on_one_network(&names(&["eth0", "eth0"])));
        // On the host's network: its own interface and the bridge its containers hang off.
        assert!(!on_one_network(&names(&["eth0", "docker0"])));
        assert!(!on_one_network(&names(&["wlan0", "eth0", "docker0", "br-1a2b3c"])));
        // On two networks of compose's: which gateway the host comes through is not known.
        assert!(!on_one_network(&names(&["eth0", "eth1"])));
        assert!(!on_one_network(&[]));
    }

    #[test]
    fn only_localhost_and_loopback_name_this_computer() {
        for ok in ["localhost", "LOCALHOST", " localhost", "127.0.0.1", "127.1.2.3", "[::1]", "::1", "127.000.000.001", "127.0.0.256"] {
            assert!(names_this_computer(ok), "{ok}");
        }
        for bad in ["rebound.example", "192.168.1.20", "10.0.2.2", "127.evil.example", "127.0.0.1.evil.example", "127.0.0.evil", "", "localhost.", "127.1", "0.0.0.0", "user@localhost"] {
            assert!(!names_this_computer(bad), "{bad}");
        }
    }

    #[test]
    fn a_connection_is_from_this_computer_by_its_address() {
        let gateway: HashSet<String> = ["172.17.0.1".to_string()].into();
        let none = HashSet::new();
        assert!(connects_from_this_computer("127.0.0.1".parse().unwrap(), &none));
        assert!(connects_from_this_computer("127.9.9.9".parse().unwrap(), &none));
        assert!(connects_from_this_computer("::1".parse().unwrap(), &none));
        assert!(connects_from_this_computer("::ffff:127.0.0.1".parse().unwrap(), &none));
        assert!(!connects_from_this_computer("192.168.1.20".parse().unwrap(), &none));
        assert!(!connects_from_this_computer("172.17.0.1".parse().unwrap(), &none));
        assert!(connects_from_this_computer("172.17.0.1".parse().unwrap(), &gateway));
        assert!(!connects_from_this_computer("172.17.0.2".parse().unwrap(), &gateway));
    }

    #[test]
    fn an_origin_is_read_strictly() {
        let read = |origin: &str| origin_host_and_port(origin);
        assert_eq!(read("http://localhost"), Some(("localhost".into(), 80)));
        assert_eq!(read("https://localhost"), Some(("localhost".into(), 443)));
        assert_eq!(read("http://127.0.0.1:8080"), Some(("127.0.0.1".into(), 8080)));
        assert_eq!(read("HTTP://127.0.0.1:080"), Some(("127.0.0.1".into(), 80)));
        assert_eq!(read("http://[::1]:80"), Some(("[::1]".into(), 80)));
        assert_eq!(read("http://[::1]"), Some(("[::1]".into(), 80)));
        assert_eq!(read("x://127.0.0.1:80"), Some(("127.0.0.1".into(), 80)));
        for bad in [
            "null", "not a url", "", "localhost", "//localhost", "http://localhost/", "http://localhost:80/path?q#f", "http://user@localhost:80",
            "http://localhost:", "http://localhost:abc", "http://127.0.0.1:65536", "http:/localhost", "http:localhost", "http://", "x://127.0.0.1",
            "file:///etc/passwd", "about:blank", "null://", "://", "http://::1", "http://[::1", "http://[::1]x",
        ] {
            assert_eq!(read(bad), None, "{bad}");
        }
    }

    fn asking<'a>(method: &'a str, path: &'a str, host: Option<&'a str>, origin: Option<&'a str>) -> Asking<'a> {
        Asking { method, raw_path: path, host, origin, authorization: None, remote: "127.0.0.1".parse().unwrap(), local_port: 8080, secure: false }
    }

    #[test]
    fn the_plain_port_asks_where_from_by_what_name_and_from_which_page() {
        let access = ServerAccess::local_only();
        let refused = |a: Asking| refusal_of(&a, &access).unwrap().map(|(status, _)| status);
        assert_eq!(refused(asking("GET", "/api/v1/manifest", Some("localhost:8080"), None)), None);
        assert_eq!(refused(asking("GET", "/api/v1/manifest", None, None)), None);
        assert_eq!(refused(asking("GET", "/api/v1/manifest", Some("rebound.example:8080"), None)), Some(403));
        assert_eq!(refused(asking("GET", "/api/v1/manifest", Some("[::1]"), None)), Some(403), "a Host with no port is on port 80");
        assert_eq!(refused(asking("GET", "/api/v1/manifest", Some("[::1]:8080"), None)), None);
        assert_eq!(refused(asking("GET", "/x", Some("localhost:8080"), Some("https://evil.example"))), None, "a GET changes nothing");
        assert_eq!(refused(asking("PUT", "/x", Some("localhost:8080"), Some("https://evil.example"))), Some(403));
        assert_eq!(refused(asking("PUT", "/x", Some("localhost:8080"), Some("http://localhost:8080"))), None);
        assert_eq!(refused(asking("PUT", "/x", Some("localhost:8080"), Some("http://LOCALHOST:8080"))), None);
        assert_eq!(refused(asking("PUT", "/x", Some("localhost:8080"), Some("http://localhost"))), Some(403));
        assert_eq!(refused(asking("PUT", "/x", Some("localhost"), Some("http://localhost"))), None);
        assert_eq!(refused(asking("PUT", "/x", Some("localhost"), Some("null"))), Some(403), "a null Origin is no page of this server's");
        assert_eq!(refused(asking("PUT", "/x", None, Some("http://localhost:8080"))), None);
        assert_eq!(refused(asking("PUT", "/x", Some("localhost:8080"), None)), None, "an app sends no Origin");
        let mut elsewhere = asking("GET", "/api/v1/manifest", Some("localhost"), None);
        elsewhere.remote = "192.168.1.20".parse().unwrap();
        assert_eq!(refused(elsewhere), Some(403));
    }

    #[test]
    fn over_tls_there_is_the_sync_api_for_paired_devices_and_pairing() {
        let access = ServerAccess::local_only();
        let code = access.devices.new_pairing_code();
        let (_, token) = access.devices.pair(&crate::pairing::PairRequest::new(&code, "Phone")).unwrap().unwrap();
        let ask = |path: &str, authorization: Option<&str>| {
            let a = Asking { method: "GET", raw_path: path, host: Some("anything"), origin: Some("https://evil.example"), authorization, remote: "192.168.1.20".parse().unwrap(), local_port: 8443, secure: true };
            refusal_of(&a, &access).unwrap().map(|(status, _)| status)
        };
        let bearer = format!("Bearer {token}");
        assert_eq!(ask("/api/pair", None), None);
        assert_eq!(ask("/api/v1/manifest", None), Some(401));
        assert_eq!(ask("/api/v1/manifest", Some("Bearer guess")), Some(401));
        assert_eq!(ask("/api/v1/manifest", Some(&bearer)), None);
        assert_eq!(ask("/api/v1/manifest", Some(&token)), None, "a bare token is taken too");
        assert_eq!(ask("/api/v1/manifest", Some(&format!("Bearer  {token} "))), None);
        assert_eq!(ask("/api/v1/manifest", Some(&format!("bearer {token}"))), Some(401));
        assert_eq!(ask("/api/v1/manifest", Some("Bearer ")), Some(401));
        for path in ["/", "/devices", "/api/", "/api/local/pairing", "/api//local/pairing", "/api/%6Cocal/pairing", "/%61pi/local/pairing", "/api/v1", "/api//v1/manifest"] {
            assert_eq!(ask(path, Some(&bearer)), Some(404), "{path}");
        }
        // These pass here, and then find no route: see the server's own tests.
        assert_eq!(ask("/api/v1/../local/pairing", Some(&bearer)), None);
    }
}
